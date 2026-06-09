package io.konektis.history

import io.konektis.config.ClickHouseConfig
import io.konektis.ems.EMSState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.*

class HistoryWriterTest {
    private fun state() = EMSState(
        gridPower = -100, gridVoltage = 230, chargerPower = 0, heatpumpPower = 0,
        solarPower = -500, batteryPower = 0, batteryCharge = 50,
    )
    private fun row(epoch: Long) = TimestampedEmsState(Instant.ofEpochSecond(epoch), state())

    private fun mockClient(
        captured: MutableList<String>, status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient {
        val engine = MockEngine { req ->
            captured.add((req.body as io.ktor.http.content.TextContent).text)
            if (status.isSuccess()) respond("") else respondError(status)
        }
        return HttpClient(engine)
    }

    @Test
    fun flushPostsBufferedRowsAsSingleInsert() = runTest {
        val captured = mutableListOf<String>()
        val writer = HistoryWriter(ClickHouseConfig(enabled = true), mockClient(captured))
        writer.enqueue(row(1749456000))
        writer.enqueue(row(1749456005))

        writer.flushOnce()

        assertEquals(1, captured.size, "one POST for the whole batch")
        assertTrue(captured[0].contains("INSERT INTO ems.power_raw"))
        assertTrue(captured[0].contains("toDateTime(1749456000)"))
        assertTrue(captured[0].contains("toDateTime(1749456005)"))
    }

    @Test
    fun flushWithNoRowsDoesNotPost() = runTest {
        val captured = mutableListOf<String>()
        val writer = HistoryWriter(ClickHouseConfig(enabled = true), mockClient(captured))
        writer.flushOnce()
        assertEquals(0, captured.size)
    }

    @Test
    fun failedPostRequeuesRowsForNextFlush() = runTest {
        val captured = mutableListOf<String>()
        val writer = HistoryWriter(
            ClickHouseConfig(enabled = true),
            mockClient(captured, status = HttpStatusCode.ServiceUnavailable),
        )
        writer.enqueue(row(1749456000))
        writer.flushOnce()  // fails, re-queues
        assertEquals(1, captured.size, "attempted once")

        val captured2 = mutableListOf<String>()
        writer.swapClientForTest(mockClient(captured2))
        writer.flushOnce()
        assertEquals(1, captured2.size)
        assertTrue(captured2[0].contains("toDateTime(1749456000)"), "re-queued row retried")
    }

    @Test
    fun disabledWriterIgnoresEnqueueAndFlush() = runTest {
        val captured = mutableListOf<String>()
        val writer = HistoryWriter(ClickHouseConfig(enabled = false), mockClient(captured))
        writer.enqueue(row(1749456000))
        writer.flushOnce()
        assertEquals(0, captured.size)
    }
}
