// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Assertions ported from OfferingHistoryTests, OfferingHistoryRepositoryTests,
// CourseInfoRepositoryTests, DegreePlanReorderingTests, and DegreeSubstitutionPresentationTests.

package app.termorganize

import android.Manifest
import android.content.Context
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import app.termorganize.core.banner.BannerSection
import app.termorganize.core.banner.CatalogBuilder
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseEquivalences
import app.termorganize.core.model.CourseInfo
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Meeting
import app.termorganize.core.model.Requisite
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.core.model.TermMenu
import app.termorganize.data.CatalogStore
import app.termorganize.logic.degree.CatalogProgram
import app.termorganize.logic.degree.CatalogYear
import app.termorganize.logic.degree.DegreeAudit
import app.termorganize.logic.degree.DegreeCourseRowInfo
import app.termorganize.logic.degree.DegreeNoteEntry
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanReordering
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.DegreeRenumbering
import app.termorganize.logic.degree.DegreeRequirementNotes
import app.termorganize.logic.degree.DegreeRequirementsFilter
import app.termorganize.logic.degree.DegreeSubstituteRows
import app.termorganize.logic.degree.DegreeSubstitutionEditing
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.degree.ProgramCatalogParser
import app.termorganize.logic.degree.ProgramRequirements
import app.termorganize.logic.degree.RequirementSubstitution
import app.termorganize.logic.degree.SubstitutionChange
import app.termorganize.logic.degree.equivalencePairId
import app.termorganize.logic.offering.CourseInfoFetching
import app.termorganize.logic.offering.CourseInfoLookup
import app.termorganize.logic.offering.CourseInfoRepository
import app.termorganize.logic.offering.OfferingFetching
import app.termorganize.logic.offering.OfferingHistory
import app.termorganize.logic.offering.OfferingHistoryRepository
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.schedule.ScheduleExporter
import app.termorganize.logic.schedule.calendarText
import app.termorganize.logic.schedule.contentValues
import app.termorganize.logic.schedule.events
import app.termorganize.logic.schedule.insertAll
import app.termorganize.logic.search.CatalogIndex
import app.termorganize.logic.search.CourseAppSearch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class OfferingHistoryTests {
    private val checked = listOf("202710", "202650", "202630", "202610", "202550", "202530", "202510")

    private fun history(offered: Map<String, List<String>>, checked: List<String>? = null, keys: List<String>? = null) = OfferingHistory(
        checkedTerms = checked ?: this.checked,
        offered = offered.mapKeys { key(it.key) }.mapValues { it.value.toSet() },
        checkedKeys = keys?.map { key(it) }?.toSet(),
    )

    @Test fun seasonsAndLikelyOfferings() {
        val history = history(
            mapOf(
                "CS 3386" to listOf("202630", "202530"),
                "CS 3800" to listOf("202710", "202610", "202510"),
                "CS 2500" to listOf("202650", "202550"),
            ),
        )
        assertEquals(setOf(PlanSeason.SPRING), history.seasons(key("CS 3386")))
        assertEquals(setOf(PlanSeason.SUMMER_FULL), history.seasons(key("CS 2500")))
        assertEquals(true, history.isLikelyOffered(key("CS 3386"), PlanSeason.SPRING))
        assertEquals(false, history.isLikelyOffered(key("CS 3386"), PlanSeason.FALL))
        assertEquals(true, history.isLikelyOffered(key("CS 2500"), PlanSeason.SUMMER_A))
        assertEquals(true, history.isLikelyOffered(key("CS 2500"), PlanSeason.SUMMER_B))
        assertEquals(false, history.isLikelyOffered(key("CS 3386"), PlanSeason.SUMMER_A))
        assertNull(history.isLikelyOffered(key("CS 3386"), PlanSeason.WINTER))
        assertEquals(false, history.isLikelyOffered(key("CS 9999"), PlanSeason.SPRING))
    }

    @Test fun unknownWhenNothingWasChecked() {
        assertNull(OfferingHistory.empty.isLikelyOffered(key("CS 3386"), PlanSeason.SPRING))
        assertEquals("Offering history unknown", OfferingHistory.empty.summary(key("CS 3386")))
        val partial = history(mapOf("CS 3386" to listOf("202630")), keys = listOf("CS 3386"))
        assertEquals(true, partial.isLikelyOffered(key("CS 3386"), PlanSeason.SPRING))
        assertNull(partial.isLikelyOffered(key("CS 3800"), PlanSeason.SPRING))
        assertEquals("Offering history unknown", partial.summary(key("CS 3800")))
        assertEquals("Offering history unknown", partial.summary(key("CS 3800"), PlanSeason.SPRING))
    }

    @Test fun summaries() {
        val history = history(
            mapOf(
                "CS 3386" to listOf("202630", "202530"),
                "CS 3800" to listOf("202710", "202610", "202510", "202630", "202530"),
                "CS 4100" to listOf("202530"),
                "CS 2500" to listOf("202650", "202550"),
                "CS 2510" to listOf("202650", "202630"),
            ),
        )
        assertEquals("Offered every spring", history.summary(key("CS 3386")))
        assertEquals("Offered every fall and spring", history.summary(key("CS 3800")))
        assertEquals("Last offered Spring 2025", history.summary(key("CS 4100")))
        assertEquals("Offered every summer", history.summary(key("CS 2500")))
        assertEquals("Last offered Summer 2026", history.summary(key("CS 2510")))
        assertEquals("Not offered in the last 2 years", history.summary(key("CS 9999")))
        val mixed = history(mapOf("CS 3800" to listOf("202710", "202610", "202510", "202630")))
        assertEquals("Offered every fall, and sometimes in the spring", mixed.summary(key("CS 3800")))
        assertEquals(
            "Offered every fall, and sometimes in the spring and summer",
            history(mapOf("CS 3800" to listOf("202710", "202610", "202510", "202630", "202550"))).summary(key("CS 3800")),
        )
        assertEquals("Offered every spring", history.summary(key("CS 3386"), PlanSeason.SPRING))
        assertEquals("Offered in Spring 2025", history.summary(key("CS 4100"), PlanSeason.SPRING))
        assertEquals("Not offered in a fall term in the last 2 years", history.summary(key("CS 3386"), PlanSeason.FALL))
        assertEquals("Offered every summer", history.summary(key("CS 2500"), PlanSeason.SUMMER_A))
    }

    @Test fun historiesMergeAndRoundTrip() {
        val first = history(mapOf("CS 3386" to listOf("202630")), checked = listOf("202710", "202630"), keys = listOf("CS 3386"))
        val second = history(mapOf("CS 3800" to listOf("202710")), checked = listOf("202710", "202610"), keys = listOf("CS 3800"))
        val merged = first.merging(second)
        assertEquals(listOf("202710", "202630", "202610"), merged.checkedTerms)
        assertEquals(setOf("202630"), merged.offered[key("CS 3386")])
        assertEquals(setOf("202710"), merged.offered[key("CS 3800")])
        assertEquals(setOf(key("CS 3386"), key("CS 3800")), merged.checkedKeys)
        assertNull(first.merging(history(emptyMap())).checkedKeys)
        for (original in listOf(merged, history(mapOf("CS 3386" to listOf("202630", "202530"))), OfferingHistory.empty)) {
            assertEquals(original, OfferingHistory.fromJson(original.toJson()))
        }
    }
}

class OfferingHistoryRepositoryTests {
    private val clock = TestClock()
    private val bannerTerms = terms(listOf("202710", "202655", "202650", "202632", "202630", "202610", "202560", "202550", "202540", "202530", "202510", "202460"))

    private fun repository(banner: FakeBanner, directory: File) =
        OfferingHistoryRepository(banner, directory, now = { clock.now })

    private fun banner(): FakeBanner {
        val banner = FakeBanner(clock, bannerTerms)
        for (term in listOf("202710", "202610", "202510")) banner.offer(term, listOf("CS 3800", "MATH 2331"))
        for (term in listOf("202630", "202530")) banner.offer(term, listOf("CS 3386", "MATH 2331"))
        banner.offer("202650", listOf("CS 2500"))
        return banner
    }

    @Test fun coversTwoYearsOfTermsSummersIncluded() {
        val window = OfferingHistoryRepository.window(bannerTerms.filter { it.college == Term.College.NEU }, 24).map { it.code }
        assertEquals(listOf("202710", "202650", "202630", "202610", "202560", "202550", "202540", "202530", "202510"), window)
        assertTrue(OfferingHistoryRepository.window(emptyList(), 24).isEmpty())
    }

    @Test fun asksOncePerTermAboutTheCoursesAndCachesThePast() = runBlocking {
        val directory = tempDir()
        val banner = banner()
        val repository = repository(banner, directory)
        val keys = listOf(key("CS 3386"), key("CS 3800"), key("MATH 2331"), key("CS 2500"))
        val history = repository.history(keys)
        assertEquals(listOf("202710", "202650", "202630", "202610", "202560", "202550", "202540", "202530", "202510"), history.checkedTerms)
        assertEquals(9, banner.offeredCalls.size)
        assertTrue(banner.offeredCalls.all { it.courses.toSet() == keys.toSet() })
        assertEquals(true, history.isLikelyOffered(key("CS 3386"), PlanSeason.SPRING))
        assertEquals(false, history.isLikelyOffered(key("CS 3386"), PlanSeason.FALL))
        assertEquals(true, history.isLikelyOffered(key("CS 3800"), PlanSeason.FALL))
        assertEquals(true, history.isLikelyOffered(key("CS 2500"), PlanSeason.SUMMER_A))
        assertEquals("Offered every fall and spring", history.summary(key("MATH 2331")))
        assertEquals(keys.toSet(), history.checkedKeys)
        banner.resetCalls()
        repository.history(keys)
        assertTrue(banner.offeredCalls.isEmpty())
        clock.advance(30)
        val relaunched = repository(banner, directory)
        relaunched.history(keys)
        assertEquals(listOf("202710"), banner.offeredCalls.map { it.term })
        banner.resetCalls()
        relaunched.history(listOf(key("PHYS 1151"), key("CS 3386")))
        assertEquals(9, banner.offeredCalls.size)
        assertTrue(banner.offeredCalls.all { it.courses == listOf(key("PHYS 1151")) })
    }

    @Test fun bigQuestionsAreBatchedAndKeepWhatWasFound() = runBlocking {
        val directory = tempDir()
        val banner = banner()
        val numbers = (1..12).map { "CS ${3010 + it}" }
        banner.offer("202630", numbers)
        val repository = repository(banner, directory)
        val keys = numbers.map { key(it) }
        banner.failQuestions("202630", key("CS 3022"))
        val partial = repository.history(keys)
        assertFalse(partial.checkedTerms.contains("202630"))
        assertTrue(partial.checkedTerms.contains("202530"))
        assertEquals(listOf(10, 2), banner.offeredCalls.filter { it.term == "202630" }.map { it.courses.size })
        assertEquals(listOf(10, 2), banner.offeredCalls.filter { it.term == "202710" }.map { it.courses.size })
        banner.failQuestions("202630", null)
        banner.resetCalls()
        val full = repository.history(keys)
        assertEquals(listOf("202630"), banner.offeredCalls.map { it.term })
        assertEquals(listOf(key("CS 3021"), key("CS 3022")), banner.offeredCalls.first().courses)
        assertTrue(full.checkedTerms.contains("202630"))
        assertTrue(keys.all { full.isLikelyOffered(it, PlanSeason.SPRING) == true })
    }

    @Test fun theCurrentTermIsRefreshedWeekly() = runBlocking {
        val directory = tempDir()
        val banner = banner()
        val repository = repository(banner, directory)
        repository.history(listOf(key("CS 3800")))
        banner.resetCalls()
        clock.advance(6)
        repository.history(listOf(key("CS 3800")))
        assertTrue(banner.offeredCalls.isEmpty())
        clock.advance(2)
        repository.history(listOf(key("CS 3800")))
        assertEquals(listOf("202710"), banner.offeredCalls.map { it.term })
    }

    @Test fun aTermReadBeforeItStartedIsReadAgain() = runBlocking {
        val directory = tempDir()
        val early = TestClock(1_780_000_000.0)
        val banner = FakeBanner(early, bannerTerms)
        banner.offer("202710", listOf("CS 3800"))
        OfferingHistoryRepository(banner, directory, now = { early.now }).history(listOf(key("CS 3800")))
        assertTrue(banner.offeredCalls.any { it.term == "202710" })
        early.advance(230)
        banner.resetCalls()
        banner.setTerms(terms(listOf("202730")) + bannerTerms)
        OfferingHistoryRepository(banner, directory, now = { early.now }).history(listOf(key("CS 3800")))
        val asked = banner.offeredCalls.map { it.term }.toSet()
        assertTrue(asked.contains("202730") && asked.contains("202710"))
        assertFalse(asked.contains("202630"))
        assertFalse(asked.contains("202610"))
        early.advance(60)
        banner.resetCalls()
        banner.setTerms(terms(listOf("202750", "202730")) + bannerTerms)
        OfferingHistoryRepository(banner, directory, now = { early.now }).history(listOf(key("CS 3800")))
        assertFalse(banner.offeredCalls.any { it.term == "202710" })
    }

    @Test fun aTermThatCouldNotBeReachedIsNotReportedAsNotOffered() = runBlocking {
        val directory = tempDir()
        val banner = banner()
        banner.fail("202630")
        val history = repository(banner, directory).history(listOf(key("CS 3386")))
        assertFalse(history.checkedTerms.contains("202630"))
        assertTrue(history.checkedTerms.contains("202530"))
        assertEquals(true, history.isLikelyOffered(key("CS 3386"), PlanSeason.SPRING))
        val offline = FakeBanner(clock, bannerTerms)
        for (term in bannerTerms) offline.fail(term.code)
        val none = OfferingHistoryRepository(offline, tempDir(), now = { clock.now }).history(listOf(key("CS 3386")))
        assertTrue(none.checkedTerms.isEmpty())
        assertNull(none.isLikelyOffered(key("CS 3386"), PlanSeason.SPRING))
    }

    @Test fun loadedCatalogsAnswerWithoutARequest() = runBlocking {
        val directory = tempDir()
        val banner = banner()
        val fall = index(listOf(course("CS 3386"), course("CS 3800")), listOf(section("1", "CS 3800")), "202710")
        val history = repository(banner, directory).history(listOf(key("CS 3386"), key("CS 3800")), listOf(fall))
        assertFalse(banner.offeredCalls.any { it.term == "202710" })
        assertTrue(history.checkedTerms.contains("202710"))
        assertEquals(true, history.offered[key("CS 3800")]?.contains("202710"))
        assertNotEquals(true, history.offered[key("CS 3386")]?.contains("202710"))
    }
}

class CourseInfoRepositoryTests {
    private val clock = TestClock()
    private val bannerTerms = terms(listOf("202710", "202655", "202650", "202632", "202630", "202610", "202560", "202550", "202540", "202530", "202510", "202460"))

    private fun repository(banner: FakeBanner, directory: File, maxTermsBack: Int = 8, concurrency: Int = 2) =
        CourseInfoRepository(banner, directory, now = { clock.now }, maxTermsBack = maxTermsBack, concurrency = concurrency)

    @Test fun searchesTheNewestTermThenEarlierFallsAndSprings() {
        assertEquals(listOf("202710", "202630", "202610", "202530"), CourseInfoRepository.catalogTerms(bannerTerms, 3))
        assertEquals(listOf("202710"), CourseInfoRepository.catalogTerms(bannerTerms, 0))
        assertEquals(
            listOf("202650", "202630", "202610"),
            CourseInfoRepository.catalogTerms(terms(listOf("202650", "202630", "202610")), 8),
        )
        assertTrue(CourseInfoRepository.catalogTerms(terms(listOf("202655", "202632")), 8).isEmpty())
    }

    @Test fun looksInTheLoadedCatalogsFirst() = runBlocking {
        val directory = tempDir()
        val banner = FakeBanner(clock, bannerTerms)
        banner.list(courseInfo("CS 3000", "202710"), "202710")
        banner.list(courseInfo("CS 4050", "202630"), "202630")
        val loaded = index(listOf(course("CS 3000", prereqs = requisite("CS 1800")), course("CS 4050", detailed = false)))
        val results = repository(banner, directory).lookup(listOf(key("CS 3000"), key("CS 4050")), listOf(loaded))
        val fromIndex = results[key("CS 3000")] as CourseInfoLookup.Found
        assertEquals(requisite("CS 1800"), fromIndex.info.prerequisites)
        assertEquals("202710", fromIndex.info.sourceTerm)
        assertEquals(false, fromIndex.info.isRetired)
        assertEquals(listOf(key("CS 4050"), key("CS 4050")), banner.entryCalls.map { it.key })
        assertEquals(listOf("202710", "202630"), banner.entryCalls.map { it.term })
        val fromBanner = results[key("CS 4050")] as CourseInfoLookup.Found
        assertEquals("202630", fromBanner.info.sourceTerm)
        assertEquals(true, fromBanner.info.isRetired)
    }

    @Test fun walksBackThroughPreviousYearsAndRemembersTheTerm() = runBlocking {
        val banner = FakeBanner(clock, bannerTerms)
        banner.list(courseInfo("CS 2510", "202530", equivalents = listOf(key("CS 2100"))), "202530")
        val result = repository(banner, tempDir()).lookup(key("CS 2510")) as CourseInfoLookup.Found
        assertEquals("202530", result.info.sourceTerm)
        assertEquals(true, result.info.isRetired)
        assertEquals(listOf(key("CS 2100")), result.info.equivalents)
        assertEquals(listOf("202710", "202630", "202610", "202530"), banner.entryCalls.map { it.term })
    }

    @Test fun foundCoursesAreCachedOnDiskForALongTime() = runBlocking {
        val directory = tempDir()
        val banner = FakeBanner(clock, bannerTerms)
        banner.list(courseInfo("CS 3386", "202710", prerequisites = requisite("CS 2386")), "202710")
        repository(banner, directory).lookup(key("CS 3386"))
        assertEquals(1, banner.entryCalls.size)
        clock.advance(30)
        val second = repository(FakeBanner(clock, bannerTerms), directory)
        val cached = (second.cachedLookup(key("CS 3386")) as CourseInfoLookup.Found).info
        assertEquals(requisite("CS 2386"), cached.prerequisites)
        assertEquals(false, cached.isRetired)
        assertEquals(CourseInfoLookup.Found(cached), second.lookup(key("CS 3386")))
        assertEquals(1, banner.entryCalls.size)
        clock.advance(200)
        val failing = FakeBanner(clock, bannerTerms)
        failing.fail("202710")
        val third = repository(failing, directory)
        assertNull(third.cachedLookup(key("CS 3386")))
        val stale = third.lookup(key("CS 3386")) as CourseInfoLookup.Found
        assertEquals(requisite("CS 2386"), stale.info.prerequisites)
        banner.list(courseInfo("CS 3386", "202710", prerequisites = requisite("CS 2000")), "202710")
        val fresh = repository(banner, directory).lookup(key("CS 3386")) as CourseInfoLookup.Found
        assertEquals(requisite("CS 2000"), fresh.info.prerequisites)
    }

    @Test fun missingCoursesAreRememberedForAWeek() = runBlocking {
        val directory = tempDir()
        val banner = FakeBanner(clock, bannerTerms)
        val repository = repository(banner, directory, maxTermsBack = 3)
        assertEquals(CourseInfoLookup.NotFound, repository.lookup(key("CS 2963")))
        assertEquals(listOf("202710", "202630", "202610", "202530"), banner.entryCalls.map { it.term })
        banner.resetCalls()
        clock.advance(6)
        assertEquals(CourseInfoLookup.NotFound, repository.lookup(key("CS 2963")))
        assertTrue(banner.entryCalls.isEmpty())
        assertEquals(CourseInfoLookup.NotFound, this@CourseInfoRepositoryTests.repository(banner, directory).cachedLookup(key("CS 2963")))
        clock.advance(2)
        assertNull(repository.cachedLookup(key("CS 2963")))
        assertEquals(CourseInfoLookup.NotFound, repository.lookup(key("CS 2963")))
        assertEquals(4, banner.entryCalls.size)
        clock.advance(8)
        banner.list(courseInfo("CS 2963", "202710"), "202710")
        assertTrue(repository.lookup(key("CS 2963")) is CourseInfoLookup.Found)
    }

    @Test fun failuresAreNotCached() = runBlocking {
        val banner = FakeBanner(clock, bannerTerms)
        banner.list(courseInfo("CS 4050", "202630"), "202630")
        banner.fail("202630")
        val repository = repository(banner, tempDir())
        assertEquals(CourseInfoLookup.Failed, repository.lookup(key("CS 4050")))
        assertNull(repository.cachedLookup(key("CS 4050")))
        banner.fail("202630", false)
        val info = repository.lookup(key("CS 4050")) as CourseInfoLookup.Found
        assertEquals("202630", info.info.sourceTerm)
    }

    @Test fun termListFailureIsAFailureToo() = runBlocking {
        assertEquals(CourseInfoLookup.Failed, repository(FakeBanner(clock, emptyList()), tempDir()).lookup(key("CS 3386")))
    }

    @Test fun fewCoursesAreLookedUpAtATime() = runBlocking {
        val banner = FakeBanner(clock, bannerTerms)
        banner.delayMillis = 20
        val codes = (1..6).map { "CS ${3010 + it}" }
        for (code in codes) banner.list(courseInfo(code, "202710"), "202710")
        val results = repository(banner, tempDir(), concurrency = 2).lookup(codes.map { key(it) })
        assertEquals(6, results.size)
        assertTrue(results.values.all { it is CourseInfoLookup.Found })
        assertEquals(2, banner.maxInFlight)
        banner.resetCalls()
        repository(banner, tempDir()).lookup(listOf(key("CS 9001"), key("CS 9001")))
        assertEquals(1, banner.entryCalls.count { it.term == "202710" })
    }
}

class DegreePlanReorderingTests {
    private val fall = listOf("202710", "202610", "202510")
    private val spring = listOf("202630", "202530")
    private val allTerms = listOf("202710", "202650", "202630", "202610", "202550", "202530", "202510")

    private fun planned(code: String, credits: Double = 4.0) = DegreePlanCourse.of(key(code), code, credits)
    private fun semester(season: PlanSeason, year: Int, codes: List<String>, credits: Double = 4.0) =
        DegreePlanTerm(season = season, year = year, courses = codes.map { planned(it, credits) })

    private fun plan(terms: List<DegreePlanTerm>, completed: List<String> = emptyList()) = DegreePlan(
        name = "Plan",
        completed = completed.map { planned(it) },
        terms = terms,
    )

    private fun taught(codes: List<String>, terms: List<String>) = codes.associateWith { terms }
    private fun history(offered: Map<String, List<String>>, keys: List<String>? = null) = OfferingHistory(
        checkedTerms = allTerms,
        offered = offered.mapKeys { key(it.key) }.mapValues { it.value.toSet() },
        checkedKeys = keys?.map { key(it) }?.toSet(),
    )

    private fun none(@Suppress("UNUSED_PARAMETER") course: CourseKey) = app.termorganize.logic.degree.PrerequisiteLookup.None
    private fun needs(table: Map<String, Requisite>): (CourseKey) -> app.termorganize.logic.degree.PrerequisiteLookup = { course ->
        val requisite = table[course.description]
        if (requisite == null) app.termorganize.logic.degree.PrerequisiteLookup.None
        else app.termorganize.logic.degree.PrerequisiteLookup.Needs(requisite)
    }

    private fun suggestions(
        plan: DegreePlan,
        term: Int = 0,
        blocking: List<String>,
        history: OfferingHistory,
        prerequisites: (CourseKey) -> app.termorganize.logic.degree.PrerequisiteLookup = { app.termorganize.logic.degree.PrerequisiteLookup.None },
        equivalences: CourseEquivalences = CourseEquivalences.none,
        maxCredits: Double = 20.0,
    ): List<DegreePlanReordering.Suggestion> {
        val sorted = plan.sorted()
        return DegreePlanReordering.suggestions(
            plan, sorted.terms[term].id, blocking.map { key(it) }, history, prerequisites, equivalences, maxCredits,
        )
    }

    private fun titles(suggestions: List<DegreePlanReordering.Suggestion>) = suggestions.map { it.title }

    @Test fun aCourseOnlyMovesToASeasonItIsTaughtIn() {
        val plan = plan(
            listOf(
                semester(PlanSeason.FALL, 2026, listOf("CS 3386", "CS 3800")),
                semester(PlanSeason.SPRING, 2027, listOf("CS 4100")),
                semester(PlanSeason.FALL, 2027, emptyList()),
                semester(PlanSeason.SPRING, 2028, emptyList()),
            ),
        )
        val history = history(mapOf("CS 3386" to spring, "CS 3800" to fall, "CS 4100" to fall + spring))
        val found = suggestions(plan, blocking = listOf("CS 3386"), history = history)
        assertEquals("Move CS 3386 to Spring 2027", found.first().title)
        assertEquals(
            listOf(DegreePlanReordering.Move(key("CS 3386"), plan.terms[0].id, plan.terms[1].id)),
            found.first().moves,
        )
        assertFalse(titles(found).any { it.contains("Fall 2027") })
        assertEquals(
            listOf("Move CS 3386 to Spring 2027", "Move CS 3386 to Spring 2028"),
            titles(found.filter { it.moves.size == 1 }),
        )
        assertEquals("Offered every spring", found.first().reasons.first())
        assertEquals("Spring 2027 would have 8 credits", found.first().reasons.last())
        assertNull(found.first().addsSemester)
    }

    @Test fun aCourseNeverTaughtAnywhereHasNoSuggestions() {
        val plan = plan(listOf(semester(PlanSeason.FALL, 2026, listOf("CS 3386")), semester(PlanSeason.SPRING, 2027, emptyList())))
        assertTrue(suggestions(plan, blocking = listOf("CS 3386"), history = history(emptyMap())).isEmpty())
    }

    @Test fun unknownHistoryIsAllowedButRankedLower() {
        val plan = plan(listOf(semester(PlanSeason.FALL, 2026, listOf("CS 9001", "CS 3386")), semester(PlanSeason.SPRING, 2027, emptyList())))
        val history = history(mapOf("CS 3386" to spring), keys = listOf("CS 3386"))
        val found = suggestions(plan, blocking = listOf("CS 9001", "CS 3386"), history = history)
        assertEquals(listOf("Move CS 3386 to Spring 2027", "Move CS 9001 to Spring 2027"), titles(found))
        assertEquals(2, found.size)
        assertEquals("Offering history unknown", found[1].reasons.first())
        assertEquals("Offered every spring", found[0].reasons.first())
    }

    @Test fun aCourseNeedsItsPrerequisitesBeforeTheSemester() {
        val plan = plan(
            listOf(
                semester(PlanSeason.FALL, 2026, listOf("CS 3386")),
                semester(PlanSeason.SPRING, 2027, listOf("CS 2386")),
                semester(PlanSeason.FALL, 2027, emptyList()),
                semester(PlanSeason.SPRING, 2028, emptyList()),
            ),
        )
        val history = history(mapOf("CS 3386" to spring, "CS 2386" to fall + spring))
        val found = suggestions(plan, blocking = listOf("CS 3386"), history = history, prerequisites = needs(mapOf("CS 3386" to requisite("CS 2386"))))
        assertEquals(listOf("Move CS 3386 to Spring 2028"), titles(found.filter { it.moves.size == 1 }))
        assertTrue(found.filter { it.moves.size == 1 }.first().reasons.contains("Prerequisites met by then"))
        assertTrue(titles(found).contains("Move CS 3386 to Spring 2027 and CS 2386 to Fall 2026"))
    }

    @Test fun nothingThatNeedsACourseMayBePlannedAtOrBeforeItsNewSemester() {
        val plan = plan(
            listOf(
                semester(PlanSeason.FALL, 2026, listOf("CS 3386")),
                semester(PlanSeason.SPRING, 2027, emptyList()),
                semester(PlanSeason.FALL, 2027, emptyList()),
                semester(PlanSeason.SPRING, 2028, listOf("CS 4500")),
            ),
        )
        val history = history(mapOf("CS 3386" to spring, "CS 4500" to spring))
        val found = suggestions(plan, blocking = listOf("CS 3386"), history = history, prerequisites = needs(mapOf("CS 4500" to requisite("CS 3386"))))
        assertEquals(listOf("Move CS 3386 to Spring 2027"), titles(found))
        val tight = this.plan(
            listOf(
                semester(PlanSeason.FALL, 2026, listOf("CS 3386")),
                semester(PlanSeason.SPRING, 2027, listOf("CS 4500")),
                semester(PlanSeason.FALL, 2027, emptyList()),
                semester(PlanSeason.SPRING, 2028, emptyList()),
            ),
        )
        assertTrue(suggestions(tight, blocking = listOf("CS 3386"), history = history, prerequisites = needs(mapOf("CS 4500" to requisite("CS 3386")))).isEmpty())
    }

    @Test fun equivalentCoursesCountForPrerequisites() {
        val plan = plan(listOf(semester(PlanSeason.FALL, 2026, listOf("CS 3386")), semester(PlanSeason.SPRING, 2027, emptyList())), completed = listOf("CS 2510"))
        val history = history(mapOf("CS 3386" to spring))
        val prerequisites = needs(mapOf("CS 3386" to requisite("CS 2100")))
        assertTrue(suggestions(plan, blocking = listOf("CS 3386"), history = history, prerequisites = prerequisites).isEmpty())
        val renumbered = CourseEquivalences.fromPairs(listOf(key("CS 2100") to key("CS 2510")))
        val found = suggestions(plan, blocking = listOf("CS 3386"), history = history, prerequisites = prerequisites, equivalences = renumbered)
        assertEquals(listOf("Move CS 3386 to Spring 2027"), titles(found))
        assertTrue(found.first().reasons.contains("Prerequisites still met"))
        val declared = plan.declareEquivalence(key("CS 2510"), key("CS 2100"))
        assertEquals(1, suggestions(declared, blocking = listOf("CS 3386"), history = history, prerequisites = prerequisites).size)
    }

    @Test fun unknownPrerequisitesAreAllowedButRankedLower() {
        val plan = plan(listOf(semester(PlanSeason.FALL, 2026, listOf("CS 3386", "CS 3387")), semester(PlanSeason.SPRING, 2027, emptyList())))
        val history = history(mapOf("CS 3386" to spring, "CS 3387" to spring))
        val prerequisites: (CourseKey) -> app.termorganize.logic.degree.PrerequisiteLookup = { course ->
            if (course == key("CS 3386")) app.termorganize.logic.degree.PrerequisiteLookup.Unknown
            else app.termorganize.logic.degree.PrerequisiteLookup.None
        }
        val found = suggestions(plan, blocking = listOf("CS 3386", "CS 3387"), history = history, prerequisites = prerequisites)
        assertEquals(listOf("Move CS 3387 to Spring 2027", "Move CS 3386 to Spring 2027"), titles(found))
        assertEquals(2, found.size)
        assertTrue(found[1].reasons.contains("Prerequisites couldn’t be checked"))
    }

    @Test fun aSemesterStaysUnderTheCreditLimit() {
        val full = listOf("CS 4100", "CS 4200", "CS 4300", "CS 4400", "CS 4500")
        val plan = plan(
            listOf(
                semester(PlanSeason.FALL, 2026, listOf("CS 3386")),
                semester(PlanSeason.SPRING, 2027, full),
                semester(PlanSeason.FALL, 2027, emptyList()),
                semester(PlanSeason.SPRING, 2028, emptyList()),
            ),
        )
        val history = history(mapOf("CS 3386" to spring) + taught(full, fall + spring))
        val found = suggestions(plan, blocking = listOf("CS 3386"), history = history)
        assertEquals(listOf("Move CS 3386 to Spring 2028"), titles(found.filter { it.moves.size == 1 }))
        val relaxed = suggestions(plan, blocking = listOf("CS 3386"), history = history, maxCredits = 24.0)
        assertEquals("Move CS 3386 to Spring 2027", titles(relaxed.filter { it.moves.size == 1 }).first())
        assertEquals("Spring 2027 would have 24 credits", relaxed.first().reasons.last())
    }

    @Test fun swapsTradeACourseThatIsTaughtNow() {
        val full = listOf("CS 3800", "CS 4200", "CS 4300", "CS 4400", "CS 4500")
        val plan = plan(listOf(semester(PlanSeason.FALL, 2026, listOf("CS 3386")), semester(PlanSeason.SPRING, 2027, full)))
        val history = history(mapOf("CS 3386" to spring, "CS 3800" to fall + spring) + taught(full.drop(1), spring))
        val found = suggestions(plan, blocking = listOf("CS 3386"), history = history)
        assertEquals(listOf("Move CS 3386 to Spring 2027 and CS 3800 to Fall 2026"), titles(found))
        val swap = found.first()
        assertEquals(
            listOf(
                DegreePlanReordering.Move(key("CS 3386"), plan.terms[0].id, plan.terms[1].id),
                DegreePlanReordering.Move(key("CS 3800"), plan.terms[1].id, plan.terms[0].id),
            ),
            swap.moves,
        )
        assertTrue(swap.reasons.contains("CS 3386: offered every spring"))
        assertTrue(swap.reasons.contains("CS 3800: offered every fall"))
        assertTrue(swap.reasons.contains("Fall 2026 would have 4 credits"))
        assertTrue(swap.reasons.contains("Spring 2027 would have 20 credits"))
    }

    @Test fun swapsRankAfterSingleMoves() {
        val plan = plan(
            listOf(
                semester(PlanSeason.FALL, 2026, listOf("CS 3386", "CS 3800")),
                semester(PlanSeason.SPRING, 2027, listOf("CS 4100")),
                semester(PlanSeason.SPRING, 2028, emptyList()),
            ),
        )
        val history = history(mapOf("CS 3386" to spring, "CS 3800" to fall, "CS 4100" to fall + spring))
        val found = suggestions(plan, blocking = listOf("CS 3386"), history = history)
        assertEquals(
            listOf(
                "Move CS 3386 to Spring 2027",
                "Move CS 3386 to Spring 2028",
                "Move CS 3386 to Spring 2027 and CS 4100 to Fall 2026",
            ),
            titles(found),
        )
        assertEquals(listOf(1, 1, 2), found.map { it.moves.size })
    }

    @Test fun swapPartnersMustBeTaughtInTheSemesterTheyMoveTo() {
        val full = listOf("CS 4100", "CS 4200", "CS 4300", "CS 4400", "CS 4500")
        val plan = plan(listOf(semester(PlanSeason.FALL, 2026, listOf("CS 3386")), semester(PlanSeason.SPRING, 2027, full)))
        val history = history(mapOf("CS 3386" to spring) + taught(full, spring))
        assertTrue(suggestions(plan, blocking = listOf("CS 3386"), history = history).none { it.moves.size == 2 })
    }

    @Test fun swapPartnersKeepTheirOwnPrerequisites() {
        val plan = plan(listOf(semester(PlanSeason.FALL, 2026, listOf("CS 3386")), semester(PlanSeason.SPRING, 2027, listOf("CS 3800", "CS 3700"))))
        val history = history(mapOf("CS 3386" to spring, "CS 3800" to fall + spring, "CS 3700" to fall + spring))
        val found = suggestions(
            plan,
            blocking = listOf("CS 3386"),
            history = history,
            prerequisites = needs(mapOf("CS 3800" to requisite("CS 3700"))),
            maxCredits = 8.0,
        )
        assertEquals(listOf("Move CS 3386 to Spring 2027 and CS 3700 to Fall 2026"), titles(found))
    }

    @Test fun aSemesterIsAddedWhenNoExistingOneWorks() {
        val plan = plan(listOf(semester(PlanSeason.FALL, 2026, listOf("CS 3386")), semester(PlanSeason.FALL, 2027, listOf("CS 3800"))))
        val history = history(mapOf("CS 3386" to spring, "CS 3800" to fall))
        val found = suggestions(plan, blocking = listOf("CS 3386"), history = history)
        assertEquals(1, found.size)
        val suggestion = found[0]
        assertEquals(PlanSeason.SPRING, suggestion.addsSemester?.season)
        assertEquals(2028, suggestion.addsSemester?.year)
        assertEquals("Move CS 3386 to a new Spring 2028 semester", suggestion.title)
        assertEquals(listOf(DegreePlanReordering.Move(key("CS 3386"), plan.terms[0].id, suggestion.addsSemester!!.id)), suggestion.moves)
        assertEquals("Adds Spring 2028 to your plan, with 4 credits", suggestion.reasons.last())
        val again = suggestions(plan, blocking = listOf("CS 3386"), history = history)
        assertEquals(suggestion.id, again.first().id)
        assertEquals(suggestion.addsSemester?.id, again.first().addsSemester?.id)
    }

    @Test fun theAddedSemesterIsInASeasonTheCourseIsTaughtIn() {
        val plan = plan(listOf(semester(PlanSeason.SPRING, 2027, listOf("CS 3800"))))
        val found = suggestions(plan, blocking = listOf("CS 3800"), history = history(mapOf("CS 3800" to fall)))
        assertEquals(PlanSeason.FALL, found.first().addsSemester?.season)
        assertEquals(2027, found.first().addsSemester?.year)
    }

    @Test fun applyingAMoveChangesThePlan() {
        var plan = plan(
            listOf(
                semester(PlanSeason.FALL, 2026, listOf("CS 3386", "CS 3800")),
                semester(PlanSeason.SPRING, 2027, listOf("CS 4100")),
                semester(PlanSeason.SPRING, 2028, emptyList()),
            ),
        )
        val found = suggestions(plan, blocking = listOf("CS 3386"), history = history(mapOf("CS 3386" to spring, "CS 3800" to fall, "CS 4100" to fall + spring)))
        val move = found.first()
        val moved = plan.terms[0].courses[0]
        plan = DegreePlanReordering.apply(move, plan)
        assertEquals(listOf("CS 3800"), plan.terms[0].courses.map { it.label })
        assertEquals(listOf("CS 4100", "CS 3386"), plan.terms[1].courses.map { it.label })
        assertEquals(moved.id, plan.terms[1].courses.last().id)
        assertEquals(3, plan.terms.size)
        val before = plan
        plan = DegreePlanReordering.apply(move, plan)
        assertEquals(before, plan)
    }

    @Test fun applyingASwapMovesBothCourses() {
        val full = listOf("CS 3800", "CS 4200", "CS 4300", "CS 4400", "CS 4500")
        var plan = plan(listOf(semester(PlanSeason.FALL, 2026, listOf("CS 3386")), semester(PlanSeason.SPRING, 2027, full)))
        val swap = suggestions(plan, blocking = listOf("CS 3386"), history = history(mapOf("CS 3386" to spring, "CS 3800" to fall) + taught(full.drop(1), spring))).first()
        plan = DegreePlanReordering.apply(swap, plan)
        assertEquals(listOf("CS 3800"), plan.terms[0].courses.map { it.label })
        assertEquals(listOf("CS 4200", "CS 4300", "CS 4400", "CS 4500", "CS 3386"), plan.terms[1].courses.map { it.label })
    }

    @Test fun applyingAddsTheNewSemester() {
        val original = plan(listOf(semester(PlanSeason.FALL, 2026, listOf("CS 3386", "CS 3800")), semester(PlanSeason.FALL, 2027, emptyList())))
        val suggestion = suggestions(original, blocking = listOf("CS 3386"), history = history(mapOf("CS 3386" to spring, "CS 3800" to fall))).first()
        val plan = DegreePlanReordering.apply(suggestion, original)
        assertEquals(listOf("Fall 2026", "Fall 2027", "Spring 2028"), plan.terms.map { it.name })
        assertEquals(listOf("CS 3386"), plan.terms.last().courses.map { it.label })
        assertEquals(listOf("CS 3800"), plan.terms[0].courses.map { it.label })
        val other = DegreePlanReordering.apply(suggestion, original.copy(terms = original.terms + semester(PlanSeason.SPRING, 2028, listOf("CS 4100"))))
        assertEquals(3, other.terms.size)
        assertEquals(listOf("CS 4100", "CS 3386"), other.terms.last().courses.map { it.label })
    }

    @Test fun staleOrUnknownRequestsAreIgnored() {
        val plan = plan(listOf(semester(PlanSeason.FALL, 2026, listOf("CS 3386")), semester(PlanSeason.SPRING, 2027, emptyList())))
        val history = history(mapOf("CS 3386" to spring))
        assertTrue(DegreePlanReordering.suggestions(plan, "missing", listOf(key("CS 3386")), history, ::none).isEmpty())
        assertTrue(suggestions(plan, blocking = listOf("CS 1234"), history = history).isEmpty())
        assertTrue(suggestions(plan, blocking = emptyList(), history = history).isEmpty())
    }

    @Test fun atMostAFewSuggestionsAreReturned() {
        val plan = plan(
            listOf(
                semester(PlanSeason.FALL, 2026, listOf("CS 3386")),
                semester(PlanSeason.SPRING, 2027, emptyList()),
                semester(PlanSeason.SPRING, 2028, emptyList()),
                semester(PlanSeason.SPRING, 2029, emptyList()),
                semester(PlanSeason.SPRING, 2030, emptyList()),
                semester(PlanSeason.SPRING, 2031, emptyList()),
                semester(PlanSeason.SPRING, 2032, emptyList()),
            ),
        )
        val found = suggestions(plan, blocking = listOf("CS 3386"), history = history(mapOf("CS 3386" to spring)))
        assertEquals(DegreePlanReordering.MAX_SUGGESTIONS, found.size)
        assertEquals("Move CS 3386 to Spring 2027", titles(found).first())
    }
}

class DegreeSubstitutionPresentationTests {
    private val cs3000 = CourseKey("CS", "3000")
    private val cs3001 = CourseKey("CS", "3001")
    private val cs4535 = CourseKey("CS", "4535")
    private val cs4973 = CourseKey("CS", "4973")
    private val cy3740 = CourseKey("CY", "3740")

    private fun rule(label: String, requirements: ProgramRequirements) = requirements.allRules.first { it.label == label }

    private fun renumberedPlan(requirements: ProgramRequirements): Triple<DegreePlan, RequirementSubstitution, RequirementSubstitution> {
        val base = DegreeAuditTests.plan()
        val plan = base.copy(completed = base.completed.filter { it.key != cs3000 } + DegreeAuditTests.course("CS 3001"))
        val required = rule("CS 3000", requirements)
        val security = rule("Complete one of the following:", requirements)
        val renumbering = RequirementSubstitution(ruleID = required.id, ruleTitle = "CS 3000", change = SubstitutionChange.CountCourse(cs3001), note = "Approved")
        val waiver = RequirementSubstitution(ruleID = security.id, ruleTitle = "Security Course", change = SubstitutionChange.Waive)
        return Triple(plan.copy(substitutions = listOf(renumbering, waiver)), renumbering, waiver)
    }

    @Test fun onlyASubstitutionForOneNamedCourseIsARenumbering() {
        val requirements = DegreeAuditTests.requirements()
        val (plan, renumbering, waiver) = renumberedPlan(requirements)
        val either = rule("CS 4500 or CS 4530", requirements)
        val other = plan.copy(
            substitutions = plan.substitutions + listOf(
                RequirementSubstitution(ruleID = either.id, ruleTitle = "CS 4500 or CS 4530", change = SubstitutionChange.CountCourse(cs4535)),
                RequirementSubstitution(ruleID = renumbering.ruleID, ruleTitle = "CS 3000", change = SubstitutionChange.CountCourse(cs3000)),
            ),
        )
        assertEquals(setOf(renumbering.id), DegreeRenumbering.renumberable(other, requirements))
        assertFalse(DegreeRenumbering.renumberable(other, requirements).contains(waiver.id))
        assertEquals(listOf(cs3000, cs3001), DegreeRenumbering.coursesToLookUp(other, requirements))
        assertTrue(DegreeRenumbering.coursesToLookUp(plan.copy(substitutions = listOf(waiver)), requirements).isEmpty())
    }

    @Test fun turnsASubstitutionIntoTheEquivalenceItAmountsTo() {
        val requirements = DegreeAuditTests.requirements()
        val (plan, renumbering, waiver) = renumberedPlan(requirements)
        val converted = DegreeRenumbering.convert(plan, renumbering, requirements)!!
        val change = converted.second
        assertEquals(cs3001, change.course)
        assertEquals(cs3000, change.countsAs)
        assertEquals("CS 3001 now counts as CS 3000", change.message)
        assertTrue(change.canUndo)
        val after = converted.first
        assertEquals(listOf(waiver.id), after.substitutions.map { it.id })
        assertEquals(listOf(cs3001), after.courseEquivalences.map { it.course })
        assertEquals(cs3000, after.courseEquivalences.first().countsAs)
        assertEquals("Renumbered", after.courseEquivalences.first().note)
        val audit = DegreeAudit.run(after, requirements)
        val result = audit.result(renumbering.ruleID)!!
        assertEquals(DegreeAudit.Status.COMPLETE, result.status)
        assertEquals(listOf(cs3001), result.used.map { it.key })
        assertEquals(cs3000, result.used.first().satisfies)
        assertFalse(result.used.first().bySubstitution)
        assertNull(DegreeRenumbering.convert(after, renumbering, requirements))
    }

    @Test fun undoPutsTheSubstitutionBackWhereItWas() {
        val requirements = DegreeAuditTests.requirements()
        val (plan, renumbering, waiver) = renumberedPlan(requirements)
        val converted = DegreeRenumbering.convert(plan, renumbering, requirements)!!
        val extra = RequirementSubstitution(ruleID = "elsewhere", ruleTitle = "Elsewhere", change = SubstitutionChange.Waive)
        val changed = converted.first.copy(substitutions = converted.first.substitutions + extra)
        val restored = converted.second.undo(changed)
        assertEquals(listOf(renumbering.id, waiver.id, extra.id), restored.substitutions.map { it.id })
        assertTrue(restored.courseEquivalences.isEmpty())
        val twice = converted.second.undo(restored)
        assertEquals(3, twice.substitutions.size)
    }

    @Test fun declaresARenumberingWithoutASubstitution() {
        val plan = DegreePlan(name = "Plan")
        assertNull(DegreeRenumbering.declare(plan, cs3000, cs3000))
        val declared = DegreeRenumbering.declare(plan, cs3001, cs3000)!!
        assertNull(declared.second.substitution)
        assertTrue(declared.second.canUndo)
        assertEquals(listOf("Renumbered"), declared.first.courseEquivalences.map { it.note })
        val again = DegreeRenumbering.declare(declared.first, cs3001, cs3000)!!
        assertFalse(again.second.canUndo)
        assertEquals(1, again.first.courseEquivalences.size)
        assertTrue(declared.second.undo(declared.first).courseEquivalences.isEmpty())
    }

    @Test fun asksAboutARetiredRequiredCourseOnly() {
        val requirements = DegreeAuditTests.requirements()
        val setup = renumberedPlan(requirements)
        val retired = DegreeSubstitutionEditing.likelyRenumberings(setup.first, requirements) { it == cs3000 }
        assertEquals(listOf(setup.second.id), retired.map { it.substitution.id })
        assertEquals("Is CS 3001 the new number of CS 3000?", retired.first().question)
        assertTrue(DegreeSubstitutionEditing.likelyRenumberings(setup.first, requirements) { null }.isEmpty())
        assertTrue(DegreeSubstitutionEditing.likelyRenumberings(setup.first, requirements) { false }.isEmpty())
        val declared = setup.first.declareEquivalence(cs3001, cs3000)
        assertTrue(DegreeSubstitutionEditing.likelyRenumberings(declared, requirements) { it == cs3000 }.isEmpty())
    }

    @Test fun aKeptSubstitutionIsNotAskedAboutAgain() {
        val requirements = DegreeAuditTests.requirements()
        val (plan, renumbering, waiver) = renumberedPlan(requirements)
        val retired: (CourseKey) -> Boolean? = { it == cs3000 }
        val asked = DegreeRenumbering.likely(plan, requirements, emptySet(), retired)
        assertEquals(setOf(renumbering.id), asked.keys)
        assertNull(asked[waiver.id])
        assertTrue(DegreeRenumbering.likely(plan, requirements, setOf(equivalencePairId(cs3001, cs3000)), retired).isEmpty())
        assertEquals(1, DegreeRenumbering.likely(plan, requirements, setOf(equivalencePairId(cs3001, cs4535)), retired).size)
        assertTrue(DegreeRenumbering.likely(plan, requirements, emptySet()) { null }.isEmpty())
    }

    @Test fun aSubstituteSitsUnderTheRequirementItReplaces() {
        val requirements = DegreeAuditTests.requirements()
        val either = rule("CS 4500 or CS 4530", requirements)
        val security = rule("Complete one of the following:", requirements)
        val plan = DegreeAuditTests.plan().copy(
            substitutions = listOf(
                RequirementSubstitution(ruleID = either.id, ruleTitle = "CS 4500 or CS 4530", change = SubstitutionChange.CountCourse(cs4535), note = "Approved by advising"),
                RequirementSubstitution(ruleID = security.id, ruleTitle = "Security Course", change = SubstitutionChange.CountCourse(cs4973)),
            ),
        )
        val audit = DegreeAudit.run(plan, requirements)
        val eitherResult = audit.result(either.id)!!
        val split = DegreeSubstituteRows.split(eitherResult, either.listedCourses.toSet())
        assertEquals(listOf(cs4535), split.substitutes.map { it.key })
        assertTrue(split.others.isEmpty())
        assertEquals("CS 4500 or CS 4530", DegreeSubstituteRows.requirementName(eitherResult, requirements))
        assertEquals("Approved by advising", DegreeSubstituteRows.note(cs4535, eitherResult))
        assertEquals(
            "Counts for CS 4500 or CS 4530 by substitution · Approved by advising",
            DegreeSubstituteRows.caption(cs4535, eitherResult, requirements),
        )
        val securityResult = audit.result(security.id)!!
        assertEquals(listOf(cs4973), DegreeSubstituteRows.split(securityResult, security.listedCourses.toSet()).substitutes.map { it.key })
        assertEquals("Counts for Security Course by substitution", DegreeSubstituteRows.caption(cs4973, securityResult, requirements))
        val info = DegreeCourseRowInfo(bySubstitution = true, caption = "ignored")
        assertEquals("Approved by advising", DegreeSubstituteRows.caption(info, cs4535, eitherResult, requirements, isChild = true))
        assertEquals(
            "Counts for CS 4500 or CS 4530 by substitution · Approved by advising",
            DegreeSubstituteRows.caption(info, cs4535, eitherResult, requirements, isChild = false),
        )
        val plain = DegreeCourseRowInfo(caption = "No longer offered")
        assertEquals("No longer offered", DegreeSubstituteRows.caption(plain, cs4535, eitherResult, requirements, isChild = false))
        val listed = DegreeAuditTests.plan().copy(
            substitutions = listOf(RequirementSubstitution(ruleID = security.id, ruleTitle = "Security Course", change = SubstitutionChange.CountCourse(cy3740))),
            completed = DegreeAuditTests.plan().completed + DegreeAuditTests.course("CY 3740"),
        )
        val listedResult = DegreeAudit.run(listed, requirements).result(security.id)!!
        assertTrue(DegreeSubstituteRows.split(listedResult, security.listedCourses.toSet()).substitutes.isEmpty())
    }

    @Test fun showsEachSentenceWithItsCheckForTheFilter() {
        val (done, requirements) = notesAudit(listOf("CS 3000", "CS 3200", "CS 3500", "COOP 3946"))
        val all = entries(DegreeRequirementsFilter.ALL, done, requirements)
        assertEquals(setOf("GPA Requirement", "Credit Requirement", "NUpath Requirements Satisfied", "Program Requirement"), all.keys)
        assertNull(all["GPA Requirement"]?.check)
        assertEquals(DegreeAudit.Status.COMPLETE, all["Credit Requirement"]?.check?.status)
        assertEquals("COOP 3946 completed", all["NUpath Requirements Satisfied"]?.check?.detail)
        assertTrue(all["Program Requirement"]?.check != null)
        assertEquals(
            setOf("Credit Requirement", "NUpath Requirements Satisfied", "Program Requirement"),
            entries(DegreeRequirementsFilter.DONE, done, requirements).keys,
        )
        assertTrue(entries(DegreeRequirementsFilter.REMAINING, done, requirements).isEmpty())
        val (short, shortRequirements) = notesAudit(listOf("CS 3000"), listOf("CS 3200"))
        val remaining = entries(DegreeRequirementsFilter.REMAINING, short, shortRequirements)
        assertEquals(setOf("Credit Requirement", "NUpath Requirements Satisfied", "Program Requirement"), remaining.keys)
        assertEquals("No co-op in the plan", remaining["NUpath Requirements Satisfied"]?.check?.detail)
        assertNull(remaining["GPA Requirement"])
        assertNull(entries(DegreeRequirementsFilter.DONE, short, shortRequirements)["NUpath Requirements Satisfied"])
    }

    @Test fun aCheckThatDoesNotCountYetShowsInAllOnly() {
        var check = DegreeAudit.NoteCheck("nupath-requirements", "NUpath", DegreeAudit.NoteCheck.Kind.Nupath, DegreeAudit.Status.MISSING, "looking up 2 courses", counts = false)
        assertTrue(DegreeRequirementsFilter.ALL.includes(check))
        assertFalse(DegreeRequirementsFilter.REMAINING.includes(check))
        assertFalse(DegreeRequirementsFilter.DONE.includes(check))
        check = check.copy(counts = true)
        assertTrue(DegreeRequirementsFilter.REMAINING.includes(check))
        assertFalse(DegreeRequirementsFilter.DONE.includes(check))
    }

    private fun notesAudit(completed: List<String>, planned: List<String> = emptyList()): Pair<DegreeAudit.Result, ProgramRequirements> {
        val program = CatalogProgram("Notes, BS", "undergraduate/notes/")
        val requirements = ProgramCatalogParser.requirements(notesPage, CatalogYear(2024), program)!!
        val plan = DegreePlan(
            name = "Notes",
            completed = completed.map { code ->
                if (code == "COOP 3946") DegreeAuditTests.course(code, 0.0, "Co-op Work Experience - HT") else DegreeAuditTests.course(code)
            },
            terms = listOf(DegreePlanTerm(season = PlanSeason.FALL, year = 2027, courses = planned.map { DegreeAuditTests.course(it) })),
        )
        return DegreeAudit.run(plan, requirements) to requirements
    }

    private fun entries(filter: DegreeRequirementsFilter, audit: DegreeAudit.Result, requirements: ProgramRequirements): Map<String, DegreeNoteEntry> {
        val found = LinkedHashMap<String, DegreeNoteEntry>()
        for (section in requirements.sections) {
            for (entry in DegreeRequirementNotes.entries(section, audit, filter, requirements.totalHours != null)) found[section.title] = entry
        }
        return found
    }
}

class CatalogCacheTests {
    @Test fun aNewerDownloadBeatsTheBundledSnapshot() {
        val directory = tempDir()
        val older = TermCatalog(
            term = Term("202710", "Fall 2026 Semester"),
            sectionsUpdatedAt = 1.0,
            courses = listOf(Course("CS", "3000", "Old title", detailsLoaded = true)),
        )
        val newer = older.copy(sectionsUpdatedAt = 9.0, courses = listOf(Course("CS", "3000", "New title", detailsLoaded = true)))
        val store = CatalogStore(directory) { code -> if (code == "202710") older else null }
        assertEquals("Old title", store.load("202710")!!.courses.first().title)
        store.save(newer)
        assertEquals("New title", store.load("202710")!!.courses.first().title)
        assertEquals(listOf("202710"), store.localCodes())
        store.saveRemoteTerms(listOf(Term("202710", "Fall 2026 Semester"), Term("202630", "Spring 2026 Semester")))
        assertEquals(listOf("202710", "202630"), store.loadRemoteTerms().map { it.code })
        assertEquals("202710", store.defaultTermCode())
    }

    @Test fun aFreshInstallDefaultsToTheNewestBundledTerm() {
        val directory = tempDir()
        fun snapshot(code: String, title: String) = TermCatalog(
            term = Term(code, title),
            courses = listOf(Course("CS", "3000", title)),
        )
        val store = CatalogStore(directory) { code ->
            when (code) {
                "202710" -> snapshot("202710", "Fall 2026 Semester")
                "202730" -> snapshot("202730", "Spring 2027 Semester (View Only)")
                else -> null
            }
        }
        assertEquals("202730", store.defaultTermCode())
        assertEquals(listOf("202730", "202710"), store.localCodes())
        store.save(snapshot("202810", "Fall 2027 Semester"))
        assertEquals("202730", store.defaultTermCode())
        assertEquals(listOf("202810", "202730", "202710"), store.localCodes())
        val listed = TermMenu.listed(emptyList(), store.localCodes())
        val named = TermMenu.preferKnownNames(
            listed,
            listOf(
                Term("202730", "Spring 2027 Semester (View Only)"),
                Term("202710", "Fall 2026 Semester"),
            ),
        )
        assertEquals(listOf("202810", "202730", "202710"), named.map { it.code })
        assertEquals("Spring 2027", named.first { it.code == "202730" }.name)
        assertEquals("Fall 2026", named.first { it.code == "202710" }.name)
        assertEquals("Term 202810", named.first { it.code == "202810" }.bannerDescription)
    }

    @Test fun mergeKeepsKnownCourseDetailsAndTakesFreshSeats() {
        val known = Course("CS", "3000", "Algorithms", description = "Graph algorithms", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true)
        val previous = CourseSection("11111", "CS", "3000", title = "Algorithms", seatsRemaining = 10, seatCapacity = 40, description = "Kept", instructors = emptyList())
        val existing = TermCatalog(
            term = Term("202710", "Fall 2026 Semester"),
            generatedAt = 3.0,
            courses = listOf(known),
            sections = listOf(previous),
        )
        val fresh = BannerSection(
            courseReferenceNumber = "11111",
            subject = "CS",
            courseNumber = "3000",
            courseTitle = "Algorithms",
            sequenceNumber = "01",
            seatsAvailable = 2,
            maximumEnrollment = 40,
            creditHours = 4.0,
            creditHourLow = 4.0,
        )
        val merged = CatalogBuilder.merge(existing, listOf(fresh), existing.term, now = 50.0)
        assertEquals("Algorithms", merged.courses.first().title)
        assertEquals("Graph algorithms", merged.courses.first().description)
        assertTrue(merged.courses.first().detailsLoaded)
        assertEquals(2, merged.sections.first().seatsRemaining)
        assertEquals("Kept", merged.sections.first().description)
        assertEquals(3.0, merged.generatedAt, 0.0)
        assertEquals(50.0, merged.sectionsUpdatedAt, 0.0)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScheduleCalendarTests {
    @Test fun everyMeetingAndTheFinalBecomeEvents() {
        val section = CourseSection(
            crn = "11111",
            subject = "CS",
            number = "3000",
            title = "Algorithms",
            sectionNumber = "01",
            classType = "Lecture",
            meetings = listOf(
                Meeting(DaySet(listOf(1, 3)), 10 * 60, 11 * 60 + 15, building = "West Village H", buildingCode = "WVH", room = "210", startDate = "2026-09-09", endDate = "2026-12-13"),
                Meeting(DaySet(listOf(5)), 14 * 60, 15 * 60, building = "West Village H", buildingCode = "WVH", room = "108", startDate = "2026-09-11", endDate = "2026-12-11"),
                Meeting(DaySet(listOf(2)), 8 * 60, 10 * 60, kind = "Final Exam", building = "West Village H", room = "210", startDate = "2026-12-15", endDate = "2026-12-15"),
                Meeting(DaySet(listOf(1)), 9 * 60, 10 * 60),
            ),
        )
        val plan = SchedulePlan(name = "Primary", termCode = "202710", selectedCRNs = listOf("11111"))
        val index = CatalogIndex.build(TermCatalog(term = Term("202710", "Fall 2026"), sections = listOf(section)))
        val (exported, summary) = ScheduleExporter.events(plan, index)
        assertEquals(3, exported.size)
        assertEquals(1, summary.classCount)
        assertEquals(2, summary.weeklyMeetings)
        assertEquals(1, summary.finalExams)
        assertEquals(1, summary.skippedMeetings)
        assertEquals("CS 3000 Lecture", exported[0].title)
        assertTrue(exported[0].isRecurring)
        assertEquals(listOf(1, 3), exported[0].weekdays)
        assertEquals("West Village H 210", exported[0].location)
        assertEquals("CS 3000 Final Exam", exported[2].title)
        assertTrue(exported[2].isFinalExam)
        val weekly = ScheduleExporter.contentValues(exported[0], 7)
        assertEquals("PT1H15M", weekly.getAsString(CalendarContract.Events.DURATION))
        assertTrue(weekly.getAsString(CalendarContract.Events.RRULE).contains("BYDAY=MO,WE"))
        assertFalse(weekly.containsKey(CalendarContract.Events.DTEND))
        val friday = ScheduleExporter.contentValues(exported[1], 7)
        assertEquals("PT1H", friday.getAsString(CalendarContract.Events.DURATION))
        val exam = ScheduleExporter.contentValues(exported[2], 7)
        assertTrue(exam.containsKey(CalendarContract.Events.DTEND))
        assertFalse(exam.containsKey(CalendarContract.Events.RRULE))
        val text = ScheduleExporter.calendarText(exported, "Primary – Fall 2026")
        assertEquals(3, Regex("BEGIN:VEVENT").findAll(text).count())
        assertTrue(text.contains("CS 3000 Final Exam"))
        assertTrue(text.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScheduleCalendarPermissionTest {
    @Test fun insertIsDeniedWithoutCalendarPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val event = app.termorganize.logic.schedule.ScheduleExportEvent("uid", "CS 3000 Lecture", null, "notes", 2026, 9, 9, 600, 675, listOf(1), 2026, 12, 13)
        val outcome = ScheduleExporter.insertAll(context, listOf(event))
        assertTrue(outcome is app.termorganize.logic.schedule.ScheduleCalendarOutcome.Denied)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CourseAppSearchTest {
    @Test fun anEmptyQueryAndAFailedIndexStayQuiet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(CourseAppSearch.search(context, "   ").isEmpty())
        val indexed = CourseAppSearch.index(context, "202710", listOf(Course("CS", "3000", "Algorithms", description = "Graphs")))
        val hits = CourseAppSearch.search(context, "Algorithms")
        if (indexed) assertTrue(hits.any { it.number == "3000" && it.title == "Algorithms" })
        else assertTrue(hits.isEmpty())
    }
}

private class TestClock(start: Double = 1_800_000_000.0) {
    var now: Double = start
    fun advance(days: Int) { now += days * 86_400.0 }
}

private class OfferedCall(val term: String, val courses: List<CourseKey>)
private class EntryCall(val key: CourseKey, val term: String)

private class FakeBanner(private val clock: TestClock, terms: List<Term>) : OfferingFetching, CourseInfoFetching {
    private val lock = Any()
    private var terms = terms
    private val catalog = HashMap<String, MutableMap<CourseKey, CourseInfo>>()
    private val offered = HashMap<String, MutableSet<CourseKey>>()
    private val failing = HashSet<String>()
    private val failingCourses = HashMap<String, CourseKey?>()
    val offeredCalls = ArrayList<OfferedCall>()
    val entryCalls = ArrayList<EntryCall>()
    private val inFlight = AtomicInteger(0)
    var maxInFlight = 0
    var delayMillis = 0L

    fun offer(term: String, courses: List<String>) = synchronized(lock) {
        offered.getOrPut(term) { mutableSetOf() }.addAll(courses.map { key(it) })
    }

    fun list(info: CourseInfo, term: String) = synchronized(lock) {
        catalog.getOrPut(term) { HashMap() }[info.key] = info
    }

    fun fail(term: String, failing: Boolean = true) = synchronized(lock) {
        if (failing) this.failing += term else this.failing -= term
    }

    fun failQuestions(term: String, course: CourseKey?) = synchronized(lock) {
        if (course == null) failingCourses.remove(term) else failingCourses[term] = course
    }

    fun setTerms(terms: List<Term>) = synchronized(lock) { this.terms = terms }
    fun resetCalls() = synchronized(lock) { offeredCalls.clear(); entryCalls.clear() }

    override suspend fun terms(): List<Term> = synchronized(lock) { terms }

    override suspend fun offeredCourses(term: String, courses: List<CourseKey>): Set<CourseKey> {
        synchronized(lock) {
            offeredCalls += OfferedCall(term, courses)
            if (term in failing) error("503")
            val marker = failingCourses[term]
            if (marker != null && marker in courses) error("503")
        }
        val all = synchronized(lock) { offered[term].orEmpty() }
        return courses.filter { it in all }.toSet()
    }

    override suspend fun catalogEntry(key: CourseKey, term: String): CourseInfo? {
        synchronized(lock) {
            entryCalls += EntryCall(key, term)
            val current = inFlight.incrementAndGet()
            if (current > maxInFlight) maxInFlight = current
        }
        try {
            if (delayMillis > 0) delay(delayMillis)
            if (synchronized(lock) { term in failing }) error("503")
            val info = synchronized(lock) { catalog[term]?.get(key) } ?: return null
            return info.copy(fetchedAt = clock.now)
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

private fun key(code: String) = CourseKey.parse(code)!!
private fun requisite(code: String) = Requisite.course(key(code))
private fun terms(codes: List<String>) = codes.map { Term(it, it) }
private fun tempDir() = File(System.getProperty("java.io.tmpdir"), "nto-${System.nanoTime()}").apply { mkdirs() }

private fun course(code: String, prereqs: Requisite? = null, detailed: Boolean = true): Course {
    val parsed = key(code)
    return Course(parsed.subject, parsed.number, "Title of $code", minCredits = 4.0, maxCredits = 4.0, prereqs = prereqs, detailsLoaded = detailed)
}

private fun courseInfo(code: String, term: String, prerequisites: Requisite? = null, equivalents: List<CourseKey> = emptyList()) = CourseInfo(
    key = key(code),
    title = "Title of $code",
    minCredits = 4.0,
    maxCredits = 4.0,
    prerequisites = prerequisites,
    equivalents = equivalents,
    sourceTerm = term,
)

private fun section(crn: String, code: String): CourseSection {
    val parsed = key(code)
    return CourseSection(
        crn, parsed.subject, parsed.number, title = code,
        meetings = listOf(Meeting(DaySet.of(1), 600, 700, startDate = "2026-09-09", endDate = "2026-12-13", buildingCode = "WVH", room = "210")),
    )
}

private fun index(courses: List<Course>, sections: List<CourseSection> = emptyList(), term: String = "202710") = CatalogIndex.build(
    TermCatalog(term = Term(term, "Fall 2026"), generatedAt = 1.0, courses = courses, sections = sections),
)

private const val notesPage = """
    <div id="programrequirementstextcontainer" class="tab_content">
    <h2>Major Requirements</h2>
    <table class="sc_courselist"><tbody>
    <tr class="even firstrow"><td class="codecol"><a class="bubblelink code">CS&#160;3000</a></td><td>Algorithms and Data</td><td class="hourscol">4</td></tr>
    <tr class="odd"><td colspan="2"><span class="courselistcomment">Complete 8 semester hours of CS classes. Choose courses within the following ranges:</span></td><td class="hourscol">8</td></tr>
    <tr class="even lastrow"><td colspan="2"><div style="margin-left:20px;" class="blockindent"><span class="courselistcomment commentindent"><a class="bubblelink code">CS&#160;2500</a> or higher</span></div></td><td class="hourscol"></td></tr>
    </tbody></table>
    <h2>GPA Requirement</h2>
    <p>Minimum 2.000 GPA required in the major.</p>
    <h2>Credit Requirement</h2>
    <p>Complete 12 semester hours in the major.</p>
    <h2>NUpath Requirements Satisfied</h2>
    <p>Integrating Knowledge and Skills Through Experience is satisfied through co-op.</p>
    <h2>Program Requirement</h2>
    <p>12 total semester hours required</p>
    </div>
"""
