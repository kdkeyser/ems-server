package io.konektis.history

import kotlinx.serialization.Serializable

/** Time window for a history query. [seconds] is how far back from now to read. */
enum class HistoryRange(val param: String, val seconds: Long) {
    H1("1h", 3_600), H6("6h", 21_600), H24("24h", 86_400),
    D7("7d", 604_800), D30("30d", 2_592_000), D365("365d", 31_536_000);

    companion object {
        fun fromParam(p: String?): HistoryRange? = entries.firstOrNull { it.param == p }
    }
}

/** Which table to read: RAW = power_raw (5s), MINUTE = power_1m (avgMerge). */
enum class HistoryResolution(val param: String) {
    RAW("5s"), MINUTE("1m");

    companion object {
        fun fromParam(p: String?): HistoryResolution? = entries.firstOrNull { it.param == p }
    }
}

/** Default resolution when the caller does not pin one: raw up to 24h, 1-minute beyond. */
fun autoResolution(range: HistoryRange): HistoryResolution =
    if (range.seconds <= HistoryRange.H24.seconds) HistoryResolution.RAW else HistoryResolution.MINUTE

private val POWER_COLUMNS = listOf(
    "grid_power", "solar_power", "charger_power", "heatpump_power", "battery_power", "battery_charge",
)

/**
 * Build the ClickHouse SELECT for a history query. RAW reads power_raw directly; MINUTE reads the
 * AggregatingMergeTree power_1m and must avgMerge + GROUP BY ts to collapse partial-aggregate rows.
 * Only enum-derived and config-trusted values are interpolated — no user strings.
 */
fun buildSelectSql(database: String, range: HistoryRange, resolution: HistoryResolution): String {
    val window = "ts >= now() - INTERVAL ${range.seconds} SECOND"
    return when (resolution) {
        HistoryResolution.RAW -> buildString {
            append("SELECT toUnixTimestamp(ts) AS ts, ")
            append(POWER_COLUMNS.joinToString(", "))
            append(" FROM $database.power_raw WHERE $window ORDER BY ts FORMAT JSONEachRow")
        }
        HistoryResolution.MINUTE -> buildString {
            append("SELECT toUnixTimestamp(ts) AS ts, ")
            append(POWER_COLUMNS.joinToString(", ") { "avgMerge($it) AS $it" })
            append(" FROM $database.power_1m WHERE $window GROUP BY ts ORDER BY ts FORMAT JSONEachRow")
        }
    }
}

@Serializable
data class PowerPoint(
    val ts: Long,
    val gridPower: Int?,
    val solarPower: Int?,
    val chargerPower: Int?,
    val heatpumpPower: Int?,
    val batteryPower: Int?,
    val batteryCharge: Int?,
)

@Serializable
data class HistoryResponse(
    val resolution: String,
    val points: List<PowerPoint>,
)
