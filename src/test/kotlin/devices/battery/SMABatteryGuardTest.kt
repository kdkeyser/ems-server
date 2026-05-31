package io.konektis.devices.battery

import io.konektis.devices.Watt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeBatteryModbus : BatteryModbus {
    // record (register, first-4-byte int value) for each write
    val writes = mutableListOf<Pair<Int, Int>>()
    var failNextWrite = false

    override suspend fun readInput(register: Int, count: Int) =
        throw UnsupportedOperationException("not needed for guard tests")

    override suspend fun writeRegisters(register: Int, count: Int, values: ByteArray) {
        if (failNextWrite) { failNextWrite = false; throw RuntimeException("modbus write failed") }
        val v = ((values[0].toInt() and 0xFF) shl 24) or
                ((values[1].toInt() and 0xFF) shl 16) or
                ((values[2].toInt() and 0xFF) shl 8) or
                (values[3].toInt() and 0xFF)
        writes.add(register to v)
    }
}

class SMABatteryGuardTest {
    private val CONTROL = 40151
    private val POWER = 40149

    @Test fun `first setChargingPower writes 802 then target`() = runTest {
        val m = FakeBatteryModbus()
        val b = SMABattery(m)
        b.setChargingPower(Watt(1000))
        assertEquals(listOf(CONTROL to 802, POWER to 1000), m.writes)
    }

    @Test fun `within-epsilon repeat writes nothing`() = runTest {
        val m = FakeBatteryModbus()
        val b = SMABattery(m)
        b.setChargingPower(Watt(1000))
        m.writes.clear()
        b.setChargingPower(Watt(1010)) // delta 10 <= 25
        assertEquals(emptyList(), m.writes)
    }

    @Test fun `beyond-epsilon change writes target only`() = runTest {
        val m = FakeBatteryModbus()
        val b = SMABattery(m)
        b.setChargingPower(Watt(1000))
        m.writes.clear()
        b.setChargingPower(Watt(1100)) // delta 100 > 25
        assertEquals(listOf(POWER to 1100), m.writes)
    }

    @Test fun `release writes 803 only when engaged`() = runTest {
        val m = FakeBatteryModbus()
        val b = SMABattery(m)
        b.releaseToInverter()                 // not engaged → no-op
        assertEquals(emptyList(), m.writes)
        b.setChargingPower(Watt(500))
        m.writes.clear()
        b.releaseToInverter()                 // engaged → 803
        assertEquals(listOf(CONTROL to 803), m.writes)
        m.writes.clear()
        b.releaseToInverter()                 // already released → no-op
        assertEquals(emptyList(), m.writes)
    }

    @Test fun `re-engages with 802 after a release`() = runTest {
        val m = FakeBatteryModbus()
        val b = SMABattery(m)
        b.setChargingPower(Watt(500))
        b.releaseToInverter()
        m.writes.clear()
        b.setChargingPower(Watt(500))         // must re-arm 802 even if target unchanged
        assertEquals(listOf(CONTROL to 802, POWER to 500), m.writes)
    }

    @Test fun `failed target write does not advance guard state`() = runTest {
        val m = FakeBatteryModbus()
        val b = SMABattery(m)
        b.setChargingPower(Watt(500))         // engaged, last=500
        m.writes.clear()
        m.failNextWrite = true
        runCatching { b.setChargingPower(Watt(2000)) } // throws inside
        // next call must retry the target (lastTarget not advanced to 2000)
        b.setChargingPower(Watt(2000))
        assertEquals(listOf(POWER to 2000), m.writes)
    }
}
