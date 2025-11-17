package io.konektis.ems

import io.klogging.Klogging
import io.konektis.devices.DaikinHomeHub
import io.konektis.EnergyManagerConfig
import io.konektis.devices.P1Meter
import io.konektis.devices.Webasto
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

    suspend fun update(p1Meter: P1Meter, webasto: Webasto, daikinHomeHub: DaikinHomeHub) : EMSState {
        val p1MeterValues =
            try {
                p1Meter.update()
            } catch (e: Exception) {
                logger.error(e)
                null
            }
        val webastoPower: Int? =
            try {
                webasto.getCurrentPowerUsage()
            } catch (e: Exception) {
                logger.error(e)
                null
            }
        val daikinPower : Int? =
            try {
                daikinHomeHub.getCurrentPowerUsage()
            } catch (e: Exception) {
                logger.error(e)
                null
            }

        return EMSState(
            gridPower = p1MeterValues?.active_power_w?.toInt(),
            gridVoltage = p1MeterValues?.active_voltage_l1_v?.toInt(),
            chargerPower = webastoPower,
            heatpumpPower = daikinPower,
            solarPower = null,
            batteryPower = null,
            batteryCharge = null
        )

    }

    suspend fun run(config: EnergyManagerConfig) {
        val P1MeterHost = config.config["P1meter"]?:"0.0.0.0"
        val webastoHost = config.config["webasto"]?:"0.0.0.0"
        val daikinHomeHubHost = config.config["daikinHomeHub"]?:"0.0.0.0"
        val p1Meter = P1Meter(P1MeterHost)
        val webasto = Webasto(webastoHost)
        val daikinHomeHub = DaikinHomeHub(daikinHomeHubHost)

        thread {
            webasto.keepAliveLoop()
        }

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
                                val maxCurrent = config.config["maxcurrent"] ?: "32"
                                val minCurrent = config.config["mincurrent"] ?: "6"
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
            webasto.update(currentMaxAmps)
            emsStateFlow.value = emsState
            logger.info("Updated Webasto charger")
            val interval = config.config["interval"]?:"5"
            delay(Duration.ofSeconds(interval.toLong()))
        }
    }
}