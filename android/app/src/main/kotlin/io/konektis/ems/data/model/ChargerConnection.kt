package io.konektis.ems.data.model

/** App-facing car-connection state; mirrors the server's ChargerConnection enum names. */
enum class ChargerConnection { NotConnected, Connected, Charging, Unknown }

fun parseChargerConnection(raw: String?): ChargerConnection? =
    raw?.let { runCatching { ChargerConnection.valueOf(it) }.getOrNull() }
