// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Ports of the Swift Testing suites that cover shared logic: CoreModel, Banner
// parsers, search, the schedule generator, degree-plan import, watchlist, rooms,
// widgets, and the preferences parser.

package app.termorganize

import app.termorganize.core.banner.BannerMeetingTime
import app.termorganize.core.banner.BannerParsers
import app.termorganize.core.banner.HTMLText
import app.termorganize.core.model.AppJson
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseEquivalences
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.CourseTitleMatcher
import app.termorganize.core.model.CreditFormatter
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Instructor
import app.termorganize.core.model.Meeting
import app.termorganize.core.model.NUPath
import app.termorganize.core.model.Requisite
import app.termorganize.core.model.Subject
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.core.model.TermMenu
import app.termorganize.core.model.TimeOfDay
import app.termorganize.logic.degree.CatalogProgram
import app.termorganize.logic.degree.CatalogYear
import app.termorganize.logic.degree.DegreeAudit
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanAnalysis
import app.termorganize.logic.degree.DegreePlanAutofill
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanFormat
import app.termorganize.logic.degree.DegreePlanImportException
import app.termorganize.logic.degree.DegreePlanPdf
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.PlanCourse
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.degree.ProgramCatalogParser
import app.termorganize.logic.degree.ProgramRequirements
import app.termorganize.logic.degree.RequirementGroup
import app.termorganize.logic.degree.RequirementRule
import app.termorganize.logic.degree.RequirementSection
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.degree.TermCalendar
import app.termorganize.logic.degree.UserDataStore
import app.termorganize.logic.degree.WatchItem
import app.termorganize.logic.nl.PreferencesParser
import app.termorganize.logic.professors.ProfessorPage
import app.termorganize.logic.rooms.RoomAvailability
import app.termorganize.logic.schedule.ScheduleCourseInput
import app.termorganize.logic.schedule.ScheduleFilters
import app.termorganize.logic.schedule.ScheduleGenerator
import app.termorganize.logic.schedule.ScheduleSlotMask
import app.termorganize.logic.search.CatalogIndex
import app.termorganize.logic.search.RoomSchedule
import app.termorganize.logic.search.SearchEngine
import app.termorganize.logic.search.SearchFilters
import app.termorganize.logic.search.SearchQuery
import app.termorganize.logic.watchlist.WatchlistComparison
import app.termorganize.logic.watchlist.WatchlistFormat
import app.termorganize.logic.watchlist.WatchlistStatus
import app.termorganize.logic.widget.WidgetMeeting
import app.termorganize.logic.widget.WidgetScheduleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class LogicTests {
    @Test fun termNames() {
        assertEquals("Fall 2026", Term("202710", "Fall 2026 Semester").name)
        assertEquals("Spring 2026", Term("202630", "Spring 2026 Semester (View Only)").name)
        val cps = Term("202635", "Spring 2026 CPS Quarter (View Only)")
        assertEquals("Spring 2026 · CPS Quarter", cps.name)
        assertEquals("Spring 2026", cps.shortName)
        assertEquals(Term.College.CPS, cps.college)
        assertTrue(cps.isViewOnly)
        assertEquals(Term.Season.FALL, Term("202710", "").season)
        val remote = listOf(
            Term("202710", "Fall 2026 Semester"),
            Term("202630", "Spring 2026 Semester"),
            Term("202610", "Fall 2025 Semester"),
            Term("202530", "Spring 2025 Semester"),
            Term("202510", "Fall 2024 Semester"),
            Term("202430", "Spring 2024 Semester"),
            Term("202410", "Fall 2023 Semester"),
            Term("202330", "Spring 2023 Semester"),
            Term("202310", "Fall 2022 Semester"),
            Term("202230", "Spring 2022 Semester"),
            Term("202210", "Fall 2021 Semester"),
            Term("202635", "Spring 2026 CPS Quarter"),
            Term("202615", "Fall 2025 CPS Quarter"),
            Term("202632", "Spring 2026 Law Semester"),
        )
        val menu = TermMenu.listed(remote, listOf("202210"))
        assertEquals(10, menu.count { it.college == Term.College.NEU && it.code != "202210" })
        assertEquals(
            listOf("202710", "202635", "202632", "202630", "202615", "202610", "202530", "202510", "202430", "202410", "202330", "202310", "202230", "202210"),
            menu.map { it.code },
        )
        assertEquals(listOf("201910"), TermMenu.listed(emptyList(), listOf("201910")).map { it.code })
        assertEquals(2026, Term("202710", "").calendarYear)
        assertEquals(2026, Term("202630", "").calendarYear)
    }

    @Test fun courseKeyParsing() {
        assertEquals(CourseKey("CS", "3000"), CourseKey.parse("CS 3000"))
        assertEquals(CourseKey("CS", "3000"), CourseKey.parse("cs3000"))
        assertEquals(CourseKey("MATH", "209M"), CourseKey.parse("math-209m"))
        assertNull(CourseKey.parse("3000"))
        assertEquals(3, CourseKey("CS", "3000").level)
        assertTrue(CourseKey("CS", "2100") < CourseKey("CS", "3000"))
        assertTrue(CourseKey("CS", "900") < CourseKey("CS", "1800"))
    }

    @Test fun daySetsAndTime() {
        val mwr = DaySet(listOf(1, 3, 4))
        assertEquals("MWR", mwr.compactLabel)
        assertTrue(mwr.contains(3))
        assertFalse(mwr.contains(2))
        assertEquals(3, mwr.count)
        assertTrue(mwr.isSubsetOf(DaySet.WEEKDAYS))
        assertFalse(DaySet.of(6).isSubsetOf(DaySet.WEEKDAYS))
        assertEquals(875, TimeOfDay.minutesFromHHMM(1435))
        assertEquals("2:35 PM", TimeOfDay.label(875))
        assertEquals("12:00 AM", TimeOfDay.label(0))
        assertEquals("10:30 – 11:35 AM", TimeOfDay.rangeLabel(630, 695))
        assertEquals("11:45 AM – 1:25 PM", TimeOfDay.rangeLabel(705, 805))
        assertEquals("4 credits", CreditFormatter.label(4.0, 4.0))
        assertEquals("1 credit", CreditFormatter.label(1.0, 1.0))
        assertEquals("1–4 credits", CreditFormatter.label(1.0, 4.0))
    }

    @Test fun requisiteHelpers() {
        val a = CourseKey("CS", "2100")
        val b = CourseKey("CS", "2510")
        val c = CourseKey("CS", "1800")
        val nested = Requisite.and(listOf(Requisite.or(listOf(Requisite.course(a), Requisite.or(listOf(Requisite.course(b))))), Requisite.and(listOf(Requisite.course(c)))))
        val simplified = nested.simplified()
        assertEquals(Requisite.and(listOf(Requisite.or(listOf(Requisite.course(a), Requisite.course(b))), Requisite.course(c))), simplified)
        assertEquals(listOf(a, b, c), nested.referencedCourses)
        assertEquals("(CS 2100 or CS 2510) and CS 1800", simplified?.summary)
        assertTrue(simplified!!.isSatisfied(setOf(a, c)))
        assertFalse(simplified.isSatisfied(setOf(a)))
    }

    @Test fun instructorNamesAndEquivalences() {
        assertEquals("Natalia Ptitsyna", Instructor("Ptitsyna, Natalia", true).displayName)
        assertEquals("Staff", Instructor("Staff").displayName)
        val a = CourseKey("CS", "2500")
        val b = CourseKey("CS", "2510")
        val c = CourseKey("CS", "2501")
        val forward = CourseEquivalences.fromPairs(listOf(a to b, b to c))
        val reverse = CourseEquivalences.fromPairs(listOf(c to b, b to a))
        assertTrue(forward.areEquivalent(a, c))
        assertEquals(forward.group(a).sorted(), reverse.group(a).sorted())
        assertTrue(c in forward.expanding(setOf(a)))
        assertTrue(CourseTitleMatcher.similarity("Algorithms and Data", "Algorithms & Data") != null)
        assertNull(CourseTitleMatcher.similarity("Algorithms I", "Algorithms II"))
    }

    @Test fun catalogJsonRoundTrip() {
        val course = Course("CS", "3000", "Algorithms and Data", "Introduces…", 4.0, 4.0, listOf("NCFQ"), Requisite.course(CourseKey("CS", "2100")), detailsLoaded = true)
        val catalog = TermCatalog(term = Term("202710", "Fall 2026 Semester"), courses = listOf(course)).recomputePostreqs()
        val raw = AppJson.catalog.encodeToString(TermCatalog.serializer(), catalog).toByteArray()
        val gzipped = java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
        val text = GZIPInputStream(gzipped.inputStream()).use { it.readBytes() }.decodeToString()
        val decoded = AppJson.catalog.decodeFromString(TermCatalog.serializer(), text)
        assertEquals("Algorithms and Data", decoded.courses.first().title)
        assertEquals(listOf(NUPath.FQ), decoded.courses.first().nupaths)
        assertEquals(Requisite.course(CourseKey("CS", "2100")), decoded.courses.first().prereqs)
    }

    @Test fun bannerParsers() {
        val subjects = BannerParsers.SubjectLookup(listOf(
            Subject("CS", "Computer Science"), Subject("DS", "Data Science"),
            Subject("MATH", "Mathematics"), Subject("EECE", "Electrical and Comp Engineerng"),
        ))
        fun row(andOr: String = "", open: Boolean = false, test: String = "", subject: String = "", number: String = "", close: Boolean = false) =
            "<tr><td>$andOr</td><td>${if (open) "(" else ""}</td><td>$test</td><td></td><td>$subject</td><td>$number</td><td>Undergraduate</td><td>D-</td><td>${if (close) ")" else ""}</td></tr>"
        fun table(rows: List<String>) = "<section><table class=\"basePreqTable\"><thead><tr><th>And/Or</th></tr></thead><tbody>${rows.joinToString("")}</tbody></table></section>"
        val html = table(listOf(
            row(open = true),
            row(open = true, subject = "Computer Science", number = "2100"),
            row("Or", subject = "Computer Science - MMC", number = "201M"),
            row("Or", subject = "Computer Science", number = "2510"),
            row("Or", subject = "Data Science", number = "2500", close = true),
            row("And", open = true, subject = "Computer Science", number = "1800"),
            row("Or", subject = "Mathematics", number = "209M"),
            row("Or", subject = "Electrical and Comp Engineerng", number = "2160", close = true),
            row(close = true),
            row("Or", subject = "Computer Science", number = "2700"),
        ))
        val requisite = BannerParsers.prerequisites(html, subjects)!!
        val top = requisite as Requisite.Or
        assertEquals(2, top.items.size)
        assertEquals(Requisite.course(CourseKey("CS", "2700")), top.items.last())
        assertEquals(2, (top.items.first() as Requisite.And).items.size)
        assertTrue(CourseKey("EECE", "2160") in requisite.referencedCourses)
        assertTrue(CourseKey("CS", "201M") in requisite.referencedCourses)
        assertEquals(Requisite.course(CourseKey("CS", "2100")), BannerParsers.prerequisites(table(listOf(row(subject = "Computer Science", number = "2100"))), subjects))
        assertEquals(
            "Covers the derivative & integration.",
            BannerParsers.description("<section aria-labelledby=\"courseDescription\">Covers the derivative &amp; integration.<br/></section>"),
        )
        assertEquals("Fin Accounting & Reporting", HTMLText.decodeEntities("Fin Accounting &amp; Reporting"))
        assertEquals("O'Brien — \"Hi\"", HTMLText.decodeEntities("O&#39;Brien &#x2014; &quot;Hi&quot;"))
        assertEquals("AT&T", HTMLText.decodeEntities("AT&T"))
        val meeting = BannerMeetingTime(
            beginTime = "0800", endTime = "0905", building = "DG", buildingDescription = "Dodge Hall", room = "070",
            campus = "BOS", startDate = "09/09/2026", endDate = "12/13/2026", meetingTypeDescription = "Class",
            monday = true, wednesday = true, thursday = true,
        ).toMeeting()
        assertEquals(480, meeting.start)
        assertEquals(545, meeting.end)
        assertEquals("MWR", meeting.days.compactLabel)
        assertEquals("DG 070", meeting.locationLabel)
        assertEquals("2026-09-09", meeting.startDate)
        assertFalse(meeting.isFinalExam)
    }

    @Test fun searchAgainstBundledCatalog() {
        val index = bundledIndex()
        assertTrue(index.courses.size > 2500)
        assertTrue(index.catalog.sections.size > 7000)
        assertTrue(index.courses.all { it.detailsLoaded })
        assertTrue(index.instructors.isNotEmpty())
        assertTrue(index.buildings.isNotEmpty())
        fun top(text: String) = SearchEngine.search(SearchQuery(text), index).results.take(5).map { it.course.code }
        assertEquals("CS 3000", top("CS 3000").first())
        assertEquals("CS 3000", top("cs3000").first())
        assertEquals("CS 3000", top("cs 30").first())
        assertEquals("CS 3000", top("algorithms and data").first())
        assertEquals("MATH 2331", top("linear algebra").first())
        assertEquals("MATH 2331", top("linalg").first())
        assertTrue(top("orgo").all { it.startsWith("CHM") || it.startsWith("CHEM") })
        assertTrue(top("algoritms").contains("CS 3000"))
        val section = index.catalog.sections.first()
        assertEquals(section.crn, SearchEngine.search(SearchQuery(section.crn), index).crnMatch?.crn)
        assertEquals(listOf("CS"), SearchEngine.search(SearchQuery("cs"), index).subjects.map { it.code })
        val filters = SearchFilters(subjects = setOf("CS"), openSeatsOnly = true, allowedDays = DaySet(listOf(1, 3, 4)))
        val results = SearchEngine.search(SearchQuery("", filters), index).results
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { result -> result.course.subject == "CS" && index.sections(result.course.key).any { filters.matches(it) } })
    }

    @Test fun scheduleGenerator() {
        fun section(crn: String, subject: String, number: String, days: List<Int>, start: Double, end: Double) = CourseSection(
            crn = crn, subject = subject, number = number, title = "$subject $number", sectionNumber = "01",
            meetings = listOf(Meeting(DaySet(days), (start * 60).toInt(), (end * 60).toInt(), "WVH", "West Village H", "210", "BOS", startDate = "2026-09-09", endDate = "2026-12-13")),
        )
        fun course(subject: String, number: String, required: Boolean = true, sections: List<CourseSection>) =
            ScheduleCourseInput(CourseKey(subject, number), required, sections)
        val a = ScheduleSlotMask.of(section("1", "CS", "1", listOf(1, 3), 10.0, 11.0))
        val b = ScheduleSlotMask.of(section("2", "CS", "2", listOf(3), 10.5, 12.0))
        val c = ScheduleSlotMask.of(section("3", "CS", "3", listOf(1, 3), 11.0, 12.0))
        assertTrue(a.conflicts(b))
        assertFalse(a.conflicts(c))
        val generated = ScheduleGenerator.generate(listOf(
            course("CS", "3000", sections = listOf(
                section("A1", "CS", "3000", listOf(1, 3, 4), 9.0, 10.0),
                section("A2", "CS", "3000", listOf(1, 3, 4), 13.0, 14.0),
            )),
            course("MATH", "2331", sections = listOf(
                section("B1", "MATH", "2331", listOf(1, 3, 4), 9.5, 10.5),
                section("B2", "MATH", "2331", listOf(2, 5), 9.0, 10.5),
            )),
        ), ScheduleFilters())
        assertEquals(3, generated.totalFound)
        assertFalse(generated.schedules.any { it.crns.toSet() == setOf("A1", "B1") })
        val filtered = ScheduleGenerator.generate(listOf(course("CS", "3000", sections = listOf(
            section("EARLY", "CS", "3000", listOf(1, 3), 8.0, 9.0),
            section("FRI", "CS", "3000", listOf(5), 12.0, 15.0),
            section("OK", "CS", "3000", listOf(2, 4), 11.0, 12.0),
        ))), ScheduleFilters(earliestStart = 9 * 60, freeDays = DaySet.FRIDAY))
        assertEquals(listOf(listOf("OK")), filtered.schedules.map { it.crns })
        val optional = ScheduleGenerator.generate(listOf(
            course("CS", "3000", sections = listOf(section("R", "CS", "3000", listOf(1, 3), 10.0, 11.0))),
            course("ARTF", "1220", required = false, sections = listOf(
                section("O1", "ARTF", "1220", listOf(1), 10.5, 12.0),
                section("O2", "ARTF", "1220", listOf(2), 10.0, 12.0),
            )),
        ), ScheduleFilters())
        assertEquals(listOf(setOf("R", "O2")), optional.schedules.map { it.crns.toSet() })
        val single = ScheduleGenerator.generate(listOf(
            course("CS", "3000", sections = listOf(section("R", "CS", "3000", listOf(1, 3), 10.0, 11.0))),
            course("ARTF", "1220", required = false, sections = listOf(section("O2", "ARTF", "1220", listOf(2), 10.0, 12.0))),
        ), ScheduleFilters(courseCount = 1))
        assertEquals(listOf(listOf("R")), single.schedules.map { it.crns })
        val index = bundledIndex()
        val real = ScheduleGenerator.generate(listOf("CS" to "3000", "CS" to "3001", "MATH" to "2331").map { (s, n) ->
            val key = CourseKey(s, n)
            ScheduleCourseInput(key, true, index.sections(key))
        }, ScheduleFilters())
        assertTrue(real.totalFound > 0)
        assertTrue(real.schedules.all { it.crns.size == 3 })
    }

    @Test fun halfTermsWidgetsAndActivePlan() {
        fun meeting(from: String?, to: String?, start: Int = 600, end: Int = 665, days: List<Int> = listOf(1, 3)) =
            Meeting(DaySet(days), start, end, "RI", "Richards Hall", "300", startDate = from, endDate = to)
        val first = meeting("2026-09-09", "2026-10-27")
        val second = meeting("2026-10-28", "2026-12-13")
        val full = meeting("2026-09-09", "2026-12-13", start = 630, end = 700, days = listOf(3))
        assertFalse(first.overlaps(second))
        assertTrue(first.overlaps(full))
        assertTrue(second.overlaps(full))
        val zone = ZoneId.of("America/New_York")
        val wednesday = ZonedDateTime.of(2026, 9, 16, 10, 0, 0, 0, zone)
        val meetings = listOf(
            WidgetMeeting("a", "1", "CS 3000", "Algo", "WVH 210", "Lecture", 3, 9 * 60, 9 * 60 + 65, 0),
            WidgetMeeting("b", "2", "MATH 2331", "LinAlg", "RI 300", "Lecture", 3, 13 * 60, 14 * 60 + 5, 1),
        )
        val data = WidgetScheduleData(0, "202710", "Fall 2026", "Plan", meetings = meetings)
        assertEquals("CS 3000", data.occurrences(wednesday, 3).first().meeting.courseCode)
        assertEquals(3, data.occurrences(wednesday, 3).size)
        assertEquals("MATH 2331", data.occurrences(wednesday.plusMinutes(30), 1).first().meeting.courseCode)
        assertEquals(2, data.classes(wednesday).size)
        val monday = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, zone)
        val halves = WidgetScheduleData(0, "202710", "Fall 2026", "Plan", meetings = listOf(
            WidgetMeeting("a", "1", "ARTF 1220", "A", "RY 200", "Lecture", 1, 600, 665, 0, "2026-09-09", "2026-10-27"),
            WidgetMeeting("b", "2", "ARTF 1221", "B", "RY 200", "Lecture", 1, 600, 665, 1, "2026-10-28", "2026-12-13"),
        ))
        assertEquals(listOf("ARTF 1221"), halves.classes(monday).map { it.meeting.courseCode })
        val fall = TermCalendar.window("202710")!!
        val spring = TermCalendar.window("202730")!!
        assertTrue(fall.start.atZone(zone).year == 2026 && fall.start.atZone(zone).monthValue == 9)
        assertEquals(2027, spring.start.atZone(zone).year)
        assertTrue(fall.endInclusive.isBefore(spring.start))
        val store = UserDataStore()
        val fallPlan = store.createPlan("Fall", "202710")
        val springPlan = store.createPlan("Spring", "202730")
        store.updatePlan(fallPlan.id) { it.copy(selectedCRNs = listOf("10001")) }
        store.updatePlan(springPlan.id) { it.copy(selectedCRNs = listOf("20001")) }
        assertEquals(fallPlan.id, store.activePrimaryPlan(ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, zone).toInstant())?.id)
        assertEquals(springPlan.id, store.activePrimaryPlan(ZonedDateTime.of(2026, 12, 28, 12, 0, 0, 0, zone).toInstant())?.id)
        assertNull(store.activePrimaryPlan(ZonedDateTime.of(2027, 7, 1, 12, 0, 0, 0, zone).toInstant()))
    }

    @Test fun degreePlanImportExportAndPdf() {
        val parsed = DegreePlanFormat.parse(sampleExport, "view-plan.pdf")
        val plan = parsed.plan
        assertEquals("New Plan", plan.name)
        assertEquals("CS-B-CSCI-BSCS", plan.program)
        assertEquals("SAMPLE STUDENT", plan.studentName)
        assertEquals("view-plan.pdf", plan.importedFrom)
        assertEquals(6, plan.completed.size)
        assertEquals(1.0, plan.completed[1].credits, 0.001)
        assertNull(plan.completed[2].key)
        assertEquals("NO AP CREDIT", plan.completed[2].label)
        assertEquals(CourseKey("CS", "1800"), plan.completed[0].key)
        assertEquals(listOf("Spring 2027", "Summer Session A 2027", "Summer Session B 2027", "Fall 2027"), plan.terms.map { it.name })
        assertEquals(3, plan.terms[0].courses.size)
        assertEquals(9.0, plan.terms[0].credits, 0.001)
        assertTrue(plan.terms[2].courses.isEmpty())
        assertTrue(parsed.skippedLines.isEmpty())
        assertEquals("CS  1800 (4.0 Hours) Discrete Structures", DegreePlanFormat.courseLine(plan.completed[0]))
        assertEquals("CS  1802 (1.0 Hour) Seminar for CS 1800", DegreePlanFormat.courseLine(plan.completed[1]))
        assertEquals("Spring  2027 (9.0 Hours) ", DegreePlanFormat.termHeader(plan.terms[0]))
        assertEquals("Summer Session A 2027 (4.0 Hours) ", DegreePlanFormat.termHeader(plan.terms[1]))
        val exported = DegreePlanFormat.text(plan, includeStudent = false)
        assertFalse(exported.contains("StudentID"))
        assertTrue(exported.contains("Degree Audit Plan Name:\tNew Plan"))
        val round = DegreePlanFormat.parse(DegreePlanPdf.textFrom(DegreePlanPdf.render(plan, includeStudent = true))!!).plan
        assertEquals(plan.name, round.name)
        assertEquals(plan.program, round.program)
        assertEquals(plan.studentName, round.studentName)
        assertEquals(plan.completed.map { it.label }, round.completed.map { it.label })
        assertEquals(plan.terms.map { it.name }, round.terms.map { it.name })
        try {
            DegreePlanFormat.parse("Hello world\nThis is a receipt\nTotal \$4.00")
            throw AssertionError("expected a rejection")
        } catch (e: DegreePlanImportException) {
            assertEquals(DegreePlanImportException.Kind.NOT_A_DEGREE_PLAN, e.kind)
        }
        val cs2100 = CourseKey("CS", "2100")
        val cs2510 = CourseKey("CS", "2510")
        val cs1800 = CourseKey("CS", "1800")
        val requisite = Requisite.and(listOf(Requisite.or(listOf(Requisite.course(cs2100), Requisite.course(cs2510))), Requisite.course(cs1800)))
        assertTrue(DegreePlanAnalysis.status(requisite, setOf(cs2510, cs1800)) is DegreePlanAnalysis.PrerequisiteStatus.Met)
        assertEquals("CS 1800", (DegreePlanAnalysis.status(requisite, setOf(cs2510)) as DegreePlanAnalysis.PrerequisiteStatus.Missing).text)
        fun key(code: String) = Requisite.course(CourseKey.parse(code)!!)
        val programming = Requisite.or(listOf(key("CS 2100"), key("CS 201M"), key("CS 2510"), key("DS 2500")))
        assertEquals("CS 2100, CS 201M or 2 others", DegreePlanAnalysis.unmetSummary(programming, emptySet()))
        val beforeSummer = DegreePlanAnalysis.coursesBefore(1, plan)
        assertTrue(CourseKey("CS", "3650") in beforeSummer)
        assertTrue(CourseKey("CS", "1800") in beforeSummer)
        assertFalse(CourseKey("CS", "4500") in beforeSummer)
        val spring = Term("202730", "Spring 2027 Semester")
        val exact = DegreePlanAutofill.scheduleSuggestions(spring, listOf(plan.copy(isPrimary = true)), emptyList())
        assertEquals(true, exact.first().isExactTerm)
        assertEquals(3, exact.first().courses.size)
        val draft = DegreePlanAutofill.newDegreePlanDraft(
            "Draft", plan,
            listOf(SchedulePlan(name = "Plan A", termCode = "202730", courses = listOf(PlanCourse(CourseKey("CS", "3650")), PlanCourse(CourseKey("MATH", "2331"))), isPrimary = true)),
            { null }, Instant.parse("2026-10-01T16:00:00Z"),
        )
        val springTerm = draft.terms.first { it.season == PlanSeason.SPRING && it.year == 2027 }
        assertEquals(listOf("CS 3650", "CS 4530", "CS 2963", "MATH 2331"), springTerm.courses.map { it.label })
        assertTrue(DegreePlanTerm(season = PlanSeason.SUMMER_A, year = 2027).matches(Term("202740", "")))
        assertFalse(DegreePlanTerm(season = PlanSeason.SUMMER_A, year = 2027).matches(Term("202760", "")))
        val store = UserDataStore()
        val first = store.addDegreePlan(DegreePlan(name = "A"))
        val second = store.addDegreePlan(DegreePlan(name = "B"))
        assertTrue(store.degreePlan(first.id)!!.isPrimary)
        assertFalse(store.degreePlan(second.id)!!.isPrimary)
        store.setPrimaryDegreePlan(second.id)
        store.deleteDegreePlan(second.id)
        assertEquals(first.id, store.primaryDegreePlan?.id)
    }

    @Test fun watchlistAndRooms() {
        val now = 1_790_000_000_000L
        val key = CourseKey("CS", "3000")
        fun item(seats: Int = 0, waitlist: Int = 0, notify: Boolean = false, crn: String = "12345", term: String = "202710", capacity: Int = 40) = WatchItem(
            term, crn, key, "Algorithms and Data", "Lecture · Section 02", lastSeatsRemaining = seats,
            lastWaitlistRemaining = waitlist, seatCapacity = capacity, notifyOnWaitlist = notify,
        )
        fun section(seats: Int, waitlist: Int = 0, crn: String = "12345") = CourseSection(
            crn, "CS", "3000", "Algorithms and Data", "02", seatCapacity = 40, seatsRemaining = seats,
            waitlistCapacity = 10, waitlistRemaining = waitlist, classType = "Lecture",
        )
        val opened = WatchlistComparison.compare(listOf(item()), mapOf("202710" to mapOf(key to listOf(section(3)))), now)
        assertEquals(1, opened.alerts.size)
        assertEquals("Seats open in CS 3000", opened.alerts.first().title)
        assertEquals("3 of 40 seats in Lecture 02 (CRN 12345). Register soon!", opened.alerts.first().body)
        assertEquals("termorganize://section/202710/12345", opened.alerts.first().url)
        assertEquals(3, opened.items[0].lastSeatsRemaining)
        val already = WatchlistComparison.compare(listOf(item(seats = 2)), mapOf("202710" to mapOf(key to listOf(section(5)))), now)
        assertTrue(already.alerts.isEmpty())
        val live = mapOf("202710" to mapOf(key to listOf(section(0, 2))))
        assertTrue(WatchlistComparison.compare(listOf(item()), live, now).alerts.isEmpty())
        val opted = WatchlistComparison.compare(listOf(item(notify = true)), live, now)
        assertEquals(WatchlistAlertKind, opted.alerts.first().kind)
        assertEquals("2 waitlist spots open in Lecture 02 (CRN 12345). Join soon!", opted.alerts.first().body)
        val missing = WatchlistComparison.compare(listOf(item(seats = 1, crn = "99999")), mapOf("202710" to mapOf(key to listOf(section(3)))), now)
        assertEquals(setOf("202710|99999"), missing.missingIDs)
        assertEquals(WatchlistStatus.OPEN, WatchlistStatus.of(item(seats = 3), false))
        assertEquals("3 of 40 open", WatchlistStatus.OPEN.detail(item(seats = 3)))
        assertEquals("just now", WatchlistFormat.ago(now - 30_000, now))
        assertEquals("5 min ago", WatchlistFormat.ago(now - 5 * 60_000, now))
        assertEquals("yesterday", WatchlistFormat.ago(now - 30 * 3_600_000, now))
        fun booking(code: String, start: Int, end: Int, days: List<Int> = listOf(2), from: String? = null, to: String? = null) =
            RoomSchedule.Booking("1$start", CourseKey.parse(code)!!, DaySet(days), start, end, from, to)
        fun room(bookings: List<RoomSchedule.Booking>) = RoomSchedule("WVH", "West Village H", "210", bookings)
        val one = listOf(booking("CS 3000", 600, 665))
        assertEquals(RoomAvailability.Status.Free(600), RoomAvailability.status(room(one), RoomAvailability.Moment(2, 599)))
        assertFalse(RoomAvailability.status(room(one), RoomAvailability.Moment(2, 600)).isFree)
        assertEquals(RoomAvailability.Status.Free(null), RoomAvailability.status(room(one), RoomAvailability.Moment(2, 665)))
        val back = listOf(booking("CS 3000", 600, 665), booking("MATH 1341", 665, 730))
        val busy = RoomAvailability.status(room(back), RoomAvailability.Moment(2, 620)) as RoomAvailability.Status.Busy
        assertEquals(730, busy.occupancy.until)
        assertEquals("Free until 10:00 AM", RoomAvailability.Status.Free(600).summary)
        assertEquals("In use until 11:05 AM", RoomAvailability.status(room(one), RoomAvailability.Moment(2, 620)).summary)
        val soon = RoomAvailability.Moment(2, 580)
        assertTrue(RoomAvailability.status(room(one), soon).isFreeOnlyBriefly(soon))
        val workshop = room(listOf(booking("CS 2500", 600, 665, from = "2026-09-15", to = "2026-09-16")))
        assertTrue(RoomAvailability.bookings(workshop, 2).isEmpty())
        assertEquals(1, RoomAvailability.bookings(workshop, 2, LocalDate.of(2026, 9, 15)).size)
        assertTrue(RoomAvailability.bookings(workshop, 2, LocalDate.of(2026, 10, 6)).isEmpty())
    }

    @Test fun preferencesParserAndTinyAudit() {
        val prefs = PreferencesParser.parse("I'm a morning person, Fridays off, nothing after 4pm, in person")
        assertEquals(app.termorganize.logic.schedule.TimePreference.PREFER, prefs.band(app.termorganize.logic.schedule.TimeBand.MORNING))
        assertTrue(prefs.daysOff.contains(5))
        assertEquals(16 * 60, prefs.latestEnd?.minutes)
        assertEquals(app.termorganize.logic.schedule.FormatPreference.IN_PERSON, prefs.format)
        val cs = CourseKey("CS", "1800")
        val requirements = ProgramRequirements(
            CatalogYear(2025), CatalogProgram("Computer Science, BS", "undergraduate/computer-science"),
            listOf(RequirementSection("major", "Major", groups = listOf(RequirementGroup("g", "Required", listOf(
                RequirementRule("cs-1800", RequirementRule.Kind.Required(app.termorganize.logic.degree.CourseOption(listOf(listOf(cs)))), "CS 1800"),
            ))))),
        )
        val plan = DegreePlan(name = "Audit", completed = listOf(DegreePlanCourse.of(cs, "Discrete", 4.0)))
        val result = DegreeAudit.run(plan, requirements)
        assertEquals(DegreeAudit.Status.COMPLETE, result.result("cs-1800")?.status)
        val html = """
            <div id="programrequirementstextcontainer"><h2>Major Requirements</h2>
            <table><tr><td>CS 1800</td><td>Discrete Structures</td><td>4</td></tr></table></div>
        """.trimIndent()
        val parsed = ProgramCatalogParser.requirements(html, CatalogYear(2025), CatalogProgram("Computer Science, BS", "undergraduate/cs"))
        assertTrue(parsed != null && parsed!!.sections.isNotEmpty())
        assertEquals(CatalogYear(2025), ProgramCatalogParser.currentEdition("Academic Catalog 2025-2026"))
    }

    @Test fun professorSummary() {
        val sections = listOf(
            CourseSection("1", "CS", "3000", sectionNumber = "01"),
            CourseSection("2", "CS", "3000", sectionNumber = "04"),
            CourseSection("3", "CS", "3500", sectionNumber = "02"),
        )
        assertEquals("Teaches 3 sections across 2 courses", ProfessorPage.summaryText(sections))
        assertEquals("Teaches 1 section across 1 course", ProfessorPage.summaryText(listOf(sections[2])))
        assertTrue(ProfessorPage.rateMyProfessorsUrl("Jane Doe").contains("q=Jane%20Doe"))
    }

    @Test fun instructorEmailMayBeOmitted() {
        val json = """
            {"crn":"1","subject":"CS","number":"3000","instructors":[
              {"name":"Doe, Jane","primary":true},
              {"name":"Doe, John","email":"j.doe@northeastern.edu","primary":false},
              {"name":"Roe, Pat","email":null}
            ]}
        """.trimIndent()
        val section = AppJson.catalog.decodeFromString(CourseSection.serializer(), json)
        val people = section.instructors!!
        assertNull(people[0].email)
        assertEquals("j.doe@northeastern.edu", people[1].email)
        assertNull(people[2].email)
        val mixed = """
            {"crn":"2","subject":"CS","number":"3000","instructors":[
              {"name":"Doe, Ada","email":"ada@gmail.com a.doe@northeastern.edu"},
              {"name":"Doe, Bea","email":"bea@gmail.com"},
              {"name":"Doe, Cy","email":"c.doe@coe.neu.edu"},
              {"name":"Doe, Dee","email":"d.doe@neu.edu"}
            ]}
        """.trimIndent()
        val kept = AppJson.catalog.decodeFromString(CourseSection.serializer(), mixed).instructors!!
        assertEquals("a.doe@northeastern.edu", kept[0].email)
        assertNull(kept[1].email)
        assertNull(kept[2].email)
        assertEquals("d.doe@neu.edu", kept[3].email)
    }

    @Test fun spring2027CatalogLoadsWithOmittedEmails() {
        val file = bundledCatalogFile("202730")
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        assertEquals(
            "1eceba7fb35edda0060f1f64f6efd7526d112cf5738d05e4a1da24a4b65fde05",
            digest.joinToString("") { "%02x".format(it) },
        )
        val catalog = bundledCatalog("202730")
        assertEquals("202730", catalog.term.code)
        assertEquals("Spring 2027", catalog.term.name)
        assertTrue(catalog.term.isViewOnly)
        assertEquals(3076, catalog.courses.size)
        assertEquals(8326, catalog.sections.size)
        val people = catalog.sections.flatMap { it.instructors.orEmpty() }
        assertTrue(people.any { it.email == null })
        val domains = people.mapNotNull { instructor ->
            instructor.email?.substringAfter('@', "")?.lowercase()
        }.toSet()
        assertTrue(domains.isNotEmpty())
        assertTrue(domains.all { it == "northeastern.edu" || it == "neu.edu" })
        assertTrue(people.none { it.email?.contains(' ') == true })
        assertEquals("Algorithms and Data", catalog.courses.first { it.subject == "CS" && it.number == "3000" }.title)
    }

    companion object {
        private var index: CatalogIndex? = null

        fun bundledCatalogFile(code: String): File = listOf(
            File("src/main/assets/catalog/term-$code.json.gz"),
            File("app/src/main/assets/catalog/term-$code.json.gz"),
        ).first { it.exists() }

        fun bundledCatalog(code: String): TermCatalog {
            val text = GZIPInputStream(bundledCatalogFile(code).inputStream()).use { it.readBytes() }.decodeToString()
            return AppJson.catalog.decodeFromString(TermCatalog.serializer(), text)
        }

        fun bundledIndex(): CatalogIndex {
            index?.let { return it }
            val built = CatalogIndex.build(bundledCatalog("202710"))
            index = built
            return built
        }
    }
}

private val WatchlistAlertKind = app.termorganize.logic.watchlist.WatchlistAlert.Kind.WAITLIST

private val sampleExport = """
Printed: 10/03/2026 12:42
Student: SAMPLE STUDENT
StudentID: 000000000
Degree Audit Plan Name: New Plan
Program: CS-B-CSCI-BSCS
-
Completed Courses:
-
CS 1800 (4.0 Hours) Discrete Structures
-
CS 1802 (1.0 Hour) Seminar for CS 1800
-
NO AP CREDIT (0.0 Hours) No description
-
CS 2510 (4.0 Hours) Fundamentals of Computer Sci
-
CS 3500 (4.0 Hours) Object-Oriented Design
-
CS 3000 (4.0 Hours) Algorithms & Data
-
-
-------------------------------------------------------------------------------------------------------------------
Spring 2027 (9.0 Hours)
-
CS 3650 (4.0 Hours) Computer Systems
-
CS 4530 (4.0 Hours) Fundamentals of Software Engin
-
CS 2963 (1.0 Hour) Topics
-------------------------------------------------------------------------------------------------------------------
Summer Session A 2027 (4.0 Hours)
-
CS 4500 (4.0 Hours) Software Development
-------------------------------------------------------------------------------------------------------------------
Summer Session B 2027 (0.0 Hours)
-------------------------------------------------------------------------------------------------------------------
Fall 2027 (0.0 Hours)
""".trimIndent()
