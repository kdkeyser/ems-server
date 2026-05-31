package io.konektis.ems

import io.konektis.devices.Watt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpleGridCompensationStrategyTest {

    private val strategy = SimpleGridCompensationStrategy()

    private fun snapshot(gridPower: Int, batteryPower: Int = 0) = WorldSnapshot(
        gridPower = Watt(gridPower),
        solarPower = Watt(0),
        batteryCharge = 50u,
        batteryPower = Watt(batteryPower),
        chargerPower = Watt(0),
        heatpumpPower = Watt(0),
        chargerMinAmps = 6,
        chargerMaxAmps = 32,
    )

    @Test
    fun `importing means the battery must discharge`() {
        // Importing 1000W, battery idle -> discharge 1000W (negative).
        val d = strategy.decide(snapshot(gridPower = 1000, batteryPower = 0))
        assertEquals(BatteryCommand.SetPower(Watt(-1000)), d.batteryCommand)
    }

    @Test
    fun `exporting means the battery must charge`() {
        // Exporting 1000W, battery idle -> charge 1000W (positive).
        val d = strategy.decide(snapshot(gridPower = -1000, batteryPower = 0))
        assertEquals(BatteryCommand.SetPower(Watt(1000)), d.batteryCommand)
    }

    @Test
    fun `the battery's current power is taken into account`() {
        // Already discharging 300W and still importing 1000W -> must discharge a further 1000W total 1300W.
        val d = strategy.decide(snapshot(gridPower = 1000, batteryPower = -300))
        assertEquals(BatteryCommand.SetPower(Watt(-1300)), d.batteryCommand)
    }

    @Test
    fun `imbalance within the deadband holds the battery steady`() {
        val d = strategy.decide(snapshot(gridPower = 40, batteryPower = 500))
        assertEquals(BatteryCommand.SetPower(Watt(500)), d.batteryCommand)
    }

    @Test
    fun `it never touches the charger or the heat pump`() {
        val d = strategy.decide(snapshot(gridPower = 1000, batteryPower = 0))
        assertNull(d.chargerMaxAmps)
        assertNull(d.heatpumpConsumeMode)
    }

    @Test
    fun `the new battery power cancels the grid imbalance to zero`() {
        // Property: outside the deadband, applying the target drives grid to 0; inside it holds.
        val battery = 300
        for (grid in listOf(-3000, -1000, -200, -51, -50, 0, 50, 51, 200, 1000, 3000)) {
            val target = strategy.decideDegraded(Watt(grid), Watt(battery)).value
            val predictedGrid = grid + (target - battery)   // moving the battery shifts grid 1:1
            assertTrue(
                abs(predictedGrid) <= 50,
                "grid=$grid battery=$battery target=$target predictedGrid=$predictedGrid"
            )
        }
    }

    @Test
    fun `degraded path uses the same compensation`() {
        assertEquals(Watt(-1000), strategy.decideDegraded(Watt(1000), Watt(0)))
        assertEquals(Watt(1000), strategy.decideDegraded(Watt(-1000), Watt(0)))
    }
}
