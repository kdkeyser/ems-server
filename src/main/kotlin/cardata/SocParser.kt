package io.konektis.cardata

import kotlinx.serialization.json.*
import kotlin.math.roundToInt

private val json = Json { ignoreUnknownKeys = true }

/**
 * Extract the SoC percentage for [descriptor] from a CarData MQTT JSON [payload].
 * Returns null on any unexpected shape, a missing descriptor, or a non-numeric value
 * (tolerant by design — a surprise must not crash the subscriber).
 */
fun parseSoc(payload: String, descriptor: String): Int? = runCatching {
    val root = json.parseToJsonElement(payload).jsonObject
    val data = root["data"]?.jsonObject ?: return null
    val entry = data[descriptor] ?: return null
    // Value may be a bare number/string, or an object with a "value" field.
    val valueElement = (entry as? JsonObject)?.get("value") ?: entry
    val prim = valueElement.jsonPrimitive
    val number = prim.doubleOrNull ?: prim.contentOrNull?.toDoubleOrNull() ?: return null
    number.roundToInt()
}.getOrNull()
