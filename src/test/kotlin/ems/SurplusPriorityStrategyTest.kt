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
        chargerMaxAmps: Int = 32
    ) = WorldSnapshot(
        gridPower = Watt(gridPower),
        solarPower = Watt(solarPower),
        batteryCharge = batteryCharge,
        batteryPower = Watt(batteryPower),
        chargerPower = Watt(chargerPower),
        heatpumpPower = Watt(heatpumpPower),
        chargerMinAmps = chargerMinAmps,
        chargerMaxAmps = chargerMaxAmps
    )

    @Test
    fun `large solar surplus — charger gets max amps, battery absorbs the remainder`() {
        // Exporting 2000W, charger at 2000W. available = 4000W -> 17A (3910W).
        // projectedGrid = -2000 + 3910 - 2000 = -90 (still 90W export). battery = -(-90) = +90W.
        val decisions = strategy.decide(snapshot(gridPower = -2000, chargerPower = 2000))
        assertEquals(17, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(90)), decisions.batteryCommand)
        assertTrue(decisions.heatpumpConsumeMode is ConsumeMode.Unrestricted)
    }

    @Test
    fun `importing from grid — charger reduces and battery charges the freed surplus`() {
        // Importing 500W, charger at 3000W. available = 2500W -> 10A (2300W).
        // projectedGrid = 500 + 2300 - 3000 = -200 (charger reduction now over-produces 200W).
        // battery = +200W. Charging while *measured* grid imports is correct: the import is caused
        // by the old charger setpoint we are simultaneously backing off.
        val decisions = strategy.decide(snapshot(gridPower = 500, chargerPower = 3000))
        assertEquals(10, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(200)), decisions.batteryCommand)
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
        // Exporting 10000W, charger at 6000W. available = 16000W -> clamped 32A (7360W).
        // projectedGrid = -10000 + 7360 - 6000 = -8640. battery = +8640W.
        val decisions = strategy.decide(snapshot(gridPower = -10000, chargerPower = 6000, chargerMaxAmps = 32))
        assertEquals(32, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(8640)), decisions.batteryCommand)
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
    fun `tiny residual within the deadband leaves the battery untouched`() {
        // grid=-3000, charger off -> 13A (2990W). projectedGrid = -3000 + 2990 = -10W.
        // |10| <= 50W deadband, so the battery holds at its current power (0W) instead of chasing 10W.
        val decisions = strategy.decide(snapshot(gridPower = -3000, batteryPower = 0))
        assertEquals(BatteryCommand.SetPower(Watt(0)), decisions.batteryCommand)
    }

    @Test
    fun `holds current battery power when the grid is balanced within the deadband`() {
        // Battery already charging 500W, grid only 40W off -> within deadband -> hold 500W.
        // available = 0 + 500 + 40 = 540W -> 2A < 6A min -> charger 0. projectedGrid = -40.
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
}
