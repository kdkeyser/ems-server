package io.konektis.devices.charger

import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest
import com.digitalpetri.modbus.pdu.WriteSingleRegisterRequest
import io.klogging.NoCoLogging
import io.konektis.ModbusTCPClient
import org.kotlincrypto.bitops.endian.Endian

private const val MODBUS_REGISTER_KEEPALIVE: Int = 6000
private const val MODBUS_REGISTER_MAX_CURRENT_CHARGING: Int = 5004
private const val MODBUS_REGISTER_CURRENT_TOTAL_POWER: Int = 1020

class Webasto(val host: String) : NoCoLogging {

    private var client = ModbusTCPClient(host)

    fun keepAliveLoop() {
        while (true) {
            try {
                client.withClient { client ->
                    client.writeSingleRegister(1, WriteSingleRegisterRequest(MODBUS_REGISTER_KEEPALIVE, 1))
                }
            } catch (e: Exception) {
                logger.error(e)
            }
            Thread.sleep(10_000)
        }
    }

    fun update(maxCurrent: Int) {
        client.withClient { client ->

            client.writeSingleRegister(1, WriteSingleRegisterRequest(MODBUS_REGISTER_MAX_CURRENT_CHARGING, maxCurrent))
        }

    }

    fun getCurrentPowerUsage(): Int {
        val result = client.withClient { client ->
            client.readInputRegisters(1, ReadInputRegistersRequest(MODBUS_REGISTER_CURRENT_TOTAL_POWER, 2))
        }
        return Endian.Big.intFrom(result.registers, 0)
    }
}