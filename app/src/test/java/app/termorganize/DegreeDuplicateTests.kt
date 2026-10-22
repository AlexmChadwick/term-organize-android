// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import app.termorganize.core.model.AppJson
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.NUPath
import app.termorganize.core.model.Requisite
import app.termorganize.core.model.Subject
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.logic.degree.CatalogProgram
import app.termorganize.logic.degree.CatalogYear
import app.termorganize.logic.degree.CourseOption
import app.termorganize.logic.degree.CourseRange
import app.termorganize.logic.degree.DegreeAudit
import app.termorganize.logic.degree.DegreeCourseAttributes
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanAnalysis
import app.termorganize.logic.degree.DegreePlanAutofill
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanEditing
import app.termorganize.logic.degree.DegreePlanReordering
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.degree.PrerequisiteLookup
import app.termorganize.logic.degree.ProgramRequirements
import app.termorganize.logic.degree.RequirementGroup
import app.termorganize.logic.degree.RequirementRule
import app.termorganize.logic.degree.RequirementSection
import app.termorganize.logic.degree.RequirementSubstitution
import app.termorganize.logic.degree.SubstitutionChange
import app.termorganize.logic.degree.UserDataStore
import app.termorganize.logic.offering.OfferingHistory
import app.termorganize.logic.schedule.SchedulePlanActions
import app.termorganize.logic.search.CatalogIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DegreeDuplicateTests {
    private val key = CourseKey("CS", "4973")
    private val header = "1 course appears more than once in this plan. Each copy still counts."
    private val adding = "Already in Fall 2026. Adding it again keeps both copies."

    @Test fun twoUnpinnedCopiesAreAddedWarnedAndBothCounted() {
        var plan = DegreePlan(
            name = "Topics",
            terms = listOf(DegreePlanTerm(id = "fall", season = PlanSeason.FALL, year = 2026)),
        )
        val first = copy("row-a")
        val second = copy("row-b")
        assertTrue(plan.admits(first))
        plan = DegreePlanEditing.add(first, "fall", plan)
        assertTrue(plan.admits(second))
        plan = DegreePlanEditing.add(second, "fall", plan)
        assertEquals(listOf("row-a", "row-b"), plan.terms.single().courses.map { it.id })
        assertTrue(plan.terms.single().courses.all { it.crn == null })
        assertEquals(header, DegreePlanAnalysis.duplicateHeader(DegreePlanAnalysis.duplicateKeys(plan).size))
        assertEquals(adding, DegreePlanAnalysis.addingAgainNotice(plan, key))
        assertEquals(setOf("row-a", "row-b"), DegreePlanAnalysis.duplicatedRowIds(plan))
        assertEquals(setOf(key), DegreePlanAnalysis.duplicateKeys(plan))

        val audit = DegreeAudit.run(plan, electives(), attributes = DegreeCourseAttributes(nupaths = mapOf(key to setOf(NUPath.WI))))
        assertEquals(8.0, audit.plannedHours, 0.0)
        val used = audit.result("electives")!!.used
        assertEquals(setOf("row-a", "row-b"), used.map { it.rowId }.toSet())
        assertEquals(listOf("CS 4973", "CS 4973 (2)"), used.map { it.id })
        assertEquals(DegreeAudit.Status.PLANNED, audit.result("electives")!!.status)
        val writing = audit.noteChecks.flatMap { it.items }.first { it.title.contains("Writing Intensive") }
        assertEquals(setOf("CS 4973", "CS 4973 (2)"), writing.courses.toSet())

        val later = CourseKey("CS", "5100")
        val catalog = CatalogIndex.build(TermCatalog(
            term = Term("202710", "Fall 2026 Semester"),
            courses = listOf(
                Course("CS", "4973", "Special Topics", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true),
                Course("CS", "5100", "Advanced", minCredits = 4.0, maxCredits = 4.0, prereqs = Requisite.CourseReq(key), detailsLoaded = true),
            ),
            sections = emptyList(),
        ))
        val withLater = plan.copy(terms = plan.terms + DegreePlanTerm(
            id = "spring",
            season = PlanSeason.SPRING,
            year = 2027,
            courses = listOf(DegreePlanCourse.of(later, "Advanced", 4.0, id = "later")),
        ))
        assertEquals(DegreePlanAnalysis.PrerequisiteStatus.Met, DegreePlanAnalysis.prerequisiteStatus(later, 1, withLater, catalog))
        assertEquals(listOf("row-a", "row-b"), withLater.terms[0].courses.map { it.id })
    }

    @Test fun eachCopyIsSubstitutedIndependently() {
        val first = copy("row-a")
        val second = copy("row-b")
        val plan = DegreePlan(
            name = "Topics",
            terms = listOf(DegreePlanTerm(id = "fall", season = PlanSeason.FALL, year = 2026, courses = listOf(first, second))),
            substitutions = listOf(
                RequirementSubstitution(id = "sub-a", ruleID = "cs4500", ruleTitle = "CS 4500", change = SubstitutionChange.CountCourse(key, "row-a")),
                RequirementSubstitution(id = "sub-b", ruleID = "cs4530", ruleTitle = "CS 4530", change = SubstitutionChange.CountCourse(key, "row-b")),
            ),
        )
        val audit = DegreeAudit.run(plan, twoRequirements())
        assertTrue(audit.unmatchedSubstitutions.isEmpty())
        assertEquals("row-a", audit.result("cs4500")!!.used.single().rowId)
        assertEquals("row-b", audit.result("cs4530")!!.used.single().rowId)
        assertTrue(audit.result("cs4500")!!.used.single().bySubstitution)
        assertTrue(audit.result("cs4530")!!.used.single().bySubstitution)
        assertEquals(DegreeAudit.Status.PLANNED, audit.result("cs4500")!!.status)
        assertEquals(DegreeAudit.Status.PLANNED, audit.result("cs4530")!!.status)

        val swapped = plan.copy(substitutions = listOf(
            RequirementSubstitution(id = "sub-a", ruleID = "cs4500", ruleTitle = "CS 4500", change = SubstitutionChange.CountCourse(key, "row-b")),
            RequirementSubstitution(id = "sub-b", ruleID = "cs4530", ruleTitle = "CS 4530", change = SubstitutionChange.CountCourse(key, "row-a")),
        ))
        val other = DegreeAudit.run(swapped, twoRequirements())
        assertEquals("row-b", other.result("cs4500")!!.used.single().rowId)
        assertEquals("row-a", other.result("cs4530")!!.used.single().rowId)

        val kept = DegreePlanEditing.removePlannedCourse("row-a", plan)
        assertEquals(listOf("row-b"), kept.terms.single().courses.map { it.id })
        val after = DegreeAudit.run(kept, twoRequirements())
        assertEquals(listOf("sub-a"), after.unmatchedSubstitutions.map { it.id })
        assertTrue(after.result("cs4500")!!.used.isEmpty())
        assertEquals(DegreeAudit.Status.MISSING, after.result("cs4500")!!.status)
        assertEquals("row-b", after.result("cs4530")!!.used.single().rowId)
        assertEquals(DegreeAudit.Status.PLANNED, after.result("cs4530")!!.status)
        assertEquals(4.0, after.plannedHours, 0.0)

        val encoded = AppJson.user.encodeToString(SubstitutionChange.serializer(), SubstitutionChange.CountCourse(key, "row-a"))
        val decoded = AppJson.user.decodeFromString(SubstitutionChange.serializer(), encoded)
        assertEquals("row-a", (decoded as SubstitutionChange.CountCourse).rowId)
        val legacy = AppJson.user.decodeFromString(
            SubstitutionChange.serializer(),
            encoded.replace(Regex(""",\s*"rowId"\s*:\s*"[^"]*""""), ""),
        )
        assertNull((legacy as SubstitutionChange.CountCourse).rowId)
    }

    @Test fun syncRoundTripKeepsBothRowsAndDoesNotInventACrn() {
        val first = copy("row-a")
        val second = copy("row-b", crn = "11111")
        val semester = DegreePlanTerm(id = "fall", season = PlanSeason.FALL, year = 2026, courses = listOf(first, second))
        val store = UserDataStore()
        val created = store.scheduleForDegreePlan(semester, "202710")!!
        assertEquals(listOf(null, "11111"), created.plan.courses.map { it.crn })
        assertEquals(listOf("11111"), created.plan.selectedCRNs)
        assertFalse(store.addCourse(key, created.plan.id))
        assertFalse(store.addCourse(key, created.plan.id, crn = "11111"))
        assertEquals(2, store.plan(created.plan.id)!!.courses.size)
        val again = store.scheduleForDegreePlan(semester, "202710")!!
        assertFalse(again.isNew)
        assertEquals(created.plan.id, again.plan.id)
        assertEquals(1, store.plans.size)

        val catalog = CatalogIndex.build(TermCatalog(
            term = Term("202710", "Fall 2026 Semester"),
            courses = listOf(Course("CS", "4973", "Special Topics", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true)),
            sections = listOf(CourseSection("99999", "CS", "4973", title = "Other topic", seatsRemaining = 8, seatCapacity = 20)),
            subjects = listOf(Subject("CS", "Computer Science")),
        ))
        val back = DegreePlanAutofill.newDegreePlanDraft(
            "Back",
            null,
            listOf(created.plan.copy(isPrimary = true)),
            { code -> if (code == "202710") catalog else null },
        )
        assertEquals(listOf(key, key), back.terms.single().courses.map { it.key })
        assertEquals(listOf(null, "11111"), back.terms.single().courses.map { it.crn })

        val unpinned = DegreePlanTerm(
            id = "fall",
            season = PlanSeason.FALL,
            year = 2026,
            courses = listOf(copy("row-a"), copy("row-b")),
        )
        val plain = UserDataStore()
        val scheduled = plain.scheduleForDegreePlan(unpinned, "202710")!!
        assertEquals(listOf(null, null), scheduled.plan.courses.map { it.crn })
        assertTrue(scheduled.plan.selectedCRNs.isEmpty())
        assertFalse(scheduled.plan.admitsCourse(key, null))
        val term = Term("202710", "Fall 2026 Semester")
        val suggestion = DegreePlanAutofill.scheduleSuggestions(
            term,
            listOf(DegreePlan(name = "Topics", isPrimary = true, terms = listOf(unpinned))),
            emptyList(),
        ).single()
        assertEquals(listOf(key, key), suggestion.courses)
        assertEquals(listOf(null, null), suggestion.courseCRNs)
        val empty = plain.createPlan("Empty", "202730")
        val added = SchedulePlanActions.addAutofill(suggestion, empty.id, plain)
        assertEquals(listOf(key, key), added)
        assertEquals(listOf(null, null), plain.plan(empty.id)!!.courses.map { it.crn })
    }

    @Test fun removingOneCopyKeepsTheOther() {
        val first = copy("row-a")
        val second = copy("row-b")
        val plan = DegreePlan(
            name = "Topics",
            completed = listOf(first),
            terms = listOf(DegreePlanTerm(id = "fall", season = PlanSeason.FALL, year = 2026, courses = listOf(second))),
        )
        assertEquals(setOf("row-a", "row-b"), DegreePlanAnalysis.duplicatedRowIds(plan))
        val droppedCompleted = plan.copy(completed = plan.completed.filter { it.id != first.id })
        assertEquals(listOf("row-b"), droppedCompleted.terms.single().courses.map { it.id })
        assertTrue(DegreePlanAnalysis.duplicatedRowIds(droppedCompleted).isEmpty())
        assertEquals(4.0, DegreeAudit.run(droppedCompleted, electives()).plannedHours, 0.0)

        val bothPlanned = DegreePlan(
            name = "Topics",
            terms = listOf(
                DegreePlanTerm(id = "fall", season = PlanSeason.FALL, year = 2026, courses = listOf(first, second)),
                DegreePlanTerm(id = "spring", season = PlanSeason.SPRING, year = 2027),
            ),
        )
        val moved = DegreePlanEditing.move(second.id, "spring", 0, bothPlanned)
        assertEquals(listOf("row-a"), moved.terms.first { it.id == "fall" }.courses.map { it.id })
        assertEquals(listOf("row-b"), moved.terms.first { it.id == "spring" }.courses.map { it.id })
        val removed = DegreePlanEditing.removePlannedCourse(first.id, moved)
        assertTrue(removed.terms.first { it.id == "fall" }.courses.isEmpty())
        assertEquals(listOf("row-b"), removed.terms.first { it.id == "spring" }.courses.map { it.id })
        assertEquals(4.0, DegreeAudit.run(removed, electives()).plannedHours, 0.0)
        assertEquals(setOf("row-b"), DegreeAudit.run(removed, electives()).result("electives")!!.used.map { it.rowId }.toSet())
    }

    @Test fun duplicateWarningsCoverEveryPairing() {
        val first = copy("row-a")
        val second = copy("row-b")
        val completed = DegreePlan(name = "Done", completed = listOf(first, second))
        val mixed = DegreePlan(
            name = "Mixed",
            completed = listOf(first),
            terms = listOf(DegreePlanTerm(season = PlanSeason.FALL, year = 2026, courses = listOf(second))),
        )
        val planned = DegreePlan(
            name = "Planned",
            terms = listOf(
                DegreePlanTerm(id = "fall", season = PlanSeason.FALL, year = 2026, courses = listOf(first)),
                DegreePlanTerm(id = "spring", season = PlanSeason.SPRING, year = 2027, courses = listOf(second)),
            ),
        )
        val once = DegreePlan(name = "Once", completed = listOf(first))
        assertEquals(setOf("row-a", "row-b"), DegreePlanAnalysis.duplicatedRowIds(completed))
        assertEquals(setOf("row-a", "row-b"), DegreePlanAnalysis.duplicatedRowIds(mixed))
        assertEquals(setOf("row-a", "row-b"), DegreePlanAnalysis.duplicatedRowIds(planned))
        assertTrue(DegreePlanAnalysis.duplicatedRowIds(once).isEmpty())
        assertEquals(header, DegreePlanAnalysis.duplicateHeader(1))
        assertEquals(
            "2 courses appear more than once in this plan. Each copy still counts.",
            DegreePlanAnalysis.duplicateHeader(2),
        )
        assertEquals("Already in Completed. Adding it again keeps both copies.", DegreePlanAnalysis.addingAgainNotice(completed, key))
        assertEquals(adding, DegreePlanAnalysis.addingAgainNotice(mixed, key))
        assertEquals(8.0, DegreeAudit.run(completed, electives()).completedHours, 0.0)
        assertEquals(listOf("CS 4973", "CS 4973 (2)"), DegreeAudit.run(completed, electives()).result("electives")!!.used.map { it.id })
        assertEquals(4.0, DegreeAudit.run(mixed, electives()).completedHours, 0.0)
        assertEquals(4.0, DegreeAudit.run(mixed, electives()).plannedHours, 0.0)
        assertEquals(8.0, DegreeAudit.run(planned, electives()).plannedHours, 0.0)
    }

    @Test fun topicTitlesAndDifferentCrnsStayQuietWhileSameCrnWarns() {
        val topics = DegreePlan(
            name = "Topics",
            terms = listOf(
                DegreePlanTerm(
                    id = "fall",
                    season = PlanSeason.FALL,
                    year = 2026,
                    courses = listOf(
                        copy("row-a", title = "Topics in Computer Science"),
                        copy("row-b", title = "Topics in Computer Science"),
                    ),
                ),
            ),
        )
        assertTrue(DegreePlanAnalysis.duplicateKeys(topics).isEmpty())
        assertTrue(DegreePlanAnalysis.duplicatedRowIds(topics).isEmpty())
        assertEquals(adding, DegreePlanAnalysis.addingAgainNotice(topics, key))
        val topicAudit = DegreeAudit.run(topics, electives())
        assertEquals(8.0, topicAudit.plannedHours, 0.0)
        assertEquals(listOf("CS 4973", "CS 4973 (2)"), topicAudit.result("electives")!!.used.map { it.id })

        val distinct = DegreePlan(
            name = "Sections",
            terms = listOf(
                DegreePlanTerm(
                    id = "fall",
                    season = PlanSeason.FALL,
                    year = 2026,
                    courses = listOf(copy("row-a", crn = "11111"), copy("row-b", crn = "33333")),
                ),
            ),
        )
        assertTrue(DegreePlanAnalysis.duplicateKeys(distinct).isEmpty())
        val distinctAudit = DegreeAudit.run(distinct, electives())
        assertEquals(8.0, distinctAudit.plannedHours, 0.0)
        assertEquals(listOf("CS 4973", "CS 4973 (2)"), distinctAudit.result("electives")!!.used.map { it.id })

        val same = DegreePlan(
            name = "Same",
            terms = listOf(
                DegreePlanTerm(
                    id = "fall",
                    season = PlanSeason.FALL,
                    year = 2026,
                    courses = listOf(copy("row-a", crn = "11111"), copy("row-b", crn = "11111")),
                ),
            ),
        )
        assertEquals(setOf(key), DegreePlanAnalysis.duplicateKeys(same))
        assertEquals(setOf("row-a", "row-b"), DegreePlanAnalysis.duplicatedRowIds(same))
        val sameAudit = DegreeAudit.run(same, electives())
        assertEquals(8.0, sameAudit.plannedHours, 0.0)
        assertEquals(listOf("CS 4973", "CS 4973 (2)"), sameAudit.result("electives")!!.used.map { it.id })

        val marketing = CourseKey("MKTG", "2602")
        val twoNumbers = DegreePlan(
            name = "Both",
            completed = listOf(copy("row-a"), copy("row-b")),
            terms = listOf(
                DegreePlanTerm(
                    season = PlanSeason.FALL,
                    year = 2026,
                    courses = listOf(
                        DegreePlanCourse.of(marketing, "Marketing Management", 4.0, id = "mkt-a"),
                        DegreePlanCourse.of(marketing, "Marketing Management", 4.0, id = "mkt-b"),
                    ),
                ),
            ),
        )
        assertEquals(setOf(key, marketing), DegreePlanAnalysis.duplicateKeys(twoNumbers))
        assertEquals(
            "2 courses appear more than once in this plan. Each copy still counts.",
            DegreePlanAnalysis.duplicateHeader(DegreePlanAnalysis.duplicateKeys(twoNumbers).size),
        )
    }

    @Test fun reorderingMovesOnlyTheChosenUnpinnedRow() {
        val first = copy("row-a")
        val second = copy("row-b")
        val plan = DegreePlan(
            name = "Topics",
            terms = listOf(
                DegreePlanTerm(id = "fall", season = PlanSeason.FALL, year = 2026, courses = listOf(first, second)),
                DegreePlanTerm(id = "spring", season = PlanSeason.SPRING, year = 2027),
            ),
        )
        val history = OfferingHistory(
            checkedTerms = listOf("202710", "202630"),
            offered = mapOf(key to setOf("202710", "202630")),
        )
        val found = DegreePlanReordering.suggestions(
            plan,
            "fall",
            listOf(key),
            history,
            prerequisites = { PrerequisiteLookup.None },
        )
        val move = found.flatMap { it.moves }.first { it.courseId == second.id }
        assertEquals("fall", move.from)
        assertEquals("spring", move.to)
        assertNull(move.crn)
        val updated = DegreePlanReordering.apply(
            DegreePlanReordering.Suggestion(move.course.description, listOf(move), "Move", emptyList()),
            plan,
        )
        assertEquals(listOf("row-a"), updated.terms.first { it.id == "fall" }.courses.map { it.id })
        assertEquals(listOf("row-b"), updated.terms.first { it.id == "spring" }.courses.map { it.id })
    }

    private fun copy(id: String, crn: String? = null, title: String = "Seminar") =
        DegreePlanCourse.of(key, title, 4.0, id = id, crn = crn)

    private fun electives() = ProgramRequirements(
        CatalogYear(2024),
        CatalogProgram("Computer Science, BS", "undergraduate/cs/"),
        listOf(
            RequirementSection(
                "major",
                "Major",
                false,
                listOf(
                    RequirementGroup(
                        "electives",
                        "Electives",
                        listOf(
                            RequirementRule(
                                "electives",
                                RequirementRule.Kind.Choose(
                                    hours = 8.0,
                                    ranges = listOf(CourseRange("CS", 4000, 4999, emptyList(), "CS courses 4000-4999")),
                                ),
                                "Complete 8 semester hours of CS coursework",
                                hours = 8.0,
                            ),
                        ),
                    ),
                ),
                listOf("Complete the NUpath requirements"),
            ),
        ),
        totalHours = 8.0,
    )

    private fun twoRequirements(): ProgramRequirements {
        fun required(id: String, code: String) = RequirementRule(
            id,
            RequirementRule.Kind.Required(CourseOption(listOf(listOf(CourseKey.parse(code)!!)))),
            code,
        )
        return ProgramRequirements(
            CatalogYear(2024),
            CatalogProgram("Computer Science, BS", "undergraduate/cs/"),
            listOf(
                RequirementSection(
                    "major",
                    "Major",
                    false,
                    listOf(RequirementGroup("core", "Core", listOf(required("cs4500", "CS 4500"), required("cs4530", "CS 4530")))),
                ),
            ),
        )
    }
}
