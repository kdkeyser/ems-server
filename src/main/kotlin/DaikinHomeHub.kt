package io.konektis

import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest
import io.klogging.NoCoLogging
import org.kotlincrypto.bitops.endian.Endian

private const val MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER: Int = 50
private const val MODBUS_HOLDING_REGISTER_SMART_GRID: Int = 55
private const val MODBUS_HOLDING_REGISTER_SMART_GRID_MAX_POWER: Int = 56

class DaikinHomeHub(private val host: String) : NoCoLogging {

    private val client = ModbusTCPClient(host)

    /* fun update(maxCurrent: Int) {
        withClient { client ->

            client.writeSingleRegister(1, WriteSingleRegisterRequest(MODBUS_REGISTER_MAX_CURRENT_CHARGING, maxCurrent))
        }

    }*/

    fun getCurrentPowerUsage(): Int {
        val result = client.withClient { client ->
            client.readInputRegisters(1, ReadInputRegistersRequest(MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER, 1))
        }

        val usage = Endian.Big.shortFrom(result.registers, 0) * 10
        return usage
    }
}