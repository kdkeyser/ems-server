package io.konektis.history

import kotlin.test.*

class HistorySqlTest {
    @Test fun rangeParsesKnownValues() {
        assertEquals(HistoryRange.H1, HistoryRange.fromParam("1h"))
        assertEquals(HistoryRange.D365, HistoryRange.fromParam("365d"))
    }

    @Test fun rangeReturnsNullForUnknown() {
        assertNull(HistoryRange.fromParam("99h"))
        assertNull(HistoryRange.fromParam(""))
        assertNull(HistoryRange.fromParam(null))
    }

    @Test fun resolutionParsesKnownValues() {
        assertEquals(HistoryResolution.RAW, HistoryResolution.fromParam("5s"))
        assertEquals(HistoryResolution.MINUTE, HistoryResolution.fromParam("1m"))
    }

    @Test fun resolutionNullForUnknown() {
        assertNull(HistoryResolution.fromParam("2m"))
    }

    @Test fun autoResolutionIsRawUpTo24hMinuteBeyond() {
        assertEquals(HistoryResolution.RAW, autoResolution(HistoryRange.H1))
        assertEquals(HistoryResolution.RAW, autoResolution(HistoryRange.H24))
        assertEquals(HistoryResolution.MINUTE, autoResolution(HistoryRange.D7))
        assertEquals(HistoryResolution.MINUTE, autoResolution(HistoryRange.D365))
    }

    @Test fun rawSelectReadsPowerRawWithWindow() {
        val sql = buildSelectSql("ems", HistoryRange.H1, HistoryResolution.RAW)
        assertTrue(sql.contains("FROM ems.power_raw"), sql)
        assertTrue(sql.contains("ts >= now() - INTERVAL 3600 SECOND"), sql)
        assertTrue(sql.contains("toUnixTimestamp(ts) AS ts"), sql)
        assertTrue(sql.contains("ORDER BY ts"), sql)
        assertFalse(sql.contains("avgMerge"), sql)
        assertTrue(sql.endsWith("FORMAT JSONEachRow"), sql)
    }

    @Test fun minuteSelectUsesAvgMergeAndGroupBy() {
        val sql = buildSelectSql("ems", HistoryRange.D365, HistoryResolution.MINUTE)
        assertTrue(sql.contains("FROM ems.power_1m"), sql)
        assertTrue(sql.contains("avgMerge(grid_power)"), sql)
        assertTrue(sql.contains("GROUP BY ts"), sql)
        assertTrue(sql.contains("ts >= now() - INTERVAL 31536000 SECOND"), sql)
    }
}
