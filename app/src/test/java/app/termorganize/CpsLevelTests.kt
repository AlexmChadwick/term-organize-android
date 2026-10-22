// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import app.termorganize.core.banner.BannerParsers
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.CpsRestriction
import app.termorganize.core.model.Requisite
import app.termorganize.core.model.RestrictionKind
import app.termorganize.core.model.SectionRestriction
import app.termorganize.logic.search.CourseLevelFilter
import app.termorganize.logic.search.SearchFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpsLevelTests {
    @Test fun cpsOnlyFollowsMustLevelsOrMustColleges() {
        val onlyUc = restrictions(
            """
            Must be enrolled in one of the following Levels:
            CPS - Undergraduate Semester (UC)
            """.trimIndent(),
        )
        assertEquals(true, CpsRestriction.isCpsOnly(onlyUc))

        val mixed = restrictions(
            """
            Must be enrolled in one of the following Levels:
            Graduate
            CPS - Undergraduate Semester (UC)
            Undergraduate
            """.trimIndent(),
        )
        assertEquals(false, CpsRestriction.isCpsOnly(mixed))

        val college = restrictions(
            """
            Must be enrolled in one of the following Colleges:
            College of Professional Studies
            """.trimIndent(),
        )
        assertEquals(true, CpsRestriction.isCpsOnly(college))

        val cannot = restrictions(
            """
            Cannot be enrolled in one of the following Levels:
            CPS - Undergraduate Semester (UC)
            """.trimIndent(),
        )
        assertEquals(false, CpsRestriction.isCpsOnly(cannot))
        assertEquals(RestrictionKind.CANNOT, cannot.single().kind)
        assertEquals("Levels", cannot.single().category)

        assertNull(CpsRestriction.isCpsOnly(null))
        assertEquals(false, CpsRestriction.isCpsOnly(emptyList()))
        assertNull(CpsRestriction.courseNote(listOf(section("34087", null))))
    }

    @Test fun courseNoteIsAllKnownSomeOrNothing() {
        val cps = section("34087", listOf(mustLevels("CPS - Undergraduate Semester (UC)")))
        val open = section("34629", listOf(mustLevels("Graduate", "Undergraduate")))
        val unknown = section("30000", null)
        assertEquals(CpsRestriction.SECTION_NOTE, CpsRestriction.courseNote(listOf(cps, unknown)))
        assertEquals(CpsRestriction.SOME_NOTE, CpsRestriction.courseNote(listOf(cps, open)))
        assertNull(CpsRestriction.courseNote(listOf(unknown, unknown)))
        assertNull(CpsRestriction.courseNote(listOf(open)))
        assertEquals(
            "Restricted to CPS (College of Professional Studies) students",
            CpsRestriction.SECTION_NOTE,
        )
        assertEquals("Some sections are CPS only", CpsRestriction.SOME_NOTE)
    }

    @Test fun undergradAndGradSplitAt5000AndCombineWithRequisiteFilters() {
        val under = Course("CS", "4999", "Boundary", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true)
        val underWritten = Course("CS", "4999W", "Writing", minCredits = 1.0, maxCredits = 1.0, detailsLoaded = true)
        val grad = Course("CS", "5000", "Graduate", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true)
        val underWithPrereq = Course(
            "CS",
            "3000",
            "Algorithms",
            minCredits = 4.0,
            maxCredits = 4.0,
            prereqs = Requisite.CourseReq(CourseKey("CS", "2500")),
            detailsLoaded = true,
        )
        val gradWithout = Course("CS", "5100", "Advanced", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true)
        val undergrad = SearchFilters(courseLevel = CourseLevelFilter.UNDERGRAD)
        val graduate = SearchFilters(courseLevel = CourseLevelFilter.GRAD)
        assertTrue(undergrad.matches(under))
        assertTrue(undergrad.matches(underWritten))
        assertFalse(undergrad.matches(grad))
        assertTrue(graduate.matches(grad))
        assertFalse(graduate.matches(under))
        val both = SearchFilters(noPrerequisites = true, courseLevel = CourseLevelFilter.UNDERGRAD)
        assertTrue(both.matches(under))
        assertFalse(both.matches(underWithPrereq))
        assertFalse(both.matches(gradWithout))
        assertEquals(1, graduate.activeCount)
        assertEquals(2, both.activeCount)
        assertEquals(0, SearchFilters().activeCount)
        assertEquals(CourseLevelFilter.ALL, SearchFilters().courseLevel)
    }

    private fun restrictions(html: String): List<SectionRestriction> = BannerParsers.restrictions(html)

    private fun mustLevels(vararg values: String) = SectionRestriction(RestrictionKind.MUST, "Levels", values.toList())

    private fun section(crn: String, restrictions: List<SectionRestriction>?) =
        CourseSection(crn = crn, subject = "ITC", number = "1000", title = "Information Technology", restrictions = restrictions)
}
