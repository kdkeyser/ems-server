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
