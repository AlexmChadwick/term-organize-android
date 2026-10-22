// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import app.termorganize.core.banner.BannerSection
import app.termorganize.core.banner.CatalogBuilder
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.core.model.VariableCredits
import app.termorganize.logic.degree.CatalogProgram
import app.termorganize.logic.degree.CatalogYear
import app.termorganize.logic.degree.CourseRange
import app.termorganize.logic.degree.CreditSync
import app.termorganize.logic.degree.DegreeAudit
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.PlanCourse
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.degree.ProgramRequirements
import app.termorganize.logic.degree.RequirementGroup
import app.termorganize.logic.degree.RequirementRule
import app.termorganize.logic.degree.RequirementSection
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.degree.UserData
import app.termorganize.logic.degree.UserDataStore
import app.termorganize.logic.degree.UserJson
import app.termorganize.logic.schedule.ResolvedPlan
import app.termorganize.logic.search.CatalogIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableCreditTests {
    private val readings = CourseKey("CS", "8982")

    @Test fun defaultsUseCreditHoursInsideTheRangeAndLowOutsideIt() {
        assertEquals(4.0, VariableCredits.defaultChosen(4.0, 1.0, 8.0), 0.0)
        assertEquals(4.0, VariableCredits.defaultChosen(4.0, 2.0, 4.0), 0.0)
        assertEquals(6.0, VariableCredits.defaultChosen(4.0, 6.0, 8.0), 0.0)
        assertEquals(8.0, VariableCredits.clamp(9.0, 1.0, 8.0), 0.0)
        assertEquals(1.0, VariableCredits.clamp(0.0, 1.0, 8.0), 0.0)
        assertEquals(4.0, VariableCredits.clamp(3.6, 1.0, 8.0), 0.0)
        assertEquals("Credits: 4 (1 to 8)", VariableCredits.label(4.0, 1.0, 8.0))
        assertFalse(VariableCredits.isVariable(4.0, 4.0))
        assertTrue(VariableCredits.isVariable(1.0, 8.0))
    }

    @Test fun duplicateCopiesKeepDifferentChoicesAndBothCount() {
        val plan = DegreePlan(
            name = "Readings",
            terms = listOf(
                DegreePlanTerm(
                    id = "fall",
                    season = PlanSeason.FALL,
                    year = 2026,
                    courses = listOf(
                        DegreePlanCourse.of(readings, "Readings", 3.0, id = "a", creditLow = 1.0, creditHigh = 8.0),
                        DegreePlanCourse.of(readings, "Readings", 7.0, id = "b", creditLow = 1.0, creditHigh = 8.0),
                    ),
                ),
            ),
        )
        assertEquals(10.0, plan.plannedCredits, 0.0)
        val audit = DegreeAudit.run(plan, readingsRequirement())
        assertEquals(10.0, audit.plannedHours, 0.0)
        assertEquals(listOf("CS 8982", "CS 8982 (2)"), audit.result("readings")!!.used.map { it.id })
    }

    @Test fun scheduleTotalsUseTheChosenValue() {
        val variable = readingsCourse(1.0, 8.0, 4.0)
        val fixed = Course("CS", "3000", "Algorithms", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true)
        val index = catalog(variable, fixed)
        val ordinary = schedule(
            PlanCourse(readings, credits = 8.0, creditLow = 1.0, creditHigh = 8.0),
            PlanCourse(fixed.key),
        )
        val resolved = ResolvedPlan(ordinary, index)
        assertEquals(12.0, resolved.credits.first, 0.0)
        assertEquals(12.0, resolved.credits.second, 0.0)
        assertFalse(resolved.isOverCreditLimit)

        val heavy = schedule(
            PlanCourse(readings, credits = 16.0, creditLow = 1.0, creditHigh = 16.0),
            PlanCourse(fixed.key),
        )
        val over = ResolvedPlan(heavy, catalog(readingsCourse(1.0, 16.0, 4.0), fixed))
        assertEquals(20.0, over.credits.first, 0.0)
        assertTrue(over.isOverCreditLimit)
    }

    @Test fun chosenCreditsSyncBothWaysWithoutMergingRows() {
        val first = DegreePlanCourse.of(readings, "Readings", 3.0, id = "a", creditLow = 1.0, creditHigh = 8.0)
        val second = DegreePlanCourse.of(readings, "Readings", 7.0, id = "b", creditLow = 1.0, creditHigh = 8.0)
        var data = UserData(
            degreePlans = listOf(
                DegreePlan(
                    id = "deg",
                    name = "Plan",
                    isPrimary = true,
                    terms = listOf(DegreePlanTerm(id = "fall", season = PlanSeason.FALL, year = 2026, courses = listOf(first, second))),
                ),
            ),
            plans = listOf(
                SchedulePlan(
                    id = "sched",
                    name = "My Schedule",
                    termCode = "202710",
                    isPrimary = true,
                    courses = listOf(
                        PlanCourse(readings, credits = 3.0, creditLow = 1.0, creditHigh = 8.0),
                        PlanCourse(readings, credits = 7.0, creditLow = 1.0, creditHigh = 8.0),
                    ),
                ),
            ),
        )
        data = CreditSync.setDegreeCredits(data, "deg", "b", 5.0)
        assertEquals(listOf(3.0, 5.0), data.plans.single().courses.map { it.credits })
        assertEquals(listOf(3.0, 5.0), data.degreePlans.single().terms.single().courses.map { it.credits })
        data = CreditSync.setScheduleCredits(data, "sched", 0, 2.0)
        assertEquals(listOf(2.0, 5.0), data.plans.single().courses.map { it.credits })
        assertEquals(listOf(2.0, 5.0), data.degreePlans.single().terms.single().courses.map { it.credits })
        assertEquals(2, data.plans.single().courses.size)

        val store = UserDataStore(data)
        store.scheduleForDegreePlan(data.degreePlans.single().terms.single(), "202710")
        assertEquals(listOf(2.0, 5.0), store.plans.single().courses.map { it.credits })
    }

    @Test fun oldDocumentsLoadAndAMissingChoiceBecomesTheDefault() {
        val decoded = UserJson.decode(
            """
            {"version":1,"plans":[{"name":"My Schedule","termCode":"202710","courses":[{"key":{"subject":"CS","number":"8982"}}]}],
            "degreePlans":[{"name":"Plan","terms":[{"season":"FALL","year":2026,"courses":[{"id":"row","key":{"subject":"CS","number":"8982"},"title":"Readings","credits":4.0}]}]}]}
            """.trimIndent(),
        )
        assertEquals(2, decoded.version)
        assertNull(decoded.plans.single().courses.single().credits)
        val clamped = CreditSync.clampSaved(decoded, TermCatalog(term = Term("202710", "Fall 2026 Semester"), courses = listOf(readingsCourse(1.0, 8.0, 4.0))))
        val schedule = clamped.plans.single().courses.single()
        assertEquals(4.0, schedule.credits!!, 0.0)
        assertEquals(1.0, schedule.creditLow!!, 0.0)
        assertEquals(8.0, schedule.creditHigh!!, 0.0)
        val degree = clamped.degreePlans.single().terms.single().courses.single()
        assertEquals(4.0, degree.credits, 0.0)
        assertEquals(1.0, degree.creditLow!!, 0.0)
        assertEquals(8.0, degree.creditHigh!!, 0.0)
    }

    @Test fun refreshClampsIntoTheNewRangeAndKeepsAnInRangeChoice() {
        val saved = UserData(
            plans = listOf(
                SchedulePlan(
                    name = "My Schedule",
                    termCode = "202710",
                    courses = listOf(PlanCourse(readings, credits = 7.0, creditLow = 1.0, creditHigh = 8.0)),
                ),
                SchedulePlan(
                    name = "Other",
                    termCode = "202630",
                    courses = listOf(PlanCourse(readings, credits = 7.0, creditLow = 1.0, creditHigh = 8.0)),
                ),
            ),
            degreePlans = listOf(
                DegreePlan(
                    name = "Plan",
                    terms = listOf(
                        DegreePlanTerm(
                            season = PlanSeason.FALL,
                            year = 2026,
                            courses = listOf(
                                DegreePlanCourse.of(readings, "Readings", 3.0, id = "kept", creditLow = 1.0, creditHigh = 8.0),
                                DegreePlanCourse.of(readings, "Readings", 7.0, id = "narrowed", creditLow = 1.0, creditHigh = 8.0),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val narrowed = CreditSync.clampSaved(saved, TermCatalog(term = Term("202710", "Fall 2026 Semester"), courses = listOf(readingsCourse(2.0, 6.0, 4.0))))
        assertEquals(6.0, narrowed.plans.first().courses.single().credits!!, 0.0)
        assertEquals(7.0, narrowed.plans[1].courses.single().credits!!, 0.0)
        val rows = narrowed.degreePlans.single().terms.single().courses
        assertEquals(3.0, rows.first { it.id == "kept" }.credits, 0.0)
        assertEquals(6.0, rows.first { it.id == "narrowed" }.credits, 0.0)
        assertEquals(2.0, rows.first().creditLow!!, 0.0)
        assertEquals(6.0, rows.first().creditHigh!!, 0.0)
    }

    @Test fun aFixedCourseDoesNotGainARange() {
        val fixed = Course("CS", "3000", "Algorithms", minCredits = 4.0, maxCredits = 4.0, creditHours = 4.0, detailsLoaded = true)
        val data = UserData(
            plans = listOf(
                SchedulePlan(
                    name = "My Schedule",
                    termCode = "202710",
                    courses = listOf(
                        PlanCourse(fixed.key),
                        PlanCourse(readings, credits = 5.0, creditLow = 1.0, creditHigh = 8.0),
                    ),
                ),
            ),
        )
        val catalog = TermCatalog(
            term = Term("202710", "Fall 2026 Semester"),
            courses = listOf(fixed, readingsCourse(4.0, 4.0, 4.0).copy(subject = "CS", number = "8982")),
        )
        val clamped = CreditSync.clampSaved(data, catalog)
        val courses = clamped.plans.single().courses
        assertNull(courses[0].credits)
        assertNull(courses[0].creditLow)
        assertFalse(courses[0].isVariableCredit)
        assertEquals(4.0, courses[1].credits!!, 0.0)
        assertNull(courses[1].creditLow)
        assertFalse(courses[1].isVariableCredit)
    }

    @Test fun sectionScrapeKeepsLowHighAndTheDefaultHours() {
        val variable = BannerSection(
            courseReferenceNumber = "41001",
            subject = "CS",
            courseNumber = "8982",
            courseTitle = "Readings",
            creditHours = 4.0,
            creditHourLow = 1.0,
            creditHourHigh = 8.0,
        )
        val alsoFixed = variable.copy(courseReferenceNumber = "41002", creditHourLow = 4.0, creditHourHigh = null, creditHours = 4.0)
        val (courses, _) = CatalogBuilder.stubCourses(listOf(variable, alsoFixed))
        assertEquals(1.0, courses.single().minCredits, 0.0)
        assertEquals(8.0, courses.single().maxCredits, 0.0)
        assertEquals(4.0, courses.single().creditHours!!, 0.0)
        val merged = CatalogBuilder.merge(
            null,
            listOf(variable, alsoFixed),
            Term("202710", "Fall 2026 Semester"),
            now = 1.0,
        )
        assertEquals(1.0, merged.courses.single().minCredits, 0.0)
        assertEquals(8.0, merged.courses.single().maxCredits, 0.0)
        assertEquals(4.0, merged.courses.single().creditHours!!, 0.0)
        assertEquals(8.0, merged.sections.first { it.crn == "41001" }.creditHourHigh!!, 0.0)
        assertNull(merged.sections.first { it.crn == "41002" }.creditHourHigh)
    }

    private fun readingsCourse(low: Double, high: Double, hours: Double) =
        Course("CS", "8982", "Readings", minCredits = low, maxCredits = high, creditHours = hours, detailsLoaded = true)

    private fun catalog(vararg courses: Course) = CatalogIndex.build(
        TermCatalog(term = Term("202710", "Fall 2026 Semester"), courses = courses.toList()),
    )

    private fun schedule(vararg courses: PlanCourse) =
        SchedulePlan(name = "My Schedule", termCode = "202710", courses = courses.toList())

    private fun readingsRequirement() = ProgramRequirements(
        CatalogYear(2024),
        CatalogProgram("Computer Science, BS", "undergraduate/cs/"),
        listOf(
            RequirementSection(
                "major",
                "Major",
                false,
                listOf(
                    RequirementGroup(
                        "readings",
                        "Readings",
                        listOf(
                            RequirementRule(
                                "readings",
                                RequirementRule.Kind.Choose(
                                    hours = 10.0,
                                    ranges = listOf(CourseRange("CS", 8000, 8999, emptyList(), "CS courses 8000-8999")),
                                ),
                                "Complete 10 semester hours",
                                hours = 10.0,
                            ),
                        ),
                    ),
                ),
            ),
        ),
        totalHours = 10.0,
    )
}
