// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import android.provider.CalendarContract
import app.termorganize.logic.schedule.ScheduleExportEvent
import app.termorganize.logic.schedule.ScheduleExporter
import app.termorganize.logic.schedule.calendarText
import app.termorganize.logic.schedule.contentValues
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScheduleExportAllDayTests {
    private fun allDay(year: Int, month: Int, day: Int) = ScheduleExportEvent(
        uid = "test-$year-$month-$day",
        title = "All day",
        location = null,
        notes = "",
        year = year,
        month = month,
        day = day,
        startMinute = null,
        endMinute = null,
    )

    private fun assertUtcMidnights(year: Int, month: Int, day: Int) {
        val values = ScheduleExporter.contentValues(allDay(year, month, day), 1L)
        val date = LocalDate.of(year, month, day)
        val start = values.getAsLong(CalendarContract.Events.DTSTART)
        val end = values.getAsLong(CalendarContract.Events.DTEND)
        assertEquals(1, values.getAsInteger(CalendarContract.Events.ALL_DAY))
        assertEquals("UTC", values.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        assertEquals(0L, start % 86_400_000L)
        assertEquals(0L, end % 86_400_000L)
        assertEquals(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), start)
        assertEquals(date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), end)
        assertEquals(86_400_000L, end - start)
    }

    @Test
    fun allDayEventUsesUtcMidnights() = assertUtcMidnights(2026, 1, 15)

    @Test
    fun allDayEventOnSpringForwardDayUsesUtcMidnights() = assertUtcMidnights(2026, 3, 8)

    @Test
    fun allDayEventOnFallBackDayUsesUtcMidnights() = assertUtcMidnights(2026, 11, 1)

    @Test
    fun allDayEventBeforeSpringForwardUsesUtcMidnights() = assertUtcMidnights(2026, 3, 7)

    @Test
    fun timedEventKeepsNewYorkTimezone() {
        val event = allDay(2026, 3, 9).copy(startMinute = 9 * 60, endMinute = 10 * 60)
        val values = ScheduleExporter.contentValues(event, 1L)
        assertEquals("America/New_York", values.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        assertEquals(0, values.getAsInteger(CalendarContract.Events.ALL_DAY))
    }

    private fun recurring(startMinute: Int?, endMinute: Int?) = allDay(2026, 1, 12).copy(
        startMinute = startMinute,
        endMinute = endMinute,
        weekdays = listOf(1, 3),
        lastYear = 2026,
        lastMonth = 4,
        lastDay = 27,
    )

    private fun untilOf(event: ScheduleExportEvent): String =
        ScheduleExporter.contentValues(event, 1L).getAsString(CalendarContract.Events.RRULE)
            .split(";").first { it.startsWith("UNTIL=") }.removePrefix("UNTIL=")

    @Test
    fun recurringAllDayRruleUntilIsLastDayUtcMidnight() {
        assertEquals("20260427T000000Z", untilOf(recurring(null, null)))
    }

    @Test
    fun recurringTimedRruleUntilKeepsNewYorkEndOfDay() {
        // 23:59:59 EDT on 2026-04-27 is 03:59:59Z on 2026-04-28.
        assertEquals("20260428T035959Z", untilOf(recurring(9 * 60, 10 * 60)))
    }

    @Test
    fun recurringAllDayUsesDurationAndNoDtend() {
        val values = ScheduleExporter.contentValues(recurring(null, null), 1L)
        assertEquals("P1D", values.getAsString(CalendarContract.Events.DURATION))
        assertFalse(values.containsKey(CalendarContract.Events.DTEND))
        assertEquals(1, values.getAsInteger(CalendarContract.Events.ALL_DAY))
        assertEquals("UTC", values.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        assertTrue(values.containsKey(CalendarContract.Events.RRULE))
    }

    @Test
    fun nonRecurringAllDayKeepsDtendAndNoDuration() {
        val values = ScheduleExporter.contentValues(allDay(2026, 1, 12), 1L)
        assertTrue(values.containsKey(CalendarContract.Events.DTEND))
        assertFalse(values.containsKey(CalendarContract.Events.DURATION))
        assertFalse(values.containsKey(CalendarContract.Events.RRULE))
    }

    private fun icsRrule(event: ScheduleExportEvent): String =
        ScheduleExporter.calendarText(listOf(event), "Test", 0L)
            .split("\r\n").first { it.startsWith("RRULE:FREQ=WEEKLY") }

    @Test
    fun icsAllDayRecurringUntilIsPlainDate() {
        val rrule = icsRrule(recurring(null, null))
        assertTrue(rrule, rrule.contains("UNTIL=20260427"))
        assertFalse(rrule, rrule.substringAfter("UNTIL=").contains("T"))
        assertEquals("20260427", rrule.substringAfter("UNTIL="))
    }

    @Test
    fun icsTimedRecurringUntilKeepsUtcDateTime() {
        val rrule = icsRrule(recurring(9 * 60, 10 * 60))
        assertTrue(rrule, rrule.contains("UNTIL=20260428T035959Z"))
    }
}
