package io.konektis.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

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

private val historyJson = Json { ignoreUnknownKeys = true }

private fun intField(obj: JsonObject, name: String): Int? {
    val el = obj[name] ?: return null
    val prim = el.jsonPrimitive
    if (prim.content == "null") return null
    return prim.doubleOrNull?.roundToInt()
}

private fun intOrNull(v: Int?): String = v?.toString() ?: "NULL"

/**
 * Build a single `INSERT INTO <db>.power_raw ... FORMAT Values` statement for [rows], or "" if empty.
 * Timestamps are sent explicitly (toDateTime(epochSeconds)); ClickHouse stores DateTime as UTC.
 * Values are mapped by schema column name, not EMSState field order.
 */
fun buildInsertSql(database: String, rows: List<TimestampedEmsState>): String {
    if (rows.isEmpty()) return ""
    val tuples = rows.joinToString(",") { row ->
        val s = row.state
        "(toDateTime(${row.ts.epochSecond})," +
            "${intOrNull(s.gridPower)},${intOrNull(s.solarPower)},${intOrNull(s.chargerPower)}," +
            "${intOrNull(s.heatpumpPower)},${intOrNull(s.batteryPower)},${intOrNull(s.batteryCharge)})"
    }
    return "INSERT INTO $database.power_raw " +
        "(ts,grid_power,solar_power,charger_power,heatpump_power,battery_power,battery_charge) " +
        "FORMAT Values $tuples"
}

/** Parse a ClickHouse JSONEachRow body (newline-delimited objects) into PowerPoints; tolerant of
 *  blank lines. Float64 averages from power_1m are rounded to whole Watts. */
fun parsePowerPoints(body: String): List<PowerPoint> =
    body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val o = historyJson.parseToJsonElement(line).jsonObject
            PowerPoint(
                ts = o["ts"]!!.jsonPrimitive.content.toDouble().toLong(),
                gridPower = intField(o, "grid_power"),
                solarPower = intField(o, "solar_power"),
                chargerPower = intField(o, "charger_power"),
                heatpumpPower = intField(o, "heatpump_power"),
                batteryPower = intField(o, "battery_power"),
                batteryCharge = intField(o, "battery_charge"),
            )
        }
        .toList()
