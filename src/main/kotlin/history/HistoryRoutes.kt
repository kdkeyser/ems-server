package io.konektis.history

import io.konektis.config.ClickHouseConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * The GET /history handler, mountable with or without auth. Validates range/resolution against the
 * enums (400 on bad input), returns 503 when ClickHouse is disabled and 502 when the query fails.
 */
fun Route.historyRoute(config: ClickHouseConfig, repository: HistoryRepository) {
    get("/history") {
        if (!config.enabled) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "history disabled"))
            return@get
        }
        val rangeParam = call.request.queryParameters["range"] ?: "1h"
        val range = HistoryRange.fromParam(rangeParam) ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid range: $rangeParam"))
            return@get
        }
        val resParam = call.request.queryParameters["resolution"]
        val resolution = if (resParam == null) autoResolution(range)
            else HistoryResolution.fromParam(resParam) ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid resolution: $resParam"))
                return@get
            }
        try {
            call.respond(repository.query(range, resolution))
        } catch (e: HistoryQueryException) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "query failed")))
        }
    }
}

/** Test/dev mount: /history without auth. */
fun Application.configureHistory(config: ClickHouseConfig, repository: HistoryRepository) {
    routing { historyRoute(config, repository) }
}

/** Production mount: the same /history handler, behind auth-basic. */
fun Application.configureHistoryAuthenticated(config: ClickHouseConfig, repository: HistoryRepository) {
    routing { authenticate("auth-basic") { historyRoute(config, repository) } }
}
