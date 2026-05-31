package io.konektis.ems

import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurplusPriorityStrategyTest {

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
    fun `large solar surplus — charger gets max amps, battery absorbs remainder`() {
        // Exporting 2000W (gridPower=-2000), charger currently at 2000W
        // Available = 2000 (charger) - (-2000) (grid export) = 4000W
        // 4000W / 230V = 17.39A -> 17A for charger
        // remainder = 4000 - 17*230 = 4000 - 3910 = 90W to battery
        val decisions = strategy.decide(snapshot(gridPower = -2000, chargerPower = 2000))
        assertEquals(17, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(90)), decisions.batteryCommand)
        assertTrue(decisions.heatpumpConsumeMode is ConsumeMode.Unrestricted)
    }

    @Test
    fun `importing from grid — charger should reduce`() {
        // Importing 500W (gridPower=500), charger at 3000W
        // Available = 3000 - 500 = 2500W
        // 2500 / 230 = 10.86A -> 10A
        // remainder = 2500 - 10*230 = 2500 - 2300 = 200W to battery
        val decisions = strategy.decide(snapshot(gridPower = 500, chargerPower = 3000))
        assertEquals(10, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(200)), decisions.batteryCommand)
    }

    @Test
    fun `surplus below charger minimum — charger stops, battery charges`() {
        // Exporting only 200W (gridPower=-200), charger at 0W
        // Available = 0 - (-200) = 200W
        // 200 / 230 = 0.87A < 6A minimum -> charger = 0A
        // All 200W goes to battery
        val decisions = strategy.decide(snapshot(gridPower = -200, chargerPower = 0, chargerMinAmps = 6))
        assertEquals(0, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(200)), decisions.batteryCommand)
    }

    @Test
    fun `zero solar and importing — charger stops, battery covers deficit`() {
        // Importing 1500W (gridPower=1500), charger at 0W, no solar
        // Available = 0 - 1500 = -1500W (deficit)
        // Charger = 0A (deficit)
        // Battery = -1500W (discharge to cover)
        val decisions = strategy.decide(snapshot(gridPower = 1500, chargerPower = 0))
        assertEquals(0, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(-1500)), decisions.batteryCommand)
    }

    @Test
    fun `charger surplus clamped to max amps`() {
        // Exporting 10000W (gridPower=-10000), charger at 6000W
        // Available = 6000 + 10000 = 16000W
        // 16000 / 230 = 69.5A -> clamped to 32A max
        // remainder = 16000 - 32*230 = 16000 - 7360 = 8640W to battery
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
    fun `battery absorbs remaining surplus after charger allocation`() {
        // gridPower=-3000 (exporting), available=3000, charger gets 13A (2990W), battery gets remainder
        // available=3000, chargerAmps=13, chargerConsumption=2990, batteryTarget=10
        val decisions = strategy.decide(snapshot(gridPower = -3000))
        assertEquals(BatteryCommand.SetPower(Watt(10)), decisions.batteryCommand)
    }

    @Test
    fun `deficit discharges battery`() {
        val decisions = strategy.decide(snapshot(gridPower = 1500))
        assertEquals(BatteryCommand.SetPower(Watt(-1500)), decisions.batteryCommand)
    }

    @Test
    fun `degraded importing grid discharges more`() {
        // currentBattery=200 (charging), grid=+600 importing → target = 200 - 600 = -400
        assertEquals(Watt(-400), strategy.decideDegraded(Watt(600), Watt(200)))
    }

    @Test
    fun `degraded exporting grid charges more`() {
        // currentBattery=200, grid=-500 exporting → target = 200 - (-500) = 700
        assertEquals(Watt(700), strategy.decideDegraded(Watt(-500), Watt(200)))
    }

    @Test
    fun `degraded balanced grid holds current battery power`() {
        assertEquals(Watt(300), strategy.decideDegraded(Watt(0), Watt(300)))
    }
}
