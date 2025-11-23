package io.konektis.devices.battery
import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersRequest
import io.klogging.NoCoLogging
import io.konektis.ModbusTCPClient
import io.konektis.devices.Watt
import org.kotlincrypto.bitops.endian.Endian

class SMABattery(private val host: String) : NoCoLogging {
    private val client = ModbusTCPClient(host)

    private val MODBUS_INPUT_REGISTER_CURRENT_BATTERY_CAPACITY = 30845
    private val MODBUS_INPUT_REGISTER_BATTERY_CHARGE = 31397 // Total energy charged to the battery over its lifetime, in Wh
    private val MODBUS_INPUT_REGISTER_CURRENT_BATTERY_CHARGE = 31393 // current power that is charging the battery, in W
    private val MODBUS_INPUT_REGISTER_CURRENT_BATTERY_DISCHARGE = 31395 // current power that is discharging the battery, in W
    private val MODBUS_OUTPUT_REGISTER_CHARGING_POWER = 40149 // s32, write-only. Power in Watt. Negative means discharge the battery, positive means charge. Only works when the next register is set to the correct value.
    private val MODBUS_OUTPUT_REGISTER_CHARGING_CONTROL = 40151 // u32, write-only. Setting to 802 allows modbus control of the charge/discharge power, 803 disallows it.

    private fun getCharge() : ULong {
        val result = client.withClient { client ->
            client.readInputRegisters(3, ReadInputRegistersRequest(MODBUS_INPUT_REGISTER_BATTERY_CHARGE, 4))
        }
        return Endian.Big.longFrom(result.registers,0).toULong()
    }

    private fun getCurrentCharge() : UInt {
        val result = client.withClient { client ->
            client.readInputRegisters(3, ReadInputRegistersRequest(MODBUS_INPUT_REGISTER_CURRENT_BATTERY_CHARGE, 2))
        }
        return Endian.Big.intFrom(result.registers,0).toUInt()
    }

    private fun getCurrentDischarge() : UInt {
        val result = client.withClient { client ->
            client.readInputRegisters(3, ReadInputRegistersRequest(MODBUS_INPUT_REGISTER_CURRENT_BATTERY_DISCHARGE, 2))
        }
        return Endian.Big.intFrom(result.registers,0).toUInt()
    }

    private fun getCapacity() : UInt {
        val result = client.withClient { client ->
            client.readInputRegisters(3, ReadInputRegistersRequest(MODBUS_INPUT_REGISTER_CURRENT_BATTERY_CAPACITY, 2))
        }
        return Endian.Big.intFrom(result.registers,0).toUInt()
    }

    fun setChargingPower(power : Watt) {
        val result = client.withClient { client ->
            val bytes = ByteArray(4)
            Endian.Big.pack(801, bytes, 0)
            client.writeMultipleRegisters(3, WriteMultipleRegistersRequest(MODBUS_OUTPUT_REGISTER_CHARGING_CONTROL, 2, bytes ))
        }
        // TODO: what to do with result?

        val result2 = client.withClient { client ->
            val bytes = ByteArray(4)
            Endian.Big.pack(power.value, bytes, 0)
            client.writeMultipleRegisters(3, WriteMultipleRegistersRequest(MODBUS_OUTPUT_REGISTER_CHARGING_POWER,2, bytes))
        }
    }

    fun getState(): BatteryState {
        val capacity = getCapacity()
        val charge = getCharge()
        val currentCharge = getCurrentCharge()
        val currentDischarge = getCurrentDischarge()

        println("Capacity: $capacity, Charge: $charge, CurrentCharge: $currentCharge, CurrentDischarge: $currentDischarge")

        return BatteryState(0u, Watt(0))
    }
}