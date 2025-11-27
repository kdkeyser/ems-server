package io.konektis.devices.Heatpump

import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest
import io.klogging.Klogging
import io.klogging.NoCoLogging
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

private const val MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER: Int = 50
private const val MODBUS_HOLDING_REGISTER_SMART_GRID: Int = 55
private const val MODBUS_HOLDING_REGISTER_SMART_GRID_MAX_POWER: Int = 56

class DaikinHeatpump(private val host: String) : Klogging, SmartConsumer {

    private val homeHub = DaikinHomeHub(host)
    private var internalState: DeviceUpdate<SmartConsumerState>? = null
    private val mutex = Mutex()

    override suspend fun update() {
        mutex.withLock {
            logger.debug { "Updating Daikin Heat Pump"}
            internalState = DeviceUpdate(
                GlobalTimeSource.source.markNow(),
                homeHub.getState()
            )
            logger.debug { "Updated Daikin Heat Pump: $internalState"}
        }
    }

    override suspend fun getState(): DeviceUpdate<SmartConsumerState>? {
        mutex.withLock {
            return(internalState)
        }
    }

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