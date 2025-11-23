package io.konektis.devices.Heatpump

import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest
import io.klogging.NoCoLogging
import io.konektis.GlobalTimeSource
import io.konektis.ModbusTCPClient
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.smartConsumers.SmartConsumerState
import org.kotlincrypto.bitops.endian.Endian

private const val MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER: Int = 50
private const val MODBUS_HOLDING_REGISTER_SMART_GRID: Int = 55
private const val MODBUS_HOLDING_REGISTER_SMART_GRID_MAX_POWER: Int = 56

class DaikinHeatpump(private val host: String) : SmartConsumer {

    private val homeHub = DaikinHomeHub(host)
    private var internalState: DeviceUpdate<SmartConsumerState>? = null

    override suspend fun update() {
        internalState = DeviceUpdate(
            GlobalTimeSource.source.markNow(),
            homeHub.getState()
        )
    }

    override val state: DeviceUpdate<SmartConsumerState>?
        get() = internalState

    override suspend fun setConsumeMode(consumeMode: ConsumeMode) {
        TODO("Not yet implemented")
    }

}

private class DaikinHomeHub(private val host: String) : NoCoLogging {

    private val client = ModbusTCPClient(host)

    /* fun update(maxCurrent: Int) {
        withClient { client ->

            client.writeSingleRegister(1, WriteSingleRegisterRequest(MODBUS_REGISTER_MAX_CURRENT_CHARGING, maxCurrent))
        }

    }*/

    fun getState(): SmartConsumerState {
        val result = client.withClient { client ->
            client.readInputRegisters(1, ReadInputRegistersRequest(MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER, 1))
        }

        val usage = Endian.Big.shortFrom(result.registers, 0) * 10
        return SmartConsumerState(Watt(usage), ConsumeMode.Unrestricted)
    }
}