// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Meeting
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.logic.degree.PlanCourse
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.schedule.ResolvedPlan
import app.termorganize.logic.search.CatalogIndex
import app.termorganize.ui.TermOrganizeTheme
import app.termorganize.ui.UntimedSections
import app.termorganize.ui.WeekCalendar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-mdpi")
class ScheduleGridA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun meeting(days: DaySet, start: Int, end: Int) = Meeting(days, start, end, buildingCode = "WVH", building = "West Village H", room = "210")

    private fun section(crn: String, subject: String, number: String, meetings: List<Meeting>) = CourseSection(
        crn, subject, number, title = "$subject $number", sectionNumber = "01", seatCapacity = 40, seatsRemaining = 10,
        enrollment = 30, campus = "Boston", meetings = meetings,
    )

    @Test fun gridAndUntimedListSurviveNarrowWidthAndLargeFont() {
        val a = section("A1", "CS", "3000", listOf(meeting(DaySet(listOf(1)), 600, 660)))
        val b = section("B1", "MATH", "2331", listOf(meeting(DaySet(listOf(1)), 630, 690)))
        val online = section("C1", "ENGW", "1111", emptyList())
        val courses = listOf("CS 3000", "MATH 2331", "ENGW 1111").map { Course(it.substringBefore(' '), it.substringAfter(' '), it, minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true) }
        val index = CatalogIndex.build(TermCatalog(term = Term("202710", "Fall 2026 Semester"), courses = courses, sections = listOf(a, b, online)))
        val plan = SchedulePlan(
            name = "Plan A", termCode = "202710",
            courses = listOf("CS 3000", "MATH 2331", "ENGW 1111").mapIndexedNotNull { i, code -> CourseKey.parse(code)?.let { PlanCourse(it, colorIndex = i) } },
            selectedCRNs = listOf("A1", "B1", "C1"),
        )
        val resolved = ResolvedPlan(plan, index)
        val opened = ArrayList<String>()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2.0f)) {
                TermOrganizeTheme(dynamic = false, dark = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        WeekCalendar(resolved.events, false, 8, 21)
                        UntimedSections(resolved.untimed) { opened += it }
                    }
                }
            }
        }
        rule.onNodeWithText("Online / TBA").assertIsDisplayed()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)).assertIsDisplayed()
        rule.onNode(hasContentDescription("CS 3000", substring = true) and hasContentDescription("conflicts with another class", substring = true)).assertExists()
        rule.onNode(hasContentDescription("MATH 2331", substring = true) and hasContentDescription("conflicts with another class", substring = true)).assertExists()
        assertEquals(2, rule.onAllNodesWithTag("conflict-marker", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("untimed-row").performClick()
        assertEquals(listOf("course/ENGW/1111"), opened)
    }
}
