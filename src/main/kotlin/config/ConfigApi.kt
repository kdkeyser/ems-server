package io.konektis.config

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Full effective config plus where it came from and its revision, so a client knows what is in effect. */
@Serializable
data class ConfigResponse(val source: ConfigSource, val version: Int, val config: Config)

/** The editable behavioural settings, grouped for the `settings` sub-resource. */
@Serializable
data class Settings(val ocpp: OcppConfig, val strategy: StrategyType, val pollIntervalMs: Long)

@Serializable
private data class ErrorBody(val message: String)

/**
 * REST config API + a small editor page, behind the existing Basic-auth realm.
 *
 * Designed as a standalone API (automation-friendly), not just for the page: the whole document is
 * editable via `PUT /api/config`, with convenience sub-resources for grid, individual devices and
 * settings. Every mutation is validated and, in `database` mode, persisted; in `file` mode mutations
 * are rejected (409). Changes take effect on the next restart (hot-reload is a later phase).
 */
fun Application.configureConfigApi(configService: ConfigService) {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    // Derived from the @Serializable config model; stable for the process lifetime, so build once.
    val schemaJson = json.encodeToString(ConfigSchemaBuilder.build())

    fun current() = configService.current()

    suspend fun RoutingCall.respondConfig(cfg: Config) = respondText(
        json.encodeToString(ConfigResponse(configService.source, configService.version, cfg)),
        ContentType.Application.Json,
    )

    // Validate + persist a candidate, translating the service's failure modes into HTTP status codes.
    suspend fun RoutingCall.applyCandidate(candidate: Config) {
        try {
            respondConfig(configService.update(candidate))
        } catch (e: ConfigReadOnlyException) {
            respondText(json.encodeToString(ErrorBody(e.message!!)),
                ContentType.Application.Json, HttpStatusCode.Conflict)
        } catch (e: ConfigValidationException) {
            respondText(json.encodeToString(e.errors),
                ContentType.Application.Json, HttpStatusCode.UnprocessableEntity)
        }
    }

    routing {
        authenticate("auth-basic") {
            get("/config-ui") {
                val bytes = object {}::class.java.getResourceAsStream("/config.html")!!.readBytes()
                call.respondBytes(bytes, ContentType.Text.Html)
            }

            route("/api/config") {
                // How config objects are shaped, so clients can render forms without hard-coding fields.
                get("/schema") { call.respondText(schemaJson, ContentType.Application.Json) }

                get { call.respondConfig(current()) }
                put { call.applyCandidate(call.receive<Config>()) }

                get("/grid") { call.respondText(json.encodeToString(current().grid), ContentType.Application.Json) }
                put("/grid") { call.applyCandidate(current().copy(grid = call.receive<Grid>())) }

                get("/settings") {
                    val c = current()
                    call.respondText(json.encodeToString(Settings(c.ocpp, c.strategy, c.pollIntervalMs)),
                        ContentType.Application.Json)
                }
                put("/settings") {
                    val s = call.receive<Settings>()
                    call.applyCandidate(current().copy(ocpp = s.ocpp, strategy = s.strategy, pollIntervalMs = s.pollIntervalMs))
                }

                get("/devices") { call.respondText(json.encodeToString(current().devices), ContentType.Application.Json) }
                get("/devices/{kind}") {
                    val d = current().devices
                    val out = when (call.parameters["kind"]) {
                        "solar" -> json.encodeToString(d.solar)
                        "charger" -> json.encodeToString(d.charger)
                        "battery" -> json.encodeToString(d.battery)
                        "heatPump" -> json.encodeToString(d.heatPump)
                        "car" -> json.encodeToString(d.car)
                        else -> null
                    } ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondText(out, ContentType.Application.Json)
                }

                // Add one device of the given kind. The body is the device object for that kind.
                post("/devices/{kind}") {
                    val d = current().devices
                    val devices = when (call.parameters["kind"]) {
                        "solar" -> d.copy(solar = d.solar + call.receive<Solar>())
                        "charger" -> d.copy(charger = d.charger + call.receive<Charger>())
                        "battery" -> d.copy(battery = d.battery + call.receive<Battery>())
                        "heatPump" -> d.copy(heatPump = d.heatPump + call.receive<HeatPump>())
                        "car" -> d.copy(car = d.car + call.receive<Car>())
                        else -> return@post call.respond(HttpStatusCode.NotFound)
                    }
                    call.applyCandidate(current().copy(devices = devices))
                }

                // Replace the device with the given name (within its kind).
                put("/devices/{kind}/{name}") {
                    val d = current().devices
                    val name = call.parameters["name"]!!
                    val devices = when (call.parameters["kind"]) {
                        "solar" -> d.solar.replaceByName(name, call.receive<Solar>()) { it.name }?.let { d.copy(solar = it) }
                        "charger" -> d.charger.replaceByName(name, call.receive<Charger>()) { it.name }?.let { d.copy(charger = it) }
                        "battery" -> d.battery.replaceByName(name, call.receive<Battery>()) { it.name }?.let { d.copy(battery = it) }
                        "heatPump" -> d.heatPump.replaceByName(name, call.receive<HeatPump>()) { it.name }?.let { d.copy(heatPump = it) }
                        "car" -> d.car.replaceByName(name, call.receive<Car>()) { it.name }?.let { d.copy(car = it) }
                        else -> return@put call.respond(HttpStatusCode.NotFound)
                    } ?: return@put call.respond(HttpStatusCode.NotFound)
                    call.applyCandidate(current().copy(devices = devices))
                }

                delete("/devices/{kind}/{name}") {
                    val d = current().devices
                    val name = call.parameters["name"]!!
                    val devices = when (call.parameters["kind"]) {
                        "solar" -> d.copy(solar = d.solar.filterNot { it.name == name })
                        "charger" -> d.copy(charger = d.charger.filterNot { it.name == name })
                        "battery" -> d.copy(battery = d.battery.filterNot { it.name == name })
                        "heatPump" -> d.copy(heatPump = d.heatPump.filterNot { it.name == name })
                        "car" -> d.copy(car = d.car.filterNot { it.name == name })
                        else -> return@delete call.respond(HttpStatusCode.NotFound)
                    }
                    call.applyCandidate(current().copy(devices = devices))
                }
            }
        }
    }
}

/** Replace the single element whose name matches; null (→ 404) if no element matched. */
private inline fun <T> List<T>.replaceByName(name: String, replacement: T, nameOf: (T) -> String): List<T>? {
    if (none { nameOf(it) == name }) return null
    return map { if (nameOf(it) == name) replacement else it }
}
