package io.konektis

import com.digitalpetri.modbus.client.ModbusTcpClient
import com.digitalpetri.modbus.tcp.client.NettyClientTransportConfig
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModbusTCPClient(private val host: String) {

    private var client = makeClient()
    private val lock = Any()

    private fun makeClient(): ModbusTcpClient {
        // Netty transport — Ktor network sockets don't have a compatible interface with the modbus library
        val transport = NettyTcpClientTransport.create { cfg: NettyClientTransportConfig.Builder ->
            cfg.hostname = host
            cfg.port = 502
        }
        val modbusClient = ModbusTcpClient.create(transport)
        modbusClient.connect()
        return modbusClient
    }

    suspend fun <T> withClient(f: (client: ModbusTcpClient) -> T): T = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!client.isConnected) client.connect()
            try {
                f(client)
            } catch (e: Exception) {
                println("[WARN] ModbusTCPClient: connection error for $host, reconnecting: ${e.message}")
                try { client = makeClient() } catch (_: Exception) { }
                throw e
            }
        }
    }
}
