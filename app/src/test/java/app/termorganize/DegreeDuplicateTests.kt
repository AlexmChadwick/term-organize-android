// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanEditing
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.DegreeSectionChoice
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.search.CatalogIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DegreeDuplicateTests {
    private val key = CourseKey("CS", "4973")

    @Test fun aSecondRowPinsEveryUnpinnedCopyAndKeepsTheCrnsDistinct() {
        val existing = DegreePlanCourse.of(key, "Seminar", 4.0, id = "old")
        val plan = DegreePlan(
            name = "Plan",
            terms = listOf(
                DegreePlanTerm(id = "fall", season = PlanSeason.FALL, year = 2026, courses = listOf(existing)),
                DegreePlanTerm(id = "spring", season = PlanSeason.SPRING, year = 2027),
            ),
        )
        val blocked = DegreePlanEditing.addDuplicate(
            plan,
            "fall",
            DegreePlanCourse.of(key, "Other topic", 4.0, id = "new", crn = "22222"),
            emptyMap(),
        )
        assertEquals(listOf("old"), blocked.terms[0].courses.map { it.id })
        val added = DegreePlanEditing.addDuplicate(
            plan,
            "fall",
            DegreePlanCourse.of(key, "Other topic", 4.0, id = "new", crn = "22222"),
            mapOf("old" to "11111"),
            mapOf("old" to "Machine Learning"),
        )
        val fall = added.terms.first { it.id == "fall" }
        assertEquals(listOf("11111", "22222"), fall.courses.map { it.crn })
        assertEquals(listOf("Machine Learning", "Other topic"), fall.courses.map { it.title })
        val repeated = DegreePlanEditing.addDuplicate(
            added,
            "fall",
            DegreePlanCourse.of(key, "Third", 4.0, id = "third", crn = "22222"),
            emptyMap(),
        )
        assertEquals(2, repeated.terms.first { it.id == "fall" }.courses.size)
        val third = DegreePlanEditing.addDuplicate(
            added,
            "spring",
            DegreePlanCourse.of(key, "Networks", 4.0, id = "third", crn = "33333"),
            emptyMap(),
        )
        assertEquals(listOf("11111", "22222"), third.terms.first { it.id == "fall" }.courses.map { it.crn })
        assertEquals(listOf("33333"), third.terms.first { it.id == "spring" }.courses.map { it.crn })
    }

    @Test fun changingMovingOrRemovingOneRowLeavesItsSibling() {
        val first = DegreePlanCourse.of(key, "Machine Learning", 4.0, id = "old", crn = "11111")
        val second = DegreePlanCourse.of(key, "Other topic", 4.0, id = "new", crn = "22222")
        val plan = DegreePlan(
            name = "Plan",
            terms = listOf(
                DegreePlanTerm(id = "fall", season = PlanSeason.FALL, year = 2026, courses = listOf(first, second)),
                DegreePlanTerm(id = "spring", season = PlanSeason.SPRING, year = 2027),
            ),
        )
        val changed = DegreePlanEditing.setSection(plan, "new", "44444", "Networks")
        assertEquals(listOf("11111", "44444"), changed.terms[0].courses.map { it.crn })
        assertEquals("Networks", changed.terms[0].courses[1].title)
        assertEquals(listOf("11111", "22222"), DegreePlanEditing.setSection(plan, "new", "11111").terms[0].courses.map { it.crn })
        val open = plan.copy(terms = plan.terms.map { term ->
            if (term.id != "fall") term else term.copy(courses = term.courses.map { it.copy(crn = null) })
        })
        assertEquals(open.terms[0].courses.map { it.crn }, DegreePlanEditing.setSection(open, "new", "44444").terms[0].courses.map { it.crn })
        val pinned = DegreePlanEditing.pinRows(open, mapOf("old" to "11111", "new" to "22222"))
        assertEquals(listOf("11111", "22222"), pinned.terms[0].courses.map { it.crn })
        val moved = DegreePlanEditing.move("new", "spring", Int.MAX_VALUE, plan)
        assertEquals(listOf("11111"), moved.terms.first { it.id == "fall" }.courses.map { it.crn })
        assertEquals(listOf("22222"), moved.terms.first { it.id == "spring" }.courses.map { it.crn })
        val removed = DegreePlanEditing.removePlannedCourse("new", plan)
        assertEquals(listOf("11111"), removed.terms.first { it.id == "fall" }.courses.map { it.crn })
        assertTrue(removed.terms.first { it.id == "spring" }.courses.isEmpty())
    }

    @Test fun aTermWithNoCatalogSectionsSaysSo() {
        assertEquals(
            "Pick a section to add this course twice. No sections are listed for Spring 2027 yet.",
            DegreeSectionChoice.message("Spring 2027"),
        )
        val section = CourseSection(crn = "11111", subject = "CS", number = "4973", title = "Machine Learning")
        val index = CatalogIndex.build(TermCatalog(
            term = Term("202710", "Fall 2026 Semester"),
            courses = listOf(Course("CS", "4973", "Special Topics", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true)),
            sections = listOf(section),
        ))
        val fall = DegreePlanTerm(season = PlanSeason.FALL, year = 2026)
        val spring = DegreePlanTerm(season = PlanSeason.SPRING, year = 2027)
        assertEquals(listOf("11111"), DegreeSectionChoice.offered(fall, key, index).map { it.crn })
        assertTrue(DegreeSectionChoice.offered(spring, key, index).isEmpty())
        assertTrue(DegreeSectionChoice.offered(fall, key, null).isEmpty())
    }
}
