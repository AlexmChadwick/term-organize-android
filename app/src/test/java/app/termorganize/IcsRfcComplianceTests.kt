// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Meeting
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.schedule.ScheduleExporter
import app.termorganize.logic.schedule.calendarText
import app.termorganize.logic.schedule.events
import app.termorganize.logic.search.CatalogIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsRfcComplianceTests {
    private fun section() = CourseSection(
        crn = "11111", subject = "CS", number = "3000", classType = "Lecture",
        meetings = listOf(
            Meeting(DaySet(listOf(1, 3)), 600, 675, startDate = "2026-09-09", endDate = "2026-12-13"),
            Meeting(DaySet(listOf(2)), 600, 675, startDate = "2026-09-09"),
            Meeting(DaySet(listOf(4)), 600, 675, endDate = "2026-12-13"),
            Meeting(DaySet(listOf(5)), 480, 600, kind = "Final Exam", startDate = "2026-12-15", endDate = "2026-12-15"),
        ),
    )

    private fun index() = CatalogIndex.build(TermCatalog(term = Term("202710", "Fall 2026"), sections = listOf(section())))

    @Test fun everyVeventHasUtcDtstamp() {
        val plan = SchedulePlan(name = "P", termCode = "202710", selectedCRNs = listOf("11111"))
        val (events, _) = ScheduleExporter.events(plan, index())
        val text = ScheduleExporter.calendarText(events, "Fall", 0L)
        val count = Regex("BEGIN:VEVENT").findAll(text).count()
        assertTrue(count > 0)
        assertEquals(count, Regex("\r\nDTSTAMP:\\d{8}T\\d{6}Z\r\n").findAll(text).count())
    }

    @Test fun uidsAreUniquePerPlanAndMeetingNotCrnOnly() {
        val one = SchedulePlan(id = "AAAA", name = "Fall", termCode = "202710", selectedCRNs = listOf("11111"))
        val two = SchedulePlan(id = "BBBB", name = "Spring", termCode = "202730", selectedCRNs = listOf("11111"))
        val first = ScheduleExporter.events(one, index()).first.map { it.uid }
        val second = ScheduleExporter.events(two, index()).first.map { it.uid }
        assertEquals(first.size, first.toSet().size)
        assertTrue((first intersect second.toSet()).isEmpty())
        assertTrue(first.all { it.startsWith("aaaa-11111-") && it.endsWith("@neuclasssearch") })
    }

    @Test fun weeklyMeetingWithoutKnownEndDateIsSkippedNotUnbounded() {
        val plan = SchedulePlan(name = "P", termCode = "202710", selectedCRNs = listOf("11111"))
        val (events, summary) = ScheduleExporter.events(plan, index())
        assertEquals(1, summary.weeklyMeetings)
        assertEquals(2, summary.skippedMeetings)
        val text = ScheduleExporter.calendarText(events, "Fall", 0L)
        val rules = Regex("RRULE:FREQ=WEEKLY[^\r]*").findAll(text).map { it.value }.toList()
        assertEquals(1, rules.size)
        assertTrue(rules.all { it.contains(";UNTIL=") })
        assertFalse(text.contains("BYDAY=TU"))
        assertFalse(text.contains("BYDAY=TH"))
    }
}
