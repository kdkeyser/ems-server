package io.konektis.devices.charger

import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest
import com.digitalpetri.modbus.pdu.WriteSingleRegisterRequest
import io.klogging.Klogging
import io.klogging.NoCoLogging
import io.konektis.GlobalTimeSource
import io.konektis.ModbusTCPClient
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kotlincrypto.bitops.endian.Endian

// Webasto Unite requires a keepalive write every <30s to maintain Modbus remote control
private const val MODBUS_REGISTER_KEEPALIVE: Int = 6000
// Maximum charging current in amps (write to set; Webasto clamps to its own max of 32A)
private const val MODBUS_REGISTER_MAX_CURRENT_CHARGING: Int = 5004
// Current total power draw by the charger, W (read-only)
private const val MODBUS_REGISTER_CURRENT_TOTAL_POWER: Int = 1020

class Webasto(val host: String) : Klogging, Charger {

    private var client = ModbusTCPClient(host)
    private val mutex = Mutex()
    private var internalState : DeviceUpdate<ChargerState>? = null

    private val keepAliveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        keepAliveScope.launch { keepAliveLoop() }
    }

    private suspend fun keepAliveLoop() {
        while (true) {
            try {
                mutex.withLock {
                client.withClient { client ->
                    client.writeSingleRegister(1, WriteSingleRegisterRequest(MODBUS_REGISTER_KEEPALIVE, 1))
                }
                }
            } catch (e: Exception) {
                logger.error(e)
            }
            delay(10_000)
        }
    }

    private suspend fun setMaxCurrent(maxCurrent: Int) {
        client.withClient { client ->

            client.writeSingleRegister(1, WriteSingleRegisterRequest(MODBUS_REGISTER_MAX_CURRENT_CHARGING, maxCurrent))
        }

    }

    private suspend fun getCurrentPowerUsage(): Int {
        val result = client.withClient { client ->
            client.readInputRegisters(1, ReadInputRegistersRequest(MODBUS_REGISTER_CURRENT_TOTAL_POWER, 2))
        }
        return Endian.Big.intFrom(result.registers, 0)
    }

    override suspend fun update() {
        mutex.withLock {
            logger.debug { "Updating charger Webasto state" }
            internalState = DeviceUpdate(GlobalTimeSource.source.markNow(), ChargerState(Watt(getCurrentPowerUsage())))
            logger.debug { "Charger updated with state $internalState" }
        }
    }

    override suspend fun getState(): DeviceUpdate<ChargerState>? {
        mutex.withLock {
            return internalState
        }
    }

    override suspend fun setMaxChargerPower(power: Watt) {
        mutex.withLock {
            setMaxCurrent(power.value/230)
        }
    }
}