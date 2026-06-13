package io.konektis.ems

import io.konektis.devices.Ampere
import io.konektis.devices.Watt
import io.konektis.devices.charger.ChargerCommand
import io.konektis.devices.smartConsumers.ConsumeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurplusPriorityStrategyTest {

    // gain = 1.0 (deadbeat: full correction per tick), deadband = 50 W (the production defaults).
    private val strategy = SurplusPriorityStrategy()

    private fun snapshot(
        gridPower: Int = 0,       // negative=export, positive=import
        solarPower: Int = 0,
        chargerPower: Int = 0,
        batteryPower: Int = 0,
        heatpumpPower: Int = 0,
        batteryCharge: UShort = 50u,
        chargerMinAmps: Int = 6,
        chargerMaxAmps: Int = 32,
        chargerOverride: ChargerCommand? = null
    ) = WorldSnapshot(
        gridPower = Watt(gridPower),
        solarPower = Watt(solarPower),
        batteryCharge = batteryCharge,
        batteryPower = Watt(batteryPower),
        chargerPower = Watt(chargerPower),
        heatpumpPower = Watt(heatpumpPower),
        chargerMinAmps = chargerMinAmps,
        chargerMaxAmps = chargerMaxAmps,
        chargerOverride = chargerOverride
    )

    @Test
    fun `large solar surplus — charger gets max amps, battery absorbs the measured export`() {
        // Exporting 2000W, charger at 2000W. available = 4000W -> 17A (3910W) for the charger.
        // Battery balances the MEASURED grid: target = batteryPower - gridPower = 0 - (-2000) = +2000W.
        val decisions = strategy.decide(snapshot(gridPower = -2000, chargerPower = 2000))
        assertEquals(ChargerCommand.Charge(Ampere(17)), decisions.chargerCommand)
        assertEquals(BatteryCommand.SetPower(Watt(2000)), decisions.batteryCommand)
        assertTrue(decisions.heatpumpConsumeMode is ConsumeMode.Unrestricted)
    }

    @Test
    fun `importing from grid — charger reduces and battery covers the measured import`() {
        // Importing 500W, charger at 3000W. available = 2500W -> 10A (2300W) for the charger.
        // Battery balances the MEASURED grid: target = 0 - 500 = -500W (discharge to cover the import).
        val decisions = strategy.decide(snapshot(gridPower = 500, chargerPower = 3000))
        assertEquals(ChargerCommand.Charge(Ampere(10)), decisions.chargerCommand)
        assertEquals(BatteryCommand.SetPower(Watt(-500)), decisions.batteryCommand)
    }

    @Test
    fun `surplus below charger minimum — charger held at minimum during the session`() {
        // Exporting 200W. 200/230 < 6A, but an active solar session floors the charger at its 6A minimum
        // (the difference is imported). Battery balances the measured grid: 0 - (-200) = +200W.
        val decisions = strategy.decide(snapshot(gridPower = -200, chargerPower = 0, chargerMinAmps = 6))
        assertEquals(ChargerCommand.Charge(Ampere(6)), decisions.chargerCommand)
        assertEquals(BatteryCommand.SetPower(Watt(200)), decisions.batteryCommand)
    }

    @Test
    fun `zero solar and importing — charger held at minimum, battery covers the measured deficit`() {
        // Importing 1500W, no solar. The active solar session still holds the charger at 6A min.
        // Battery balances the measured grid: 0 - 1500 = -1500W (discharge).
        val decisions = strategy.decide(snapshot(gridPower = 1500, chargerPower = 0))
        assertEquals(ChargerCommand.Charge(Ampere(6)), decisions.chargerCommand)
        assertEquals(BatteryCommand.SetPower(Watt(-1500)), decisions.batteryCommand)
    }

    @Test
    fun `charger surplus clamped to max amps`() {
        // Exporting 10000W, charger at 6000W. available = 16000W -> clamped 32A (7360W) for the charger.
        // Battery balances the MEASURED grid: target = 0 - (-10000) = +10000W.
        val decisions = strategy.decide(snapshot(gridPower = -10000, chargerPower = 6000, chargerMaxAmps = 32))
        assertEquals(ChargerCommand.Charge(Ampere(32)), decisions.chargerCommand)
        assertEquals(BatteryCommand.SetPower(Watt(10000)), decisions.batteryCommand)
    }

    @Test
    fun `heat pump unrestricted when surplus available`() {
        val decisions = strategy.decide(snapshot(gridPower = -500, chargerPower = 0))
        assertTrue(decisions.heatpumpConsumeMode is ConsumeMode.Unrestricted)
    }

    @Test
    fun `heat pump throttled when in deficit`() {
        // Importing 800W, heat pump using 1000W
        val decisions = strategy.decide(snapshot(gridPower = 800, chargerPower = 0, heatpumpPower = 1000))
        val mode = decisions.heatpumpConsumeMode
        assertTrue(mode is ConsumeMode.SuggestConsumeUpTo)
    }

    @Test
    fun `holds current battery power when the grid is balanced within the deadband`() {
        // Battery already charging 500W; measured grid only 40W off -> within the 50W deadband -> hold 500W.
        // Surplus maps to 2A < 6A, but the active solar session floors the charger at 6A min.
        val decisions = strategy.decide(snapshot(gridPower = -40, chargerPower = 0, batteryPower = 500))
        assertEquals(ChargerCommand.Charge(Ampere(6)), decisions.chargerCommand)
        assertEquals(BatteryCommand.SetPower(Watt(500)), decisions.batteryCommand)
    }

    @Test
    fun `no surplus during an active solar session still holds the charger at minimum`() {
        // Grid balanced (no surplus, no import). The surplus path (active solar session) still floors
        // the charger at its minimum rather than dropping to 0 — preventing on/off relay chatter.
        val decisions = strategy.decide(snapshot(gridPower = 0, chargerPower = 0, chargerMinAmps = 6))
        assertEquals(ChargerCommand.Charge(Ampere(6)), decisions.chargerCommand)
    }

    @Test
    fun `deficit discharges battery to cover it in one step`() {
        val decisions = strategy.decide(snapshot(gridPower = 1500))
        assertEquals(BatteryCommand.SetPower(Watt(-1500)), decisions.batteryCommand)
    }

    @Test
    fun `corrects an over-discharge in one step instead of dumping into the grid`() {
        // Battery discharging 2000W while exporting 1500W (a load just dropped). True deficit is only
        // 500W (available = -2000 + 1500 = -500), so the battery should ease to -500W, not stay at
        // -2000W feeding the grid. Deadbeat lands that in one tick.
        val decisions = strategy.decide(snapshot(gridPower = -1500, chargerPower = 0, batteryPower = -2000))
        assertEquals(BatteryCommand.SetPower(Watt(-500)), decisions.batteryCommand)
    }

    @Test
    fun `degraded importing grid discharges to cover the deficit`() {
        // currentBattery=200 (charging), grid=+600 importing -> target = 200 - 600 = -400
        assertEquals(Watt(-400), strategy.decideDegraded(Watt(600), Watt(200)))
    }

    @Test
    fun `degraded exporting grid charges the surplus`() {
        // currentBattery=200, grid=-500 exporting -> target = 200 - (-500) = 700
        assertEquals(Watt(700), strategy.decideDegraded(Watt(-500), Watt(200)))
    }

    @Test
    fun `degraded balanced grid within deadband holds current battery power`() {
        assertEquals(Watt(300), strategy.decideDegraded(Watt(0), Watt(300)))
    }

    @Test
    fun `degraded small imbalance within deadband holds current battery power`() {
        assertEquals(Watt(300), strategy.decideDegraded(Watt(40), Watt(300)))
    }

    @Test
    fun `charger override is used verbatim, battery still balances the measured grid`() {
        // override 5A; grid -2000 export, charger 0. Charger command = override.
        // Battery balances the MEASURED grid: target = 0 - (-2000) = +2000W (independent of the override).
        val d = strategy.decide(snapshot(gridPower = -2000, chargerPower = 0, chargerOverride = ChargerCommand.Charge(Ampere(5))))
        assertEquals(ChargerCommand.Charge(Ampere(5)), d.chargerCommand)
        assertEquals(BatteryCommand.SetPower(Watt(2000)), d.batteryCommand)
    }

    @Test
    fun `fixed charger the car under-draws — battery soaks the measured surplus, not the setpoint`() {
        // Fixed 16A commanded, but the car only draws 1000W (measured), so the grid is still exporting
        // 2000W. The OLD projection would discharge the battery (projectedGrid = -2000 + 3680 - 1000 =
        // +680 -> battery -680, discharging while exporting). Balancing the MEASURED grid instead:
        // target = 0 - (-2000) = +2000W (charge), soaking the surplus the car won't take.
        val d = strategy.decide(snapshot(gridPower = -2000, chargerPower = 1000, chargerOverride = ChargerCommand.Charge(Ampere(16))))
        assertEquals(ChargerCommand.Charge(Ampere(16)), d.chargerCommand)
        assertEquals(BatteryCommand.SetPower(Watt(2000)), d.batteryCommand)
    }

    @Test
    fun `charger override Stop stops the charger regardless of surplus`() {
        val d = strategy.decide(snapshot(gridPower = -5000, chargerPower = 0, chargerOverride = ChargerCommand.Stop))
        assertEquals(ChargerCommand.Stop, d.chargerCommand)
    }
}
