package io.konektis

import com.digitalpetri.modbus.client.ModbusTcpClient
import com.digitalpetri.modbus.tcp.client.NettyClientTransportConfig
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport
import io.klogging.NoCoLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModbusTCPClient(private val host: String) : NoCoLogging {

    private var client = makeClient()
    private val lock = Any()

    private fun makeClient(): ModbusTcpClient {
        // Netty transport — Ktor network sockets don't have a compatible interface with the modbus library
        val transport = NettyTcpClientTransport.create { cfg: NettyClientTransportConfig.Builder ->
            cfg.hostname = host
            cfg.port = 502
        }
        // No connect() here: withClient connects lazily, so constructing device drivers at startup
        // succeeds even while the device is offline.
        return ModbusTcpClient.create(transport)
    }

    suspend fun <T> withClient(f: (client: ModbusTcpClient) -> T): T = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!client.isConnected) client.connect()
            try {
                f(client)
            } catch (e: Exception) {
                logger.warn("Modbus connection error for $host, reconnecting: ${e.message}")
                runCatching { client.disconnect() } // release the broken transport before replacing it
                try { client = makeClient() } catch (_: Exception) { }
                throw e
            }
        }
    }
}
