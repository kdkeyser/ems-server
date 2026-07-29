package io.konektis.ems.data.ws

import kotlinx.serialization.json.Json

/**
 * The JSON codec for both WebSocket clients.
 *
 * `ignoreUnknownKeys` is the important part: the server and this app are versioned independently, so
 * the server routinely sends fields an older app build has never heard of. The default [Json] is
 * strict and throws on the first such field, which takes down the whole socket — a server-side field
 * addition (`batterySoc` on `DeviceHealth.Online`) once bricked every installed app this way.
 * Unknown fields are skipped instead, so an older app degrades to "doesn't show the new data"
 * rather than "won't connect".
 */
internal val WS_JSON = Json { ignoreUnknownKeys = true }
