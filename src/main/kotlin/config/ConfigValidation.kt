package io.konektis.config

import kotlinx.serialization.Serializable

/** A single config problem, addressed to a dotted field path so the UI/automation can target it. */
@Serializable
data class ValidationError(val field: String, val message: String)

/** Thrown when a candidate config fails [Config.validate]. Carries every problem, not just the first. */
class ConfigValidationException(val errors: List<ValidationError>) :
    Exception("Invalid config: " + errors.joinToString("; ") { "${it.field}: ${it.message}" })

/**
 * Structural validation of a candidate config. These are the same rules `World.fromConfig` relies on
 * (at most one charger/battery/heat pump, required connection fields per device type) plus uniqueness
 * checks, lifted out so the API can reject bad input *before* anything is persisted or built.
 *
 * Returns every problem found; empty list means valid.
 */
fun Config.validate(): List<ValidationError> = buildList {
    if (grid.host.isBlank()) add(ValidationError("grid.host", "host is required"))

    if (devices.charger.size > 1)
        add(ValidationError("devices.charger", "at most one charger is supported (got ${devices.charger.size})"))
    if (devices.battery.size > 1)
        add(ValidationError("devices.battery", "at most one battery is supported (got ${devices.battery.size})"))
    if (devices.heatPump.size > 1)
        add(ValidationError("devices.heatPump", "at most one heat pump is supported (got ${devices.heatPump.size})"))

    devices.solar.forEachIndexed { i, s ->
        if (s.name.isBlank()) add(ValidationError("devices.solar[$i].name", "name is required"))
        if (s.host.isBlank()) add(ValidationError("devices.solar[$i].host", "host is required"))
    }
    duplicateNames(devices.solar.map { it.name }).forEach {
        add(ValidationError("devices.solar", "duplicate name '$it' (names must be unique)"))
    }

    devices.battery.forEachIndexed { i, b ->
        if (b.name.isBlank()) add(ValidationError("devices.battery[$i].name", "name is required"))
        if (b.host.isBlank()) add(ValidationError("devices.battery[$i].host", "host is required"))
    }
    devices.heatPump.forEachIndexed { i, h ->
        if (h.name.isBlank()) add(ValidationError("devices.heatPump[$i].name", "name is required"))
        if (h.host.isBlank()) add(ValidationError("devices.heatPump[$i].host", "host is required"))
    }

    devices.charger.forEachIndexed { i, c ->
        if (c.name.isBlank()) add(ValidationError("devices.charger[$i].name", "name is required"))
        when (c.type) {
            ChargerType.WebastoUnite ->
                if (c.host.isNullOrBlank())
                    add(ValidationError("devices.charger[$i].host", "host is required for a WebastoUnite charger"))
            ChargerType.OCPP ->
                if (c.chargePointId.isNullOrBlank())
                    add(ValidationError("devices.charger[$i].chargePointId", "chargePointId is required for an OCPP charger"))
        }
        if (c.chargingCurrent.min < 0 || c.chargingCurrent.max < c.chargingCurrent.min)
            add(ValidationError("devices.charger[$i].chargingCurrent", "require 0 <= min <= max"))
    }

    if (pollIntervalMs < 250)
        add(ValidationError("pollIntervalMs", "must be at least 250 ms"))
}

/** Validate and throw [ConfigValidationException] if invalid; returns the config unchanged otherwise. */
fun Config.validatedOrThrow(): Config {
    val errors = validate()
    if (errors.isNotEmpty()) throw ConfigValidationException(errors)
    return this
}

private fun duplicateNames(names: List<String>): List<String> =
    names.filter { it.isNotBlank() }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()
