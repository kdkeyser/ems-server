package io.konektis

import io.klogging.Klogging
import io.konektis.devices.World
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class DataCollector(threads: Int, val world: World) : Klogging {
    private val workerPool = Executors.newFixedThreadPool(threads)
    private val dispatcher = workerPool.asCoroutineDispatcher()

    suspend fun refresh() {
        withContext(dispatcher) {
                logger.debug { "Starting refresh of devices."}
                val updates = mutableListOf(async { world.grid.update() })
                updates.addAll(world.solar.values.map { async { it.update() } })
                updates.addAll(world.chargers.values.map { async { it.update() } })
                updates.addAll(world.smartConsumers.values.map { async { it.update() } })
                updates.addAll(world.batteries.values.map { async { it.update() } })
                updates.awaitAll()
                logger.debug { "Refresh of devices done."}
        }
    }
}