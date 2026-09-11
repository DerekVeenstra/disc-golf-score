package com.veenstra.discgolfscore

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedRoundTest {

    @Test
    fun `a round's date is shown in the watch's own time zone, not UTC`() {
        // 11:30pm in Chicago on Sep 11 is already Sep 12 in UTC.
        val chicago = ZoneId.of("America/Chicago")
        val lateEvening = ZonedDateTime.of(2026, 9, 11, 23, 30, 0, 0, chicago).toInstant().toEpochMilli()

        assertEquals("Sep 11, 2026", formatRoundDate(lateEvening, chicago, Locale.US))
        assertEquals("Sep 12, 2026", formatRoundDate(lateEvening, ZoneId.of("UTC"), Locale.US))
    }
}
