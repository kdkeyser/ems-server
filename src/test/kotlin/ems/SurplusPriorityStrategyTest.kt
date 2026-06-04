package io.konektis.ems

import io.konektis.devices.Watt
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
        chargerOverrideAmps: Int? = null
    ) = WorldSnapshot(
        gridPower = Watt(gridPower),
        solarPower = Watt(solarPower),
        batteryCharge = batteryCharge,
        batteryPower = Watt(batteryPower),
        chargerPower = Watt(chargerPower),
        heatpumpPower = Watt(heatpumpPower),
        chargerMinAmps = chargerMinAmps,
        chargerMaxAmps = chargerMaxAmps,
        chargerOverrideAmps = chargerOverrideAmps
    )

    @Test
    fun `large solar surplus — charger gets max amps, battery absorbs the measured export`() {
        // Exporting 2000W, charger at 2000W. available = 4000W -> 17A (3910W) for the charger.
        // Battery balances the MEASURED grid: target = batteryPower - gridPower = 0 - (-2000) = +2000W.
        val decisions = strategy.decide(snapshot(gridPower = -2000, chargerPower = 2000))
        assertEquals(17, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(2000)), decisions.batteryCommand)
        assertTrue(decisions.heatpumpConsumeMode is ConsumeMode.Unrestricted)
    }

    @Test
    fun `importing from grid — charger reduces and battery covers the measured import`() {
        // Importing 500W, charger at 3000W. available = 2500W -> 10A (2300W) for the charger.
        // Battery balances the MEASURED grid: target = 0 - 500 = -500W (discharge to cover the import).
        val decisions = strategy.decide(snapshot(gridPower = 500, chargerPower = 3000))
        assertEquals(10, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(-500)), decisions.batteryCommand)
    }

    @Test
    fun `surplus below charger minimum — charger stops, battery charges the surplus`() {
        // Exporting 200W, charger off. 200/230 < 6A min -> charger = 0. projectedGrid = -200 -> +200W.
        val decisions = strategy.decide(snapshot(gridPower = -200, chargerPower = 0, chargerMinAmps = 6))
        assertEquals(0, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(200)), decisions.batteryCommand)
    }

    @Test
    fun `zero solar and importing — charger stops, battery covers the deficit`() {
        // Importing 1500W, charger off, no solar. projectedGrid = 1500 -> battery -1500W (discharge).
        val decisions = strategy.decide(snapshot(gridPower = 1500, chargerPower = 0))
        assertEquals(0, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(-1500)), decisions.batteryCommand)
    }

    @Test
    fun `charger surplus clamped to max amps`() {
        // Exporting 10000W, charger at 6000W. available = 16000W -> clamped 32A (7360W) for the charger.
        // Battery balances the MEASURED grid: target = 0 - (-10000) = +10000W.
        val decisions = strategy.decide(snapshot(gridPower = -10000, chargerPower = 6000, chargerMaxAmps = 32))
        assertEquals(32, decisions.chargerMaxAmps)
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
        // available = 0 + 500 + 40 = 540W -> 2A < 6A min -> charger 0.
        val decisions = strategy.decide(snapshot(gridPower = -40, chargerPower = 0, batteryPower = 500))
        assertEquals(0, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(500)), decisions.batteryCommand)
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
        // override 5A; grid -2000 export, charger 0. Charger amps = override = 5.
        // Battery balances the MEASURED grid: target = 0 - (-2000) = +2000W (independent of the override).
        val d = strategy.decide(snapshot(gridPower = -2000, chargerPower = 0, chargerOverrideAmps = 5))
        assertEquals(5, d.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(2000)), d.batteryCommand)
    }

    @Test
    fun `fixed charger the car under-draws — battery soaks the measured surplus, not the setpoint`() {
        // Fixed 16A commanded, but the car only draws 1000W (measured), so the grid is still exporting
        // 2000W. The OLD projection would discharge the battery (projectedGrid = -2000 + 3680 - 1000 =
        // +680 -> battery -680, discharging while exporting). Balancing the MEASURED grid instead:
        // target = 0 - (-2000) = +2000W (charge), soaking the surplus the car won't take.
        val d = strategy.decide(snapshot(gridPower = -2000, chargerPower = 1000, chargerOverrideAmps = 16))
        assertEquals(16, d.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(2000)), d.batteryCommand)
    }

    @Test
    fun `charger override zero stops the charger regardless of surplus`() {
        val d = strategy.decide(snapshot(gridPower = -5000, chargerPower = 0, chargerOverrideAmps = 0))
        assertEquals(0, d.chargerMaxAmps)
    }

    @Test
    fun `charger override clamped to max amps`() {
        val d = strategy.decide(snapshot(chargerOverrideAmps = 100, chargerMaxAmps = 32))
        assertEquals(32, d.chargerMaxAmps)
    }
}
