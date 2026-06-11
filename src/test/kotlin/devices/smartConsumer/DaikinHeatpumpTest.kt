package io.konektis.devices.Heatpump

import com.digitalpetri.modbus.pdu.ReadInputRegistersResponse
import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// If this constructor does not compile, mirror how SMABatteryGuardTest fakes ReadInputRegistersResponse.
private class FakeHeatpumpModbus : HeatpumpModbus {
    val writes = mutableListOf<Pair<Int, Int>>()
    override suspend fun readInput(register: Int, count: Int) =
        ReadInputRegistersResponse(ByteArray(count * 2))
    override suspend fun writeHolding(register: Int, value: Int) { writes.add(register to value) }
}

class DaikinHeatpumpTest {

    @Test fun `zero-watt suggestion also writes the max-power register`() = runTest {
        val fake = FakeHeatpumpModbus()
        DaikinHeatpump(fake).setConsumeMode(ConsumeMode.SuggestConsumeUpTo(Watt(0)))
        assertEquals(listOf(55 to 1, 56 to 0), fake.writes)
    }

    @Test fun `unchanged command is not rewritten every tick`() = runTest {
        val fake = FakeHeatpumpModbus()
        val hp = DaikinHeatpump(fake)
        hp.setConsumeMode(ConsumeMode.SuggestConsumeUpTo(Watt(1500)))
        hp.setConsumeMode(ConsumeMode.SuggestConsumeUpTo(Watt(1500)))
        assertEquals(listOf(55 to 1, 56 to 1500), fake.writes)
    }

    @Test fun `unrestricted writes only the mode register`() = runTest {
        val fake = FakeHeatpumpModbus()
        val hp = DaikinHeatpump(fake)
        hp.setConsumeMode(ConsumeMode.SuggestConsumeUpTo(Watt(1000)))
        hp.setConsumeMode(ConsumeMode.Unrestricted)
        assertEquals(listOf(55 to 1, 56 to 1000, 55 to 0), fake.writes)
    }
}
