package io.konektis.devices.Heatpump

import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest
import com.digitalpetri.modbus.pdu.ReadInputRegistersResponse
import com.digitalpetri.modbus.pdu.WriteSingleRegisterRequest
import io.klogging.Klogging
import io.konektis.GlobalTimeSource
import io.konektis.ModbusTCPClient
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.smartConsumers.SmartConsumerState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kotlincrypto.bitops.endian.Endian

// Current total heat pump power consumption, unit 10W (multiply raw value by 10 for W)
private const val MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER: Int = 50
// SG-Ready mode: 0=normal, 1=lock (min operation), 2=recommended, 3=max operation
private const val MODBUS_HOLDING_REGISTER_SMART_GRID: Int = 55
// Maximum power suggestion to heat pump when in SG-Ready mode, W
private const val MODBUS_HOLDING_REGISTER_SMART_GRID_MAX_POWER: Int = 56

/** Minimal seam over the raw Modbus ops, so the SG-Ready write logic is testable. */
interface HeatpumpModbus {
    suspend fun readInput(register: Int, count: Int): ReadInputRegistersResponse
    suspend fun writeHolding(register: Int, value: Int)
}

private class RealHeatpumpModbus(host: String) : HeatpumpModbus {
    private val client = ModbusTCPClient(host)

    override suspend fun readInput(register: Int, count: Int): ReadInputRegistersResponse =
        client.withClient { it.readInputRegisters(1, ReadInputRegistersRequest(register, count)) }

    override suspend fun writeHolding(register: Int, value: Int) {
        client.withClient { it.writeSingleRegister(1, WriteSingleRegisterRequest(register, value)) }
    }
}

class DaikinHeatpump(private val modbus: HeatpumpModbus) : Klogging, SmartConsumer {
    constructor(host: String) : this(RealHeatpumpModbus(host))

    private var internalState: DeviceUpdate<SmartConsumerState>? = null
    private val mutex = Mutex()
    /** Last (mode, maxPower) written, to skip rewriting the same command every 5s tick. */
    private var lastWritten: Pair<Int, Int>? = null

    override suspend fun update() {
        mutex.withLock {
            internalState = DeviceUpdate(GlobalTimeSource.source.markNow(), readState())
            logger.trace { "DaikinHeatpump: $internalState" }
        }
    }

    override suspend fun getState(): DeviceUpdate<SmartConsumerState>? {
        mutex.withLock {
            return internalState
        }
    }

    override suspend fun setConsumeMode(consumeMode: ConsumeMode) {
        mutex.withLock {
            val (mode, maxPower) = when (consumeMode) {
                is ConsumeMode.Unrestricted -> 0 to 0
                is ConsumeMode.SuggestConsumeUpTo -> 1 to consumeMode.power.value
            }
            if (lastWritten == mode to maxPower) return@withLock
            modbus.writeHolding(MODBUS_HOLDING_REGISTER_SMART_GRID, mode)
            // Always write the cap in SG mode — including 0 W. Skipping 0 left the previous,
            // higher suggestion active exactly when the EMS wanted full throttling.
            if (mode == 1) modbus.writeHolding(MODBUS_HOLDING_REGISTER_SMART_GRID_MAX_POWER, maxPower)
            lastWritten = mode to maxPower
        }
    }

    private suspend fun readState(): SmartConsumerState {
        val result = modbus.readInput(MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER, 1)
        val usage = Endian.Big.shortFrom(result.registers, 0) * 10
        return SmartConsumerState(Watt(usage), ConsumeMode.Unrestricted)
    }
}
