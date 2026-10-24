package io.github.proify.lyricon.app.activity.lyric

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TranslationLibraryDateGroupingTest {
    @Test fun groupsUseLocalCalendarDatesAcrossMidnightAndMissingTimestamps() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 10, 23)
        fun time(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals("今天", translationLibraryDateGroup(time(today), today, zone))
        assertEquals("昨天", translationLibraryDateGroup(time(today) - 1, today, zone))
        assertEquals("更早", translationLibraryDateGroup(time(today.minusDays(2)), today, zone))
        assertEquals("时间未记录", translationLibraryDateGroup(0, today, zone))
        assertEquals("未来日期", translationLibraryDateGroup(time(today.plusDays(1)), today, zone))
    }

    @Test fun yesterdayIsACalendarDateRatherThanTwentyFourHoursAgo() {
        val zone = ZoneId.of("America/New_York")
        val today = LocalDate.of(2026, 11, 2)
        val yesterday = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals("昨天", translationLibraryDateGroup(yesterday, today, zone))
        assertEquals("昨天", translationLibraryDateGroup(today.atStartOfDay(zone).toInstant().toEpochMilli() - 1, today, zone))
    }
}
