package io.konektis

import io.klogging.Klogging
import kotlinx.coroutines.runBlocking
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


    suspend fun run(config: EnergyManagerConfig) {
        val P1MeterHost = config.config["P1meter"]?:"0.0.0.0"
        val webastoHost = config.config["webasto"]?:"0.0.0.0"
        val p1Meter = P1Meter(P1MeterHost)//192.168.129.5
        val webasto = Webasto(webastoHost)//192.168.129.109
        val daikinHomeHub = DaikinHomeHub("192.168.129.131")

        thread {
            webasto.keepAliveLoop()
        }

        while (true) {
            logger.debug("EnergyManager loop")
            currentMaxAmps = when (mode) {
                Mode.AUTO -> {
                    val p1MeterValues =
                        try {
                            runBlocking { p1Meter.update() }
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
                    val current = (// never more than 32A
                            (if ((p1MeterValues != null) && (webastoPower != null) && (daikinPower != null)) {
                                logger.debug("P1 power = ${p1MeterValues.active_power_w} W")
                                logger.debug("Charger power = $webastoPower W")
                                logger.debug("Daikin Heatpump power = $daikinPower W")
                                val availablePower = webastoPower.toDouble() - p1MeterValues.active_power_w
                                logger.info("Available power: $availablePower W")
                                val maxCurrent = config.config["maxcurrent"] ?: "32"
                                val minCurrent = config.config["mincurrent"] ?: "6"
                                if (availablePower > 0) {
                                    max(
                                        min(
                                            (availablePower / p1MeterValues.active_voltage_l1_v),
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
            logger.info("Updated Webasto charger")
            val interval = config.config["interval"]?:"5"
            delay(Duration.ofSeconds(interval.toLong()))
        }
    }
}