// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import app.termorganize.logic.schedule.ScheduleExportEvent
import app.termorganize.logic.schedule.ScheduleExporter
import app.termorganize.logic.schedule.calendarText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsParityTests {
    private fun weekly(title: String = "CS 2500 Lecture", notes: String = "") = ScheduleExportEvent(
        uid = "plan-1-0@neuclasssearch", title = title, location = "West Village H 210", notes = notes,
        year = 2026, month = 9, day = 9, startMinute = 600, endMinute = 665,
        weekdays = listOf(1, 3), lastYear = 2026, lastMonth = 12, lastDay = 4,
    )

    private fun text(vararg events: ScheduleExportEvent) = ScheduleExporter.calendarText(events.toList(), "Fall", 0L)

    private fun physicalLines(ics: String) = ics.split("\r\n").filter { it.isNotEmpty() }

    @Test fun writesVtimezoneBeforeEvents() {
        val ics = text(weekly())
        assertTrue(ics.indexOf("BEGIN:VTIMEZONE") in 0 until ics.indexOf("BEGIN:VEVENT"))
        assertTrue(ics.contains("TZID:America/New_York"))
        assertTrue(ics.contains("END:VTIMEZONE"))
    }

    @Test fun timedFinalExamUsesTzidAndSameDayEnd() {
        val exam = ScheduleExportEvent(
            uid = "plan-1-final@neuclasssearch", title = "CS 2500 Final Exam", location = null, notes = "",
            year = 2026, month = 12, day = 10, startMinute = 480, endMinute = 600, isFinalExam = true,
        )
        val ics = text(weekly(), exam)
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertTrue(ics.contains("DTSTART;TZID=America/New_York:20261210T080000"))
        assertTrue(ics.contains("DTEND;TZID=America/New_York:20261210T100000"))
        assertEquals(1, Regex("RRULE:FREQ=WEEKLY").findAll(ics).count())
    }

    @Test fun untimedFinalExamIsDateValuedWithNextDayEnd() {
        val exam = ScheduleExportEvent(
            uid = "plan-1-final@neuclasssearch", title = "CS 2500 Final Exam", location = null, notes = "",
            year = 2026, month = 12, day = 31, startMinute = null, endMinute = null, isFinalExam = true,
        )
        val ics = text(exam)
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261231"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:20270101"))
        assertTrue(!ics.contains("RRULE:FREQ=WEEKLY"))
    }

    @Test fun timedUntilIsUtcAndAllDayUntilIsDate() {
        assertTrue(text(weekly()).contains("UNTIL=20261205T045959Z"))
        val allDay = weekly().copy(startMinute = null, endMinute = null)
        assertTrue(text(allDay).contains("UNTIL=20261204\r\n"))
    }

    @Test fun foldsByOctetsAndUnfoldsToTheOriginal() {
        val notes = "café 中文 🎓 ".repeat(30).trim()
        val ics = text(weekly(notes = notes))
        assertTrue(ics.contains("\r\n "))
        for (line in physicalLines(ics)) {
            assertTrue(line, line.toByteArray(Charsets.UTF_8).size <= 75)
        }
        val unfolded = ics.replace("\r\n ", "")
        assertTrue(unfolded.contains("DESCRIPTION:" + notes))
        assertTrue(!unfolded.contains("�"))
    }

    @Test fun shortLinesAreNotFolded() {
        val ics = text(weekly())
        assertTrue(ics.contains("SUMMARY:CS 2500 Lecture\r\n"))
    }
}
