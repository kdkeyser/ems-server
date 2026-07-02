package io.konektis.ems

import io.konektis.devices.Ampere
import io.konektis.devices.Watt
import io.konektis.devices.charger.ChargerCommand
import io.konektis.devices.smartConsumers.ConsumeMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val VOLTAGE = 230 // single-phase installation; the only amps<->watts site

/**
 * Surplus cascade: heat pump -> charger -> battery.
 *
 * The battery soaks up whatever grid imbalance the charger didn't. Because the battery's own power
 * cancels against its contribution to the grid reading, one full correction per tick (deadbeat,
 * [gain] = 1) lands the right setpoint immediately:
 *     target = solar - houseLoad - heatpump - charger
 *
 * That fast, full correction matters. A damped gain < 1 leaves the loop sluggish: after the sun
 * drops it lingers for many ticks still *charging* into a deficit before it ramps down to discharge
 * (observed as "charging the battery while importing from the grid"). Keep [gain] = 1.
 *
 * [gridDeadbandW] ignores imbalances below meter/control noise so the battery doesn't chase a few
 * watts back and forth.
 *
 * Solar charging sessions are hysteresis-gated. Without it the min-amps floor means a plugged-in
 * car in solar mode charges at 6 A forever — all night, drained from the home battery. A session
 * opens only after `available >= chargerMinAmps * 230 V` has held for [startAfterTicks] consecutive
 * ticks (~60 s), and closes only after `available < minW / 2` has held for [stopAfterTicks]
 * consecutive ticks (~5 min) — so passing clouds don't chatter the car's contactor. While a session
 * is active the current tracks the surplus but never drops below the minimum (a car won't charge
 * below it; the shortfall is imported and the battery covers it first).
 *
 * NOTE: the deadbeat cancellation is only exact when grid and battery are sampled simultaneously and
 * report instantly. They are not — the SMA smooths its reported power, and grid (P1/HTTP) and
 * battery (Modbus) are polled on independent loops — so on fast ramps the loop can still briefly
 * overshoot and discharge while exporting. Removing that residual needs synchronised sampling, not
 * a lower gain.
 */
class SurplusPriorityStrategy(
    private val gain: Double = 1.0,
    private val gridDeadbandW: Int = 50,
    private val startAfterTicks: Int = 12, // ~60 s at 5 s cadence
    private val stopAfterTicks: Int = 60,  // ~5 min at 5 s cadence
) : Strategy {

    private var sessionActive = false
    private var startTicks = 0
    private var stopTicks = 0

    override fun decide(snapshot: WorldSnapshot): ControlDecisions {
        // Available power = what charger + battery currently use, minus any grid import (or plus export).
        // gridPower: negative = exporting (surplus), positive = importing (deficit)
        val available = snapshot.chargerPower.value + snapshot.batteryPower.value - snapshot.gridPower.value

        // Heat pump: unrestricted when surplus, throttled to currently-available headroom on deficit
        val heatpumpMode: ConsumeMode = if (available >= 0) {
            ConsumeMode.Unrestricted
        } else {
            val headroom = max(0, snapshot.heatpumpPower.value + available)
            ConsumeMode.SuggestConsumeUpTo(Watt(headroom))
        }

        val override = snapshot.chargerOverride
        val chargerCommand: ChargerCommand = if (override != null) {
            // Forced command (Stop / Fixed): sync the session flag so a later switch back to
            // solar starts from reality — unplug resets it, an open fixed session continues.
            sessionActive = override is ChargerCommand.Charge
            startTicks = 0
            stopTicks = 0
            override
        } else {
            solarSessionCommand(available, snapshot.chargerMinAmps, snapshot.chargerMaxAmps)
        }

        // Battery balances the MEASURED grid, not the charger setpoint: the charger's real draw —
        // whatever the car actually takes — already shows up in gridPower. Feeding the commanded
        // setpoint forward instead parked the grid in steady-state export whenever the car drew less
        // than commanded (e.g. car full). The battery's own-power deadbeat (gain 1) is unchanged.
        val batteryTarget = batteryTarget(snapshot.batteryPower.value, snapshot.gridPower.value)

        return ControlDecisions(
            chargerCommand = chargerCommand,
            batteryCommand = BatteryCommand.SetPower(batteryTarget),
            heatpumpConsumeMode = heatpumpMode
        )
    }

    /**
     * Charger command for an unforced (solar) tick: hysteresis-gated session start/stop, and while
     * a session is active, the surplus mapped to amps with the min floor applied (see class KDoc).
     */
    private fun solarSessionCommand(available: Int, minAmps: Int, maxAmps: Int): ChargerCommand {
        val minW = minAmps * VOLTAGE
        if (!sessionActive) {
            startTicks = if (available >= minW) startTicks + 1 else 0
            if (startTicks >= startAfterTicks) {
                sessionActive = true
                startTicks = 0
                stopTicks = 0
            }
        } else {
            stopTicks = if (available < minW / 2) stopTicks + 1 else 0
            if (stopTicks >= stopAfterTicks) {
                sessionActive = false
                startTicks = 0
                stopTicks = 0
            }
        }
        if (!sessionActive) return ChargerCommand.Stop
        return ChargerCommand.Charge(Ampere((available / VOLTAGE).coerceIn(minAmps, maxAmps)))
    }

    override fun decideDegraded(gridPower: Watt, batteryPower: Watt): Watt =
        batteryTarget(batteryPower.value, gridPower.value)

    /**
     * New battery setpoint = current power minus the correction toward grid = 0, ignoring imbalances
     * inside the deadband. Positive = charge, negative = discharge.
     */
    private fun batteryTarget(batteryPower: Int, gridImbalance: Int): Watt {
        val error = if (abs(gridImbalance) <= gridDeadbandW) 0 else gridImbalance
        return Watt(batteryPower - (gain * error).roundToInt())
    }
}
