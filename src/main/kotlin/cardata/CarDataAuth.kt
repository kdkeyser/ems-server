package io.konektis.cardata

import kotlinx.serialization.json.*
import java.time.Instant

data class DeviceCodeResponse(
    val deviceCode: String, val userCode: String, val verificationUri: String, val intervalSeconds: Int,
)

data class TokenResponse(
    val idToken: String, val refreshToken: String?, val expiresInSeconds: Int, val gcid: String?,
)

private val authJson = Json { ignoreUnknownKeys = true }

fun parseDeviceCodeResponse(body: String): DeviceCodeResponse? = runCatching {
    val o = authJson.parseToJsonElement(body).jsonObject
    DeviceCodeResponse(
        deviceCode = o["device_code"]!!.jsonPrimitive.content,
        userCode = o["user_code"]!!.jsonPrimitive.content,
        verificationUri = (o["verification_uri_complete"] ?: o["verification_uri"])!!.jsonPrimitive.content,
        intervalSeconds = o["interval"]?.jsonPrimitive?.intOrNull ?: 5,
    )
}.getOrNull()

/** Parse a token endpoint success body. Returns null for error bodies (e.g. authorization_pending). */
fun parseTokenResponse(body: String): TokenResponse? = runCatching {
    val o = authJson.parseToJsonElement(body).jsonObject
    if (o["error"] != null) return null
    val idToken = o["id_token"]?.jsonPrimitive?.content ?: return null
    TokenResponse(
        idToken = idToken,
        refreshToken = o["refresh_token"]?.jsonPrimitive?.content,
        expiresInSeconds = o["expires_in"]?.jsonPrimitive?.intOrNull ?: 3600,
        gcid = o["gcid"]?.jsonPrimitive?.content,
    )
}.getOrNull()

/** True when [expiresAt] is within [marginSeconds] of [now] (so we should refresh proactively). */
fun needsRefresh(expiresAt: Instant, now: Instant, marginSeconds: Long): Boolean =
    !expiresAt.minusSeconds(marginSeconds).isAfter(now)
