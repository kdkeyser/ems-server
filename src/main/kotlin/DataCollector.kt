package io.konektis

import io.klogging.Klogging
import io.konektis.devices.World
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class DataCollector(threads: Int, val world: World) : Klogging {
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
            world.chargers.forEach { (name, charger) ->
                jobs.add(async { poll(name, "charger") {
                    charger.update()
                    val state = charger.getState() ?: throw Exception("$name returned no data")
                    DeviceHealth.Online(System.currentTimeMillis(), state.update.currentPower.value)
                }})
            }
            world.batteries.forEach { (name, battery) ->
                jobs.add(async { poll(name, "battery") {
                    battery.update()
                    val state = battery.getState() ?: throw Exception("$name returned no data")
                    DeviceHealth.Online(
                        System.currentTimeMillis(),
                        state.update.power.value,
                        "${state.update.charge.toInt()}% SoC"
                    )
                }})
            }
            world.smartConsumers.forEach { (name, consumer) ->
                jobs.add(async { poll(name, "heatpump") {
                    consumer.update()
                    val state = consumer.getState() ?: throw Exception("$name returned no data")
                    DeviceHealth.Online(System.currentTimeMillis(), state.update.power.value)
                }})
            }

            val statuses = jobs.awaitAll()

            val gridW = (healthMap["Grid meter"] as? DeviceHealth.Online)?.powerW
            val totalSolarW = world.solar.keys
                .mapNotNull { (healthMap[it] as? DeviceHealth.Online)?.powerW }
                .takeIf { it.isNotEmpty() }
                ?.sum()
            val batteryKey = world.batteries.keys.firstOrNull()
            val batteryOnline = batteryKey?.let { healthMap[it] as? DeviceHealth.Online }
            val batteryW = batteryOnline?.powerW
            val batteryCharge = batteryOnline?.extraInfo?.removeSuffix("% SoC")?.toIntOrNull()
            val chargerW = world.chargers.keys.firstOrNull()
                ?.let { (healthMap[it] as? DeviceHealth.Online)?.powerW }
            val heatpumpW = world.smartConsumers.keys.firstOrNull()
                ?.let { (healthMap[it] as? DeviceHealth.Online)?.powerW }

            statusStateFlow.value = StatusState(
                devices = statuses,
                totalSolarW = totalSolarW,
                gridW = gridW,
                batteryW = batteryW,
                batteryCharge = batteryCharge,
                chargerW = chargerW,
                heatpumpW = heatpumpW
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
            val health = block()
            healthMap[name] = health
            DeviceStatus(name, health, category)
        } catch (e: Exception) {
            val health = DeviceHealth.Offline(previousLastSeen(name), e.message)
            healthMap[name] = health
            DeviceStatus(name, health, category)
        }
    }
}
