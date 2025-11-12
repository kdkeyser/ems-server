package io.konektis

import com.digitalpetri.modbus.client.ModbusTcpClient
import com.digitalpetri.modbus.tcp.client.NettyClientTransportConfig
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport

class ModbusTCPClient(private val host: String) {

    private var client = makeClient()

    private fun makeClient(): ModbusTcpClient {
        val transport = NettyTcpClientTransport.create { cfg: NettyClientTransportConfig.Builder ->
            cfg.hostname = host
            cfg.port = 502
        }
        val modbusClient = ModbusTcpClient.create(transport)
        modbusClient.connect()
        return (modbusClient)
    }

    private fun <T> tryUseClient(f: (client: ModbusTcpClient) -> T, retry: Boolean): T {
        synchronized(client) {
            if (!client.isConnected) {
                client.connect()
            }
            try {
                return f(client)
            } catch (e: Exception) {
                client = makeClient()
                if (retry) {
                    return tryUseClient(f, false)
                } else {
                    throw (e)
                }
            }

        }
    }

    fun <T> withClient(f: (client: ModbusTcpClient) -> T): T {
        return tryUseClient(f, true)
    }
}