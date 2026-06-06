package io.konektis.cardata

import io.klogging.Klogging
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

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

/**
 * OAuth client for BMW CarData (Device Code Flow with PKCE). One-time [ensureAuthorized] bootstrap
 * persists a refresh token; [currentIdToken] mints/refreshes the ~1h ID token used as the MQTT password.
 * Pure parsing/timing live in the top-level helpers above.
 */
class CarDataAuth(
    private val config: CarDataConfig,
    private val tokenStore: CarDataTokenStore,
    private val http: HttpClient,
) : Klogging {

    @Volatile private var cached: TokenResponse? = null
    @Volatile private var expiresAt: Instant = Instant.EPOCH

    /** PKCE pair: a high-entropy verifier and its S256 challenge (both base64url, no padding). */
    private fun pkce(): Pair<String, String> {
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val challengeBytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes)
        return verifier to challenge
    }

    /** Bootstrap once via the device-code flow; no-op if a refresh token is already stored. */
    suspend fun ensureAuthorized() {
        if (tokenStore.get(config.clientId) != null) return
        val (verifier, challenge) = pkce()
        val deviceBody = http.submitForm(CarDataEndpoints.DEVICE_CODE_URL, parameters {
            append("client_id", config.clientId)
            append("response_type", "device_code")
            append("scope", CarDataEndpoints.SCOPE)
            append("code_challenge", challenge)
            append("code_challenge_method", "S256")
        }).bodyAsText()
        val dc = parseDeviceCodeResponse(deviceBody)
            ?: error("CarData device-code request failed: $deviceBody")
        logger.warn(
            "BMW CarData: authorize this server — open ${dc.verificationUri} and enter code ${dc.userCode}"
        )
        val deadline = Instant.now().plusSeconds(600)
        while (Instant.now().isBefore(deadline)) {
            delay(dc.intervalSeconds.seconds)
            val body = http.submitForm(CarDataEndpoints.TOKEN_URL, parameters {
                append("client_id", config.clientId)
                append("device_code", dc.deviceCode)
                append("grant_type", CarDataEndpoints.GRANT_DEVICE_CODE)
                append("code_verifier", verifier)
            }).bodyAsText()
            val token = parseTokenResponse(body)
            if (token != null) {
                cache(token)
                tokenStore.save(
                    config.clientId,
                    token.refreshToken ?: error("CarData token had no refresh_token"),
                    token.gcid,
                )
                logger.info("BMW CarData: device authorized")
                return
            }
        }
        error("CarData device authorization timed out (code not approved in time)")
    }

    /** A valid ID token, refreshing proactively before it expires. */
    suspend fun currentIdToken(): String {
        val c = cached
        if (c != null && !needsRefresh(expiresAt, Instant.now(), REFRESH_MARGIN_SECONDS)) return c.idToken
        return refresh().idToken
    }

    /** The account id (GCID) used as the MQTT username; learned during authorization. */
    suspend fun gcid(): String =
        tokenStore.get(config.clientId)?.gcid ?: error("CarData GCID unknown (not authorized yet)")

    private suspend fun refresh(): TokenResponse {
        val stored = tokenStore.get(config.clientId) ?: error("CarData not authorized")
        val body = http.submitForm(CarDataEndpoints.TOKEN_URL, parameters {
            append("grant_type", CarDataEndpoints.GRANT_REFRESH)
            append("refresh_token", stored.refreshToken)
            append("client_id", config.clientId)
        }).bodyAsText()
        val token = parseTokenResponse(body) ?: error("CarData token refresh failed: $body")
        cache(token)
        // Persist a rotated refresh token if the response carried one; keep the known GCID otherwise.
        tokenStore.save(config.clientId, token.refreshToken ?: stored.refreshToken, token.gcid ?: stored.gcid)
        return token
    }

    private fun cache(token: TokenResponse) {
        cached = token
        expiresAt = Instant.now().plusSeconds(token.expiresInSeconds.toLong())
    }

    companion object {
        const val REFRESH_MARGIN_SECONDS = 300L
    }
}
