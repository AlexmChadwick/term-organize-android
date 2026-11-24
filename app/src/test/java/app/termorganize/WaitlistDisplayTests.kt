package app.termorganize

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import app.termorganize.core.model.AppJson
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Meeting
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.logic.degree.PlanCourse
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.degree.WatchItem
import app.termorganize.logic.schedule.ScheduleCourseInput
import app.termorganize.logic.schedule.ScheduleFilters
import app.termorganize.logic.schedule.ScheduleGenerator
import app.termorganize.logic.schedule.inputs
import app.termorganize.logic.search.CatalogIndex
import app.termorganize.logic.watchlist.WatchlistComparison
import app.termorganize.logic.watchlist.newWatchItem
import app.termorganize.logic.schedule.ScheduleEngine
import app.termorganize.logic.schedule.ScheduleSortOption
import app.termorganize.logic.search.SearchEngine
import app.termorganize.logic.search.SearchFilters
import app.termorganize.logic.search.SearchQuery
import app.termorganize.logic.search.SearchSort
import app.termorganize.ui.SectionSeatsText
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WaitlistDisplayTests {
    @get:Rule
    val rule = createComposeRule()

    private fun section(seats: Int, cap: Int = 0, count: Int = 0) = CourseSection(
        "39766", "INAM", "1925", seatCapacity = 15, seatsRemaining = seats,
        waitlistCapacity = cap, waitlistCount = count,
    )

    @Test fun noWaitlist() {
        val s = section(seats = 0)
        assertNull(s.waitlistLabel)
        assertEquals("Full", s.availabilityLabel)
        assertFalse(s.isOpen)
        assertTrue(section(seats = 3).isOpen)
    }

    @Test fun waitlistWithNoSeats() {
        val s = section(seats = 0, cap = 10, count = 7)
        assertEquals("Waitlist 7/10", s.waitlistLabel)
        assertEquals("Full", s.availabilityLabel)
        assertFalse(s.isOpen)
    }

    @Test fun waitlistQueueWithSeatsIsNotOpen() {
        val s = section(seats = 2, cap = 10, count = 3)
        assertEquals("Waitlist 3/10", s.waitlistLabel)
        assertEquals("Waitlist", s.availabilityLabel)
        assertFalse(s.isOpen)
        assertEquals(0, s.openSeats)
    }

    @Test fun emptyWaitlistWithSeatsIsOpen() {
        val s = section(seats = 2, cap = 10, count = 0)
        assertEquals("Waitlist 0/10", s.waitlistLabel)
        assertEquals("Open", s.availabilityLabel)
        assertTrue(s.isOpen)
        assertEquals(2, s.openSeats)
    }

    @Test fun cachedSectionWithoutWaitFieldsDecodesAsZero() {
        val s = AppJson.catalog.decodeFromString(
            CourseSection.serializer(),
            """{"crn":"1","subject":"CS","number":"1000","seatCapacity":10,"seatsRemaining":4}""",
        )
        assertEquals(0, s.waitlistCapacity)
        assertEquals(0, s.waitlistCount)
        assertTrue(s.isOpen)
    }

    @Test fun waitlistWithoutCapacityIsNotOpenAndShowsTheCount() {
        val s = section(seats = 2, cap = 0, count = 4)
        assertEquals("Waitlist 4", s.waitlistLabel)
        assertFalse(s.isOpen)
        assertTrue(s.isFull)
    }

    @Test fun isFullIsNotOpen() {
        assertTrue(section(seats = 2, cap = 10, count = 3).isFull)
        assertTrue(section(seats = 0).isFull)
        assertFalse(section(seats = 2).isFull)
    }

    @Test fun seatsRowShowsTheWaitlistOnce() {
        rule.setContent { SectionSeatsText(section(seats = 0, cap = 10, count = 7)) }
        rule.onNodeWithText("Full · Waitlist 7/10").assertExists()
    }

    @Test fun seatsRowForQueuedSectionWithSeatsHasNoDuplicateWord() {
        rule.setContent { SectionSeatsText(section(seats = 2, cap = 10, count = 3)) }
        rule.onNodeWithText("Full · Waitlist 3/10").assertExists()
    }

    @Test fun seatsRowForOpenSection() {
        rule.setContent { SectionSeatsText(section(seats = 2, cap = 10, count = 0)) }
        rule.onNodeWithText("2 of 15 open · Waitlist 0/10").assertExists()
    }

    @Test fun seatsRowIsSpokenAsOneMergedDescriptionWithTheWaitlistNumbers() {
        rule.setContent { SectionSeatsText(section(seats = 0, cap = 10, count = 3)) }
        rule.onNodeWithContentDescription("Full, 0 of 15 seats open, 3 of 10 on the waitlist").assertExists()
    }

    @Test fun spokenLabelForQueuedSectionWithSeatsAndOpenSection() {
        assertEquals("Waitlist, 0 of 15 seats open, 3 of 10 on the waitlist", section(seats = 2, cap = 10, count = 3).seatsSpokenLabel)
        assertEquals("Open, 2 of 15 seats open, 0 of 10 on the waitlist", section(seats = 2, cap = 10, count = 0).seatsSpokenLabel)
        assertEquals("Full, 0 of 15 seats open", section(seats = 0).seatsSpokenLabel)
    }

    @Test fun seatsLabelMatchesTheWebCopy() {
        assertEquals("Full", section(seats = 0).seatsLabel)
        assertEquals("Full", section(seats = 2, cap = 10, count = 3).seatsLabel)
        assertEquals("2 of 15 open", section(seats = 2).seatsLabel)
        assertEquals("2 open", CourseSection("1", "CS", "1000", seatCapacity = 0, seatsRemaining = 2).seatsLabel)
    }

    @Test fun overbookedSectionIsFullWithZeroOpenSeats() {
        val s = section(seats = -2)
        assertEquals(0, s.openSeats)
        assertFalse(s.isOpen)
    }

    @Test fun openSeatsOnlyFilterRejectsQueuedSectionWithSeats() {
        val filters = SearchFilters(openSeatsOnly = true)
        assertFalse(filters.matches(section(seats = 5, cap = 10, count = 3)))
        assertFalse(filters.matches(section(seats = 0)))
        assertTrue(filters.matches(section(seats = 5)))
    }

    @Test fun openSeatsSortIgnoresSeatsBehindAQueue() {
        val queued = gsec("1", seats = 30, count = 3, start = 9)
        val open = gsec("2", seats = 4, count = 0, start = 11)
        fun course(number: String) = Course("CS", number, "CS $number", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true)
        val index = CatalogIndex.build(
            TermCatalog(
                term = Term("202710", "Fall 2026 Semester"),
                courses = listOf(course("3000"), course("3100")),
                sections = listOf(queued, open.copy(number = "3100")),
            ),
        )
        val codes = SearchEngine.search(SearchQuery("", sort = SearchSort.OPEN_SEATS), index).results.map { it.course.code }
        assertEquals(listOf("CS 3100", "CS 3000"), codes)
    }

    @Test fun scheduleMeasureCountsOnlyOpenSeats() {
        val g = ScheduleEngine.measure(listOf(gsec("1", seats = 30, count = 3, start = 9), gsec("2", seats = 4, count = 0, start = 11)))
        assertEquals(0, g.minSeats)
        assertEquals(4, g.totalSeats)
        assertEquals(1, g.fullSections)
    }

    @Test fun generatorRanksByOpenSeatsNotRawSeats() {
        val index = catalog(gsec("1", seats = 30, count = 3, start = 9), gsec("2", seats = 4, count = 0, start = 11))
        val plan = SchedulePlan(name = "P", termCode = "202710", courses = listOf(PlanCourse(CourseKey("CS", "3000"), colorIndex = 0)))
        val schedules = ScheduleGenerator.generate(ScheduleCourseInput.inputs(plan, index), ScheduleFilters(), sort = ScheduleSortOption.MOST_SEATS).schedules
        assertEquals(listOf("2", "1"), schedules.map { it.crns.single() })
    }

    @Test fun watchSeedsTheOpenSeatCountSoAQueueIsNotRecordedAsOpen() {
        val item = newWatchItem(section(seats = 2, cap = 10, count = 3), "Title")
        assertEquals(0, item.lastSeatsRemaining)
    }

    @Test fun watchSeedsOpenSeatsWhenNoQueue() {
        val item = newWatchItem(section(seats = 2, cap = 10, count = 0), "Title")
        assertEquals(2, item.lastSeatsRemaining)
    }

    private fun watch(seats: Int) = WatchItem(
        "202730", "39766", CourseKey("INAM", "1925"), "T", "Lecture 01",
        lastSeatsRemaining = seats, lastWaitlistRemaining = 0, seatCapacity = 15,
    )

    private fun live(s: CourseSection) = mapOf("202730" to mapOf(CourseKey("INAM", "1925") to listOf(s)))

    @Test fun noSeatAlertBehindAQueue() {
        val out = WatchlistComparison.compare(listOf(watch(0)), live(section(seats = 2, cap = 10, count = 3)), 1L)
        assertTrue(out.alerts.isEmpty())
        assertEquals(0, out.items[0].lastSeatsRemaining)
    }

    @Test fun seatAlertWhenQueueClears() {
        val out = WatchlistComparison.compare(listOf(watch(0)), live(section(seats = 2, cap = 10, count = 0)), 1L)
        assertEquals(1, out.alerts.size)
        assertEquals(2, out.alerts[0].seatsRemaining)
    }

    private fun gsec(crn: String, seats: Int, count: Int, start: Int) = CourseSection(
        crn, "CS", "3000", title = "CS 3000", sectionNumber = "01", seatCapacity = 40, seatsRemaining = seats,
        waitlistCapacity = 10, waitlistCount = count, campus = "Boston",
        meetings = listOf(Meeting(DaySet(listOf(1)), start * 60, (start + 1) * 60)),
    )

    private fun catalog(vararg sections: CourseSection) = CatalogIndex.build(
        TermCatalog(
            term = Term("202710", "Fall 2026 Semester"),
            courses = listOf(Course("CS", "3000", "CS 3000", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true)),
            sections = sections.toList(),
        ),
    )

    private fun crns(index: CatalogIndex, filters: ScheduleFilters): List<String> {
        val plan = SchedulePlan(name = "P", termCode = "202710", courses = listOf(PlanCourse(CourseKey("CS", "3000"), colorIndex = 0)))
        return ScheduleGenerator.generate(ScheduleCourseInput.inputs(plan, index), filters).schedules.flatMap { it.crns }.sorted()
    }

    @Test fun generatorHideFullSkipsQueuedSectionWithSeats() {
        val index = catalog(gsec("1", seats = 5, count = 3, start = 9), gsec("2", seats = 5, count = 0, start = 11))
        assertEquals(listOf("1", "2"), crns(index, ScheduleFilters()))
        assertEquals(listOf("2"), crns(index, ScheduleFilters(hideFullSections = true)))
    }

    @Test fun generatorMinSeatsIgnoresSeatsBehindAQueue() {
        val index = catalog(gsec("1", seats = 5, count = 3, start = 9), gsec("2", seats = 5, count = 0, start = 11))
        assertEquals(listOf("2"), crns(index, ScheduleFilters(minSeats = 1)))
    }

    @Test fun courseStatsOpenCountAndSeatsSkipQueuedSections() {
        val index = catalog(gsec("1", seats = 5, count = 3, start = 9), gsec("2", seats = 4, count = 0, start = 11))
        val stats = index.stats(CourseKey("CS", "3000"))
        assertEquals(1, stats.openSectionCount)
        assertEquals(4, stats.seatsRemaining)
        assertEquals("1 of 2 sections open", stats.availabilityLabel)
    }
}
