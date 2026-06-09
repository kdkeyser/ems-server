package io.konektis.history

import io.konektis.config.ClickHouseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** Thrown when ClickHouse is unreachable or returns a non-2xx response for a history query. */
class HistoryQueryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads power history from ClickHouse over its HTTP interface. SQL generation and parsing are the pure
 * helpers in HistorySql.kt; this class only does the HTTP call. Owns its HttpClient (injected).
 */
class HistoryRepository(
    private val config: ClickHouseConfig,
    private val http: HttpClient,
) {
    private val url = "http://${config.host}:${config.port}/"

    suspend fun query(range: HistoryRange, resolution: HistoryResolution): HistoryResponse {
        val sql = buildSelectSql(config.database, range, resolution)
        val resp: HttpResponse = try {
            http.get(url) { parameter("query", sql) }
        } catch (e: Exception) {
            throw HistoryQueryException("ClickHouse unreachable: ${e.message}", e)
        }
        if (!resp.status.isSuccess()) {
            throw HistoryQueryException("ClickHouse error ${resp.status}: ${resp.bodyAsText()}")
        }
        return HistoryResponse(resolution.param, parsePowerPoints(resp.bodyAsText()))
    }
}
