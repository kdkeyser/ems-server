package io.konektis.tools

import io.konektis.config.loadConfig
import io.konektis.devices.Watt
import io.konektis.devices.battery.SMABattery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Standalone probe for the SMA inverter's Modbus-control watchdog.
 *
 * Procedure:
 *   1. Run: ./gradlew batteryWatchdogTest
 *   2. It arms Modbus control (802) + a 500W charge target, then prints SoC + net power
 *      every ~3s, forever.
 *   3. Ctrl-C it WITHOUT letting it send 803, then watch the inverter: does it revert to
 *      its own logic, and after how long?
 *
 * TODO: This MUST be run against real hardware to confirm/quantify the watchdog timeout.
 *       Prior manual observation suggests >= 15 minutes (too slow to be a safety net, which
 *       is why graceful 803-on-shutdown is required).
 */
fun main() = runBlocking {
    val config = loadConfig("/config.yaml")
    val batteryConfig = config.devices.battery.firstOrNull()
        ?: error("No battery configured in config.yaml")
    val battery = SMABattery(batteryConfig.host)

    println("Arming Modbus control + 500W charge target on ${batteryConfig.host} ...")
    battery.setChargingPower(Watt(500))
    println("Armed. Polling every 3s. Ctrl-C WITHOUT releasing to observe the watchdog.")

    while (true) {
        battery.update()
        val state = battery.getState()?.update
        println("SoC=${state?.charge}%  netPower=${state?.power?.value}W")
        delay(3_000)
    }
}
