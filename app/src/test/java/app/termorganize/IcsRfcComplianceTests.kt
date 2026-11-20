// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Meeting
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.logic.degree.PlanCourse
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
        val (events, summary) = ScheduleExporter.events(plan, index())
        assertEquals(1, summary.weeklyMeetings)
        assertEquals(1, summary.finalExams)
        assertEquals(2, summary.skippedMeetings)
        assertEquals(2, events.size)

        val text = ScheduleExporter.calendarText(events, "Fall", 0L)
        val veventBlocks = Regex("BEGIN:VEVENT\r\n[\\s\\S]*?END:VEVENT\r\n").findAll(text).map { it.value }.toList()
        assertEquals(2, veventBlocks.size)
        for (block in veventBlocks) {
            val stamps = Regex("\r\nDTSTAMP:\\d{8}T\\d{6}Z\r\n").findAll(block).toList()
            assertEquals(1, stamps.size)
        }
    }

    @Test fun uidsAreUniquePerPlanAndMeetingNotCrnOnly() {
        val one = SchedulePlan(id = "AAAA", name = "Fall", termCode = "202710", selectedCRNs = listOf("11111"))
        val two = SchedulePlan(id = "BBBB", name = "Spring", termCode = "202730", selectedCRNs = listOf("11111"))
        val (firstEvents, firstSummary) = ScheduleExporter.events(one, index())
        val (secondEvents, secondSummary) = ScheduleExporter.events(two, index())
        assertEquals(1, firstSummary.weeklyMeetings)
        assertEquals(1, firstSummary.finalExams)
        assertEquals(1, secondSummary.weeklyMeetings)
        assertEquals(1, secondSummary.finalExams)

        val firstUids = firstEvents.map { it.uid }
        val secondUids = secondEvents.map { it.uid }
        assertTrue(firstUids.isNotEmpty())
        assertTrue(secondUids.isNotEmpty())
        assertEquals(firstUids.size, firstUids.toSet().size)
        assertEquals(secondUids.size, secondUids.toSet().size)
        assertTrue((firstUids intersect secondUids.toSet()).isEmpty())
        assertTrue(firstUids.all { it.startsWith("aaaa-11111-") && it.endsWith("@neuclasssearch") })
        assertTrue(secondUids.all { it.startsWith("bbbb-11111-") && it.endsWith("@neuclasssearch") })

        val textOne = ScheduleExporter.calendarText(firstEvents, "Fall", 0L)
        val textTwo = ScheduleExporter.calendarText(secondEvents, "Spring", 0L)
        val uidsFromBlocksOne = Regex("BEGIN:VEVENT\r\n[\\s\\S]*?END:VEVENT\r\n").findAll(textOne).map { block ->
            Regex("\r\nUID:([^\r\n]+)\r\n").find(block.value)!!.groupValues[1]
        }.toList()
        val uidsFromBlocksTwo = Regex("BEGIN:VEVENT\r\n[\\s\\S]*?END:VEVENT\r\n").findAll(textTwo).map { block ->
            Regex("\r\nUID:([^\r\n]+)\r\n").find(block.value)!!.groupValues[1]
        }.toList()
        assertTrue(uidsFromBlocksOne.isNotEmpty())
        assertTrue(uidsFromBlocksTwo.isNotEmpty())
        assertEquals(uidsFromBlocksOne.size, uidsFromBlocksOne.toSet().size)
        assertEquals(uidsFromBlocksTwo.size, uidsFromBlocksTwo.toSet().size)
        assertTrue((uidsFromBlocksOne intersect uidsFromBlocksTwo.toSet()).isEmpty())
        assertEquals(firstUids, uidsFromBlocksOne)
        assertEquals(secondUids, uidsFromBlocksTwo)
    }

    @Test fun weeklyMeetingWithoutKnownEndDateIsSkippedNotUnbounded() {
        val plan = SchedulePlan(name = "P", termCode = "202710", selectedCRNs = listOf("11111"))
        val (events, summary) = ScheduleExporter.events(plan, index())
        assertEquals(1, summary.weeklyMeetings)
        assertEquals(1, summary.finalExams)
        assertEquals(2, summary.skippedMeetings)
        assertEquals(2, events.size)
        val text = ScheduleExporter.calendarText(events, "Fall", 0L)
        val rules = Regex("RRULE:FREQ=WEEKLY[^\r]*").findAll(text).map { it.value }.toList()
        assertEquals(1, rules.size)
        assertTrue(rules.all { it.contains(";UNTIL=") })
        assertFalse(text.contains("BYDAY=TU"))
        assertFalse(text.contains("BYDAY=TH"))
    }

    @Test fun timedUntilChecksBoth2026DstTransitions() {
        val springTransitionSection = CourseSection(
            crn = "22222", subject = "CS", number = "3500", classType = "Lecture",
            meetings = listOf(
                Meeting(DaySet(listOf(0)), 600, 675, startDate = "2026-02-01", endDate = "2026-03-08"),
            ),
        )
        val fallTransitionSection = CourseSection(
            crn = "33333", subject = "CS", number = "4500", classType = "Lecture",
            meetings = listOf(
                Meeting(DaySet(listOf(0)), 600, 675, startDate = "2026-10-01", endDate = "2026-11-01"),
            ),
        )
        val cat = CatalogIndex.build(TermCatalog(
            term = Term("202630", "Spring 2026"),
            sections = listOf(springTransitionSection, fallTransitionSection),
        ))
        val plan = SchedulePlan(name = "DST", termCode = "202630", selectedCRNs = listOf("22222", "33333"))
        val (events, summary) = ScheduleExporter.events(plan, cat)
        assertEquals(2, summary.weeklyMeetings)
        assertEquals(0, summary.finalExams)
        val text = ScheduleExporter.calendarText(events, "DST", 0L)
        assertTrue(text.contains("UNTIL=20260309T035959Z"))
        assertTrue(text.contains("UNTIL=20261102T045959Z"))
    }

    @Test fun fullFileCrlfLineEndingsWithNoBareCrOrBareLf() {
        val plan = SchedulePlan(name = "Line\rEndings", termCode = "202710", selectedCRNs = listOf("11111"))
        val (events, _) = ScheduleExporter.events(plan, index())
        val text = ScheduleExporter.calendarText(events, "Fall\rTerm", 0L)
        assertTrue(text.endsWith("\r\n"))
        val withoutCrlf = text.replace("\r\n", "")
        assertFalse(withoutCrlf.contains("\r"))
        assertFalse(withoutCrlf.contains("\n"))

        val bytes = text.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.isNotEmpty())
        for (i in bytes.indices) {
            if (bytes[i] == '\r'.code.toByte()) {
                assertTrue("CR at $i must be followed by LF", i + 1 < bytes.size && bytes[i + 1] == '\n'.code.toByte())
            }
            if (bytes[i] == '\n'.code.toByte()) {
                assertTrue("LF at $i must be preceded by CR", i > 0 && bytes[i - 1] == '\r'.code.toByte())
            }
        }
    }

    @Test fun descriptionUsesCatalogCourseTitleWithSectionTitleFallbackAndFormattedCrn() {
        val sectionWithTitles = CourseSection(
            crn = "44444", subject = "CS", number = "3500", classType = "Lecture", sectionNumber = "01",
            title = "Section Specific Title",
            meetings = listOf(
                Meeting(DaySet(listOf(1)), 600, 675, startDate = "2026-09-09", endDate = "2026-12-13"),
            ),
        )
        val sectionEmptyCatalogTitle = CourseSection(
            crn = "55555", subject = "CS", number = "4500", classType = "Lab", sectionNumber = "02",
            title = "Section Fallback Title",
            meetings = listOf(
                Meeting(DaySet(listOf(2)), 600, 675, startDate = "2026-09-09", endDate = "2026-12-13"),
            ),
        )
        val catalog = TermCatalog(
            term = Term("202710", "Fall 2026"),
            courses = listOf(
                Course(subject = "CS", number = "3500", title = "Object-Oriented Design"),
                Course(subject = "CS", number = "4500", title = ""),
            ),
            sections = listOf(sectionWithTitles, sectionEmptyCatalogTitle),
        )
        val idx = CatalogIndex.build(catalog)
        val plan = SchedulePlan(
            name = "Titles", termCode = "202710",
            courses = listOf(PlanCourse(key = CourseKey("CS", "3500")), PlanCourse(key = CourseKey("CS", "4500"))),
            selectedCRNs = listOf("44444", "55555"),
        )
        val (events, summary) = ScheduleExporter.events(plan, idx)
        assertEquals(2, summary.weeklyMeetings)
        val text = ScheduleExporter.calendarText(events, "Titles", 0L)

        // Course with catalog title uses catalog title in description, not course key or section title
        assertTrue(text.contains("DESCRIPTION:Object-Oriented Design\\nCRN 44444 · Lecture 01"))
        assertFalse(text.contains("DESCRIPTION:CS 3500"))
        assertFalse(text.contains("DESCRIPTION:Section Specific Title"))

        // Course with empty catalog title falls back to section title
        assertTrue(text.contains("DESCRIPTION:Section Fallback Title\\nCRN 55555 · Lab 02"))
    }

    @Test fun invalidDatesAndUntimedFinalsSkippedWithoutThrowing() {
        val sectionWithBadDates = CourseSection(
            crn = "66666", subject = "CS", number = "2500", classType = "Lecture",
            meetings = listOf(
                Meeting(DaySet(listOf(1)), 600, 675, startDate = "2026-09-09", endDate = "2026-12-13"),
                Meeting(DaySet(listOf(2)), 600, 675, startDate = "2026-02-30", endDate = "2026-05-01"),
                Meeting(DaySet(listOf(3)), 600, 675, startDate = "2026-01-10", endDate = "2026-02-30"),
                Meeting(DaySet(listOf(4)), 600, 720, kind = "Final Exam", startDate = "2026-02-30"),
            ),
        )
        val sectionWithUntimedFinal = CourseSection(
            crn = "77777", subject = "CS", number = "2510", classType = "Lecture",
            meetings = listOf(
                Meeting(DaySet(listOf(1)), 600, 675, startDate = "2026-09-09", endDate = "2026-12-13"),
                Meeting(DaySet(listOf(5)), null, null, kind = "Final Exam", startDate = "2026-12-18", endDate = "2026-12-18"),
            ),
        )
        val cat = CatalogIndex.build(TermCatalog(
            term = Term("202710", "Fall 2026"),
            sections = listOf(sectionWithBadDates, sectionWithUntimedFinal),
        ))
        val plan = SchedulePlan(name = "BadDates", termCode = "202710", selectedCRNs = listOf("66666", "77777"))
        val (events, summary) = ScheduleExporter.events(plan, cat)
        assertEquals(2, summary.weeklyMeetings)
        assertEquals(1, summary.finalExams)
        assertEquals(2, summary.skippedMeetings)

        val text = ScheduleExporter.calendarText(events, "BadDates", 0L)
        assertTrue(text.contains("UID:baddates-66666-0@neuclasssearch"))
        assertTrue(text.contains("UID:baddates-77777-0@neuclasssearch"))
        assertTrue(text.contains("UID:baddates-77777-final@neuclasssearch"))
        assertFalse(text.contains("UID:baddates-66666-final@neuclasssearch"))
        assertTrue(text.contains("DTSTART;VALUE=DATE:20261218"))
        assertTrue(text.contains("DTEND;VALUE=DATE:20261219"))
    }
}
