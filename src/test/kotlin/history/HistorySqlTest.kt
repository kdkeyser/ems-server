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

    @Test fun parsesNewlineDelimitedRows() {
        val body = """
            {"ts":1749456000,"grid_power":-1200,"solar_power":-3400,"charger_power":1100,"heatpump_power":800,"battery_power":-1300,"battery_charge":72}
            {"ts":1749456005,"grid_power":-1100,"solar_power":-3300,"charger_power":1100,"heatpump_power":810,"battery_power":-1200,"battery_charge":72}
        """.trimIndent()
        val points = parsePowerPoints(body)
        assertEquals(2, points.size)
        assertEquals(1749456000L, points[0].ts)
        assertEquals(-1200, points[0].gridPower)
        assertEquals(72, points[1].batteryCharge)
    }

    @Test fun parseHandlesNullsAndBlankLines() {
        val body = "{\"ts\":1749456000,\"grid_power\":null,\"solar_power\":0,\"charger_power\":0,\"heatpump_power\":0,\"battery_power\":0,\"battery_charge\":50}\n\n"
        val points = parsePowerPoints(body)
        assertEquals(1, points.size)
        assertNull(points[0].gridPower)
        assertEquals(0, points[0].solarPower)
    }

    @Test fun parseEmptyBodyReturnsEmpty() {
        assertTrue(parsePowerPoints("").isEmpty())
        assertTrue(parsePowerPoints("   \n  ").isEmpty())
    }

    @Test fun insertValuesFormatsTimestampAndNulls() {
        val rows = listOf(
            TimestampedEmsState(
                java.time.Instant.ofEpochSecond(1749456000),
                io.konektis.ems.EMSState(
                    gridPower = -1200, gridVoltage = 230, chargerPower = 1100,
                    heatpumpPower = 800, solarPower = -3400, batteryPower = -1300,
                    batteryCharge = 72,
                ),
            ),
            TimestampedEmsState(
                java.time.Instant.ofEpochSecond(1749456005),
                io.konektis.ems.EMSState(
                    gridPower = null, gridVoltage = null, chargerPower = null,
                    heatpumpPower = null, solarPower = null, batteryPower = null,
                    batteryCharge = null,
                ),
            ),
        )
        val sql = buildInsertSql("ems", rows)
        assertTrue(sql.startsWith("INSERT INTO ems.power_raw"), sql)
        assertTrue(sql.contains("FORMAT Values"), sql)
        // First row: epoch wrapped in toDateTime, columns in schema order, NULL spelled out.
        assertTrue(sql.contains("(toDateTime(1749456000),-1200,-3400,1100,800,-1300,72)"), sql)
        assertTrue(sql.contains("(toDateTime(1749456005),NULL,NULL,NULL,NULL,NULL,NULL)"), sql)
    }

    @Test fun insertValuesEmptyRowsIsBlank() {
        assertTrue(buildInsertSql("ems", emptyList()).isEmpty())
    }
}
