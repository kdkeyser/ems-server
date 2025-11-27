package io.konektis.ems

import io.klogging.Klogging
import io.konektis.config.Config
import io.konektis.config.GridMeterType
import io.konektis.config.GridType
import io.konektis.devices.Heatpump.DaikinHeatpump
import io.konektis.devices.Watt
import io.konektis.devices.charger.Webasto
import io.konektis.devices.grid.GridProperties
import io.konektis.devices.grid.P1Meter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.time.delay
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Mode {
    AUTO, MANUAL
}

class EnergyManager : Klogging {
    var currentMaxAmps = 0
    var mode = Mode.AUTO
    val emsStateFlow = MutableStateFlow(EMSState(null, null, null, null, null, null, null))

    suspend fun update(p1Meter: P1Meter, webasto: Webasto, daikinHeatpump: DaikinHeatpump) : EMSState {
        val p1MeterValues =
            try {
                p1Meter.update()
                p1Meter.getState()?.update
            } catch (e: Exception) {
                logger.error(e)
                null
            }
        val webastoPower: Int? =
            try {
                webasto.update()
                webasto.getState()?.update?.currentPower?.value?.toInt()
            } catch (e: Exception) {
                logger.error(e)
                null
            }
        val daikinPower : Int? =
            try {
                daikinHeatpump.update()
                daikinHeatpump.getState()?.update?.power?.value?.toInt()
            } catch (e: Exception) {
                logger.error(e)
                null
            }

        return EMSState(
            gridPower = p1MeterValues?.power?.value,
            gridVoltage = p1MeterValues?.voltage?.value?.toInt(),
            chargerPower = webastoPower,
            heatpumpPower = daikinPower,
            solarPower = null,
            batteryPower = null,
            batteryCharge = null
        )

    }

    suspend fun run(config: Config) {

        val p1Meter =
            when (config.grid.type) {
                GridMeterType.P1HomeWizard -> P1Meter(config.grid.host, GridProperties(config.grid.gridType))

        }
        val charger = config.devices.charger?.get(0)!!
        val heatPump = config.devices.heatPump?.get(0)!!
        val webasto = Webasto(charger.host)
        val daikinHomeHub = DaikinHeatpump(heatPump.host)

        while (true) {
            logger.debug("EnergyManager loop")
            val emsState = update(p1Meter, webasto, daikinHomeHub)
            currentMaxAmps = when (mode) {
                Mode.AUTO -> {
                    val current = (// never more than 32A
                            (if ((emsState.gridPower != null) && (emsState.gridVoltage != null) && (emsState.chargerPower != null) && (emsState.heatpumpPower != null)) {
                                logger.debug("P1 power = ${emsState.gridPower} W")
                                logger.debug("Charger power = ${emsState.chargerPower} W")
                                logger.debug("Daikin Heatpump power = ${emsState.heatpumpPower} W")
                                val availablePower = emsState.chargerPower - emsState.gridPower
                                logger.info("Available power: $availablePower W")
                                val maxCurrent = charger.chargingCurrent.max
                                val minCurrent = charger.chargingCurrent.min
                                if (availablePower > 0) {
                                    max(
                                        min(
                                            (availablePower.toDouble() / emsState.gridVoltage.toDouble()),
                                            maxCurrent.toDouble()
                                        ), minCurrent.toDouble()
                                    )  // never more than 32A
                                } else {
                                    minCurrent.toDouble()
                                }
                            } else {
                                0.0
                            }).roundToInt())
                    logger.info("EnergyManager in AUTO mode, setting current to $current")
                    current
                }
                Mode.MANUAL -> {
                    logger.info("EnergyManager in MANUAL mode, setting current to $currentMaxAmps")
                    currentMaxAmps
                }
            }
            webasto.setMaxChargerPower(Watt(currentMaxAmps * 230))
            emsStateFlow.value = emsState
            logger.info("Updated Webasto charger")
            val interval = 5
            delay(Duration.ofSeconds(interval.toLong()))
        }
    }
}