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
import app.termorganize.logic.degree.AppPreferences
import app.termorganize.logic.degree.CourseDegreePlanStanding
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.degree.ScheduleAutofillSuggestion
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.degree.UserDataStore
import app.termorganize.logic.professors.CourseColors
import app.termorganize.logic.schedule.CalendarEvent
import app.termorganize.logic.schedule.CalendarLayout
import app.termorganize.logic.schedule.BlockedTime
import app.termorganize.logic.schedule.GeneratedSchedule
import app.termorganize.logic.schedule.PlanEditing
import app.termorganize.logic.schedule.PreferenceStrength
import app.termorganize.logic.schedule.ResolvedPlan
import app.termorganize.logic.schedule.ScheduleAutofillSupport
import app.termorganize.logic.schedule.ScheduleCarouselLayout
import app.termorganize.logic.schedule.ScheduleClock
import app.termorganize.logic.schedule.ScheduleCourseInput
import app.termorganize.logic.schedule.ScheduleDroppedCourse
import app.termorganize.logic.schedule.ScheduleFilters
import app.termorganize.logic.schedule.ScheduleGenerator
import app.termorganize.logic.schedule.ScheduleNoResultsText
import app.termorganize.logic.schedule.ScheduleNotices
import app.termorganize.logic.schedule.SchedulePlanActions
import app.termorganize.logic.schedule.SchedulePreferences
import app.termorganize.logic.schedule.ScheduleRelaxation
import app.termorganize.logic.schedule.ScheduleReorderSuggestions
import app.termorganize.logic.schedule.analyzeEmptyResult
import app.termorganize.logic.schedule.favorites
import app.termorganize.logic.schedule.inputs
import app.termorganize.logic.search.CatalogIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleSupportTests {
    @Test fun resolvedEventsExpandAndFoldWeeklyBlocksFromPinnedAndLinkedSections() {
        val lecture = section("A1", "CS", "3000", listOf(1, 3), 10.0, 11.0)
        val first = lecture.meetings.single().copy(startDate = "2026-09-09", endDate = "2026-10-14")
        val listed = lecture.copy(meetings = listOf(
            first,
            first.copy(startDate = "2026-10-15", endDate = "2026-12-10"),
            first.copy(days = DaySet.MONDAY, room = "211"),
            first.copy(kind = "Final Exam"),
            first.copy(start = null),
        ))
        val lab = section("A2", "CS", "3000", listOf(5), 11.0, 12.0).copy(
            classType = "Lab",
            meetings = listOf(Meeting(DaySet.FRIDAY, 660, 720, campusCode = "VTL")),
        )
        val untimed = lecture.copy(crn = "A3", meetings = listOf(first.copy(kind = "Final Exam")))
        val orphan = section("B1", "MATH", "2331", listOf(1), 10.0, 11.0)
        val base = plan(listOf("CS 3000"), listOf("A2", "A1", "A3", "B1", "missing"))
        val pinned = base.copy(courses = listOf(base.courses.single().copy(crn = "A1", colorIndex = 3)))
        val catalog = index(listOf(course("CS", "3000")), listOf(listed, lab, untimed, orphan))
        val resolved = ResolvedPlan(pinned, catalog)
        val color = CourseColors.at(3)
        val later = listOf(CalendarEvent.DateWindow("2026-10-15", "2026-12-10"))
        assertEquals(listOf(
            CalendarEvent("A1-0-1-600", "CS 3000", "WVH 210", "Lecture", 1, 600, 660, color, "A1", first.startDate, first.endDate, laterWindows = later),
            CalendarEvent("A1-0-3-600", "CS 3000", "WVH 210", "Lecture", 3, 600, 660, color, "A1", first.startDate, first.endDate, laterWindows = later),
            CalendarEvent("A1-2-1-600", "CS 3000", "WVH 211", "Lecture", 1, 600, 660, color, "A1", first.startDate, first.endDate),
            CalendarEvent("A2-0-5-660", "CS 3000", "Online", "Lab", 5, 660, 720, color, "A2", null, null),
        ), resolved.events)
        assertEquals(listOf("A3"), resolved.untimed.map { it.section.crn })
        assertEquals(listOf("B1"), resolved.orphanedCRNs)
        assertEquals(listOf("missing"), resolved.unlistedCRNs)
        assertTrue(ResolvedPlan(pinned, null).events.isEmpty())
    }

    @Test fun resolvedEventsUseEachPinnedRowsColorAndMarkOnlyActualOverlaps() {
        val early = section("A1", "CS", "3000", listOf(1, 3), 10.0, 11.0)
        val slot = early.meetings.single().copy(endDate = "2026-10-14")
        val a = early.copy(meetings = listOf(slot))
        val b = early.copy(crn = "A2", meetings = listOf(slot.copy(days = DaySet.MONDAY, startDate = "2026-10-15", endDate = null)))
        val c = section("B1", "MATH", "2331", listOf(1), 10.5, 11.0)
        val overlap = c.copy(meetings = listOf(c.meetings.single().copy(endDate = "2026-10-14")))
        val backToBack = c.copy(crn = "B2", meetings = listOf(c.meetings.single().copy(start = 660, end = 720)))
        val base = plan(listOf("CS 3000", "CS 3000", "MATH 2331"), listOf("B1", "A2", "B2", "A1"))
        val pinned = base.copy(courses = listOf(
            base.courses[0].copy(crn = "A1"),
            base.courses[1].copy(crn = "A2"),
            base.courses[2],
        ))
        val catalog = index(listOf(course("CS", "3000"), course("MATH", "2331")), listOf(a, b, overlap, backToBack))
        val events = ResolvedPlan(pinned, catalog).events
        assertEquals(listOf("A1", "A1", "A2", "B1", "B2"), events.map { it.payload })
        assertEquals(listOf(0, 0, 1, 2, 2).map(CourseColors::at), events.map { it.color })
        assertEquals(listOf(true, false, false, true, false), events.map { it.isConflict })
    }

    @Test fun twoUnpinnedRowsWithTheSameCourseKeyDoNotDoubleSectionsBlocksOrConflicts() {
        val a1 = section("A1", "CS", "3000", listOf(1, 3), 10.0, 11.0)
        val a2 = section("A2", "CS", "3000", listOf(2, 4), 13.0, 14.0)
        val catalog = index(listOf(course("CS", "3000")), listOf(a1, a2))
        val resolved = ResolvedPlan(plan(listOf("CS 3000", "CS 3000"), listOf("A1", "A2")), catalog)
        assertEquals(listOf("A1", "A2"), resolved.selectedSections.map { it.crn })
        assertEquals(listOf("A1", "A1", "A2", "A2"), resolved.events.map { it.payload })
        assertEquals(listOf(false, false, false, false), resolved.events.map { it.isConflict })
        assertTrue(resolved.conflicts.isEmpty())
        assertTrue(resolved.unlistedCRNs.isEmpty() && resolved.orphanedCRNs.isEmpty())
        assertEquals(listOf("A1", "A2"), resolved.entries.flatMap { it.selected }.map { it.crn })
    }

    @Test fun anUnpinnedRowSkipsCrnsClaimedByALaterPinnedSiblingRow() {
        val a1 = section("A1", "CS", "3000", listOf(1), 10.0, 11.0)
        val a2 = section("A2", "CS", "3000", listOf(2), 10.0, 11.0)
        val base = plan(listOf("CS 3000", "CS 3000"), listOf("A1", "A2"))
        val pinned = base.copy(courses = listOf(base.courses[0], base.courses[1].copy(crn = "A2")))
        val resolved = ResolvedPlan(pinned, index(listOf(course("CS", "3000")), listOf(a1, a2)))
        assertEquals(listOf("A1"), resolved.entries[0].selected.map { it.crn })
        assertEquals(listOf("A2"), resolved.entries[1].selected.map { it.crn })
        assertEquals(listOf("A1", "A2"), resolved.selectedSections.map { it.crn })
        assertTrue(resolved.conflicts.isEmpty())
    }

    private fun windowed(crn: String, subject: String, number: String, vararg windows: Pair<String, String>): CourseSection {
        val base = section(crn, subject, number, listOf(1), 10.0, 11.0)
        val slot = base.meetings.single()
        return base.copy(meetings = windows.map { (from, to) -> slot.copy(startDate = from, endDate = to) })
    }

    private fun resolvedEvents(a: CourseSection, b: CourseSection): List<CalendarEvent> {
        val catalog = index(
            listOf(course(a.courseKey.subject, a.courseKey.number), course(b.courseKey.subject, b.courseKey.number)),
            listOf(a, b),
        )
        val chosen = plan(listOf(a.courseKey.description, b.courseKey.description), listOf(a.crn, b.crn))
        return ResolvedPlan(chosen, catalog).events
    }

    @Test fun foldedRepeatsKeepEveryDateRangeSoALaterRangeStillConflicts() {
        val early = "2026-09-14" to "2026-09-14"
        val late = "2026-10-19" to "2026-10-19"
        val first = windowed("A1", "BIOT", "5145", early, late)
        val second = windowed("B1", "SCHM", "6418", late)
        val events = resolvedEvents(first, second)
        assertEquals(listOf("A1", "B1"), events.map { it.payload })
        assertEquals(listOf(true, true), events.map { it.isConflict })
        assertEquals(
            listOf(CalendarEvent.DateWindow(early.first, early.second), CalendarEvent.DateWindow(late.first, late.second)),
            events.first().dateWindows,
        )
        val reversed = resolvedEvents(windowed("A1", "BIOT", "5145", late, early), second)
        assertEquals(listOf(true, true), reversed.map { it.isConflict })
    }

    @Test fun foldedRepeatsDoNotConflictInTheGapBetweenTheirRanges() {
        val first = windowed("A1", "BIOT", "5145", "2026-09-14" to "2026-09-20", "2026-10-19" to "2026-10-25")
        val second = windowed("B1", "SCHM", "6418", "2026-09-28" to "2026-10-04")
        assertEquals(listOf(false, false), resolvedEvents(first, second).map { it.isConflict })
    }

    @Test fun identicalRepeatRangesFoldWithoutExtraWindows() {
        val folded = CalendarEvent.forSection(windowed("A1", "BIOT", "5145", "2026-09-14" to "2026-09-20", "2026-09-14" to "2026-09-20"), 0)
        assertEquals(1, folded.size)
        assertTrue(folded.single().laterWindows.isEmpty())
    }

    private fun block(id: String, day: Int, start: Int, end: Int) =
        CalendarEvent(id, "CS 3000", "Online", "Lecture", day, start, end, 0, id, null, null)

    @Test fun laneCountIsPerOverlapGroupSoALoneBlockKeepsFullWidth() {
        val crowded = (1..8).map { block("c$it", 1, 600, 660) }
        val lone = block("lone", 1, 780, 840)
        val chained = listOf(block("x", 1, 900, 960), block("y", 1, 930, 990))
        val laned = CalendarLayout.lanes(crowded + lone + chained).associateBy { it.event.id }
        assertEquals(1, laned.getValue("lone").count)
        assertEquals(0, laned.getValue("lone").index)
        crowded.forEach { assertEquals(8, laned.getValue(it.id).count) }
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6, 7), crowded.map { laned.getValue(it.id).index }.toSet())
        assertEquals(listOf(2, 2), chained.map { laned.getValue(it.id).count })
        assertEquals(listOf(0, 1), chained.map { laned.getValue(it.id).index })
    }

    @Test fun gridWidensHoursAndAddsWeekendDaysForSelectedMeetings() {
        val late = block("late", 1, 19 * 60, 21 * 60 + 30)
        val early = block("early", 2, 7 * 60 + 30, 9 * 60)
        val saturday = block("sat", 6, 600, 660)
        assertEquals(CalendarLayout.HourRange(8, 21), CalendarLayout.hourRange(emptyList(), 8, 21))
        assertEquals(CalendarLayout.HourRange(8, 22), CalendarLayout.hourRange(listOf(late), 8, 21))
        assertEquals(CalendarLayout.HourRange(7, 21), CalendarLayout.hourRange(listOf(early), 8, 21))
        assertEquals(CalendarLayout.HourRange(8, 21), CalendarLayout.hourRange(listOf(saturday), 8, 21))
        assertEquals(listOf(1, 2, 3, 4, 5), CalendarLayout.visibleDays(listOf(late), false))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), CalendarLayout.visibleDays(listOf(saturday), false))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), CalendarLayout.visibleDays(listOf(saturday, block("sun", 0, 600, 660)), false))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), CalendarLayout.visibleDays(emptyList(), true))
    }

    @Test fun blockAccessibilityLabelNamesCourseDayTimeRoomAndConflict() {
        val event = block("a", 1, 600, 660)
        assertEquals("CS 3000, Monday 10:00 \u2013 11:00 AM, Online", CalendarLayout.accessibilityLabel(event))
        assertEquals(
            "CS 3000, Monday 10:00 \u2013 11:00 AM, Online, conflicts with another class",
            CalendarLayout.accessibilityLabel(event.copy(isConflict = true)),
        )
    }

    @Test fun warningsListWhatNeedsAttention() {
        val index = clashingIndex()
        val resolved = ResolvedPlan(plan(listOf("CS 3000", "MATH 2331", "ENGW 1111"), listOf("A1", "B1")), index)
        assertEquals(listOf("1 conflict", "1 full section", "1 course needs a section"), resolved.warnings.map { it.text })
        assertEquals(app.termorganize.logic.schedule.ScheduleWarning.Severity.ERROR, resolved.warnings.first().severity)
        assertEquals(app.termorganize.logic.schedule.ScheduleWarning.Severity.NEUTRAL, resolved.warnings.last().severity)
        assertTrue(ResolvedPlan(plan(listOf("CS 3000")), null).warnings.isEmpty())
    }

    @Test fun noticesExplainConflictsAndFullSections() {
        val resolved = ResolvedPlan(plan(listOf("CS 3000", "MATH 2331"), listOf("A1", "B1")), clashingIndex())
        val notices = ScheduleNotices.make(resolved, "Fall 2026", false)
        assertEquals(listOf("conflicts", "full"), notices.map { it.id })
        assertEquals(app.termorganize.logic.schedule.ScheduleNotice.Severity.ERROR, notices[0].severity)
        assertEquals("1 time conflict", notices[0].title)
        assertTrue(notices[0].lines.first().contains("CS 3000 overlaps MATH 2331"))
        assertEquals(listOf("MATH 2331 Lecture 01 is full · Waitlist 0/5"), notices[1].lines)
        assertTrue(notices.all { it.fix == null })
    }

    @Test fun noticesOfferToForgetSectionsTheCatalogDoesNotList() {
        val resolved = ResolvedPlan(plan(listOf("CS 3000"), listOf("A1", "99999", "C1")), clashingIndex())
        val notices = ScheduleNotices.make(resolved, "Fall 2026", false)
        val ids = notices.map { it.id }
        assertTrue(ids.contains("unlisted"))
        assertTrue(ids.contains("orphaned"))
        assertEquals(listOf("99999"), notices.first { it.id == "unlisted" }.fix?.removeSections)
        assertEquals(listOf("C1"), notices.first { it.id == "orphaned" }.fix?.removeSections)
        assertEquals("Remove", notices.first { it.id == "orphaned" }.fixTitle)
    }

    @Test fun aMissingCatalogIsReportedOnceItIsKnownToBeMissing() {
        val resolved = ResolvedPlan(plan(listOf("CS 3000")), null)
        assertTrue(ScheduleNotices.make(resolved, "Spring 2027", false).isEmpty())
        val notices = ScheduleNotices.make(resolved, "Spring 2027", true)
        assertEquals(listOf("catalog"), notices.map { it.id })
        assertEquals("Course data for Spring 2027 isn’t on this device", notices[0].title)
        assertTrue(ScheduleNotices.make(ResolvedPlan(plan(emptyList()), null), "Spring 2027", true).isEmpty())
    }

    @Test fun favoritesAreMeasuredAndSkipWhenTheyCanNoLongerBeApplied() {
        var schedule = plan(listOf("CS 3000", "ENGW 1111"))
        schedule = schedule.copy(favoriteCombinations = listOf(listOf("A1", "C1"), listOf("A1", "GONE"), listOf("B1", "C1")))
        val favorites = GeneratedSchedule.favorites(schedule, clashingIndex(), SchedulePreferences())
        assertEquals(listOf(listOf("A1", "C1")), favorites.map { it.crns })
        assertEquals(listOf("A1", "C1"), favorites.first().sortedCRNs)
    }

    @Test fun favoritesComeNewestFirstInPlanOrder() {
        var schedule = plan(listOf("ENGW 1111", "CS 3000"))
        schedule = schedule.copy(favoriteCombinations = listOf(listOf("A1"), listOf("C1", "A1")))
        val favorites = GeneratedSchedule.favorites(schedule, clashingIndex(), SchedulePreferences())
        assertEquals(listOf(setOf("A1", "C1"), setOf("A1")), favorites.map { it.crns.toSet() })
        assertEquals(listOf("C1", "A1"), favorites.first().crns)
    }

    @Test fun courseInputsRoundCreditsUpAndKeepExclusions() {
        val index = index(
            listOf(course("CS", "3000", 3.5), course("MATH", "2331")),
            listOf(section("A1", "CS", "3000", listOf(1), 9.0, 10.0), section("A2", "CS", "3000", listOf(2), 9.0, 10.0)),
        )
        var schedule = plan(listOf("CS 3000", "MATH 2331"))
        val courses = schedule.courses.toMutableList()
        courses[0] = courses[0].copy(excludedCRNs = setOf("A2"))
        courses[1] = courses[1].copy(isRequired = false)
        schedule = schedule.copy(courses = courses)
        val inputs = ScheduleCourseInput.inputs(schedule, index)
        assertEquals(listOf("CS 3000", "MATH 2331"), inputs.map { it.key.description })
        assertEquals(4, inputs[0].minCredits)
        assertEquals(2, inputs[0].sections.size)
        assertEquals(setOf("A2"), inputs[0].excludedCRNs)
        assertFalse(inputs[1].isRequired)
        assertTrue(inputs[1].sections.isEmpty())
    }

    @Test fun looseningARuleEditsThePlanOrItsPreferences() {
        var schedule = plan(listOf("CS 3000", "MATH 2331")).copy(
            filters = ScheduleFilters(earliestStart = 10 * 60, freeDays = DaySet.FRIDAY),
            preferences = SchedulePreferences(daysOff = DaySet.FRIDAY, daysOffStrength = PreferenceStrength.REQUIRED),
        )
        schedule = PlanEditing.apply(ScheduleRelaxation.ClearEarliestStart, schedule)
        assertNull(schedule.filters.earliestStart)
        assertEquals(DaySet.FRIDAY, schedule.filters.freeDays)
        schedule = PlanEditing.apply(ScheduleRelaxation.MakeOptional(CourseKey("CS", "3000")), schedule)
        assertFalse(schedule.courses[0].isRequired)
        assertTrue(schedule.courses[1].isRequired)
        schedule = PlanEditing.apply(ScheduleRelaxation.Preference(app.termorganize.logic.schedule.PreferenceRelaxation.SoftenDaysOff), schedule)
        assertEquals(PreferenceStrength.PREFERRED, schedule.preferences?.daysOffStrength)
        var onlyBlock = plan(listOf("CS 3000")).copy(preferences = SchedulePreferences(maxClassesPerDay = 2))
        onlyBlock = PlanEditing.apply(ScheduleRelaxation.Preference(app.termorganize.logic.schedule.PreferenceRelaxation.ClearMaxClassesPerDay), onlyBlock)
        assertNull(onlyBlock.preferences)
        schedule = PlanEditing.makeOptional(listOf(CourseKey("CS", "3000"), CourseKey("MATH", "2331")), schedule)
        assertTrue(schedule.courses.all { !it.isRequired })
    }

    @Test fun noResultsWordingNamesWhatBlocksAndWhatWouldWork() {
        val courses = ScheduleCourseInput.inputs(plan(listOf("CS 3000", "MATH 2331")), clashingIndex())
        val result = ScheduleGenerator.generate(courses, ScheduleFilters())
        assertTrue(result.schedules.isEmpty())
        assertEquals("No schedule fits all 2 courses", ScheduleNoResultsText.title(result.diagnostics))
        val analysis = ScheduleGenerator.analyzeEmptyResult(courses, ScheduleFilters(), SchedulePreferences(), result.diagnostics)
        assertEquals("Here’s what would work.", ScheduleNoResultsText.headline(result.diagnostics, analysis.blockers))
        val reasons = ScheduleNoResultsText.reasons(result.diagnostics, "Fall 2026")
        assertEquals(listOf("CS 3000 and MATH 2331 always overlap"), reasons.map { it.title })
        val option = analysis.blockers.options.firstOrNull()
        assertNotNull(option)
        assertTrue(ScheduleNoResultsText.detail(option!!, analysis.blockers, "Fall 2026").startsWith("1 schedule"))
        assertEquals(
            "CS 3000, MATH 2331 and PHYS 1151",
            ScheduleNoResultsText.list(listOf(CourseKey("CS", "3000"), CourseKey("MATH", "2331"), CourseKey("PHYS", "1151"))),
        )
    }

    @Test fun noResultsWordingSaysWhyEverySectionWasRuledOut() {
        val result = ScheduleGenerator.generate(
            ScheduleCourseInput.inputs(plan(listOf("CS 3000")), clashingIndex()),
            ScheduleFilters(earliestStart = 20 * 60),
        )
        val reasons = ScheduleNoResultsText.reasons(result.diagnostics, "Fall 2026")
        assertEquals(listOf("No CS 3000 sections left"), reasons.map { it.title })
        assertEquals("Its only section starts before 8:00 PM.", reasons.first().detail)
    }

    @Test fun uncertainReorderReasonsAreNotGoodNews() {
        assertTrue(ScheduleNoResultsText.isUncertain("Offering history unknown"))
        assertTrue(ScheduleNoResultsText.isUncertain("Couldn’t check the catalog"))
        assertFalse(ScheduleNoResultsText.isUncertain("Offered every spring"))
    }

    @Test fun blockedTimesReadAsTheyAreConfigured() {
        var block = BlockedTime(label = "Club", days = DaySet(listOf(2, 4)), start = 18 * 60, end = 20 * 60)
        assertEquals("Tue, Thu 6:00 – 8:00 PM", block.listDetail)
        block = block.copy(strength = PreferenceStrength.PREFERRED)
        assertEquals("Tue, Thu 6:00 – 8:00 PM · If I must", block.listDetail)
        block = block.copy(isEnabled = false)
        assertEquals("Tue, Thu 6:00 – 8:00 PM · Off", block.listDetail)
        assertEquals("Choose the days", BlockedTime(label = "", days = DaySet(0), start = 600, end = 660).listDetail)
        assertEquals("Check the times", BlockedTime(label = "", days = DaySet.MONDAY, start = 700, end = 660).listDetail)
    }

    @Test fun calendarLayoutFitsEverySchedule() {
        val early = GeneratedSchedule.measured(listOf(section("A", "CS", "1", listOf(1), 8.0, 9.0)))
        val late = GeneratedSchedule.measured(listOf(section("B", "CS", "2", listOf(6), 17.0, 20.0)))
        val layout = ScheduleCarouselLayout(listOf(early, late), AppPreferences())
        assertEquals(8..20, layout.hours)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), layout.days)
        assertEquals(listOf(1, 2, 3, 4, 5), ScheduleCarouselLayout(emptyList(), AppPreferences()).days)
    }

    @Test fun autofillSuggestionsComeFromTheDegreePlanAndOtherSchedules() {
        val index = clashingIndex()
        val target = plan(emptyList())
        val sibling = plan(listOf("CS 3000", "ENGW 1111")).copy(name = "Plan B")
        val suggestions = ScheduleAutofillSupport.suggestions(target, emptyList(), emptyList(), listOf(sibling, target), index)
        assertEquals(1, suggestions.size)
        assertEquals(listOf("CS 3000", "ENGW 1111"), suggestions.first().courses.map { it.description })
        assertEquals("Copy “Plan B”", ScheduleAutofillSupport.sourceName(suggestions[0]))
        assertTrue(ScheduleAutofillSupport.suggestions(target, emptyList(), emptyList(), listOf(target), index).isEmpty())
        assertEquals("1 course", ScheduleAutofillSupport.courseCountLabel(1))
        assertEquals("3 courses", ScheduleAutofillSupport.courseCountLabel(3))
        val key = ScheduleAutofillSupport.dismissalKey(target.id, suggestions[0])
        val stored = ScheduleAutofillSupport.adding(key, "")
        assertTrue(ScheduleAutofillSupport.isDismissed(key, stored))
        assertFalse(ScheduleAutofillSupport.isDismissed("other", stored))
    }

    @Test fun draggedTextNamesCoursesAndSections() {
        val parsed = ScheduleDroppedCourse.parse(listOf("CS 3000", "math 2331 12345\nPHYS 1151 99\nCS 3000", "Dr. Smith", "John Smith"))
        assertEquals(listOf("CS 3000", "MATH 2331", "PHYS 1151"), parsed.map { it.key.description })
        assertEquals(listOf(null, "12345", null), parsed.map { it.crn })
        assertTrue(ScheduleDroppedCourse.parse(emptyList()).isEmpty())
        assertTrue(ScheduleDroppedCourse.parse(listOf("hello")).isEmpty())
    }

    @Test fun clockConvertsMinutesAndPickerDates() {
        for (minutes in listOf(0, 9 * 60 + 15, 23 * 60 + 59)) {
            assertEquals(minutes, ScheduleClock.minutes(ScheduleClock.date(minutes)))
        }
        assertEquals(24 * 60 - 1, ScheduleClock.minutes(ScheduleClock.date(24 * 60)))
        assertEquals(0, ScheduleClock.minutes(ScheduleClock.date(-5)))
    }

    @Test fun removingACourseTakesItsSectionsAndFavoritesAndUndoPutsThemBack() {
        val userData = UserDataStore()
        val index = clashingIndex()
        val created = userData.createPlan("Plan A", "202710")
        for (code in listOf("CS 3000", "MATH 2331", "ENGW 1111")) userData.addCourse(CourseKey.parse(code)!!, created.id)
        userData.updatePlan(created.id) { it.copy(selectedCRNs = listOf("A1", "C1"), favoriteCombinations = listOf(listOf("A1", "C1"), listOf("B1", "C1"))) }
        val current = userData.plan(created.id)!!
        val entry = ResolvedPlan(current, index).entry(CourseKey("CS", "3000"))!!
        val removal = SchedulePlanActions.removeCourse(entry, current, index, userData)
        val after = userData.plan(created.id)!!
        assertEquals(listOf("MATH 2331", "ENGW 1111"), after.courses.map { it.key.description })
        assertEquals(listOf("C1"), after.selectedCRNs)
        assertEquals(listOf(listOf("B1", "C1")), after.favoriteCombinations)
        assertEquals(0, removal.index)
        assertEquals(listOf("A1"), removal.selectedCRNs)
        userData.updatePlan(created.id) { PlanEditing.restore(removal, it) }
        val restored = userData.plan(created.id)!!
        assertEquals(listOf("CS 3000", "MATH 2331", "ENGW 1111"), restored.courses.map { it.key.description })
        assertEquals(setOf("A1", "C1"), restored.selectedCRNs.toSet())
        assertTrue(restored.favoriteCombinations.contains(listOf("A1", "C1")))
    }

    @Test fun autofillAddsRequiredCoursesOnceAndUndoTakesThemOut() {
        val userData = UserDataStore()
        val created = userData.createPlan("Plan A", "202710")
        userData.addCourse(CourseKey("CS", "3000"), created.id)
        val suggestion = ScheduleAutofillSuggestion(
            ScheduleAutofillSuggestion.Source.Schedule("other"),
            "Plan B", null, "Copy “Plan B”", "", false,
            listOf(CourseKey("CS", "3000"), CourseKey("ENGW", "1111")), emptyList(),
        )
        val added = SchedulePlanActions.addAutofill(suggestion, created.id, userData)
        assertEquals(listOf("ENGW 1111"), added.map { it.description })
        assertEquals(2, userData.plan(created.id)?.courses?.size)
        assertEquals(true, userData.plan(created.id)?.courses?.first { it.key == CourseKey("ENGW", "1111") }?.isRequired)
        SchedulePlanActions.undoAutofill(added, created.id, clashingIndex(), userData)
        assertEquals(listOf("CS 3000"), userData.plan(created.id)?.courses?.map { it.key.description })
    }

    @Test fun reorderingTheDegreePlanNeedsASemesterThatMatchesTheTerm() {
        val schedule = plan(listOf("CS 3000"))
        val degreePlan = DegreePlan(
            name = "Plan",
            terms = listOf(DegreePlanTerm(season = PlanSeason.FALL, year = 2026, courses = listOf(DegreePlanCourse.of(CourseKey("CS", "3000"), "Algorithms", 4.0)))),
        )
        val terms = listOf(Term("202710", "Fall 2026 Semester"))
        val found = ScheduleReorderSuggestions.semester(schedule, degreePlan, terms, null)
        assertEquals("202710", found?.second?.code)
        assertEquals(degreePlan.id, found?.first?.id)
        assertNull(ScheduleReorderSuggestions.semester(schedule.copy(termCode = "202730"), degreePlan, terms, null))
        assertNull(ScheduleReorderSuggestions.semester(schedule, null, terms, null))
    }

    @Test fun degreePlanStandingTexts() {
        val completed = CourseDegreePlanStanding("plan", CourseDegreePlanStanding.Kind.Completed(null))
        assertEquals("Completed", completed.text)
        assertEquals("checkmark.circle.fill", completed.symbol)
        assertEquals("Completed, in your degree plan", completed.spokenText)
        val via = CourseDegreePlanStanding("plan", CourseDegreePlanStanding.Kind.Completed(CourseKey("CS", "2510")))
        assertEquals("Completed via CS 2510", via.text)
        val planned = CourseDegreePlanStanding("plan", CourseDegreePlanStanding.Kind.Planned("Spring 2027", null))
        assertEquals("Planned · Spring 2027", planned.text)
        assertEquals("calendar", planned.symbol)
        val plannedVia = CourseDegreePlanStanding("plan", CourseDegreePlanStanding.Kind.Planned("Spring 2027", CourseKey("CS", "2510")))
        assertEquals("Planned via CS 2510 · Spring 2027", plannedVia.text)
        assertEquals("Planned via CS 2510 for Spring 2027 in your degree plan", plannedVia.spokenText)
    }
}

private fun course(subject: String, number: String, credits: Double = 4.0) =
    Course(subject, number, "$subject $number", minCredits = credits, maxCredits = credits, detailsLoaded = true)

private fun section(crn: String, subject: String, number: String, days: List<Int>, start: Double, end: Double, seats: Int = 10, waitlist: Int = 0) =
    CourseSection(
        crn, subject, number, title = "$subject $number", sectionNumber = "01", seatCapacity = 40, seatsRemaining = seats,
        enrollment = 40 - seats, waitlistCapacity = waitlist, waitlistRemaining = waitlist, campus = "Boston",
        meetings = listOf(Meeting(DaySet(days), (start * 60).toInt(), (end * 60).toInt(), buildingCode = "WVH", building = "West Village H", room = "210")),
    )

private fun index(courses: List<Course>, sections: List<CourseSection>) = CatalogIndex.build(
    TermCatalog(term = Term("202710", "Fall 2026 Semester"), courses = courses, sections = sections),
)

private fun plan(codes: List<String>, selected: List<String> = emptyList()): SchedulePlan {
    val courses = codes.mapIndexedNotNull { offset, code -> CourseKey.parse(code)?.let { app.termorganize.logic.degree.PlanCourse(it, colorIndex = offset) } }
    return SchedulePlan(name = "Plan A", termCode = "202710", courses = courses, selectedCRNs = selected)
}

private fun clashingIndex() = index(
    listOf(course("CS", "3000"), course("MATH", "2331"), course("ENGW", "1111")),
    listOf(
        section("A1", "CS", "3000", listOf(1, 3), 10.0, 11.5),
        section("B1", "MATH", "2331", listOf(1), 11.0, 12.0, seats = 0, waitlist = 5),
        section("C1", "ENGW", "1111", listOf(2, 4), 13.0, 14.5),
    ),
)
