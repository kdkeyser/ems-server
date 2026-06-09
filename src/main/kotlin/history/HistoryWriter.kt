package io.konektis.history

import io.klogging.Klogging
import io.konektis.config.ClickHouseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Persists EMS ticks to ClickHouse. Ticks arrive via [enqueue] (fed from EnergyManager.emsHistoryFlow)
 * into a bounded channel; [flushOnce] drains the channel and any previously-failed rows and POSTs them
 * as one INSERT. A failed POST re-queues the batch so a transient ClickHouse blip loses no data; only a
 * sustained outage beyond [MAX_BUFFER] drops the oldest rows. Owns a dedicated HttpClient so its bulk
 * timeouts are isolated from device polling. No-op when ClickHouse is disabled.
 */
class HistoryWriter(
    private val config: ClickHouseConfig,
    httpClient: HttpClient,
) : Klogging {

    private var http: HttpClient = httpClient
    private val url = "http://${config.host}:${config.port}/"

    private val channel = Channel<TimestampedEmsState>(
        capacity = MAX_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Rows from a failed POST, retried at the head of the next flush. */
    private val pending = ArrayDeque<TimestampedEmsState>()

    /** Offer a tick to the buffer. Non-blocking; drops oldest on overflow. No-op when disabled. */
    fun enqueue(row: TimestampedEmsState) {
        if (!config.enabled) return
        channel.trySend(row)
    }

    /** Collect [flow] into the buffer, then flush every 30s, forever. Call from a launched coroutine. */
    suspend fun run(flow: SharedFlow<TimestampedEmsState>) {
        if (!config.enabled) {
            logger.info("ClickHouse history disabled; HistoryWriter not started")
            return
        }
        logger.info("HistoryWriter started -> $url")
        coroutineScope {
            launch { flow.collect { enqueue(it) } }
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flushOnce()
            }
        }
    }

    /** Drain pending + channel and POST as one INSERT. On failure, re-queue (capped). */
    suspend fun flushOnce() {
        if (!config.enabled) return
        val batch = ArrayList<TimestampedEmsState>(pending)
        pending.clear()
        while (true) {
            val r = channel.tryReceive().getOrNull() ?: break
            batch.add(r)
        }
        if (batch.isEmpty()) return

        val sql = buildInsertSql(config.database, batch)
        val ok = runCatching {
            val resp: HttpResponse = http.post(url) { setBody(sql) }
            resp.status.isSuccess()
        }.getOrElse { e ->
            logger.warn(e, "ClickHouse insert failed: ${e.message}")
            false
        }
        if (!ok) {
            pending.addAll(0, batch)
            while (pending.size > MAX_BUFFER) pending.removeFirst()
            logger.warn("Re-queued ${batch.size} history rows after failed flush (pending=${pending.size})")
        }
    }

    /** Test seam: replace the HTTP client between flushes. */
    fun swapClientForTest(client: HttpClient) { http = client }

    companion object {
        const val MAX_BUFFER = 10_000
        const val FLUSH_INTERVAL_MS = 30_000L
    }
}
