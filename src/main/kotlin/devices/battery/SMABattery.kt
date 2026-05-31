package io.konektis.devices.battery
import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest
import com.digitalpetri.modbus.pdu.ReadInputRegistersResponse
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersRequest
import io.klogging.Klogging
import io.konektis.GlobalTimeSource
import io.konektis.ModbusTCPClient
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kotlincrypto.bitops.endian.Endian

/** Minimal seam over the raw Modbus reads/writes SMABattery needs, so the guard is testable. */
interface BatteryModbus {
    suspend fun readInput(register: Int, count: Int): ReadInputRegistersResponse
    suspend fun writeRegisters(register: Int, count: Int, values: ByteArray)
}

private class RealBatteryModbus(host: String) : BatteryModbus {
    private val client = ModbusTCPClient(host)

    override suspend fun readInput(register: Int, count: Int): ReadInputRegistersResponse =
        client.withClient { it.readInputRegisters(3, ReadInputRegistersRequest(register, count)) }

    override suspend fun writeRegisters(register: Int, count: Int, values: ByteArray) {
        client.withClient { it.writeMultipleRegisters(3, WriteMultipleRegistersRequest(register, count, values)) }
    }
}

class SMABattery(private val modbus: BatteryModbus) : Klogging, Battery {
    constructor(host: String) : this(RealBatteryModbus(host))

    private var internalState: DeviceUpdate<BatteryState>? = null
    private val mutex = Mutex()

    // State of charge percentage (0-100), U32, unit-id 3. Verify scaling against SMA docs.
    private val MODBUS_INPUT_REGISTER_CURRENT_BATTERY_CAPACITY = 30845
    // Total energy charged to battery over its lifetime, Wh (not SoC — do not use for charge %)
    private val MODBUS_INPUT_REGISTER_BATTERY_CHARGE = 31397
    // Current charging power into battery, W, U32
    private val MODBUS_INPUT_REGISTER_CURRENT_BATTERY_CHARGE = 31393
    // Current discharging power from battery, W, U32
    private val MODBUS_INPUT_REGISTER_CURRENT_BATTERY_DISCHARGE = 31395
    // Target charge/discharge power, S32 (positive=charge, negative=discharge), W
    private val MODBUS_OUTPUT_REGISTER_CHARGING_POWER = 40149
    // Write 802 to enable Modbus power control; write 803 to hand control back to the inverter
    private val MODBUS_OUTPUT_REGISTER_CHARGING_CONTROL = 40151

    private suspend fun getCharge(): ULong {
        val result = modbus.readInput(MODBUS_INPUT_REGISTER_BATTERY_CHARGE, 4)
        return Endian.Big.longFrom(result.registers, 0).toULong()
    }

    private suspend fun getCurrentCharge(): UInt {
        val result = modbus.readInput(MODBUS_INPUT_REGISTER_CURRENT_BATTERY_CHARGE, 2)
        return Endian.Big.intFrom(result.registers, 0).toUInt()
    }

    private suspend fun getCurrentDischarge(): UInt {
        val result = modbus.readInput(MODBUS_INPUT_REGISTER_CURRENT_BATTERY_DISCHARGE, 2)
        return Endian.Big.intFrom(result.registers, 0).toUInt()
    }

    private suspend fun getCapacity(): UInt {
        val result = modbus.readInput(MODBUS_INPUT_REGISTER_CURRENT_BATTERY_CAPACITY, 2)
        return Endian.Big.intFrom(result.registers, 0).toUInt()
    }

    override suspend fun setChargingPower(power: Watt) {
        mutex.withLock {
            val enable = ByteArray(4).also { Endian.Big.pack(802, it, 0) }
            modbus.writeRegisters(MODBUS_OUTPUT_REGISTER_CHARGING_CONTROL, 2, enable)
            val target = ByteArray(4).also { Endian.Big.pack(power.value, it, 0) }
            modbus.writeRegisters(MODBUS_OUTPUT_REGISTER_CHARGING_POWER, 2, target)
        }
    }

    override suspend fun releaseToInverter() {
        mutex.withLock {
            val disable = ByteArray(4).also { Endian.Big.pack(803, it, 0) }
            modbus.writeRegisters(MODBUS_OUTPUT_REGISTER_CHARGING_CONTROL, 2, disable)
        }
    }

    override suspend fun update() {
        mutex.withLock {
            internalState = DeviceUpdate(GlobalTimeSource.source.markNow(), getInternalState())
            logger.trace { "SMABattery: $internalState" }
        }
    }

    override suspend fun getState(): DeviceUpdate<BatteryState>? {
        mutex.withLock {
            return internalState
        }
    }

    suspend fun getInternalState(): BatteryState {
        val soc = getCapacity()                          // SoC percent (0-100) from register 30845
        val charging = getCurrentCharge()                // charging power W from register 31393
        val discharging = getCurrentDischarge()          // discharging power W from register 31395
        val netPower = charging.toInt() - discharging.toInt()
        return BatteryState(soc.toUShort(), Watt(netPower))
    }
}
