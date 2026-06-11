package io.konektis.devices.Solar

import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest
import io.klogging.NoCoLogging
import io.konektis.GlobalTimeSource
import io.konektis.ModbusTCPClient
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt
import io.konektis.devices.solar.Solar
import io.konektis.devices.solar.SolarState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kotlincrypto.bitops.endian.Endian

class SMASolar(private val host: String) : NoCoLogging, Solar {
    private val client = ModbusTCPClient(host)
    private var internalState: DeviceUpdate<SolarState>? = null
    private val mutex = Mutex()


    // SMA Sunny Boy Modbus: total AC power output, U32, unit W, unit-id 3
    private val MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER = 30775

    private suspend fun getSolarState(): SolarState {
        val result = client.withClient { client ->
            client.readInputRegisters(3, ReadInputRegistersRequest(MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER, 2))
        }
        val data = result.registers
        val power = Endian.Big.intFrom(result.registers,0)
        // SMA returns Int.MIN_VALUE when the inverter is off (no sunlight); treat as 0W.
        // NOTE: production is reported POSITIVE here — the documented exception to the
        // negative-=-producing convention (the app and EMSState consumers expect it this way).
        return SolarState(Watt(if (power > 0) power else 0 ))
    }

    override suspend fun update() {
        mutex.withLock {
            internalState = DeviceUpdate(GlobalTimeSource.source.markNow(), getSolarState())
            logger.trace { "SMASolar: $internalState" }
        }
    }

    override suspend fun getState(): DeviceUpdate<SolarState>? {
        return mutex.withLock { internalState }
    }
}