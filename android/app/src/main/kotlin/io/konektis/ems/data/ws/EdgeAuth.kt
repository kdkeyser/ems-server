package io.konektis.ems.data.ws

import io.konektis.ems.data.settings.Settings

/**
 * Cloudflare Access service-token headers for the edge-auth layer. Both values must be set;
 * otherwise no headers are sent (local LAN use bypasses Cloudflare and needs none).
 */
internal fun edgeAuthHeaders(s: Settings): Map<String, String> =
    if (s.cfAccessClientId.isNotBlank() && s.cfAccessClientSecret.isNotBlank()) {
        mapOf(
            "CF-Access-Client-Id" to s.cfAccessClientId,
            "CF-Access-Client-Secret" to s.cfAccessClientSecret
        )
    } else {
        emptyMap()
    }
