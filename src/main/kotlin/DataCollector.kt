package io.konektis

import io.klogging.Klogging
import io.konektis.devices.World
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class DataCollector(
    threads: Int,
    val world: World,
    private val pollTimeoutMs: Long = 10_000,
) : Klogging {
    private val workerPool = Executors.newFixedThreadPool(threads)
    private val dispatcher = workerPool.asCoroutineDispatcher()
    private val healthMap = ConcurrentHashMap<String, DeviceHealth>()

    val statusStateFlow = MutableStateFlow<StatusState?>(null)

    suspend fun refresh() {
        withContext(dispatcher) {
            val jobs = mutableListOf(
                async { poll("Grid meter", "grid") {
                    world.grid.update()
                    val state = world.grid.getState() ?: throw Exception("Grid meter returned no data")
                    DeviceHealth.Online(System.currentTimeMillis(), state.update.power.value)
                }}
            )
            world.solar.forEach { (name, solar) ->
                jobs.add(async { poll(name, "solar") {
                    solar.update()
                    val state = solar.getState() ?: throw Exception("$name returned no data")
                    DeviceHealth.Online(System.currentTimeMillis(), state.update.power.value)
                }})
            }
            world.charger?.let { charger ->
                jobs.add(async { poll("charger", "charger") {
                    charger.update()
                    val state = charger.getState() ?: throw Exception("charger returned no data")
                    DeviceHealth.Online(System.currentTimeMillis(), state.update.currentPower.value,
                        state.update.connection.name)
                }})
            }
            world.battery?.let { battery ->
                jobs.add(async { poll("battery", "battery") {
                    battery.update()
                    val state = battery.getState() ?: throw Exception("battery returned no data")
                    DeviceHealth.Online(System.currentTimeMillis(), state.update.power.value,
                        "${state.update.charge.toInt()}% SoC")
                }})
            }
            world.heatPump?.let { consumer ->
                jobs.add(async { poll("heatpump", "heatpump") {
                    consumer.update()
                    val state = consumer.getState() ?: throw Exception("heatpump returned no data")
                    DeviceHealth.Online(System.currentTimeMillis(), state.update.power.value)
                }})
            }

            val statuses = jobs.awaitAll()

            val gridW = (healthMap["Grid meter"] as? DeviceHealth.Online)?.powerW
            val totalSolarW = world.solar.keys
                .mapNotNull { (healthMap[it] as? DeviceHealth.Online)?.powerW }
                .takeIf { it.isNotEmpty() }
                ?.sum()
            val batteryOnline = if (world.battery != null) healthMap["battery"] as? DeviceHealth.Online else null
            val batteryW = batteryOnline?.powerW
            val batteryCharge = batteryOnline?.extraInfo?.removeSuffix("% SoC")?.toIntOrNull()
            val chargerOnline = if (world.charger != null) healthMap["charger"] as? DeviceHealth.Online else null
            val chargerW = chargerOnline?.powerW
            val chargerConnection = chargerOnline?.extraInfo
            val heatpumpW = if (world.heatPump != null) (healthMap["heatpump"] as? DeviceHealth.Online)?.powerW else null

            statusStateFlow.value = StatusState(
                devices = statuses,
                totalSolarW = totalSolarW,
                gridW = gridW,
                batteryW = batteryW,
                batteryCharge = batteryCharge,
                chargerW = chargerW,
                heatpumpW = heatpumpW,
                chargerConnection = chargerConnection
            )
        }
    }

    private fun previousLastSeen(name: String): Long? = when (val h = healthMap[name]) {
        is DeviceHealth.Online -> h.lastSeenAt
        is DeviceHealth.Offline -> h.lastSeenAt
        null -> null
    }

    private suspend fun poll(name: String, category: String, block: suspend () -> DeviceHealth.Online): DeviceStatus {
        return try {
            val health = withTimeout(pollTimeoutMs) { block() }
            healthMap[name] = health
            DeviceStatus(name, health, category)
        } catch (e: TimeoutCancellationException) {
            // A hung device must not stall the whole refresh cycle (awaitAll waits for every poll).
            val health = DeviceHealth.Offline(previousLastSeen(name), "poll timed out after ${pollTimeoutMs}ms")
            healthMap[name] = health
            DeviceStatus(name, health, category)
        } catch (e: Exception) {
            val health = DeviceHealth.Offline(previousLastSeen(name), e.message)
            healthMap[name] = health
            DeviceStatus(name, health, category)
        }
    }
}
