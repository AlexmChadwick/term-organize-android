// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.logic.degree.PlanCourse
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.schedule.ResolvedPlan
import app.termorganize.logic.schedule.ScheduleGenerationResult
import app.termorganize.logic.search.CatalogIndex
import app.termorganize.ui.RouteState
import app.termorganize.ui.ScheduleUiState
import app.termorganize.ui.TermOrganizeTheme
import app.termorganize.ui.UntimedSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h840dp-mdpi")
class ScheduleBackNavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun scheduleWithState(): Pair<RouteState, ScheduleUiState> {
        val nav = RouteState("home")
        nav.navigate("schedule")
        val ui = ScheduleUiState()
        ui.selectedId = "plan-b"
        ui.generated = ScheduleGenerationResult(totalFound = 7)
        return nav to ui
    }

    private fun assertScheduleIntact(nav: RouteState, ui: ScheduleUiState, generated: ScheduleGenerationResult?) {
        assertEquals("schedule", nav.route)
        assertNull(nav.backRoute)
        assertEquals("plan-b", ui.selectedId)
        assertSame(generated, ui.generated)
    }

    @Test fun courseOpenedFromScheduleReturnsToScheduleWithStateIntact() {
        val (nav, ui) = scheduleWithState()
        val generated = ui.generated
        nav.navigate("course/CS/3000")
        assertEquals("course/CS/3000", nav.route)
        nav.closeDetail()
        assertScheduleIntact(nav, ui, generated)
    }

    @Test fun sectionOpenedFromScheduleReturnsToScheduleEvenAfterDetailToDetail() {
        val (nav, ui) = scheduleWithState()
        val generated = ui.generated
        nav.navigate("course/CS/3000")
        nav.navigate("section/12345")
        nav.closeDetail()
        assertScheduleIntact(nav, ui, generated)
    }

    @Test fun onlineTbaRowOpensCourseAndBackReturnsToSchedule() {
        val online = CourseSection("C1", "ENGW", "1111", title = "ENGW 1111", sectionNumber = "01", seatCapacity = 40, seatsRemaining = 10, enrollment = 30, campus = "Online", meetings = emptyList())
        val course = Course("ENGW", "1111", "ENGW 1111", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true)
        val index = CatalogIndex.build(TermCatalog(term = Term("202710", "Fall 2026 Semester"), courses = listOf(course), sections = listOf(online)))
        val plan = SchedulePlan(
            name = "Plan B", termCode = "202710",
            courses = listOfNotNull(CourseKey.parse("ENGW 1111")?.let { PlanCourse(it, colorIndex = 0) }),
            selectedCRNs = listOf("C1"),
        )
        val resolved = ResolvedPlan(plan, index)
        val (nav, ui) = scheduleWithState()
        val generated = ui.generated
        rule.setContent {
            TermOrganizeTheme(dynamic = false, dark = false) { UntimedSections(resolved.untimed, nav::navigate) }
        }
        rule.onNodeWithTag("untimed-row").performClick()
        rule.waitForIdle()
        assertEquals("course/ENGW/1111", nav.route)
        nav.closeDetail()
        assertScheduleIntact(nav, ui, generated)
    }

    @Test fun searchOriginBackStillReturnsToSearch() {
        val nav = RouteState("home")
        nav.navigate("search")
        nav.navigate("course/CS/3000")
        nav.closeDetail()
        assertEquals("search", nav.route)
        nav.navigate("home")
        nav.navigate("section/12345")
        nav.closeDetail()
        assertEquals("search", nav.route)
        assertNull(nav.backRoute)
    }
}
