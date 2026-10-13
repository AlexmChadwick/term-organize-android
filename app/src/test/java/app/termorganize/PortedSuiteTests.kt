// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import app.termorganize.core.banner.BannerCodeDescription
import app.termorganize.core.banner.BannerParsers
import app.termorganize.core.model.AppJson
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseEquivalences
import app.termorganize.core.model.CourseInfo
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.CourseTitleMatcher
import app.termorganize.core.model.EquivalenceSuggester
import app.termorganize.core.model.EquivalenceSuggestion
import app.termorganize.core.model.NUPath
import app.termorganize.core.model.PrerequisiteListing
import app.termorganize.core.model.Requisite
import app.termorganize.core.model.Subject
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.logic.degree.CourseEquivalence
import app.termorganize.logic.degree.DegreeAudit
import app.termorganize.logic.degree.DegreeListEntry
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanAnalysis
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanCourseChoice
import app.termorganize.logic.degree.DegreePlanEditing
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.DegreeRequirementsListing
import app.termorganize.logic.degree.DegreeSubstitutionEditing
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.degree.RequirementSubstitution
import app.termorganize.logic.degree.RequisiteAnswer
import app.termorganize.logic.degree.SubstitutionChange
import app.termorganize.logic.degree.UserDataStore
import app.termorganize.logic.degree.decodeLenient
import app.termorganize.logic.offering.AttributeFetching
import app.termorganize.logic.offering.CourseAttributeRepository
import app.termorganize.logic.offering.CourseInfoFetching
import app.termorganize.logic.offering.CourseInfoRepository
import app.termorganize.logic.offering.CourseInfoStore
import app.termorganize.logic.offering.OfferingFetching
import app.termorganize.logic.offering.OfferingHistoryRepository
import app.termorganize.logic.offering.fromCrossListed
import app.termorganize.logic.professors.ProfessorPage
import app.termorganize.logic.rooms.LegalText
import app.termorganize.logic.rooms.RoomAvailability
import app.termorganize.logic.rooms.RoomsText
import app.termorganize.logic.rooms.SettingsFormat
import app.termorganize.logic.search.BuildingSummary
import app.termorganize.logic.search.CatalogIndex
import app.termorganize.logic.search.HomeQuickSearch
import app.termorganize.logic.search.RoomSchedule
import app.termorganize.logic.search.SearchDayPreset
import app.termorganize.logic.search.SearchEngine
import app.termorganize.logic.search.SearchExampleCache
import app.termorganize.logic.search.SearchFilterTitles
import app.termorganize.logic.search.SearchQuery
import app.termorganize.logic.search.SearchTimeOfDay
import app.termorganize.logic.search.browseLevelNoun
import app.termorganize.logic.search.browseLevelTitle
import app.termorganize.logic.search.browseSummaryText
import app.termorganize.logic.search.popularSubjects
import app.termorganize.logic.watchlist.WatchlistFormat
import app.termorganize.logic.watchlist.WatchlistStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

class CourseCatalogParserTests {
    private val subjects = BannerParsers.SubjectLookup(listOf(
        Subject("CS", "Computer Science"),
        Subject("ENGL", "English"),
        Subject("MATH", "Mathematics"),
    ))

    @Test fun typeaheadCodesMustMatchExactly() {
        val json = """[{"code":"CS3386","description":"CS3386 Computer Science"},{"code":"CS33860","description":"CS33860 Computer Science"}]"""
        val items = Json.decodeFromString<List<BannerCodeDescription>>(json)
        assertTrue(BannerParsers.catalogCodes(items, key("CS 3386")))
        assertFalse(BannerParsers.catalogCodes(items, key("CS 338")))
        assertFalse(BannerParsers.catalogCodes(emptyList(), key("CS 3386")))
    }

    @Test fun readsCatalogDetailsAndCredits() {
        val html = """
        <section aria-labelledby="catalog">
        <span class="status-bold">Title:</span><span>Directed Study</span><br/>
        <span class="status-bold">College:</span>
        <span>Coll of Soc Sci &amp; Humanities  SH</span>
        <br/>
        <span class="status-bold">Department:</span>
        <span>Sociology and Anthropology  SOCL</span><br/>
        <span class="status-bold">Hours:</span><br/>
        <span class="indent-left">Credit Hours:</span><span
        class="credit-hours-direction">1 TO 4</span><br/>
        <div class="indent-left"><span class="credit-hours-direction">Lecture: 1 TO 4</span><br/></div>
        <span class="status-bold">Levels:</span><br/>
        </section>
        """
        val details = BannerParsers.catalogDetails(html)
        assertEquals("Directed Study", details.title)
        assertEquals("Sociology and Anthropology", details.department)
        assertEquals(1.0, details.minCredits!!, 0.0)
        assertEquals(4.0, details.maxCredits!!, 0.0)
        val fixed = BannerParsers.catalogDetails("""<span class="indent-left">Credit Hours:</span><span class="credit-hours-direction">4</span><br/>""")
        assertEquals(4.0, fixed.minCredits!!, 0.0)
        assertEquals(4.0, fixed.maxCredits!!, 0.0)
        assertEquals(4.0, BannerParsers.creditRange("1.000 TO 4.000")!!.second, 0.0)
        assertEquals(0.5, BannerParsers.creditRange("0.5")!!.first, 0.0)
        assertNull(BannerParsers.creditRange("TBA"))
    }

    @Test fun readsCoursePrerequisites() {
        fun row(andOr: String = "", open: Boolean = false, subject: String = "", number: String = "", close: Boolean = false) =
            "<tr><td>$andOr</td><td>${if (open) "(" else ""}</td><td></td><td></td><td>$subject</td><td>$number</td><td>Undergraduate</td><td>D-</td><td>${if (close) ")" else ""}</td></tr>"
        val html = "<section><table class=\"basePreqTable\"><thead><tr><th>And/Or</th></tr></thead><tbody>" + listOf(
            row(open = true),
            row(open = true, subject = "Computer Science", number = "2100"),
            row("Or", subject = "Computer Science - MMC", number = "201M", close = true),
            row("Or", subject = "Computer Science", number = "2510", close = true),
            row("And", open = true, subject = "Computer Science", number = "2386"),
            row("Or", subject = "Computer Science", number = "3540", close = true),
        ).joinToString("") + "</tbody></table></section>"
        val parsed = BannerParsers.prerequisites(html, subjects)!!
        assertEquals(listOf("CS 2100", "CS 201M", "CS 2510", "CS 2386", "CS 3540"), parsed.referencedCourses.map { it.description })
        assertTrue(parsed.isSatisfied(setOf(key("CS 2510"), key("CS 3540"))))
        assertFalse(parsed.isSatisfied(setOf(key("CS 2510"))))
        val empty = "<section aria-labelledby=\"preReqs\"><h3>Catalog Prerequisites</h3> No prerequisite information available.</section>"
        assertNull(BannerParsers.prerequisites(empty, subjects))
    }

    @Test fun readsMutualExclusions() {
        val none = """<section aria-labelledby="mexc">No Mutual Exclusion information available.</section>"""
        assertTrue(BannerParsers.mutualExclusions(none, subjects).isEmpty())
        val table = """
        <section aria-labelledby="mexc"><table><thead><tr><th>Subject</th><th>Course Number</th><th>Title</th></tr></thead><tbody>
        <tr><td>Computer Science</td><td>2510</td><td>Fundamentals of Computer Science 2</td></tr>
        <tr><td>Mathematics</td><td>1341</td><td>Calculus 1</td></tr>
        </tbody></table></section>
        """
        assertEquals(listOf(key("CS 2510"), key("MATH 1341")), BannerParsers.mutualExclusions(table, subjects))
        val leading = "<section><table><tbody><tr><td>Or</td><td>Computer Science</td><td>2500</td></tr></tbody></table></section>"
        assertEquals(listOf(key("CS 2500")), BannerParsers.mutualExclusions(leading, subjects))
        val text = "<section>Credit cannot be earned for both CS 2510 and CS 2100.</section>"
        assertEquals(listOf(key("CS 2510"), key("CS 2100")), BannerParsers.mutualExclusions(text, subjects))
    }

    @Test fun readsCrossListedSections() {
        val html = """
        <section aria-labelledby="xlstSections">
            <span class="status-bold">Section Enrollment:</span> 4<br/>
            Sections:<br/>
        <table>
            <thead><tr><th>CRN</th><th>Subject</th><th>Course Number</th><th>Title</th><th>Section</th></tr></thead>
            <tbody>
                <tr><td>22773</td><td>English</td><td>2690</td><td>Boston in Literature</td><td>01</td></tr>
            </tbody>
        </table>
        </section>
        """
        val sections = BannerParsers.crossListedSections(html, subjects)
        assertEquals(1, sections.size)
        assertEquals("22773", sections[0].crn)
        assertEquals(key("ENGL 2690"), sections[0].course)
        assertEquals("Boston in Literature", sections[0].title)
        assertEquals("01", sections[0].section)
        assertTrue(BannerParsers.crossListedSections("<section>No cross list sections</section>", subjects).isEmpty())
    }

    @Test fun courseInfoNormalizesEquivalents() {
        val info = CourseInfo(key("CS 2100"), "PDI 1", 4.0, 4.0, equivalents = listOf(key("CS 2510"), key("CS 2100"), key("CS 2510"), key("CS 2000")), sourceTerm = "202710")
        assertEquals(listOf(key("CS 2000"), key("CS 2510")), info.equivalents)
        assertEquals("4 credits", info.creditsLabel)
        assertEquals("1–4 credits", CourseInfo(key("CS 4973"), "Topics", 1.0, 4.0, sourceTerm = "202710").creditsLabel)
        assertNull(CourseInfo(key("CS 4973"), "Topics", sourceTerm = "202710").creditsLabel)
    }
}

class CourseEquivalencesTests {
    private val cs2000 = key("CS 2000")
    private val cs2100 = key("CS 2100")
    private val cs2500 = key("CS 2500")
    private val cs2510 = key("CS 2510")
    private val cs3100 = key("CS 3100")
    private val cs3500 = key("CS 3500")
    private val engl1102 = key("ENGL 1102")
    private val engw1102 = key("ENGW 1102")

    @Test fun groupsAreSymmetric() {
        val equivalences = CourseEquivalences.fromPairs(listOf(cs2100 to cs2510, engw1102 to engl1102))
        assertEquals(setOf(cs2510), equivalences.equivalents(cs2100))
        assertEquals(setOf(cs2100), equivalences.equivalents(cs2510))
        assertTrue(equivalences.areEquivalent(engl1102, engw1102))
        assertTrue(equivalences.areEquivalent(cs2100, cs2100))
        assertFalse(equivalences.areEquivalent(cs2100, engl1102))
        assertFalse(cs2100 in equivalences.equivalents(cs2100))
        assertTrue(equivalences.equivalents(cs3100).isEmpty())
        assertEquals(listOf(cs3100), equivalences.group(cs3100))
        assertTrue(CourseEquivalences.none.isEmpty)
        val encoded = CourseEquivalences.fromPairs(listOf(cs2100 to cs2510, cs3100 to cs3500))
        assertEquals(encoded, CourseEquivalences.fromJson(encoded.toJson()))
    }

    @Test fun chainsAreOneGroup() {
        val forward = CourseEquivalences.fromPairs(listOf(cs2500 to cs2000, cs2000 to cs2100))
        val backward = CourseEquivalences.fromPairs(listOf(cs2100 to cs2000, cs2000 to cs2500))
        assertEquals(forward.groups, backward.groups)
        assertEquals(1, forward.groups.size)
        assertEquals(setOf(cs2000, cs2100), forward.equivalents(cs2500))
        assertTrue(forward.areEquivalent(cs2500, cs2100))
        assertEquals(listOf(cs2000, cs2100, cs2500), forward.group(cs2100))
    }

    @Test fun overlappingGroupsMergeAndSingletonsAreDropped() {
        val equivalences = CourseEquivalences.fromGroups(listOf(
            listOf(cs2500, cs2000), listOf(cs3100, cs3500), listOf(cs2000, cs2100), listOf(cs2510), emptyList(), listOf(cs2510, cs2510),
        ))
        assertEquals(listOf(listOf(cs2000, cs2100, cs2500), listOf(cs3100, cs3500)), equivalences.groups)
        assertEquals(setOf(cs2000, cs2100, cs2500, cs3100, cs3500), equivalences.courses)
        assertFalse(cs2510 in equivalences.courses)
    }

    @Test fun expandingWhatWasTaken() {
        val equivalences = CourseEquivalences.fromPairs(listOf(cs2100 to cs2510, cs3100 to cs3500))
        val taken = setOf(cs2510, engl1102)
        assertEquals(setOf(cs2510, cs2100, engl1102), equivalences.expanding(taken))
        assertTrue(equivalences.expanding(emptySet()).isEmpty())
        assertEquals(taken, CourseEquivalences.none.expanding(taken))
    }

    @Test fun satisfierIsTheTakenCourse() {
        val equivalences = CourseEquivalences.fromPairs(listOf(cs2500 to cs2000, cs2000 to cs2100))
        assertEquals(cs2100, equivalences.satisfier(cs2100, setOf(cs2100, cs2500)))
        assertEquals(cs2500, equivalences.satisfier(cs2100, setOf(cs2500)))
        assertEquals(cs2000, equivalences.satisfier(cs2100, setOf(cs2500, cs2000)))
        assertEquals(listOf(cs2000, cs2500), equivalences.satisfiers(cs2100, setOf(cs2500, cs2000)))
        assertNull(equivalences.satisfier(cs2100, setOf(cs3100)))
        assertEquals(cs3100, equivalences.satisfier(cs3100, setOf(cs3100)))
        assertNull(CourseEquivalences.none.satisfier(cs2100, setOf(cs2510)))
    }

    @Test fun mergingTwoSources() {
        val banner = CourseEquivalences.fromPairs(listOf(cs2100 to cs2510))
        val student = CourseEquivalences.fromPairs(listOf(cs2510 to cs2500, cs3100 to cs3500))
        val merged = banner.merging(student)
        assertTrue(merged.areEquivalent(cs2100, cs2500))
        assertTrue(merged.areEquivalent(cs3100, cs3500))
        assertEquals(banner.groups, banner.merging(CourseEquivalences.none).groups)
        assertEquals(student.groups, CourseEquivalences.none.merging(student).groups)
        assertTrue(banner.adding(cs2000, cs2100).areEquivalent(cs2000, cs2510))
    }

    @Test fun studentDeclarationsBecomeGroups() {
        val declared = listOf(
            CourseEquivalence(course = cs2510, countsAs = cs2100, note = "Renumbered"),
            CourseEquivalence(course = cs2500, countsAs = cs2000),
            CourseEquivalence(course = cs2000, countsAs = cs2100),
        )
        val equivalences = CourseEquivalences.fromPairs(declared.map { it.course to it.countsAs })
        assertEquals(listOf(listOf(cs2000, cs2100, cs2500, cs2510)), equivalences.groups)
        var plan = DegreePlan(name = "Plan")
        val added = plan.declareEquivalence(cs2510, cs2100, "Renumbered")
        val reversed = added.declareEquivalence(cs2100, cs2510)
        val same = plan.declareEquivalence(cs2100, cs2100)
        assertEquals(1, added.courseEquivalences.size)
        assertEquals(added.courseEquivalences, reversed.courseEquivalences)
        assertEquals(plan.courseEquivalences, same.courseEquivalences)
        assertTrue(added.declaredEquivalences.areEquivalent(cs2100, cs2510))
        assertTrue(added.removeEquivalence(added.courseEquivalences[0].id).declaredEquivalences.isEmpty)
    }

    @Test fun plansKeepTheirEquivalences() {
        var plan = DegreePlan(name = "Plan").declareEquivalence(cs2510, cs2100, "Renumbered")
        val roundTrip = AppJson.user.decodeFromString(DegreePlan.serializer(), AppJson.user.encodeToString(DegreePlan.serializer(), plan))
        assertEquals(plan, roundTrip)
        assertEquals("Renumbered", roundTrip.courseEquivalences.first().note)
        val element = AppJson.user.parseToJsonElement(AppJson.user.encodeToString(DegreePlan.serializer(), plan)).jsonObject
        val older = DegreePlan.decodeLenient(JsonObject(element.filterKeys { it != "courseEquivalences" }))
        assertTrue(older.courseEquivalences.isEmpty() && older.name == "Plan")
        val broken = buildJsonObject {
            for ((k, v) in element) if (k != "courseEquivalences") put(k, v)
            put("courseEquivalences", JsonPrimitive("not a list"))
        }
        val decoded = DegreePlan.decodeLenient(broken)
        assertTrue(decoded.courseEquivalences.isEmpty() && decoded.name == "Plan")
        val draft = app.termorganize.logic.degree.DegreePlanAutofill.newDegreePlanDraft("Draft", plan, emptyList(), catalogIndex = { null })
        assertEquals(listOf(cs2510), draft.courseEquivalences.map { it.course })
        assertNotEquals(plan.courseEquivalences.first().id, draft.courseEquivalences.first().id)
    }

    @Test fun crossListedCoursesAreEquivalentOnlyWhenTheyAreTheSameCourse() {
        val courses = listOf(
            course("AFCS 1225", title = "Gender, Race, and Medicine"),
            course("HIST 1225", title = "Gender, Race, and Medicine "),
            course("WMNS 1225", title = "Gender, Race, and Medicine"),
            course("ALY 6983", title = "Special Topics", topics = true),
            course("CS 6983", title = "Special Topics", topics = true),
            course("CS 3000", title = "Algorithms and Data"),
            course("CS 5800", title = "Algorithms"),
            course("ARCH 5310", title = "Design Tactics"),
            course("LARC 5310", title = "Urban Landscape Seminar"),
        )
        val sections = listOf(
            section("1", "AFCS 1225", cross = listOf("2", "3")),
            section("2", "HIST 1225", cross = listOf("1", "3")),
            section("3", "WMNS 1225", cross = listOf("1", "2")),
            section("4", "ALY 6983", cross = listOf("5")),
            section("5", "CS 6983", cross = listOf("4")),
            section("6", "CS 3000", cross = listOf("7")),
            section("7", "CS 5800", cross = listOf("6")),
            section("8", "ARCH 5310", cross = listOf("9")),
            section("9", "LARC 5310", cross = listOf("8")),
        )
        val equivalences = CourseEquivalences.fromCrossListed(index(courses, sections))
        assertEquals(listOf(listOf(key("AFCS 1225"), key("HIST 1225"), key("WMNS 1225"))), equivalences.groups)
    }
}

class PrerequisiteCheckTests {
    private val cs1800 = key("CS 1800")
    private val cs2100 = key("CS 2100")
    private val cs2510 = key("CS 2510")
    private val cs3386 = key("CS 3386")
    private val cs4050 = key("CS 4050")
    private val renumbered = CourseEquivalences.fromPairs(listOf(cs2100 to cs2510))

    @Test fun equivalentCoursesSatisfyARequisite() {
        val needed = Requisite.and(listOf(Requisite.course(cs2100), Requisite.course(cs1800)))
        assertTrue(DegreePlanAnalysis.status(needed, setOf(cs2510, cs1800)) is DegreePlanAnalysis.PrerequisiteStatus.Missing)
        assertTrue(DegreePlanAnalysis.status(needed, setOf(cs2510, cs1800), renumbered) is DegreePlanAnalysis.PrerequisiteStatus.Met)
        assertEquals("CS 1800", (DegreePlanAnalysis.status(needed, setOf(cs2510), renumbered) as DegreePlanAnalysis.PrerequisiteStatus.Missing).text)
        val chain = CourseEquivalences.fromPairs(listOf(key("CS 2500") to key("CS 2000"), key("CS 2000") to cs2100))
        assertTrue(DegreePlanAnalysis.status(Requisite.course(cs2100), setOf(key("CS 2500")), chain) is DegreePlanAnalysis.PrerequisiteStatus.Met)
    }

    @Test fun summariesAndCountsKnowEquivalents() {
        val needed = Requisite.and(listOf(Requisite.or(listOf(Requisite.course(cs2100), Requisite.course(key("CS 201M")))), Requisite.course(cs1800)))
        assertEquals("(CS 2100 or CS 201M) and CS 1800", DegreePlanAnalysis.unmetSummary(needed, setOf(cs2510)))
        assertEquals("CS 1800", DegreePlanAnalysis.unmetSummary(needed, setOf(cs2510), renumbered))
        assertNull(DegreePlanAnalysis.unmetSummary(needed, setOf(cs2510, cs1800), renumbered))
        assertEquals(2, DegreePlanAnalysis.missingCount(needed, setOf(cs2510)))
        assertEquals(1, DegreePlanAnalysis.missingCount(needed, setOf(cs2510), renumbered))
    }

    @Test fun saysWhichEquivalentCoveredACourse() {
        val needed = Requisite.or(listOf(Requisite.course(cs2100), Requisite.course(cs1800)))
        assertEquals(mapOf(cs2100 to cs2510), DegreePlanAnalysis.coveringEquivalents(needed, setOf(cs2510), renumbered))
        assertTrue(DegreePlanAnalysis.coveringEquivalents(needed, setOf(cs2510, cs2100), renumbered).isEmpty())
    }

    @Test fun coursesTheTermDoesNotListAreCheckedThroughTheLookup() {
        val plan = plan(listOf("CS 1800", "CS 2510"), listOf("CS 3386", "CS 4050", "CS 2963"))
        val needs = Requisite.and(listOf(Requisite.or(listOf(Requisite.course(cs2100), Requisite.course(cs2510))), Requisite.course(key("CS 2386"))))
        val table = mapOf(cs3386 to RequisiteAnswer.Known(needs), cs4050 to RequisiteAnswer.Known(null))
        val lookup: (CourseKey) -> RequisiteAnswer = { table[it] ?: RequisiteAnswer.Unknown }
        val termIndex = index(listOf(course("CS 1800")))
        assertTrue(DegreePlanAnalysis.prerequisiteStatus(cs3386, 0, plan, termIndex) is DegreePlanAnalysis.PrerequisiteStatus.Unknown)
        assertEquals("CS 2386", (DegreePlanAnalysis.prerequisiteStatus(cs3386, 0, plan, termIndex, lookup) as DegreePlanAnalysis.PrerequisiteStatus.Missing).text)
        assertTrue(DegreePlanAnalysis.prerequisiteStatus(cs4050, 0, plan, termIndex, lookup) is DegreePlanAnalysis.PrerequisiteStatus.Met)
        assertTrue(DegreePlanAnalysis.prerequisiteStatus(key("CS 2963"), 0, plan, termIndex, lookup) is DegreePlanAnalysis.PrerequisiteStatus.Unknown)
        assertEquals("CS 2386", (DegreePlanAnalysis.prerequisiteStatus(cs3386, 0, plan, null, lookup) as DegreePlanAnalysis.PrerequisiteStatus.Missing).text)
    }

    @Test fun theTermsOwnRequisitesWinWhenItHasDetails() {
        val plan = plan(listOf("CS 1800"), listOf("CS 3000"))
        val listed = index(listOf(course("CS 3000", prereqs = Requisite.course(cs1800))))
        val wrong: (CourseKey) -> RequisiteAnswer = { RequisiteAnswer.Known(Requisite.course(key("CS 9999"))) }
        assertTrue(DegreePlanAnalysis.prerequisiteStatus(key("CS 3000"), 0, plan, listed, wrong) is DegreePlanAnalysis.PrerequisiteStatus.Met)
        val stub = index(listOf(course("CS 3000", detailed = false)))
        assertTrue(DegreePlanAnalysis.prerequisiteStatus(key("CS 3000"), 0, plan, stub) is DegreePlanAnalysis.PrerequisiteStatus.Unknown)
        assertEquals("CS 9999", (DegreePlanAnalysis.prerequisiteStatus(key("CS 3000"), 0, plan, stub, wrong) as DegreePlanAnalysis.PrerequisiteStatus.Missing).text)
    }

    @Test fun equivalencesApplyToPlannedCourses() {
        val plan = plan(listOf("CS 2510"), listOf("CS 3386"))
        val lookup: (CourseKey) -> RequisiteAnswer = { if (it == cs3386) RequisiteAnswer.Known(Requisite.course(cs2100)) else RequisiteAnswer.Unknown }
        assertEquals("CS 2100", (DegreePlanAnalysis.prerequisiteStatus(cs3386, 0, plan, null, lookup) as DegreePlanAnalysis.PrerequisiteStatus.Missing).text)
        assertTrue(DegreePlanAnalysis.prerequisiteStatus(cs3386, 0, plan, null, lookup, renumbered) is DegreePlanAnalysis.PrerequisiteStatus.Met)
        val declaring = plan.declareEquivalence(cs2510, cs2100)
        assertTrue(DegreePlanAnalysis.prerequisiteStatus(cs3386, 0, declaring, null, lookup) is DegreePlanAnalysis.PrerequisiteStatus.Met)
    }

    @Test fun theLoadedCatalogsPrerequisitesStillWorkWithoutNewArguments() {
        val plan = plan(listOf("CS 2510"), listOf("CS 3000"))
        val listed = index(listOf(course("CS 3000", prereqs = Requisite.or(listOf(Requisite.course(cs2100), Requisite.course(cs2510))))))
        assertTrue(DegreePlanAnalysis.prerequisiteStatus(key("CS 3000"), 0, plan, listed) is DegreePlanAnalysis.PrerequisiteStatus.Met)
    }

    private fun plan(completed: List<String>, planned: List<String>) = DegreePlan(
        name = "Plan",
        completed = completed.map { DegreePlanCourse.of(key(it), it, 4.0) },
        terms = listOf(DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = planned.map { DegreePlanCourse.of(key(it), it, 4.0) })),
    )
}

class EquivalentCourseAuditTests {
    private val cs3000 = key("CS 3000")
    private val cs3990 = key("CS 3990")
    private val cs3991 = key("CS 3991")
    private val cs4530 = key("CS 4530")
    private val cs4999 = key("CS 4999")

    private fun plan(completed: List<String>, planned: List<String> = emptyList()): DegreePlan {
        val terms = if (planned.isEmpty()) emptyList() else listOf(
            DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = planned.map { DegreeAuditTests.course(it) }),
        )
        return DegreePlan(
            name = "Plan",
            completed = completed.map { DegreeAuditTests.course(it, if (it == "CS 1802") 1.0 else 4.0) },
            terms = terms,
            catalogYear = 2023,
            catalogProgramPath = DegreeAuditTests.program.path,
            concentrationID = "concentration-in-human-centered-computing",
        )
    }

    private fun result(audit: DegreeAudit.Result, label: String) = audit.ruleResults.firstOrNull { it.rule.label == label }

    @Test fun aRequirementIsMetByAnEquivalentCourse() {
        val requirements = DegreeAuditTests.requirements()
        val student = plan(listOf("CS 1800", "CS 1802", "CS 3990"))
        val without = DegreeAudit.run(student, requirements)
        assertEquals(DegreeAudit.Status.MISSING, result(without, "CS 3000")?.status)
        val audit = DegreeAudit.run(student, requirements, CourseEquivalences.fromPairs(listOf(cs3000 to cs3990)))
        val rule = result(audit, "CS 3000")!!
        assertEquals(DegreeAudit.Status.COMPLETE, rule.status)
        val use = rule.used.first()
        assertEquals(cs3990, use.key)
        assertEquals(cs3000, use.satisfies)
        assertTrue(use.viaEquivalence)
        assertEquals("counts as CS 3000", use.countsAsLabel)
        assertTrue(use.isCompleted)
        assertEquals(cs3990, rule.use(cs3000)?.key)
        assertNull(rule.use(cs3990))
        val plain = result(audit, "CS 1800 and CS 1802")!!
        assertTrue(plain.used.all { it.satisfies == null && !it.viaEquivalence && it.countsAsLabel == null })
        assertEquals(key("CS 1800"), plain.use(key("CS 1800"))?.key)
    }

    @Test fun theStudentsDeclaredEquivalencesApplyOnTheirOwn() {
        val requirements = DegreeAuditTests.requirements()
        val student = plan(listOf("CS 1800", "CS 1802", "CS 3990")).declareEquivalence(cs3990, cs3000)
        val audit = DegreeAudit.run(student, requirements)
        assertEquals(DegreeAudit.Status.COMPLETE, result(audit, "CS 3000")?.status)
        assertEquals(cs3000, result(audit, "CS 3000")?.used?.first()?.satisfies)
    }

    @Test fun plannedEquivalentsCountAsPlanned() {
        val requirements = DegreeAuditTests.requirements()
        val audit = DegreeAudit.run(plan(listOf("CS 1800", "CS 1802"), listOf("CS 3990")), requirements, CourseEquivalences.fromPairs(listOf(cs3000 to cs3990)))
        val rule = result(audit, "CS 3000")!!
        assertEquals(DegreeAudit.Status.PLANNED, rule.status)
        assertFalse(rule.used.first().isCompleted)
        assertEquals(cs3000, rule.used.first().satisfies)
    }

    @Test fun everyCourseStillCountsOnce() {
        val requirements = DegreeAuditTests.requirements()
        val audit = DegreeAudit.run(plan(listOf("CS 1800", "CS 1802", "CS 3000", "CS 3990")), requirements, CourseEquivalences.fromPairs(listOf(cs3000 to cs3990)))
        val rule = result(audit, "CS 3000")!!
        assertEquals(listOf(cs3000), rule.used.map { it.key })
        assertNull(rule.used.first().satisfies)
        val counted = audit.ruleResults.flatMap { it.used.map { use -> use.key } }
        assertEquals(counted.size, counted.toSet().size)
        val elsewhere = audit.ruleResults.filter { it.rule.label != "CS 3000" }.flatMap { it.used.map { use -> use.key } } +
            audit.generalElectives.map { it.key } + audit.unused.map { it.key }
        assertEquals(1, elsewhere.count { it == cs3990 })
    }

    @Test fun aChainOfRenumberingsReachesTheRequirement() {
        val requirements = DegreeAuditTests.requirements()
        val chain = CourseEquivalences.fromPairs(listOf(cs3000 to cs3990, cs3990 to cs3991))
        val audit = DegreeAudit.run(plan(listOf("CS 1800", "CS 1802", "CS 3991")), requirements, chain)
        assertEquals(DegreeAudit.Status.COMPLETE, result(audit, "CS 3000")?.status)
        assertEquals(cs3991, result(audit, "CS 3000")?.used?.first()?.key)
    }

    @Test fun alternativesAcceptEquivalents() {
        val requirements = DegreeAuditTests.requirements()
        val audit = DegreeAudit.run(plan(listOf("CS 1800", "CS 1802", "CS 4999")), requirements, CourseEquivalences.fromPairs(listOf(cs4530 to cs4999)))
        val rule = result(audit, "CS 4500 or CS 4530")!!
        assertEquals(DegreeAudit.Status.COMPLETE, rule.status)
        assertEquals(cs4530, rule.used.first().satisfies)
    }

    @Test fun rangesAcceptEquivalentsOfCoursesInThem() {
        val requirements = DegreeAuditTests.requirements()
        val student = plan(listOf("CS 1800", "CS 1802", "CS 2100", "CS 4973"))
        fun electives(audit: DegreeAudit.Result) = audit.ruleResults.firstOrNull { it.rule.label.startsWith("Complete 8 semester hours of CS") }
        val without = DegreeAudit.run(student, requirements)
        assertEquals(DegreeAudit.Status.PARTIAL, electives(without)?.status)
        assertEquals(listOf(key("CS 4973")), electives(without)?.used?.map { it.key })
        val audit = DegreeAudit.run(student, requirements, CourseEquivalences.fromPairs(listOf(key("CS 2100") to key("CS 2510"))))
        val rule = electives(audit)!!
        assertEquals(DegreeAudit.Status.COMPLETE, rule.status)
        val use = rule.used.first { it.key.description == "CS 2100" }
        assertEquals("CS 2510", use.satisfies?.description)
    }

    @Test fun substitutionsAcceptEquivalentsToo() {
        val requirements = DegreeAuditTests.requirements()
        val rule = requirements.allRules.first { it.label == "CS 4500 or CS 4530" }
        val student = plan(listOf("CS 1800", "CS 1802", "CS 3990")).copy(
            substitutions = listOf(RequirementSubstitution(ruleID = rule.id, ruleTitle = "Software development", change = SubstitutionChange.CountCourse(cs3000))),
        )
        val audit = DegreeAudit.run(student, requirements, CourseEquivalences.fromPairs(listOf(cs3000 to cs3990)))
        val found = audit.result(rule.id)!!
        assertEquals(cs3990, found.used.first().key)
        assertTrue(found.used.first().bySubstitution)
        assertTrue(audit.unmatchedSubstitutions.isEmpty())
    }
}

class CourseTitleMatcherTests {
    private fun similarity(a: String, b: String) = CourseTitleMatcher.similarity(a, b)

    @Test fun titlesAreReducedToTheirTellingWords() {
        assertEquals(setOf("human", "computer", "interaction"), CourseTitleMatcher.Title("Principles of Human-Computer Interaction").words)
        assertEquals(setOf("human", "computer", "interaction"), CourseTitleMatcher.Title("PRNCPLES of Human Computer Interaction").words)
        assertEquals(setOf("data", "structure", "algorithm"), CourseTitleMatcher.Title("Intr to Data Structures & Algorithms").words)
        assertEquals(setOf("women", "study"), CourseTitleMatcher.Title("Introduction to Women’s Studies").words)
        assertEquals(setOf("software", "engineering"), CourseTitleMatcher.Title("Fundamentals of Software Engineering").words)
        assertEquals(setOf("2"), CourseTitleMatcher.Title("Fundamentals of Computer Science 2").parts)
        val ii = CourseTitleMatcher.Title("Computer Science II")
        assertEquals(setOf("2"), ii.parts)
        assertEquals(setOf("computer", "science"), ii.words)
        assertTrue(CourseTitleMatcher.Title("Topics").words.isEmpty())
    }

    @Test fun aRenumberedCourseKeepsItsTitleAlike() {
        assertEquals(1.0, similarity("Human Computer Interaction", "Principles of Human-Computer Interaction")!!, 0.0001)
        assertEquals(1.0, similarity("Principles of Human-Computer Interaction", "Human Computer Interaction")!!, 0.0001)
        assertEquals(1.0, similarity("Prncples of Human-Computer Interaction", "Human Computer Interaction")!!, 0.0001)
        assertEquals(1.0, similarity("First-Year Writing", "First Year Writing")!!, 0.0001)
    }

    @Test fun titlesThatOnlyShareAWordAreNotAlike() {
        assertNull(similarity("Human Computer Interaction", "Computer Systems"))
        assertNull(similarity("Human Computer Interaction", "Human Factors in Computing"))
        assertNull(similarity("Data Science", "Data Structures"))
        assertNull(similarity("Cell Biology", "Cell Culture Techniques"))
        assertNull(similarity("Computer Systems", "Computer Networks"))
    }

    @Test fun similarityIsTheShareOfWordsOrContainment() {
        assertEquals(0.75, similarity("Object-Oriented Design", "Object-Oriented Design Studio")!!, 0.0001)
        assertEquals(2.0 / 3.0, similarity("Machine Learning", "Machine Learning Theory")!!, 0.0001)
        assertNull(similarity("Machine Learning Theory", "Machine Learning Practice"))
        assertEquals(0.6, similarity("Cell Biology", "Cell Biology of Cancer Metastasis")!!, 0.0001)
        assertEquals(0.8, CourseTitleMatcher.similarity("Cell Biology", "Cell Biology of Cancer Metastasis", 0.8)!!, 0.0001)
        assertEquals(0.5, CourseTitleMatcher.similarity("Machine Learning Theory", "Machine Learning Practice", 0.4)!!, 0.0001)
    }

    @Test fun courseSequencesAreNotAlike() {
        assertNull(similarity("Fundamentals of Computer Science 1", "Fundamentals of Computer Science 2"))
        assertNull(similarity("Calculus 1 for Science and Engineering", "Calculus 2 for Science and Engineering"))
        assertNull(similarity("General Chemistry I", "General Chemistry II"))
        assertNull(similarity("Physics for Engineering 1", "Physics for Engineering 3"))
        assertEquals(1.0, similarity("Fundamentals of Computer Science 2", "Computer Science 2")!!, 0.0001)
        assertEquals(1.0, similarity("Computer Science I", "Computer Science")!!, 0.0001)
        assertEquals(1.0, similarity("Computer Science 1", "Computer Science I")!!, 0.0001)
        assertNull(similarity("Computer Science 2", "Computer Science I"))
    }

    @Test fun aCourseAndItsLabOrAdvancedVersionAreNotAlike() {
        assertNull(similarity("Sensation and Perception", "Laboratory in Sensation and Perception"))
        assertNull(similarity("General Chemistry 1", "General Chemistry 1 Lab"))
        assertNull(similarity("Fundamentals of Computer Science 2", "Lab for Fundamentals of Computer Science 2"))
        assertNull(similarity("Machine Learning", "Advanced Machine Learning"))
        assertNotEquals(null, similarity("Advanced Machine Learning", "Advanced Machine Learning Methods"))
        assertEquals(1.0, similarity("General Chemistry Lab", "General Chemistry Laboratory")!!, 0.0001)
        assertEquals(setOf("lab"), CourseTitleMatcher.Title("Lab for Computer Systems").qualifiers)
    }

    @Test fun shortAndGenericTitlesAreNeverAlike() {
        for (pair in listOf("Seminar" to "Seminar", "Algorithms" to "Algorithms and Data", "Calculus" to "Calculus for Engineers", "Special Topics" to "Special Topics", "Directed Study" to "Directed Study", "Research Seminar" to "Research Seminar", "Co-op Work Experience" to "Co-op Work Experience", "Topics" to "Topics in Computer Science", "Introduction to" to "Introduction to Computer Science", "" to "Human Computer Interaction", "" to "")) {
            assertNull(similarity(pair.first, pair.second))
        }
        assertFalse(CourseTitleMatcher.Title("Seminar").isDistinctive)
        assertFalse(CourseTitleMatcher.Title("Directed Study").isDistinctive)
        assertTrue(CourseTitleMatcher.Title("Machine Learning").isDistinctive)
        assertEquals(1.0, similarity("Machine Learning", "Machine Learning")!!, 0.0001)
        assertEquals(0.75, similarity("Seminar in Human Computer Interaction", "Human Computer Interaction")!!, 0.0001)
    }

    @Test fun cutOffAuditTitlesStillMatch() {
        assertEquals(1.0, similarity("Fundamentals of Software Engin", "Fundamentals of Software Engineering")!!, 0.0001)
        assertEquals(1.0, similarity("Fundamentals of Software Engineering", "Fundamentals of Software Engin")!!, 0.0001)
        val cut = similarity("Principles of Human-Computer I", "Human Computer Interaction")
        assertTrue(cut != null && cut > 0.66)
        assertNull(similarity("Fundamentals of Software Engin", "Fundamentals of Software Testing"))
        assertEquals("engin", CourseTitleMatcher.Title("Fundamentals of Software Engin").cutOffWord)
        assertNull(CourseTitleMatcher.Title("Software Engineering").cutOffWord)
    }
}

class EquivalenceSuggesterTests {
    private fun either(vararg codes: String) = Requisite.or(codes.map { Requisite.course(key(it)) })
    private val catalog = listOf(
        PrerequisiteListing(key("CS 3000"), Requisite.and(listOf(either("CS 2100", "CS 2510", "DS 2500"), either("CS 1800", "MATH 1365")))),
        PrerequisiteListing(key("CS 3200"), either("CS 2100", "CS 2510")),
        PrerequisiteListing(key("CS 3386"), Requisite.and(listOf(Requisite.or(listOf(Requisite.or(listOf(Requisite.course(key("CS 2100")), Requisite.course(key("CS 201M")))), Requisite.course(key("CS 2510")))), either("CS 2386", "CS 3540")))),
        PrerequisiteListing(key("CS 4100"), Requisite.course(key("CS 2510"))),
        PrerequisiteListing(key("CS 4200"), Requisite.and(listOf(Requisite.course(key("CS 2510")), Requisite.course(key("MATH 1365"))))),
        PrerequisiteListing(key("ANTH 2000"), either("ANTH 1101", "POLS 1160")),
    )
    private val current = setOf(key("CS 2100"), key("DS 2500"), key("CS 3000"), key("ANTH 1101"))
    private val titles = mapOf(
        key("IS 4300") to "Human Computer Interaction",
        key("CS 2484") to "Principles of Human-Computer Interaction",
        key("CS 3000") to "Algorithms and Data",
        key("CS 3650") to "Computer Systems",
        key("CS 2510") to "Fundamentals of Computer Science 2",
        key("CS 2515") to "Computer Science 2 Honors",
        key("CS 2100") to "Program Design and Implementation 1",
        key("CS 4550") to "Web Development",
    )

    @Test fun suggestsTheCurrentCoursesListedBesideTheRetiredOne() {
        val found = EquivalenceSuggester.suggestions(key("CS 2510"), catalog, { it in current })
        assertEquals(listOf(key("CS 2100")), found.map { it.candidate })
        val suggestion = found.first()
        assertEquals(key("CS 2510"), suggestion.course)
        assertEquals(3, suggestion.together)
        assertEquals(5, suggestion.total)
        assertEquals(0.6, suggestion.confidence, 0.0001)
        assertEquals("Listed together in 3 of the 5 prerequisites that name CS 2510", suggestion.evidence)
        assertEquals("CS 2510|CS 2100", suggestion.id)
    }

    @Test fun thresholdsAndLimitsApply() {
        val wide = EquivalenceSuggester.suggestions(key("CS 2510"), catalog, { it in current }, minimumTogether = 1, minimumShare = 0.2)
        assertEquals(listOf(key("CS 2100"), key("DS 2500")), wide.map { it.candidate })
        assertEquals(listOf(key("CS 2100")), EquivalenceSuggester.suggestions(key("CS 2510"), catalog, { it in current }, minimumTogether = 1, minimumShare = 0.2, limit = 1).map { it.candidate })
        assertTrue(EquivalenceSuggester.suggestions(key("CS 2510"), catalog, { it in current }, minimumShare = 0.8).isEmpty())
    }

    @Test fun leavesOutWhatIsKnownOrNotCurrent() {
        val known = CourseEquivalences.fromPairs(listOf(key("CS 2510") to key("CS 2100")))
        assertTrue(EquivalenceSuggester.suggestions(key("CS 2510"), catalog, { it in current }, excluding = known).isEmpty())
        assertTrue(EquivalenceSuggester.suggestions(key("CS 2510"), catalog, isCurrent = { false }).isEmpty())
        assertTrue(EquivalenceSuggester.suggestions(key("CS 9999"), catalog, isCurrent = { true }).isEmpty())
        assertTrue(EquivalenceSuggester.suggestions(key("CS 4100"), catalog, isCurrent = { true }).isEmpty())
        val own = listOf(PrerequisiteListing(key("CS 2510"), either("CS 2510", "CS 2100")))
        assertTrue(EquivalenceSuggester.suggestions(key("CS 2510"), own, isCurrent = { true }).isEmpty())
    }

    @Test fun aSingleDependentReadsNaturally() {
        val one = listOf(
            PrerequisiteListing(key("CS 3000"), either("CS 2100", "CS 2510")),
            PrerequisiteListing(key("CS 3001"), either("CS 2100", "CS 2510")),
        )
        val found = EquivalenceSuggester.suggestions(key("CS 2510"), one, isCurrent = { true })
        assertEquals("Listed together in 2 of the 2 prerequisites that name CS 2510", found.first().evidence)
        val lone = EquivalenceSuggestion(key("CS 2510"), key("CS 2100"), 1, 1)
        assertEquals("Listed together in the prerequisites of the 1 course that names CS 2510", lone.evidence)
    }

    private fun byTitle(
        retired: String,
        among: List<String>,
        titles: Map<CourseKey, String> = this.titles,
        prerequisites: List<PrerequisiteListing> = emptyList(),
        isCurrent: (CourseKey) -> Boolean = { true },
        excluding: CourseEquivalences = CourseEquivalences.none,
        limit: Int = 3,
    ) = EquivalenceSuggester.suggestions(
        key(retired), prerequisites, isCurrent, excluding, limit = limit,
        title = { titles[it] }, titleCandidates = among.map { key(it) },
    )

    @Test fun suggestsAStudentCourseWithASimilarTitle() {
        val found = byTitle("IS 4300", listOf("CS 3000", "CS 2484", "CS 3650", "CS 2510"))
        assertEquals(listOf(key("CS 2484")), found.map { it.candidate })
        val suggestion = found.first()
        assertEquals(0, suggestion.together)
        assertEquals(0, suggestion.total)
        assertFalse(suggestion.isListedTogether)
        assertEquals(0.0, suggestion.confidence, 0.0)
        assertEquals("Principles of Human-Computer Interaction", suggestion.similarTitle)
        assertEquals(1.0, suggestion.titleScore!!, 0.0001)
        assertEquals("Similar title: Principles of Human-Computer Interaction", suggestion.evidence)
        assertEquals("IS 4300|CS 2484", suggestion.id)
    }

    @Test fun aTrueNegativeIsNotSuggested() {
        assertTrue(byTitle("IS 4300", listOf("CS 3650", "CS 3000", "CS 4550")).isEmpty())
    }

    @Test fun genericTitlesGiveNoTitleEvidence() {
        for (generic in listOf("Seminar", "Special Topics", "Directed Study", "Topics", "Introduction to", "")) {
            assertTrue(byTitle("IS 4300", listOf("CS 3000"), mapOf(key("IS 4300") to generic, key("CS 3000") to generic)).isEmpty())
            assertTrue(byTitle("IS 4300", listOf("CS 3000"), mapOf(key("IS 4300") to generic, key("CS 3000") to "Human Computer Interaction")).isEmpty())
            assertTrue(byTitle("IS 4300", listOf("CS 3000"), mapOf(key("IS 4300") to "Human Computer Interaction", key("CS 3000") to generic)).isEmpty())
        }
        assertTrue(byTitle("IS 4300", listOf("CS 3000"), mapOf(key("CS 3000") to "Human Computer Interaction")).isEmpty())
        assertTrue(byTitle("IS 4300", listOf("CS 3000"), mapOf(key("IS 4300") to "Human Computer Interaction")).isEmpty())
        assertTrue(byTitle("CS 2510", listOf("CS 2501"), mapOf(key("CS 2510") to "Fundamentals of Computer Science 2", key("CS 2501") to "Fundamentals of Computer Science 1")).isEmpty())
    }

    @Test fun titleSuggestionsAreRankedByHowAlikeTheTitlesAre() {
        val table = titles + mapOf(
            key("CS 5100") to "Human Computer Interaction Methods and Practice",
            key("CS 5200") to "Human-Computer Interaction Studio",
        )
        val found = byTitle("IS 4300", listOf("CS 5100", "CS 2484", "CS 5200"), table)
        assertEquals(listOf(key("CS 2484"), key("CS 5200"), key("CS 5100")), found.map { it.candidate })
        assertEquals(listOf(1.0, 0.75, 0.6), found.map { it.titleScore })
    }

    @Test fun titlesRankBelowListedTogetherAndBothKindsAreReturned() {
        val prerequisites = listOf(
            PrerequisiteListing(key("CS 4520"), either("IS 4300", "CS 2484", "CS 4550")),
            PrerequisiteListing(key("CS 4540"), either("IS 4300", "CS 2484", "CS 4550")),
            PrerequisiteListing(key("CS 4600"), Requisite.course(key("IS 4300"))),
        )
        val table = titles + (key("CS 5100") to "Human Computer Interaction Studio")
        val found = byTitle("IS 4300", listOf("CS 5100", "CS 2484"), table, prerequisites)
        assertEquals(listOf(key("CS 2484"), key("CS 4550"), key("CS 5100")), found.map { it.candidate })
        val both = found.first()
        assertEquals(2, both.together)
        assertEquals(3, both.total)
        assertTrue(both.isListedTogether)
        assertEquals("Principles of Human-Computer Interaction", both.similarTitle)
        assertEquals(1.0, both.titleScore!!, 0.0001)
        assertEquals("Listed together in 2 of the 3 prerequisites that name IS 4300 · similar title", both.evidence)
        assertNull(found[1].similarTitle)
        assertEquals("Listed together in 2 of the 3 prerequisites that name IS 4300", found[1].evidence)
        assertEquals(0, found[2].together)
        assertEquals(3, found[2].total)
        assertEquals("Similar title: Human Computer Interaction Studio", found[2].evidence)
    }

    @Test fun leavesOutTheRetiredCourseAndWhatIsKnown() {
        assertTrue(byTitle("IS 4300", listOf("IS 4300")).isEmpty())
        val known = CourseEquivalences.fromPairs(listOf(key("IS 4300") to key("CS 2484")))
        assertTrue(byTitle("IS 4300", listOf("CS 2484"), excluding = known).isEmpty())
        val chain = CourseEquivalences.fromPairs(listOf(key("IS 4300") to key("CS 9000"), key("CS 9000") to key("CS 2484")))
        assertTrue(byTitle("IS 4300", listOf("CS 2484"), excluding = chain).isEmpty())
    }

    @Test fun titleCandidatesNeedNotBeCurrent() {
        assertEquals(listOf(key("CS 2484")), byTitle("IS 4300", listOf("CS 2484"), isCurrent = { false }).map { it.candidate })
    }

    @Test fun aTitleSuggestionKeepsAPlaceWhenThePrerequisitesWouldFillTheList() {
        val listing = (1..4).map { number ->
            PrerequisiteListing(key("CS 40${number}0"), either("IS 4300", "CS 2110", "CS 2210", "CS 2310"))
        }
        val all = listing + listOf(PrerequisiteListing(key("CS 4500"), either("IS 4300", "CS 2484")))
        val found = byTitle("IS 4300", listOf("CS 2484"), prerequisites = all, limit = 3)
        assertEquals(3, found.size)
        assertEquals(key("CS 2484"), found.last().candidate)
        assertEquals(0, found.last().together)
        assertTrue(found.dropLast(1).all { it.isListedTogether })
        val one = byTitle("IS 4300", listOf("CS 2484"), prerequisites = all, limit = 1)
        assertEquals(1, one.size)
        assertTrue(one.first().isListedTogether)
        val two = byTitle("IS 4300", listOf("CS 2484"), prerequisites = all, limit = 2)
        assertEquals(listOf(true, false), two.map { it.isListedTogether })
        assertTrue(byTitle("IS 4300", listOf("CS 2484"), prerequisites = all, limit = 0).isEmpty())
    }
}

class CourseAttributeRepositoryTests {
    @Test fun searchesRecentTermsUntilACourseHasSections() = runBlocking {
        val banner = FakeAttributes(mapOf(
            "202630" to mapOf("CS 3386" to setOf("NCFQ", "NCEI")),
            "202610" to mapOf("HIST 1130" to setOf("NCDD", "NCSI"), "CS 3386" to setOf("NCFQ")),
        ))
        val directory = tempDir()
        val repository = CourseAttributeRepository(banner, directory)
        val keys = listOf(key("ENGW 1111"), key("CS 3386"), key("HIST 1130"), key("ZZZZ 9999"))
        val results = repository.lookup(keys, listOf(loaded()))
        assertEquals(CourseAttributeRepository.Lookup.Found(setOf("NCW1"), "202710"), results[key("ENGW 1111")])
        assertEquals(CourseAttributeRepository.Lookup.Found(setOf("NCFQ", "NCEI"), "202630"), results[key("CS 3386")])
        assertEquals(CourseAttributeRepository.Lookup.Found(setOf("NCDD", "NCSI"), "202610"), results[key("HIST 1130")])
        assertEquals(CourseAttributeRepository.Lookup.NotFound, results[key("ZZZZ 9999")])
        assertTrue(banner.calls.none { it.first == "202710" || it.first == "202655" })
        assertEquals(listOf(key("HIST 1130"), key("ZZZZ 9999")), banner.calls.first { it.first == "202610" }.second)
        assertEquals(listOf(key("ZZZZ 9999")), banner.calls.first { it.first == "202560" }.second)
        val again = CourseAttributeRepository(banner, directory)
        val calls = banner.calls.size
        assertEquals(results, again.lookup(keys, listOf(loaded())))
        assertEquals(calls, banner.calls.size)
        directory.deleteRecursively()
        Unit
    }

    @Test fun failuresAreNotCached() = runBlocking {
        val banner = FakeAttributes(mapOf("202610" to mapOf("HIST 1130" to setOf("NCDD"))))
        banner.failing += "202650"
        val directory = tempDir()
        val repository = CourseAttributeRepository(banner, directory)
        assertEquals(mapOf(key("HIST 1130") to CourseAttributeRepository.Lookup.Failed), repository.lookup(listOf(key("HIST 1130"))))
        banner.failing.clear()
        assertEquals(
            mapOf(key("HIST 1130") to CourseAttributeRepository.Lookup.Found(setOf("NCDD"), "202610")),
            repository.lookup(listOf(key("HIST 1130"))),
        )
        directory.deleteRecursively()
        Unit
    }

    private fun loaded(): CatalogIndex {
        val course = course("ENGW 1111", title = "First-Year Writing").copy(attributes = listOf("NCW1"))
        val section = section("10001", "ENGW 1111").copy(attributes = listOf("NCW1"), title = "First-Year Writing")
        return index(listOf(course), listOf(section))
    }
}

class CourseInfoStoreTests {
    private val clock = StoreClock()
    private val bannerTerms = listOf("202710", "202655", "202650", "202632", "202630", "202610", "202560", "202550", "202540", "202530", "202510", "202460").map { Term(it, it) }

    @Test fun loadsInfoForCoursesTheTermDoesNotList() = runBlocking {
        val directory = tempDir()
        val banner = StoreBanner(clock, bannerTerms)
        banner.list(info("CS 3386", "202710", Requisite.course(key("CS 2386"))), "202710")
        banner.list(info("CS 4050", "202630"), "202630")
        val store = store(banner, directory)
        val loaded = index(listOf(course("CS 3000", prereqs = Requisite.course(key("CS 1800")))))
        assertTrue(store.state(key("CS 3386")) is CourseInfoStore.LookupState.Unknown)
        store.load(listOf(key("CS 3386"), key("CS 4050"), key("CS 2963"), key("CS 3000")), listOf(loaded))
        assertEquals(Requisite.course(key("CS 2386")), store.info(key("CS 3386"))?.prerequisites)
        assertEquals("202630", store.info(key("CS 4050"))?.sourceTerm)
        assertEquals(Requisite.course(key("CS 1800")), store.info(key("CS 3000"))?.prerequisites)
        assertTrue(store.state(key("CS 2963")) is CourseInfoStore.LookupState.NotFound)
        val lookup = store.requisiteLookup()
        assertEquals(RequisiteAnswer.Known(Requisite.course(key("CS 2386"))), lookup(key("CS 3386")))
        assertEquals(RequisiteAnswer.Known(null), lookup(key("CS 4050")))
        assertEquals(RequisiteAnswer.Unknown, lookup(key("CS 2963")))
        val plan = DegreePlan(
            name = "Plan",
            completed = listOf(DegreePlanCourse.of(key("CS 2386"), "", 4.0)),
            terms = listOf(DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = listOf(DegreePlanCourse.of(key("CS 3386"), "", 4.0)))),
        )
        assertTrue(DegreePlanAnalysis.prerequisiteStatus(key("CS 3386"), 0, plan, loaded, lookup) is DegreePlanAnalysis.PrerequisiteStatus.Met)
        directory.deleteRecursively()
        Unit
    }

    @Test fun loadingAgainChangesNothing() = runBlocking {
        val directory = tempDir()
        val banner = StoreBanner(clock, bannerTerms)
        banner.list(info("CS 3386", "202710"), "202710")
        val store = store(banner, directory)
        val keys = listOf(key("CS 3386"), key("CS 2963"))
        store.load(keys, emptyList())
        val version = store.version
        assertTrue(version > 0)
        val calls = banner.entryCalls.size
        store.load(keys, emptyList())
        assertEquals(version, store.version)
        assertEquals(calls, banner.entryCalls.size)
        directory.deleteRecursively()
        Unit
    }

    @Test fun equivalencesMergeBannerDeclaredAndCrossListed() = runBlocking {
        val directory = tempDir()
        val banner = StoreBanner(clock, bannerTerms)
        banner.list(info("CS 2100", "202710", equivalents = listOf(key("CS 2510"))), "202710")
        val store = store(banner, directory)
        val loaded = index(
            listOf(course("HIST 1225", title = "Gender, Race, and Medicine"), course("WMNS 1225", title = "Gender, Race, and Medicine")),
            listOf(section("1", "HIST 1225", listOf("2")), section("2", "WMNS 1225", listOf("1"))),
        )
        store.load(listOf(key("CS 2100")), listOf(loaded))
        val fromBanner = store.equivalences(null)
        assertTrue(fromBanner.areEquivalent(key("CS 2100"), key("CS 2510")))
        assertTrue(fromBanner.areEquivalent(key("HIST 1225"), key("WMNS 1225")))
        assertFalse(fromBanner.areEquivalent(key("CS 3100"), key("CS 3500")))
        val plan = DegreePlan(name = "Plan").declareEquivalence(key("CS 3500"), key("CS 3100")).declareEquivalence(key("CS 2500"), key("CS 2510"))
        val merged = store.equivalences(plan)
        assertTrue(merged.areEquivalent(key("CS 3100"), key("CS 3500")))
        assertTrue(merged.areEquivalent(key("CS 2500"), key("CS 2100")))
        assertTrue(merged.areEquivalent(key("HIST 1225"), key("WMNS 1225")))
        directory.deleteRecursively()
        Unit
    }

    @Test fun suggestsReplacementsForRetiredCoursesOnly() = runBlocking {
        val directory = tempDir()
        val banner = StoreBanner(clock, bannerTerms)
        banner.list(info("CS 2510", "202530"), "202530")
        banner.list(info("CS 3386", "202710"), "202710")
        val store = store(banner, directory)
        val alternatives = Requisite.or(listOf(Requisite.course(key("CS 2100")), Requisite.course(key("CS 2510"))))
        val loaded = index(listOf(
            course("CS 2100"),
            course("CS 3000", prereqs = alternatives),
            course("CS 3200", prereqs = alternatives),
            course("CS 3300", prereqs = Requisite.or(listOf(Requisite.course(key("CS 2100")), Requisite.course(key("CS 2510")), Requisite.course(key("DS 2500"))))),
        ))
        assertTrue(store.equivalenceSuggestions(key("CS 2510")).isEmpty())
        store.load(listOf(key("CS 2510"), key("CS 3386"), key("CS 3000"), key("CS 2963")), listOf(loaded))
        assertEquals(true, store.info(key("CS 2510"))?.isRetired)
        val found = store.equivalenceSuggestions(key("CS 2510"))
        assertEquals(listOf(key("CS 2100")), found.map { it.candidate })
        assertEquals(3, found.first().together)
        assertEquals(3, found.first().total)
        assertTrue(store.equivalenceSuggestions(key("CS 3000")).isEmpty())
        assertTrue(store.equivalenceSuggestions(key("CS 3386")).isEmpty())
        assertTrue(store.state(key("CS 2963")) is CourseInfoStore.LookupState.NotFound)
        val plan = DegreePlan(name = "Plan").declareEquivalence(key("CS 2510"), key("CS 2100"))
        assertTrue(store.equivalenceSuggestions(key("CS 2510"), plan).isEmpty())
        directory.deleteRecursively()
        Unit
    }

    @Test fun failedLoadsCanBeRetried() = runBlocking {
        val directory = tempDir()
        val banner = StoreBanner(clock, bannerTerms)
        banner.list(info("CS 3386", "202710"), "202710")
        banner.fail("202710")
        val store = store(banner, directory)
        store.load(listOf(key("CS 3386")), emptyList())
        assertTrue(store.state(key("CS 3386")) is CourseInfoStore.LookupState.Failed)
        banner.fail("202710", false)
        store.load(listOf(key("CS 3386")), emptyList())
        assertTrue(store.state(key("CS 3386")) is CourseInfoStore.LookupState.Failed)
        val again = store(banner, directory)
        again.load(listOf(key("CS 3386")), emptyList())
        assertTrue(again.state(key("CS 3386")) is CourseInfoStore.LookupState.Found)
        directory.deleteRecursively()
        Unit
    }

    @Test fun coursesTheTermDoesNotOfferAreCheckedFromPreviousYears() = runBlocking {
        val loaded = LogicTests.bundledIndex()
        val cs3386 = key("CS 3386")
        val cs4050 = key("CS 4050")
        val cs2963 = key("CS 2963")
        assertNull(loaded.course(cs3386))
        assertNull(loaded.course(cs4050))
        assertNull(loaded.course(cs2963))
        val directory = tempDir()
        val banner = StoreBanner(clock, bannerTerms)
        val needs3386 = Requisite.and(listOf(
            Requisite.or(listOf(Requisite.course(key("CS 2100")), Requisite.course(key("CS 201M")), Requisite.course(key("CS 2510")))),
            Requisite.or(listOf(Requisite.course(key("CS 2386")), Requisite.course(key("CS 3540")))),
        ))
        banner.list(info("CS 3386", "202710", needs3386), "202710")
        banner.list(info("CS 4050", "202630"), "202630")
        val store = store(banner, directory)
        val plan = DegreePlan(
            name = "Plan",
            completed = listOf("CS 1800", "CS 2000", "CS 2386").map { DegreePlanCourse.of(key(it), it, 4.0) },
            terms = listOf(DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = listOf("CS 3386", "CS 4050", "CS 2963").map { DegreePlanCourse.of(key(it), it, 4.0) })),
        )
        store.load(listOf(cs3386, cs4050, cs2963), listOf(loaded))
        fun status(course: CourseKey, current: DegreePlan) = DegreePlanAnalysis.prerequisiteStatus(
            course, 0, current, loaded, store.requisiteLookup(), store.equivalences(current),
        )
        assertEquals(DegreePlanAnalysis.PrerequisiteStatus.Missing("CS 2100 or CS 201M or CS 2510"), status(cs3386, plan))
        assertEquals(DegreePlanAnalysis.PrerequisiteStatus.Met, status(cs4050, plan))
        assertEquals(true, store.info(cs4050)?.isRetired)
        assertEquals(DegreePlanAnalysis.PrerequisiteStatus.Unknown, status(cs2963, plan))
        assertTrue(store.state(cs2963) is CourseInfoStore.LookupState.NotFound)
        val declared = plan.declareEquivalence(key("CS 2000"), key("CS 2100"), "Curriculum change")
        assertEquals(DegreePlanAnalysis.PrerequisiteStatus.Met, status(cs3386, declared))
        assertEquals(key("CS 2000"), store.equivalences(declared).satisfier(key("CS 2100"), declared.completedKeys))
        directory.deleteRecursively()
        Unit
    }

    @Test fun suggestsTheStudentsCourseWithASimilarTitle() = runBlocking {
        val directory = tempDir()
        val banner = StoreBanner(clock, bannerTerms)
        banner.list(CourseInfo(key("IS 4300"), "Human Computer Interaction", 4.0, 4.0, sourceTerm = "202530"), "202530")
        val store = store(banner, directory)
        var plan = DegreePlan(
            name = "Plan",
            completed = listOf(
                DegreePlanCourse.of(key("CS 2484"), "Principles of Human-Computer I", 4.0),
                DegreePlanCourse.of(key("CS 3650"), "Computer Systems", 4.0),
            ),
            terms = listOf(DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = listOf(DegreePlanCourse.of(key("CS 4550"), "Web Development", 4.0)))),
        )
        store.load(listOf(key("IS 4300"), key("CS 2484"), key("CS 3650")), listOf(suggestionCatalog()))
        assertEquals(true, store.info(key("IS 4300"))?.isRetired)
        val found = store.equivalenceSuggestions(key("IS 4300"), plan)
        assertEquals(listOf(key("CS 2484")), found.map { it.candidate })
        assertEquals("Principles of Human-Computer I", found.first().similarTitle)
        assertFalse(found.first().isListedTogether)
        assertEquals("Similar title: Principles of Human-Computer I", found.first().evidence)
        assertTrue(store.equivalenceSuggestions(key("IS 4300")).isEmpty())
        val catalog = mapOf(key("CS 2484") to "Principles of Human-Computer Interaction", key("CS 4550") to "Web Development")
        val listed = store.equivalenceSuggestions(key("IS 4300"), catalogTitles = catalog, alsoComparing = listOf(key("CS 2484"), key("CS 4550")))
        assertEquals(listOf(key("CS 2484")), listed.map { it.candidate })
        assertEquals("Principles of Human-Computer Interaction", listed.first().similarTitle)
        val titled = store.equivalenceSuggestions(key("IS 4300"), plan, catalogTitles = mapOf(key("IS 4300") to "Computer Systems"))
        assertEquals(listOf(key("CS 3650")), titled.map { it.candidate })
        plan = plan.declareEquivalence(key("CS 2484"), key("IS 4300"))
        assertTrue(store.equivalenceSuggestions(key("IS 4300"), plan).isEmpty())
        directory.deleteRecursively()
        Unit
    }

    @Test fun titlesComeFromTheBestSource() = runBlocking {
        val directory = tempDir()
        val banner = StoreBanner(clock, bannerTerms)
        banner.list(CourseInfo(key("IS 4300"), "Human Computer Interaction", sourceTerm = "202530"), "202530")
        banner.list(CourseInfo(key("CS 2484"), "Principles of Human-Computer Interaction (Banner)", sourceTerm = "202710"), "202710")
        val store = store(banner, directory)
        val plan = DegreePlan(name = "Plan", completed = listOf(DegreePlanCourse.of(key("CS 2484"), "Principles of Human-Computer I", 4.0)))
        store.load(listOf(key("IS 4300"), key("CS 2484")), listOf(suggestionCatalog()))
        assertEquals("Principles of Human-Computer Interaction (Banner)", store.equivalenceSuggestions(key("IS 4300"), plan).first().similarTitle)
        val catalog = mapOf(key("CS 2484") to "Human-Computer Interaction Principles (catalog)")
        assertEquals("Human-Computer Interaction Principles (catalog)", store.equivalenceSuggestions(key("IS 4300"), plan, catalogTitles = catalog).first().similarTitle)
        directory.deleteRecursively()
        Unit
    }

    @Test fun currentCoursesGetNoTitleSuggestions() = runBlocking {
        val directory = tempDir()
        val banner = StoreBanner(clock, bannerTerms)
        banner.list(CourseInfo(key("IS 4300"), "Human Computer Interaction", sourceTerm = "202710"), "202710")
        val store = store(banner, directory)
        val plan = DegreePlan(name = "Plan", completed = listOf(DegreePlanCourse.of(key("CS 2484"), "Principles of Human-Computer Interaction", 4.0)))
        store.load(listOf(key("IS 4300"), key("CS 2484")), listOf(suggestionCatalog()))
        assertEquals(false, store.info(key("IS 4300"))?.isRetired)
        assertTrue(store.equivalenceSuggestions(key("IS 4300"), plan).isEmpty())
        directory.deleteRecursively()
        Unit
    }

    @Test fun loadedCatalogsDoNotDropBannerEquivalents() = runBlocking {
        val directory = tempDir()
        val banner = StoreBanner(clock, bannerTerms)
        banner.list(info("CS 2100", "202710", equivalents = listOf(key("CS 2510"))), "202710")
        val store = store(banner, directory)
        store.load(listOf(key("CS 2100")), emptyList())
        assertTrue(store.equivalences(null).areEquivalent(key("CS 2100"), key("CS 2510")))
        store.load(listOf(key("CS 2100")), listOf(index(listOf(course("CS 2100", prereqs = Requisite.course(key("CS 1800")))))))
        assertEquals(Requisite.course(key("CS 1800")), store.info(key("CS 2100"))?.prerequisites)
        assertEquals(false, store.info(key("CS 2100"))?.isRetired)
        assertTrue(store.equivalences(null).areEquivalent(key("CS 2100"), key("CS 2510")))
        store.load(listOf(key("CS 2100")), listOf(index(listOf(course("CS 2100", prereqs = Requisite.course(key("CS 1000")))), term = "202610")))
        assertEquals(Requisite.course(key("CS 1800")), store.info(key("CS 2100"))?.prerequisites)
        directory.deleteRecursively()
        Unit
    }

    private fun suggestionCatalog(): CatalogIndex {
        val alternatives = Requisite.or(listOf(Requisite.course(key("CS 2100")), Requisite.course(key("CS 2510"))))
        return index(listOf(
            course("CS 2100"),
            course("CS 3000", prereqs = alternatives),
            course("CS 3200", prereqs = alternatives),
            course("CS 3300", prereqs = Requisite.or(listOf(Requisite.course(key("CS 2100")), Requisite.course(key("CS 2510")), Requisite.course(key("DS 2500"))))),
        ))
    }

    private fun store(banner: StoreBanner, directory: File) = CourseInfoStore(
        CourseInfoRepository(banner, directory, now = { clock.now }),
        OfferingHistoryRepository(banner, directory, now = { clock.now }),
    )
}

class CatalogAndDegreePresentationTests {
    @Test fun dayPresetsAndFilterTitles() {
        assertEquals(SearchDayPreset.ANY, SearchDayPreset.selection(null))
        assertEquals(SearchDayPreset.NO_FRIDAYS, SearchDayPreset.selection(app.termorganize.core.model.DaySet(listOf(1, 2, 3, 4))))
        assertEquals(SearchDayPreset.TF, SearchDayPreset.selection(app.termorganize.core.model.DaySet(listOf(2, 5))))
        assertEquals(SearchDayPreset.CUSTOM, SearchDayPreset.selection(app.termorganize.core.model.DaySet(listOf(1, 5))))
        assertNull(SearchDayPreset.matching(app.termorganize.core.model.DaySet(listOf(1, 5))))
        assertEquals("Days", SearchFilterTitles.days(null))
        assertEquals("No Fridays", SearchFilterTitles.days(app.termorganize.core.model.DaySet(listOf(1, 2, 3, 4))))
        assertEquals("MF only", SearchFilterTitles.days(app.termorganize.core.model.DaySet(listOf(1, 5))))
        assertEquals("No set days", SearchFilterTitles.days(app.termorganize.core.model.DaySet(emptyList())))
        assertEquals("Subject", SearchFilterTitles.subjects(emptySet()))
        assertEquals("CS", SearchFilterTitles.subjects(setOf("CS")))
        assertEquals("3 subjects", SearchFilterTitles.subjects(setOf("CS", "DS", "MATH")))
        assertEquals("FQ, WI", SearchFilterTitles.nupaths(setOf(NUPath.FQ, NUPath.WI)))
        assertEquals("NUpath · 3", SearchFilterTitles.nupaths(setOf(NUPath.FQ, NUPath.WI, NUPath.CE)))
        assertEquals("Under 1000", SearchFilterTitles.level(0))
        assertEquals("3000", SearchFilterTitles.level(3))
        for (minutes in listOf(0, 9 * 60, 12 * 60 + 35, 17 * 60, 23 * 60 + 59)) {
            assertEquals(minutes, SearchTimeOfDay.minutes(minutes))
        }
        assertEquals(0, SearchTimeOfDay.minutes(-30))
        assertEquals(24 * 60 - 1, SearchTimeOfDay.minutes(5_000))
    }

    @Test fun quickSearchesAndBrowseText() {
        for (preset in HomeQuickSearch.entries) {
            assertEquals(1, preset.filters.activeCount)
            assertTrue(preset.symbol.isNotEmpty())
        }
        assertTrue(HomeQuickSearch.OPEN_SEATS.filters.openSeatsOnly)
        assertTrue(HomeQuickSearch.HONORS.filters.honorsOnly)
        assertEquals(app.termorganize.logic.search.InstructionFilter.ONLINE, HomeQuickSearch.ONLINE.filters.instruction)
        assertEquals("42 courses", browseSummaryText(42, 42, 0))
        assertEquals("1 course", browseSummaryText(1, 1, 0))
        assertEquals("12 of 42 courses · 9 with open seats", browseSummaryText(12, 42, 9))
        assertEquals("9 of 42 courses", browseSummaryText(9, 42, 9, openOnly = true))
        assertEquals("2 professors", browseSummaryText(2, 2, 0, noun = "professor"))
        assertEquals("1000-level", browseLevelTitle(1))
        assertEquals("Under 1000", browseLevelTitle(0))
        assertEquals("3000-level courses", browseLevelNoun(3))
        assertEquals("courses under 1000", browseLevelNoun(0))
    }

    @Test fun bundledCatalogExamplesAndPopularSubjects() {
        val index = LogicTests.bundledIndex()
        val popular = index.popularSubjects(12)
        assertEquals(12, popular.size)
        val counts = popular.map { index.courseCountBySubject[it.code] ?: 0 }
        assertEquals(counts.sortedDescending(), counts)
        assertEquals(popular.take(3).map { it.code }, index.popularSubjects(3).map { it.code })
        val busiestOutside = index.activeSubjects
            .filter { subject -> popular.none { it.code == subject.code } }
            .maxOfOrNull { index.courseCountBySubject[it.code] ?: 0 } ?: 0
        assertTrue((counts.lastOrNull() ?: 0) >= busiestOutside)
        val teacher = index.instructors.first { it.courseKeys.size >= 3 }
        val taught = ProfessorPage.sections(teacher, index)
        assertEquals(teacher.crns.size, taught.size)
        for (pair in taught.zipWithNext()) assertTrue(pair.first.courseKey <= pair.second.courseKey)
        val keys = teacher.courseKeys.take(app.termorganize.logic.professors.CourseColors.palette.size)
        val colors = ProfessorPage.courseColors(keys)
        assertEquals(keys.size, colors.size)
        assertEquals(keys.size, colors.values.toSet().size)
        assertEquals(colors, ProfessorPage.courseColors(keys))
        assertEquals("Teaches 3 sections across 2 courses", ProfessorPage.summaryText(listOf(section("1", "CS 3000"), section("2", "CS 3000"), section("3", "CS 3001"))))
        assertEquals("Teaches 1 section across 1 course", ProfessorPage.summaryText(listOf(section("3", "CS 3001"))))
        val examples = SearchExampleCache.examples(index)
        assertTrue(examples.size >= 3)
        for (example in examples) {
            val response = SearchEngine.search(SearchQuery(example.title), index)
            assertTrue(response.results.isNotEmpty() || response.crnMatch != null || response.instructors.isNotEmpty())
        }
        for (preset in HomeQuickSearch.entries) {
            assertTrue(SearchEngine.search(SearchQuery("", preset.filters), index).results.isNotEmpty())
        }
    }

    @Test fun professorNotesAndScheduleMembership() {
        val a = key("CS 3000")
        val b = key("CS 3001")
        val sections = listOf(section("1", "CS 3000").copy(sectionNumber = "01"), section("2", "CS 3000").copy(sectionNumber = "04"), section("3", "CS 3001").copy(sectionNumber = "02"))
        assertEquals("Sections 01, 04", ProfessorPage.sectionNote(a, sections))
        assertEquals("Section 02", ProfessorPage.sectionNote(b, sections))
        assertTrue(ProfessorPage.rateMyProfessorsUrl("José O'Neil-Smith").contains("q=Jos%C3%A9%20O%27Neil-Smith"))
        val store = UserDataStore()
        assertFalse(store.courseIsInSchedule("202710", a))
        val plan = store.createPlan("Plan A", "202710")
        store.addCourse(a, plan.id)
        assertTrue(store.courseIsInSchedule("202710", a))
        assertFalse(store.courseIsInSchedule("202630", a))
        assertFalse(store.courseIsInSchedule("202710", a, "12726"))
        store.updatePlan(plan.id) { it.copy(selectedCRNs = it.selectedCRNs + "12726") }
        assertTrue(store.courseIsInSchedule("202710", a, "12726"))
    }

    @Test fun reordersWithinASemesterAndSummarizes() {
        var plan = DegreePlan(name = "Plan", terms = listOf(
            DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = listOf(DegreePlanCourse.of(key("CS 3000"), "CS 3000", 4.0), DegreePlanCourse.of(key("CS 3500"), "CS 3500", 4.0), DegreePlanCourse.of(key("CS 3650"), "CS 3650", 4.0))),
            DegreePlanTerm(season = PlanSeason.FALL, year = 2027, courses = listOf(DegreePlanCourse.of(key("CS 4400"), "CS 4400", 4.0))),
        ))
        val spring = plan.terms[0]
        val a = spring.courses[0].id
        val b = spring.courses[1].id
        val c = spring.courses[2].id
        plan = DegreePlanEditing.move(a, spring.id, 3, plan)
        assertEquals(listOf("CS 3500", "CS 3650", "CS 3000"), plan.terms[0].courses.map { it.key?.description })
        plan = DegreePlanEditing.move(a, spring.id, 1, plan)
        assertEquals(listOf("CS 3500", "CS 3000", "CS 3650"), plan.terms[0].courses.map { it.key?.description })
        plan = DegreePlanEditing.move(c, spring.id, 0, plan)
        assertEquals(listOf("CS 3650", "CS 3500", "CS 3000"), plan.terms[0].courses.map { it.key?.description })
        val unchanged = plan
        plan = DegreePlanEditing.move(b, spring.id, 1, plan)
        plan = DegreePlanEditing.move(b, spring.id, 2, plan)
        assertEquals(unchanged.terms[0].courses.map { it.id }, plan.terms[0].courses.map { it.id })
        var across = DegreePlan(name = "Plan", terms = listOf(
            DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = listOf(DegreePlanCourse.of(key("CS 3000"), "CS 3000", 4.0), DegreePlanCourse.of(key("CS 3500"), "CS 3500", 4.0), DegreePlanCourse.of(key("CS 3650"), "CS 3650", 4.0))),
            DegreePlanTerm(season = PlanSeason.FALL, year = 2027, courses = listOf(DegreePlanCourse.of(key("CS 4400"), "CS 4400", 4.0))),
        ))
        val moved = across.terms[0].courses[1].id
        across = DegreePlanEditing.move(moved, across.terms[1].id, 0, across)
        assertEquals(listOf("CS 3000", "CS 3650"), across.terms[0].courses.map { it.key?.description })
        assertEquals(listOf("CS 3500", "CS 4400"), across.terms[1].courses.map { it.key?.description })
        val first = across.terms[0].courses[0].id
        across = DegreePlanEditing.move(first, across.terms[1].id, 99, across)
        assertEquals(listOf("CS 3500", "CS 4400", "CS 3000"), across.terms[1].courses.map { it.key?.description })
        val last = across.terms[0].courses[0].id
        across = DegreePlanEditing.move(last, across.terms[1].id, -5, across)
        assertEquals(listOf("CS 3650", "CS 3500", "CS 4400", "CS 3000"), across.terms[1].courses.map { it.key?.description })
        assertTrue(across.terms[0].courses.isEmpty())
        val gone = DegreePlanEditing.move("missing", across.terms[1].id, 0, across)
        assertEquals(across, gone)
        val empty = DegreePlan(name = "Plan")
        val now = java.time.Instant.parse("2026-10-13T12:00:00Z")
        assertEquals("No completed courses yet", empty.degreePlanCompletedLine)
        assertEquals("No semesters planned yet", empty.degreePlanNextLine(now))
        val done = empty.copy(completed = listOf(DegreePlanCourse.of(key("CS 1800"), "", 4.0), DegreePlanCourse.of(key("CS 1802"), "", 1.0)))
        assertEquals("5 credits completed", done.degreePlanCompletedLine)
        val past = done.copy(terms = listOf(DegreePlanTerm(season = PlanSeason.FALL, year = 2001, courses = listOf(DegreePlanCourse.of(key("CS 1800"), "", 4.0)))))
        assertEquals("No upcoming semesters", past.degreePlanNextLine(now))
        val upcoming = past.copy(terms = past.terms + DegreePlanTerm(season = PlanSeason.SPRING, year = 2099))
        assertEquals("No courses planned yet", upcoming.degreePlanNextLine(now))
        val filled = upcoming.copy(terms = listOf(upcoming.terms[0], upcoming.terms[1].copy(courses = listOf(DegreePlanCourse.of(key("CS 3000"), "", 4.0), DegreePlanCourse.of(key("CS 3500"), "", 5.0)))))
        assertEquals("Spring 2099 · 9 credits · 2 courses", filled.degreePlanNextSummary(now))
        assertEquals("Next: Spring 2099 · 9 credits · 2 courses", filled.degreePlanNextLine(now))
    }

    @Test fun laysRequirementsOutLikeTheCatalog() {
        val requirements = DegreeAuditTests.requirements()
        val audit = DegreeAudit.run(DegreeAuditTests.plan(), requirements)
        val either = audit.ruleResults.first { it.rule.label == "CS 4500 or CS 4530" }
        assertFalse(DegreeRequirementsListing.hasHeaderRow(either))
        val option = (either.rule.kind as app.termorganize.logic.degree.RequirementRule.Kind.Required).option
        val alternatives = DegreeRequirementsListing.entries(option)
        assertEquals(listOf(listOf(key("CS 4500")), listOf(key("CS 4530"))), alternatives.map { it.courses })
        val security = audit.ruleResults.first { it.rule.label.startsWith("Complete one of the following") }
        assertTrue(DegreeRequirementsListing.hasHeaderRow(security))
        val (options, ranges) = DegreeRequirementsListing.listing(security.rule)
        assertEquals(2, options.size)
        assertTrue(ranges.isEmpty())
        val science = audit.ruleResults.first { it.rule.label.startsWith("Complete two courses") }
        val listed = DegreeRequirementsListing.listing(science.rule).first.flatMap { it.allCourses }.toSet()
        val entries = DegreeRequirementsListing.entries(DegreeRequirementsListing.listing(science.rule).first, science.rule) { null }
        assertEquals(listOf("0 Biology", "1 Mathematics"), entries.mapNotNull { entry -> entry.category?.let { "${entry.id} $it" } })
        assertTrue(DegreeRequirementsListing.isShown(DegreeAudit.CourseUse(key("BIOL 1112"), "", 1.0, null, 0), listed))
        assertFalse(DegreeRequirementsListing.isShown(DegreeAudit.CourseUse(key("CS 4973"), "", 4.0, null, 1), listed))
        val collapsed = DegreeRequirementsListing.collapsed((0 until 10).map { DegreeListEntry(it, listOf(listOf(key("CS ${4000 + it}")))) }, 3) { it.id == 2 || it.id == 7 }
        assertEquals(listOf(0, 1, 2, 3, 7), collapsed.map { it.id })
    }

    @Test fun addsADraggedCourseAndBuildsOneSchedule() {
        var plan = DegreePlan(name = "Plan", terms = listOf(
            DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = listOf(DegreePlanCourse.of(key("CS 3000"), "CS 3000", 4.0), DegreePlanCourse.of(key("CS 3500"), "CS 3500", 4.0), DegreePlanCourse.of(key("CS 3650"), "CS 3650", 4.0))),
            DegreePlanTerm(season = PlanSeason.FALL, year = 2027, courses = listOf(DegreePlanCourse.of(key("CS 4400"), "CS 4400", 4.0))),
        ))
        val spring = plan.terms[0]
        fun drop(code: String, at: Int) {
            val added = DegreePlanCourse.of(key(code), code, 4.0)
            plan = DegreePlanEditing.add(added, spring.id, plan)
            plan = DegreePlanEditing.move(added.id, spring.id, at, plan)
        }
        drop("CS 4500", 1)
        assertEquals(listOf("CS 3000", "CS 4500", "CS 3500", "CS 3650"), plan.terms[0].courses.map { it.key?.description })
        drop("CS 4530", 0)
        assertEquals(listOf("CS 4530", "CS 3000", "CS 4500", "CS 3500", "CS 3650"), plan.terms[0].courses.map { it.key?.description })
        drop("CS 4535", plan.terms[0].courses.size)
        assertEquals(listOf("CS 4530", "CS 3000", "CS 4500", "CS 3500", "CS 3650", "CS 4535"), plan.terms[0].courses.map { it.key?.description })
        assertEquals(listOf("CS 4400"), plan.terms[1].courses.map { it.key?.description })
        val before = plan
        plan = DegreePlanEditing.move(java.util.UUID.randomUUID().toString(), plan.terms[1].id, 0, plan)
        plan = DegreePlanEditing.move(plan.terms[0].courses[0].id, java.util.UUID.randomUUID().toString(), 0, plan)
        assertEquals(before, plan)
        val store = UserDataStore()
        val semester = DegreePlan(name = "Plan", terms = listOf(
            DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = listOf(DegreePlanCourse.of(key("CS 3000"), "CS 3000", 4.0), DegreePlanCourse.of(key("CS 3500"), "CS 3500", 4.0), DegreePlanCourse.of(key("CS 3650"), "CS 3650", 4.0))),
        )).terms[0]
        val first = store.scheduleForDegreePlan(semester, "202730")!!
        assertTrue(first.isNew)
        assertEquals(3, first.courseCount)
        assertEquals("Spring 2027 plan", first.plan.name)
        assertEquals(listOf("CS 3000", "CS 3500", "CS 3650"), store.plan(first.plan.id)!!.courses.map { it.key.description })
        val again = store.scheduleForDegreePlan(semester, "202730")!!
        assertFalse(again.isNew)
        assertEquals(first.plan.id, again.plan.id)
        assertEquals(1, store.plans.size)
        val empty = DegreePlanTerm(season = PlanSeason.SUMMER_A, year = 2027, courses = listOf(DegreePlanCourse.of(null, "", 0.0, label = "NO AP CREDIT")))
        assertNull(store.scheduleForDegreePlan(empty, "202740"))
        val choices = listOf(
            DegreePlanCourseChoice(key("CS 3000"), "Algorithms and Data"),
            DegreePlanCourseChoice(key("MATH 1341"), "Calculus 1"),
        )
        assertEquals(2, DegreeSubstitutionEditing.matching(choices, "").size)
        assertEquals(listOf("CS 3000"), DegreeSubstitutionEditing.matching(choices, "cs 3000").map { it.key.description })
        assertEquals(listOf("MATH 1341"), DegreeSubstitutionEditing.matching(choices, "math1341").map { it.key.description })
        assertEquals(listOf("MATH 1341"), DegreeSubstitutionEditing.matching(choices, "calculus").map { it.key.description })
        assertTrue(DegreeSubstitutionEditing.matching(choices, "chemistry").isEmpty())
        val collapsedNone = DegreeRequirementsListing.collapsed((0 until 10).map { DegreeListEntry(it, listOf(listOf(key("CS ${4000 + it}")))) }, 0) { it.id == 2 || it.id == 7 }
        assertEquals(listOf(2, 7), collapsedNone.map { it.id })
    }

    @Test fun duplicatesADegreePlanWithFreshIds() {
        val plan = DegreePlan(
            name = "Plan",
            isPrimary = true,
            importedFrom = "pdf",
            completed = listOf(DegreePlanCourse.of(key("CS 1800"), "", 4.0)),
            terms = listOf(DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = listOf(DegreePlanCourse.of(key("CS 3000"), "", 4.0)))),
        )
        val copy = DegreePlanEditing.duplicate(plan, listOf(plan.name))
        assertEquals("Plan copy", copy.name)
        assertFalse(copy.isPrimary)
        assertNull(copy.importedFrom)
        assertNotEquals(plan.id, copy.id)
        assertNotEquals(plan.completed[0].id, copy.completed[0].id)
        assertNotEquals(plan.terms[0].id, copy.terms[0].id)
        assertNotEquals(plan.terms[0].courses[0].id, copy.terms[0].courses[0].id)
        assertEquals(listOf("CS 3000"), copy.terms[0].courses.map { it.key?.description })
        assertEquals("Plan copy 2", DegreePlanEditing.duplicate(plan, listOf(plan.name, copy.name)).name)
    }

    @Test fun roomsAndSettingsWording() {
        val day = listOf(booking("CS 3000", 600, 665), booking("MATH 1341", 665, 730), booking("PHYS 1151", 780, 840))
        assertEquals(listOf(RoomAvailability.MinuteSpan(420, 600), RoomAvailability.MinuteSpan(730, 780), RoomAvailability.MinuteSpan(840, 1320)), RoomAvailability.freeStretches(day, 420, 1320))
        assertEquals(listOf(RoomAvailability.MinuteSpan(730, 780)), RoomAvailability.freeStretches(day, 620, 800))
        assertTrue(RoomAvailability.freeStretches(day, 600, 730).isEmpty())
        assertEquals(listOf(RoomAvailability.MinuteSpan(420, 1320)), RoomAvailability.freeStretches(emptyList(), 420, 1320))
        val busyMorning = RoomSchedule("RY", "Ryder Hall", "210", listOf(booking("CS 3000", 9 * 60 + 50, 11 * 60 + 30)))
        val busyAfternoon = RoomSchedule("RY", "Ryder Hall", "300", listOf(booking("MATH 1341", 13 * 60, 14 * 60)))
        val empty = RoomSchedule("RY", "Ryder Hall", "400", emptyList())
        val counts = RoomAvailability.freeRoomCounts(listOf(busyMorning, busyAfternoon, empty), 2)
        assertEquals(15, counts.size)
        assertEquals(3, counts[0])
        assertEquals(2, counts[2])
        assertEquals(3, counts[5])
        assertEquals(2, counts[6])
        assertEquals(3, counts[7])
        val wrapped = RoomAvailability.Moment(8, 99_999)
        assertEquals(1, wrapped.weekday)
        assertEquals(24 * 60 - 1, wrapped.minutes)
        assertEquals("Tuesday at 2:30 PM", RoomAvailability.Moment(2, 14 * 60 + 30).label)
        val today = listOf(booking("CS 3000", 600, 665), booking("MATH 1341", 720, 780))
        assertEquals("Next: CS 3000, 10:00 – 11:05 AM", RoomsText.followUpNote(RoomAvailability.Status.Free(600), today))
        assertEquals("No more classes today.", RoomsText.followUpNote(RoomAvailability.Status.Free(null), today))
        assertEquals("No classes are scheduled here today.", RoomsText.followUpNote(RoomAvailability.Status.Free(null), emptyList()))
        val busy = RoomAvailability.status(today, 620) as RoomAvailability.Status.Busy
        assertEquals("Then free until 12:00 PM", RoomsText.followUpNote(busy, today))
        assertEquals("12 of 40 rooms free", RoomsText.freeSummary(12, 40))
        assertEquals("1 of 1 room free", RoomsText.freeSummary(1, 1))
        val ryder = BuildingSummary("RY", "Ryder Hall", "BOS", listOf(RoomSchedule("RY", "Ryder Hall", "210", emptyList()), RoomSchedule("RY", "Ryder Hall", "300", emptyList())))
        val snell = BuildingSummary("SL", "Snell Library", "BOS", listOf(RoomSchedule("SL", "Snell Library", "210", emptyList())))
        val all = RoomAvailability.availability(listOf(ryder, snell), RoomAvailability.Moment(2, 600))
        assertEquals(listOf("RY"), RoomAvailability.filter(all, "ry").map { it.building.code })
        assertEquals(listOf("SL"), RoomAvailability.filter(all, "snell").map { it.building.code })
        assertEquals(2, RoomAvailability.filter(all, "ryder").first().free.size)
        val byRoom = RoomAvailability.filter(all, "210")
        assertEquals(listOf("RY", "SL"), byRoom.map { it.building.code })
        assertEquals(listOf("210"), byRoom.first().free.map { it.room.room })
        assertEquals(2, RoomAvailability.filter(all, "  ").size)
        assertEquals("12 AM", SettingsFormat.hourLabel(0))
        assertEquals("8 AM", SettingsFormat.hourLabel(8))
        assertEquals("12 PM", SettingsFormat.hourLabel(12))
        assertEquals("9 PM", SettingsFormat.hourLabel(21))
        assertEquals("12 AM", SettingsFormat.hourLabel(24))
        assertEquals("https://www.gnu.org/licenses/agpl-3.0.html", LegalText.LICENSE_URL)
        assertTrue(LegalText.SEARCH_NEU_SUMMARY.contains("${LegalText.PLATFORM_NAME} adaptation of SearchNEU"))
        val now = 1_790_000_000_000L
        assertEquals("just now", WatchlistFormat.ago(now - 30_000, now))
        assertEquals("3 hr ago", WatchlistFormat.ago(now - 3 * 3_600_000, now))
        assertEquals("4 days ago", WatchlistFormat.ago(now - 4 * 86_400_000, now))
        assertEquals("just now", WatchlistFormat.ago(now + 60_000, now))
        val open = app.termorganize.logic.degree.WatchItem("202710", "12345", key("CS 3000"), "Algorithms", "Lecture 02", lastSeatsRemaining = 1, lastWaitlistRemaining = 0, seatCapacity = 0)
        assertEquals("1 seat open", WatchlistStatus.OPEN.spokenDetail(open))
    }
}

private class StoreClock(var now: Double = 1_800_000_000.0)

private class StoreBanner(private val clock: StoreClock, private val terms: List<Term>) : OfferingFetching, CourseInfoFetching {
    private val catalog = HashMap<String, MutableMap<CourseKey, CourseInfo>>()
    private val failing = HashSet<String>()
    val entryCalls = ArrayList<Pair<CourseKey, String>>()
    fun list(info: CourseInfo, term: String) { catalog.getOrPut(term) { HashMap() }[info.key] = info }
    fun fail(term: String, failing: Boolean = true) {
        if (failing) this.failing += term else this.failing -= term
    }
    override suspend fun terms() = terms
    override suspend fun offeredCourses(term: String, courses: List<CourseKey>) = emptySet<CourseKey>()
    override suspend fun catalogEntry(key: CourseKey, term: String): CourseInfo? {
        entryCalls += key to term
        if (term in failing) error("down")
        return catalog[term]?.get(key)?.copy(fetchedAt = clock.now)
    }
}

private class FakeAttributes(raw: Map<String, Map<String, Set<String>>>) : AttributeFetching {
    private val byTerm = raw.mapValues { (_, courses) -> courses.mapKeys { key(it.key) } }
    val calls = ArrayList<Pair<String, List<CourseKey>>>()
    val failing = HashSet<String>()
    override suspend fun terms() = listOf("202710", "202655", "202650", "202630", "202610", "202560", "202550", "202540", "202530", "202510").map { Term(it, "") }
    override suspend fun sectionAttributes(term: String, courses: List<CourseKey>): Map<CourseKey, Set<String>> {
        calls += term to courses
        if (term in failing) error("503")
        return byTerm[term].orEmpty().filterKeys { it in courses }
    }
}

private fun key(code: String) = CourseKey.parse(code)!!

private fun course(code: String, title: String = "Title of $code", prereqs: Requisite? = null, detailed: Boolean = true, topics: Boolean = false): Course {
    val parsed = key(code)
    return Course(parsed.subject, parsed.number, title, minCredits = 4.0, maxCredits = 4.0, prereqs = prereqs, specialTopics = topics, detailsLoaded = detailed)
}

private fun info(code: String, term: String, prerequisites: Requisite? = null, equivalents: List<CourseKey> = emptyList()) =
    CourseInfo(key(code), "Title of $code", 4.0, 4.0, prerequisites = prerequisites, equivalents = equivalents, sourceTerm = term)

private fun section(crn: String, code: String, cross: List<String> = emptyList()): CourseSection {
    val parsed = key(code)
    return CourseSection(crn, parsed.subject, parsed.number, title = code, crossListCRNs = cross)
}

private fun index(courses: List<Course>, sections: List<CourseSection> = emptyList(), term: String = "202710") = CatalogIndex.build(
    TermCatalog(term = Term(term, "Fall 2026"), generatedAt = 1.0, courses = courses, sections = sections),
)

private fun booking(code: String, start: Int, end: Int) =
    RoomSchedule.Booking("1$start", key(code), app.termorganize.core.model.DaySet(listOf(2)), start, end)

private fun tempDir() = File(System.getProperty("java.io.tmpdir"), "nto-${System.nanoTime()}").apply { mkdirs() }
