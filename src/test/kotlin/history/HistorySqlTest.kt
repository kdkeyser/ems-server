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
}
