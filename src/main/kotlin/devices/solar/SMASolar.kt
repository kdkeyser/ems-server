package io.konektis.devices.Solar

import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest
import io.klogging.NoCoLogging
import io.konektis.ModbusTCPClient
import io.konektis.devices.Watt
import io.konektis.devices.solar.SolarState
import org.kotlincrypto.bitops.endian.Endian

class SMASolar(private val host: String) : NoCoLogging {
    private val client = ModbusTCPClient(host)

    private val MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER = 30775

    fun getState(): SolarState {
        val result = client.withClient { client ->
            client.readInputRegisters(3, ReadInputRegistersRequest(MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER, 2))
        }
        val data = result.registers
        //println("SMA: ${data[0].toUByte()}, ${data[1].toUByte()}, ${data[2].toUByte()}, ${data[3].toUByte()}")
        val power = Endian.Big.intFrom(result.registers,0)
        //println("Power: ${power}")
        // if the SMA PV Inverter is turned off (e.g. because there is no sunlight), the returned Modbus value is NaN, which
        // is the smallest negative number.
        return SolarState(Watt(if (power > 0) power else 0 ))
    }
}