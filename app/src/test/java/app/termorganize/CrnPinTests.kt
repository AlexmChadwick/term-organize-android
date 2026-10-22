// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import app.termorganize.core.model.AppJson
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Meeting
import app.termorganize.core.model.NUPath
import app.termorganize.core.model.Requisite
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.logic.degree.CatalogProgram
import app.termorganize.logic.degree.CatalogYear
import app.termorganize.logic.degree.DegreeAudit
import app.termorganize.logic.degree.DegreeCourseAttributes
import app.termorganize.logic.degree.DegreeCourseRepeats
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanAnalysis
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanEditing
import app.termorganize.logic.degree.DegreePlanReordering
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.PlanCourse
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.degree.PrerequisiteLookup
import app.termorganize.logic.degree.ProgramRequirements
import app.termorganize.logic.degree.RequirementSection
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.degree.UserDataStore
import app.termorganize.logic.offering.OfferingHistory
import app.termorganize.logic.schedule.PlanEditing
import app.termorganize.logic.schedule.ResolvedPlan
import app.termorganize.logic.schedule.ScheduleCourseInput
import app.termorganize.logic.schedule.ScheduleFilters
import app.termorganize.logic.schedule.ScheduleNoResultsText
import app.termorganize.logic.schedule.ScheduleGenerator
import app.termorganize.logic.schedule.SchedulePlanActions
import app.termorganize.logic.schedule.inputs
import app.termorganize.logic.search.CatalogIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chosen CRN stays chosen, and the same subject and number may appear twice only when each
 * entry has its own manual CRN.
 */
class CrnPinTests {
    @Test fun generationKeepsThePinnedSectionWhenAnotherFitsBetter() {
        val pin = "11111"
        val better = "22222"
        val math = "33333"
        val catalog = index(
            listOf(course("CS", "3000"), course("MATH", "2331")),
            listOf(
                section(pin, "CS", "3000", listOf(1), 10.0, 11.5, seats = 2),
                section(better, "CS", "3000", listOf(2), 10.0, 11.5, seats = 40),
                section(math, "MATH", "2331", listOf(3), 10.0, 11.5),
            ),
        )
        val schedule = pinnedPlan(listOf("CS 3000" to pin, "MATH 2331" to null))
        val first = schedulesOf(schedule, catalog)
        val second = schedulesOf(schedule, catalog)
        assertTrue(first.isNotEmpty())
        assertTrue(first.all { pin in it && better !in it && math in it })
        assertEquals(first, second)
        assertEquals(pin, schedule.courses.first().crn)
    }

    @Test fun anUnpinnedCourseStillVariesWhileThePinDoesNot() {
        val pin = "11111"
        val monday = "33333"
        val wednesday = "44444"
        val catalog = index(
            listOf(course("CS", "3000"), course("MATH", "2331")),
            listOf(
                section(pin, "CS", "3000", listOf(2), 10.0, 11.5),
                section(monday, "MATH", "2331", listOf(1), 10.0, 11.5, seats = 5),
                section(wednesday, "MATH", "2331", listOf(3), 10.0, 11.5, seats = 30),
            ),
        )
        val found = schedulesOf(pinnedPlan(listOf("CS 3000" to pin, "MATH 2331" to null)), catalog)
        assertTrue(found.all { pin in it })
        assertEquals(setOf(monday, wednesday), found.map { it.first { crn -> crn != pin } }.toSet())
    }

    @Test fun aConflictingPinIsNotReplacedByAnotherSectionOfTheSameNumber() {
        val pin = "11111"
        val alternate = "22222"
        val math = "33333"
        val catalog = index(
            listOf(course("CS", "3000"), course("MATH", "2331")),
            listOf(
                section(pin, "CS", "3000", listOf(1), 10.0, 11.5),
                section(alternate, "CS", "3000", listOf(2), 10.0, 11.5),
                section(math, "MATH", "2331", listOf(1), 10.0, 12.0),
            ),
        )
        val schedule = pinnedPlan(listOf("CS 3000" to pin, "MATH 2331" to null))
        val found = schedulesOf(schedule, catalog)
        assertTrue(found.isEmpty())
        assertTrue(found.none { alternate in it })
        assertEquals(pin, schedule.courses.first().crn)
    }

    @Test fun twoPinnedSectionsOfTheSameNumberStaySeparate() {
        val first = "11111"
        val second = "33333"
        val math = "44444"
        val catalog = index(
            listOf(course("CS", "4973"), course("MATH", "2331")),
            listOf(
                section(first, "CS", "4973", listOf(1), 10.0, 11.5),
                section(second, "CS", "4973", listOf(3), 10.0, 11.5),
                section("99999", "CS", "4973", listOf(5), 10.0, 11.5, seats = 40),
                section(math, "MATH", "2331", listOf(2), 13.0, 14.5),
            ),
        )
        val schedule = pinnedPlan(listOf("CS 4973" to first, "CS 4973" to second, "MATH 2331" to null))
        val inputs = ScheduleCourseInput.inputs(schedule, catalog)
        assertEquals(3, inputs.size)
        assertEquals(listOf(listOf(first), listOf(second)), inputs.take(2).map { it.sections.map { section -> section.crn } })
        assertTrue(inputs.take(2).all { it.locked })
        val found = schedulesOf(schedule, catalog)
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { first in it && second in it && "99999" !in it })
        val resolved = ResolvedPlan(schedule.copy(selectedCRNs = listOf(first, second, math)), catalog)
        assertEquals(12.0, resolved.credits.first, 0.0)
        assertEquals(listOf(listOf(first), listOf(second)), resolved.entries.take(2).map { it.selected.map { section -> section.crn } })
    }

    @Test fun overlappingPinsAreNotResolvedBySubstitutingAThirdSection() {
        val first = "11111"
        val second = "33333"
        val spare = "44444"
        val catalog = index(
            listOf(course("CS", "4973")),
            listOf(
                section(first, "CS", "4973", listOf(1), 10.0, 11.5),
                section(second, "CS", "4973", listOf(1), 10.0, 11.5),
                section(spare, "CS", "4973", listOf(3), 10.0, 11.5),
            ),
        )
        val found = schedulesOf(pinnedPlan(listOf("CS 4973" to first, "CS 4973" to second)), catalog)
        assertTrue(found.isEmpty())
        assertTrue(found.none { spare in it })
    }

    @Test fun aLockedPinSurvivesFiltersThatWouldDropIt() {
        val pin = "11111"
        val open = "22222"
        val catalog = index(
            listOf(course("CS", "3000")),
            listOf(
                section(pin, "CS", "3000", listOf(1), 8.0, 9.0, seats = 0),
                section(open, "CS", "3000", listOf(1), 11.0, 12.0, seats = 20),
            ),
        )
        val filters = ScheduleFilters(earliestStart = 10 * 60, hideFullSections = true)
        val found = ScheduleGenerator.generate(ScheduleCourseInput.inputs(pinnedPlan(listOf("CS 3000" to pin)), catalog), filters).schedules
        assertEquals(listOf(listOf(pin)), found.map { it.crns })
    }

    @Test fun theSameNumberIsAddedTwiceOnlyWithDifferentManualCrns() {
        val store = UserDataStore()
        val plan = store.createPlan("Plan A", "202710")
        val key = CourseKey("CS", "4973")
        assertTrue(store.addCourse(key, plan.id, crn = "11111"))
        assertFalse(store.addCourse(key, plan.id))
        assertFalse(store.addCourse(key, plan.id, crn = "11111"))
        assertTrue(store.addCourse(key, plan.id, crn = "33333"))
        assertFalse(store.addCourse(CourseKey("MATH", "2331"), plan.id, crn = "33333"))
        val saved = store.plan(plan.id)!!
        assertEquals(listOf("11111", "33333"), saved.courses.map { it.crn })
        assertEquals(listOf("11111", "33333"), saved.selectedCRNs)
        store.removeCourse(key, plan.id, listOf("11111", "33333", "99999"), crn = "11111")
        val left = store.plan(plan.id)!!
        assertEquals(listOf("33333"), left.courses.map { it.crn })
        assertEquals(listOf("33333"), left.selectedCRNs)
    }

    @Test fun choosingASectionDoesNotRetargetAnotherPinOfTheSameNumber() {
        val key = CourseKey("CS", "4973")
        val schedule = pinnedPlan(listOf("CS 4973" to "11111", "CS 4973" to "33333"))
        val both = PlanEditing.setSection("99999", setOf("11111", "33333", "99999"), schedule)
        assertEquals(schedule.courses, both.courses)
        assertEquals(schedule.selectedCRNs, both.selectedCRNs)
        val one = PlanEditing.setSection("44444", setOf("11111", "44444"), schedule)
        assertEquals(listOf("44444", "33333"), one.courses.map { it.crn })
        assertTrue("33333" in one.selectedCRNs)
        assertFalse("11111" in one.selectedCRNs)
        val applied = PlanEditing.applyCombination(listOf("99999", "55555"), schedule) { crn ->
            if (crn == "55555") CourseKey("MATH", "2331") else key
        }
        assertEquals(listOf("55555", "11111", "33333"), applied.selectedCRNs)
    }

    @Test fun removingOnePinnedEntryLeavesTheOther() {
        val store = UserDataStore()
        val catalog = index(
            listOf(course("CS", "4973")),
            listOf(
                section("11111", "CS", "4973", listOf(1), 10.0, 11.5),
                section("33333", "CS", "4973", listOf(3), 10.0, 11.5),
            ),
        )
        val created = store.createPlan("Plan A", "202710")
        val key = CourseKey("CS", "4973")
        store.addCourse(key, created.id, crn = "11111")
        store.addCourse(key, created.id, crn = "33333")
        val current = store.plan(created.id)!!
        val entry = ResolvedPlan(current, catalog).entries.first { it.planCourse.crn == "11111" }
        SchedulePlanActions.removeCourse(entry, current, catalog, store)
        val left = store.plan(created.id)!!
        assertEquals(listOf("33333"), left.courses.map { it.crn })
        assertEquals(listOf("33333"), left.selectedCRNs)
    }

    @Test fun differentCrnsOfTheSameNumberCountSeparatelyInTheAudit() {
        val key = CourseKey("CS", "4973")
        val first = degreeCourse(key, "Seminar", "11111")
        val second = degreeCourse(key, "Seminar", "33333")
        val plan = DegreePlan(name = "Topics", terms = listOf(DegreePlanTerm(season = PlanSeason.FALL, year = 2026, courses = listOf(first, second))))
        assertTrue(plan.admits(degreeCourse(key, "Seminar", null)))
        val open = DegreePlan(name = "Open", completed = listOf(degreeCourse(key, "Seminar", null)))
        assertTrue(open.admits(degreeCourse(key, "Seminar", null)))
        assertTrue(plan.admits(degreeCourse(key, "Seminar", "11111")))
        assertTrue(plan.admits(degreeCourse(key, "Other", "11111")))
        assertTrue(plan.admits(degreeCourse(key, "Seminar", "44444")))
        assertFalse(DegreeCourseRepeats.countsSeparately(first, first.copy(id = "other", crn = "11111")))
        assertTrue(DegreeCourseRepeats.countsSeparately(first, second))
        assertTrue(DegreePlanAnalysis.duplicateKeys(plan).isEmpty())
        assertEquals(8.0, DegreeAudit.run(plan, requirements()).plannedHours, 0.0)
        val completed = DegreePlan(name = "Done", completed = listOf(first, second))
        assertEquals(8.0, DegreeAudit.run(completed, requirements()).completedHours, 0.0)
        val sameCrn = DegreePlan(
            name = "Same",
            completed = listOf(first, first.copy(id = "copy", crn = "11111")),
        )
        val sameAudit = DegreeAudit.run(sameCrn, requirements())
        assertEquals(8.0, sameAudit.completedHours, 0.0)
        assertEquals(setOf(key), DegreePlanAnalysis.duplicateKeys(sameCrn))
        assertEquals(listOf("CS 4973", "CS 4973 (2)"), sameAudit.unused.map { it.id })
        val unpinned = DegreePlan(
            name = "Unpinned",
            terms = listOf(
                DegreePlanTerm(
                    season = PlanSeason.FALL,
                    year = 2026,
                    courses = listOf(degreeCourse(key, "Seminar", null), degreeCourse(key, "Seminar", null)),
                ),
            ),
        )
        assertEquals(setOf(key), DegreePlanAnalysis.duplicateKeys(unpinned))
        assertEquals(8.0, DegreeAudit.run(unpinned, requirements()).plannedHours, 0.0)
    }

    @Test fun nupathCountsEachPinnedEntry() {
        val key = CourseKey("CS", "4973")
        val plan = DegreePlan(
            name = "Writing",
            completed = listOf(degreeCourse(key, "Seminar", "11111"), degreeCourse(key, "Seminar", "33333")),
        )
        val audit = DegreeAudit.run(
            plan,
            requirements(notes = listOf("Complete the NUpath requirements")),
            attributes = DegreeCourseAttributes(nupaths = mapOf(key to setOf(NUPath.WI))),
        )
        val writing = audit.noteChecks.flatMap { it.items }.first { it.title.contains("Writing Intensive") }
        assertEquals(setOf("CS 4973", "CS 4973 (2)"), writing.courses.toSet())
        assertEquals(DegreeAudit.Status.COMPLETE, writing.status)
    }

    @Test fun prerequisitesSeeEachEntryAndOneCompletionIsEnoughLater() {
        val topics = CourseKey("CS", "4973")
        val later = CourseKey("CS", "5100")
        val needed = CourseKey("CS", "2500")
        val catalog = index(
            listOf(
                course("CS", "2500"),
                Course("CS", "4973", "Special Topics", minCredits = 4.0, maxCredits = 4.0, prereqs = Requisite.CourseReq(needed), detailsLoaded = true),
                Course("CS", "5100", "Advanced", minCredits = 4.0, maxCredits = 4.0, prereqs = Requisite.CourseReq(topics), detailsLoaded = true),
            ),
            emptyList(),
        )
        val plan = DegreePlan(
            name = "Plan",
            terms = listOf(
                DegreePlanTerm(
                    id = "fall",
                    season = PlanSeason.FALL,
                    year = 2026,
                    courses = listOf(degreeCourse(topics, "Seminar", "11111"), degreeCourse(topics, "Seminar", "33333")),
                ),
                DegreePlanTerm(
                    id = "spring",
                    season = PlanSeason.SPRING,
                    year = 2027,
                    courses = listOf(DegreePlanCourse.of(later, "Advanced", 4.0)),
                ),
            ),
        )
        assertEquals(2, plan.terms[0].courses.size)
        assertEquals(8.0, plan.terms[0].credits, 0.0)
        assertEquals(12.0, plan.plannedCredits, 0.0)
        assertEquals(
            DegreePlanAnalysis.PrerequisiteStatus.Missing::class,
            DegreePlanAnalysis.prerequisiteStatus(topics, 0, plan, catalog)::class,
        )
        assertEquals(DegreePlanAnalysis.PrerequisiteStatus.Met, DegreePlanAnalysis.prerequisiteStatus(later, 1, plan, catalog))
        val added = DegreePlanEditing.add(degreeCourse(topics, "Seminar", null), "fall", plan)
        assertEquals(3, added.terms[0].courses.size)
        assertEquals(listOf("11111", "33333"), added.terms[0].courses.take(2).map { it.crn })
        assertNull(added.terms[0].courses[2].crn)
    }

    @Test fun reorderingMovesThePinnedEntryRatherThanTheFirstCourseOfThatNumber() {
        val key = CourseKey("CS", "4973")
        val plan = DegreePlan(
            name = "Plan",
            terms = listOf(
                DegreePlanTerm(
                    id = "fall",
                    season = PlanSeason.FALL,
                    year = 2026,
                    courses = listOf(degreeCourse(key, "Seminar", "22222"), degreeCourse(key, "Seminar", "11111")),
                ),
                DegreePlanTerm(id = "spring", season = PlanSeason.SPRING, year = 2027),
            ),
        )
        val history = OfferingHistory(
            checkedTerms = listOf("202710", "202630"),
            offered = mapOf(key to setOf("202710", "202630")),
        )
        val found = DegreePlanReordering.suggestions(
            plan, "fall", listOf(key), history,
            prerequisites = { _: CourseKey -> PrerequisiteLookup.None },
        )
        val move = found.flatMap { it.moves }.first { it.crn == "11111" }
        assertEquals(key, move.course)
        assertEquals("fall", move.from)
        assertEquals("spring", move.to)
        val updated = DegreePlanReordering.apply(
            DegreePlanReordering.Suggestion(move.course.description, listOf(move), "Move", emptyList()),
            plan,
        )
        assertEquals(listOf("22222"), updated.terms.first { it.id == "fall" }.courses.map { it.crn })
        assertEquals(listOf("11111"), updated.terms.first { it.id == "spring" }.courses.map { it.crn })
    }

    @Test fun refreshingTheCatalogLeavesThePinnedCrnInPlace() {
        val pin = "11111"
        val better = "22222"
        val key = CourseKey("CS", "3000")
        val original = index(
            listOf(course("CS", "3000"), course("MATH", "2331")),
            listOf(
                section(pin, "CS", "3000", listOf(1), 10.0, 11.5, seats = 2),
                section("33333", "MATH", "2331", listOf(3), 10.0, 11.5),
            ),
        )
        val schedule = pinnedPlan(listOf("CS 3000" to pin, "MATH 2331" to null))
        val refreshed = original.replacingCourse(
            key,
            course("CS", "3000"),
            listOf(
                section(pin, "CS", "3000", listOf(1), 10.0, 11.5, seats = 1),
                section(better, "CS", "3000", listOf(2), 10.0, 11.5, seats = 40),
            ),
        )
        assertEquals(pin, schedule.courses.first { it.key == key }.crn)
        assertEquals(pin, refreshed.section(pin)?.crn)
        val found = schedulesOf(schedule, refreshed)
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { pin in it && better !in it })
    }

    @Test fun aPinnedCrnMissingFromTheCatalogStaysLocked() {
        val pin = "11111"
        val other = "22222"
        val catalog = index(
            listOf(course("CS", "3000")),
            listOf(section(other, "CS", "3000", listOf(1), 10.0, 11.5, seats = 40)),
        )
        val schedule = pinnedPlan(listOf("CS 3000" to pin))
        val inputs = ScheduleCourseInput.inputs(schedule, catalog)
        assertEquals(1, inputs.size)
        assertTrue(inputs[0].locked)
        assertTrue(inputs[0].sections.isEmpty())
        assertEquals(pin, inputs[0].unavailableCRN)
        val result = ScheduleGenerator.generate(inputs, ScheduleFilters())
        assertTrue(result.schedules.isEmpty())
        assertTrue(result.schedules.none { other in it.crns })
        val reasons = ScheduleNoResultsText.reasons(result.diagnostics, "Fall 2026")
        assertEquals(listOf("CRN $pin is unavailable"), reasons.map { it.title })
        assertTrue(result.diagnostics.suggestions.contains("CRN $pin is unavailable"))
    }

    @Test fun aSavedPlanWithoutACrnStillLoads() {
        val key = CourseKey("CS", "4973")
        val plan = pinnedPlan(listOf("CS 4973" to "11111", "CS 4973" to "33333"))
        val decoded = AppJson.user.decodeFromString(SchedulePlan.serializer(), AppJson.user.encodeToString(SchedulePlan.serializer(), plan))
        assertEquals(listOf("11111", "33333"), decoded.courses.map { it.crn })
        val legacy = """{"id":"p","name":"Plan A","termCode":"202710","courses":[{"key":{"subject":"CS","number":"4973"},"isRequired":true,"excludedCRNs":[],"colorIndex":0}]}"""
        val old = AppJson.user.decodeFromString(SchedulePlan.serializer(), legacy)
        assertEquals(listOf(PlanCourse(key)), old.courses.map { it.copy() })
        assertEquals(null, old.courses.first().crn)
    }
}

private fun course(subject: String, number: String, credits: Double = 4.0) =
    Course(subject, number, "$subject $number", minCredits = credits, maxCredits = credits, detailsLoaded = true)

private fun section(
    crn: String,
    subject: String,
    number: String,
    days: List<Int>,
    start: Double,
    end: Double,
    seats: Int = 10,
) = CourseSection(
    crn, subject, number, title = "$subject $number", sectionNumber = "01", seatCapacity = 40, seatsRemaining = seats,
    enrollment = 40 - seats, campus = "Boston",
    meetings = listOf(Meeting(DaySet(days), (start * 60).toInt(), (end * 60).toInt(), buildingCode = "WVH", building = "West Village H", room = "210")),
)

private fun index(courses: List<Course>, sections: List<CourseSection>) = CatalogIndex.build(
    TermCatalog(term = Term("202710", "Fall 2026 Semester"), courses = courses, sections = sections),
)

private fun pinnedPlan(entries: List<Pair<String, String?>>): SchedulePlan {
    val courses = entries.mapIndexed { offset, (code, crn) ->
        PlanCourse(CourseKey.parse(code)!!, colorIndex = offset, crn = crn)
    }
    val selected = courses.mapNotNull { it.crn }
    return SchedulePlan(name = "Plan A", termCode = "202710", courses = courses, selectedCRNs = selected)
}

private fun schedulesOf(plan: SchedulePlan, catalog: CatalogIndex) =
    ScheduleGenerator.generate(ScheduleCourseInput.inputs(plan, catalog), ScheduleFilters()).schedules.map { it.crns }

private fun degreeCourse(key: CourseKey, title: String, crn: String?) =
    DegreePlanCourse.of(key, title, 4.0, crn = crn)

private fun requirements(notes: List<String> = emptyList()) = ProgramRequirements(
    CatalogYear(2024),
    CatalogProgram("Random, BS", "undergraduate/random/"),
    listOf(RequirementSection("major", "Major", false, emptyList(), notes)),
)
