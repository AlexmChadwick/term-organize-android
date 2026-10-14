// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import app.termorganize.core.banner.BannerFaculty
import app.termorganize.core.banner.BannerMeetingFaculty
import app.termorganize.core.banner.BannerMeetingTime
import app.termorganize.core.banner.BannerSection
import app.termorganize.core.banner.BannerSectionPage
import app.termorganize.core.banner.BannerTerm
import app.termorganize.core.banner.BannerTermList
import app.termorganize.core.banner.CatalogBuilder
import app.termorganize.core.banner.withFacultyMeetings
import app.termorganize.core.model.AppJson
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermMenu
import app.termorganize.logic.degree.CatalogYear
import app.termorganize.logic.degree.CatalogProgram
import app.termorganize.logic.degree.ProgramCatalogParser
import app.termorganize.logic.degree.ProgramCatalogWalker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes captured from live Northeastern endpoints in October 2026. */
class LiveShapeTests {
    @Test fun integerCreditHoursDecode() {
        val json = """
            {"success":true,"totalCount":1,"data":[{
              "courseReferenceNumber":"12726","subject":"CS","courseNumber":"3000",
              "creditHourLow":4,"creditHourHigh":null,"creditHours":null,
              "seatsAvailable":1,"maximumEnrollment":113,"enrollment":112,
              "openSection":true,
              "meetingsFaculty":[{"faculty":[],"meetingTime":{
                "beginTime":"0915","endTime":"1020","monday":true,"wednesday":true,"thursday":true,
                "meetingType":"CLAS","meetingTypeDescription":"Class",
                "startDate":"09/09/2026","endDate":"12/13/2026","buildingDescription":"Cargill Hall","room":"097"
              }}]
            }]}
        """.trimIndent()
        val page = AppJson.catalog.decodeFromString(BannerSectionPage.serializer(), json)
        val section = page.data!!.first()
        assertEquals(4.0, section.creditHourLow!!, 0.0)
        assertEquals(1, section.seatsAvailable)
        val meeting = section.meetingsFaculty!!.first().meetingTime!!.toMeeting()
        assertEquals(9 * 60 + 15, meeting.start)
        assertEquals("2026-09-09", meeting.startDate)
        assertFalse(meeting.isFinalExam)
    }

    @Test fun facultyMeetingsMatchAcrossDifferentOrder() {
        val search = BannerSection(
            courseReferenceNumber = "12726",
            subject = "CS",
            courseNumber = "3000",
            courseTitle = "Algorithms and Data",
            sequenceNumber = "02",
            meetingsFaculty = listOf(
                BannerMeetingFaculty(meetingTime = BannerMeetingTime(beginTime = "0800", endTime = "1000", meetingType = "FNEX", meetingTypeDescription = "Final Exam", wednesday = true, startDate = "12/16/2026", endDate = "12/16/2026")),
                BannerMeetingFaculty(meetingTime = BannerMeetingTime(beginTime = "0915", endTime = "1020", meetingType = "CLAS", meetingTypeDescription = "Class", monday = true, wednesday = true, thursday = true, startDate = "09/09/2026", endDate = "12/13/2026")),
            ),
        )
        val fmt = listOf(
            BannerMeetingFaculty(
                meetingTime = BannerMeetingTime(beginTime = "0915", endTime = "1020", meetingType = "CLAS", monday = true, wednesday = true, thursday = true),
                faculty = listOf(BannerFaculty(displayName = "Venkatesaramani, Rajagopal", primaryIndicator = true)),
            ),
            BannerMeetingFaculty(
                meetingTime = BannerMeetingTime(beginTime = "0800", endTime = "1000", meetingType = "FNEX", wednesday = true),
                faculty = listOf(BannerFaculty(displayName = "Exam, Proctor", primaryIndicator = true)),
            ),
        )
        val built = CatalogBuilder.makeSections(listOf(search.withFacultyMeetings(fmt))).first()
        assertEquals(setOf("Venkatesaramani, Rajagopal", "Exam, Proctor"), built.instructors!!.map { it.name }.toSet())
        assertEquals(1, built.weeklyMeetings.size)
        assertNotNull(built.finalExam)
        assertEquals("Exam, Proctor", built.meetings.first { it.isFinalExam }.let { built.instructors!!.first { name -> name.name == "Exam, Proctor" }.name })
    }

    @Test fun termPagesKeepLawTermsPastTheFirstPage() {
        var calls = 0
        val terms = runBlocking {
            BannerTermList.collect(pageSize = 2) { page ->
                calls += 1
                when (page) {
                    1 -> listOf(
                        BannerTerm("202710", "Fall 2026 Semester"),
                        BannerTerm("202655", "Summer 2026 CPS Quarter (View Only)"),
                    )
                    2 -> listOf(BannerTerm("202552", "Summer 2025 Law Semester (View Only)"))
                    else -> error("page $page")
                }
            }
        }
        assertEquals(2, calls)
        assertEquals(listOf("202710", "202655", "202552"), terms.map { it.code })
        val menu = TermMenu.listed(terms.map { Term(it.code, it.description) })
        assertTrue(menu.any { it.college == Term.College.LAW && it.code == "202552" })
        assertTrue(menu.any { it.college == Term.College.CPS })
        assertTrue(menu.any { it.college == Term.College.NEU })
    }

    @Test fun catalogWalkerReachesDegreePages() {
        val pages = mapOf(
            "https://catalog.northeastern.edu/undergraduate/" to """
                <a href="/undergraduate/admission/">Admission</a>
                <a href="/undergraduate/computer-information-science/">Khoury College of Computer Sciences</a>
            """.trimIndent(),
            "https://catalog.northeastern.edu/undergraduate/computer-information-science/" to """
                <a href="/undergraduate/engineering/">College of Engineering</a>
                <a href="/undergraduate/computer-information-science/computer-science/">Computer Science</a>
            """.trimIndent(),
            "https://catalog.northeastern.edu/undergraduate/computer-information-science/computer-science/" to """
                <a href="/undergraduate/computer-information-science/computer-science/bscs/">Computer Science, BSCS (Boston)</a>
                <a href="/undergraduate/computer-information-science/computer-science/minor/">Computer Science, Minor</a>
            """.trimIndent(),
        )
        val fetched = mutableListOf<String>()
        val programs = ProgramCatalogWalker.programs("https://catalog.northeastern.edu/undergraduate/") { url ->
            fetched += url
            pages[url] ?: error(url)
        }
        assertEquals(listOf("undergraduate/computer-information-science/computer-science/bscs/"), programs.map { it.path })
        assertEquals("Computer Science, BSCS (Boston)", programs.single().title)
        assertFalse(fetched.any { "admission" in it })
        assertFalse(fetched.any { "/engineering/" in it })
    }

    @Test fun liveBscsRequirementsParseCourseCodes() {
        val html = checkNotNull(javaClass.classLoader).getResource("live/bscs-requirements.html")!!.readText()
        val program = CatalogProgram(
            "Computer Science, BSCS (Boston)",
            "undergraduate/computer-information-science/computer-science/bscs/",
        )
        val parsed = ProgramCatalogParser.requirements(html, CatalogYear(2026), program)
        assertNotNull(parsed)
        val courses = parsed!!.allRules.flatMap { it.listedCourses }
        assertTrue(courses.any { it.subject == "CS" && it.number == "1200" })
        assertTrue(courses.any { it.subject == "CS" && it.number == "3000" })
        assertTrue(parsed.sections.any { "Computer Science Requirements" in it.title })
        assertTrue(parsed.totalHours != null && parsed.totalHours!! >= 120.0)
    }
}
