package io.konektis.ems.data.ws

import io.konektis.ems.data.settings.Settings

/**
 * Edge-auth header for the Cloudflare WAF custom rule. When the edge key is set, it is sent as
 * `X-EMS-Edge-Key`; the WAF rule on `ems.kenas.be` blocks any request whose header value doesn't
 * match. Blank means no header (local LAN use bypasses Cloudflare and needs none).
 */
internal fun edgeAuthHeaders(s: Settings): Map<String, String> =
    if (s.edgeKey.isNotBlank()) {
        mapOf("X-EMS-Edge-Key" to s.edgeKey)
    } else {
        emptyMap()
    }
