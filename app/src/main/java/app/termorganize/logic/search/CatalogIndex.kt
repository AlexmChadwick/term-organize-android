// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.search

import app.termorganize.core.banner.BannerParsers
import app.termorganize.core.model.AttributeInfo
import app.termorganize.core.model.Campus
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Instructor
import app.termorganize.core.model.Meeting
import app.termorganize.core.model.NUPath
import app.termorganize.core.model.Subject
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class CourseStats(
    val sectionCount: Int = 0,
    val openSectionCount: Int = 0,
    val seatsRemaining: Int = 0,
    val seatCapacity: Int = 0,
    val waitlistOpenCount: Int = 0,
    val campuses: Set<String> = emptySet(),
    val classTypes: Set<String> = emptySet(),
    val hasHonors: Boolean = false,
    val hasOnline: Boolean = false,
    val hasInPerson: Boolean = false,
    val instructorNames: List<String> = emptyList(),
    val meetingDays: DaySet = DaySet(0),
) {
    val isFull: Boolean get() = sectionCount > 0 && openSectionCount == 0
    val availabilityLabel: String
        get() = when {
            sectionCount <= 0 -> "No sections"
            sectionCount == 1 -> if (openSectionCount == 1) "1 section · open" else "1 section · full"
            else -> "$openSectionCount of $sectionCount sections open"
        }
}

data class InstructorSummary(
    val name: String,
    val displayName: String,
    val email: String?,
    val crns: List<String>,
    val courseKeys: List<CourseKey>,
) {
    val id: String get() = name
}

data class RoomSchedule(
    val buildingCode: String,
    val buildingName: String,
    val room: String,
    val bookings: List<Booking>,
) {
    data class Booking(
        val crn: String,
        val course: CourseKey,
        val days: DaySet,
        val start: Int,
        val end: Int,
        val startDate: String? = null,
        val endDate: String? = null,
    ) {
        fun occurs(on: LocalDate): Boolean = days.contains(on.dayOfWeek.value % 7) && isActive(on)
        fun isActive(on: LocalDate): Boolean {
            val iso = on.format(ISO)
            if (startDate != null && iso < startDate) return false
            if (endDate != null && iso > endDate) return false
            return true
        }
        val isShortSession: Boolean
            get() {
                val a = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
                val b = endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
                return ChronoUnit.DAYS.between(a, b) < 21
            }
    }
    val id: String get() = "$buildingCode|$room"
    val label: String get() = "$buildingCode $room"

    companion object {
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}

data class BuildingSummary(
    val code: String,
    val name: String,
    val campusCode: String?,
    val rooms: List<RoomSchedule>,
) {
    val id: String get() = code
}

class CatalogIndex(
    val catalog: TermCatalog,
    val coursesByKey: Map<CourseKey, Course>,
    val sectionsByCourse: Map<CourseKey, List<CourseSection>>,
    val sectionsByCRN: Map<String, CourseSection>,
    val stats: Map<CourseKey, CourseStats>,
    val subjectsByCode: Map<String, Subject>,
    val attributeNames: Map<String, String>,
    val campusNames: List<String>,
    val classTypes: List<String>,
    val activeSubjects: List<Subject>,
    val courseCountBySubject: Map<String, Int>,
    val instructors: List<InstructorSummary>,
    val instructorsByName: Map<String, InstructorSummary>,
    val buildings: List<BuildingSummary>,
    val searchDocuments: List<SearchDocument>,
) {
    val term: Term get() = catalog.term
    val courses: List<Course> get() = catalog.courses
    fun course(key: CourseKey): Course? = coursesByKey[key]
    fun sections(key: CourseKey): List<CourseSection> = sectionsByCourse[key] ?: emptyList()
    fun section(crn: String): CourseSection? = sectionsByCRN[crn]
    fun stats(key: CourseKey): CourseStats = stats[key] ?: CourseStats()
    fun subjectName(code: String): String? = subjectsByCode[code]?.name
    fun instructor(named: String): InstructorSummary? = instructorsByName[named]
    fun attributeName(code: String): String = attributeNames[code] ?: code
    fun coursesInSubject(code: String): List<Course> = catalog.courses.filter { it.subject == code }.sortedBy { it.key }
    fun coursesWith(nupath: NUPath): List<Course> =
        catalog.courses.filter { nupath.attributeCode in it.attributes }.sortedBy { it.key }

    fun replacingCourse(key: CourseKey, incoming: Course?, newSections: List<CourseSection>): CatalogIndex {
        val course = incoming?.let { normalizedRequisites(it, BannerParsers.SubjectLookup(catalog.subjects)) }
        val sections = catalog.sections.filter { it.courseKey != key } + newSections
        val courses = catalog.courses.toMutableList()
        val byKey = coursesByKey.toMutableMap()
        val docs = searchDocuments.toMutableList()
        val sorted = sortedSections(newSections)
        val statsMap = stats.toMutableMap()
        statsMap[key] = makeStats(sorted)
        if (course != null) {
            val i = courses.indexOfFirst { it.key == key }
            if (i >= 0) courses[i] = course else {
                courses += course
                courses.sortBy { it.key }
            }
            byKey[key] = course
        }
        byKey[key]?.let { current ->
            val document = makeDocument(current, subjectsByCode[key.subject]?.name ?: "", statsMap[key], sorted)
            val i = docs.indexOfFirst { it.key == key }
            if (i >= 0) docs[i] = document else docs += document
        }
        val byCourse = sectionsByCourse.toMutableMap()
        val byCrn = sectionsByCRN.toMutableMap()
        (sectionsByCourse[key] ?: emptyList()).forEach { byCrn.remove(it.crn) }
        sorted.forEach { byCrn[it.crn] = it }
        byCourse[key] = sorted
        val (instructors, byName) = makeInstructorDirectory(sections)
        val nextCatalog = catalog.copy(courses = courses, sections = sections)
        return CatalogIndex(
            nextCatalog, byKey, byCourse, byCrn, statsMap, subjectsByCode, attributeNames,
            campusNames, classTypes, activeSubjects, courseCountBySubject, instructors, byName, buildings, docs,
        )
    }

    companion object {
        fun normalizedRequisites(catalog: TermCatalog): TermCatalog {
            if (catalog.courses.none { needsSubjectFix(it) }) return catalog
            val lookup = BannerParsers.SubjectLookup(catalog.subjects)
            val fixed = catalog.copy(courses = catalog.courses.map { normalizedRequisites(it, lookup) })
            return fixed.recomputePostreqs()
        }

        fun normalizedRequisites(course: Course, lookup: BannerParsers.SubjectLookup): Course {
            if (!needsSubjectFix(course)) return course
            fun normalized(key: CourseKey): CourseKey {
                if (' ' !in key.subject) return key
                return CourseKey(lookup.code(key.subject), key.number)
            }
            return course.copy(
                prereqs = course.prereqs?.mapCourses(::normalized),
                coreqs = course.coreqs?.mapCourses(::normalized),
            )
        }

        private fun needsSubjectFix(course: Course): Boolean {
            fun check(r: app.termorganize.core.model.Requisite?) =
                r?.referencedCourses?.any { ' ' in it.subject } == true
            return check(course.prereqs) || check(course.coreqs)
        }

        fun build(input: TermCatalog): CatalogIndex {
            val catalog = normalizedRequisites(input)
            val coursesByKey = catalog.courses.associateBy { it.key }
            val sectionsByCourse = mutableMapOf<CourseKey, MutableList<CourseSection>>()
            val sectionsByCRN = HashMap<String, CourseSection>(catalog.sections.size)
            val campusCounts = mutableMapOf<String, Int>()
            val typeCounts = mutableMapOf<String, Int>()
            val rooms = mutableMapOf<String, RoomSchedule>()
            val buildingNames = mutableMapOf<String, Pair<String, String?>>()
            for (s in catalog.sections) {
                sectionsByCourse.getOrPut(s.courseKey) { mutableListOf() }.add(s)
                sectionsByCRN[s.crn] = s
                if (s.campus.isNotEmpty()) campusCounts[s.campus] = (campusCounts[s.campus] ?: 0) + 1
                typeCounts[s.classType] = (typeCounts[s.classType] ?: 0) + 1
                for (m in s.weeklyMeetings) {
                    val code = m.buildingCode ?: continue
                    val room = m.room ?: continue
                    val start = m.start ?: continue
                    val end = m.end ?: continue
                    if (m.isOnline) continue
                    val id = "$code|$room"
                    val existing = rooms[id] ?: RoomSchedule(code, m.building ?: code, room, emptyList())
                    rooms[id] = existing.copy(
                        bookings = existing.bookings + RoomSchedule.Booking(s.crn, s.courseKey, m.days, start, end, m.startDate, m.endDate),
                    )
                    if (code !in buildingNames) buildingNames[code] = (m.building ?: code) to m.campusCode
                }
            }
            val sortedSections = sectionsByCourse.mapValues { (_, list) -> sortedSections(list) }
            val stats = sortedSections.mapValues { (_, list) -> makeStats(list) }
            val subjectsByCode = catalog.subjects.associateBy { it.code }
            val courseCount = mutableMapOf<String, Int>()
            for (c in catalog.courses) courseCount[c.subject] = (courseCount[c.subject] ?: 0) + 1
            val active = courseCount.keys.sorted().map { code -> subjectsByCode[code] ?: Subject(code, code) }
            val (instructors, byName) = makeInstructorDirectory(catalog.sections)
            val buildingRooms = mutableMapOf<String, MutableList<RoomSchedule>>()
            for (room in rooms.values) buildingRooms.getOrPut(room.buildingCode) { mutableListOf() }.add(room)
            val buildings = buildingRooms.map { (code, list) ->
                BuildingSummary(
                    code,
                    buildingNames[code]?.first ?: code,
                    buildingNames[code]?.second,
                    list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.room }),
                )
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            val attributeNames = catalog.attributes.associate { it.code to it.name }
            val documents = catalog.courses.map { course ->
                makeDocument(course, subjectsByCode[course.subject]?.name ?: "", stats[course.key], sortedSections[course.key] ?: emptyList())
            }
            return CatalogIndex(
                catalog, coursesByKey, sortedSections, sectionsByCRN, stats, subjectsByCode, attributeNames,
                campusCounts.entries.sortedByDescending { it.value }.map { it.key }.filter { !it.contains("no campus", true) },
                typeCounts.entries.sortedByDescending { it.value }.map { it.key },
                active, courseCount, instructors, byName, buildings, documents,
            )
        }

        fun makeInstructorDirectory(sections: List<CourseSection>): Pair<List<InstructorSummary>, Map<String, InstructorSummary>> {
            data class Entry(var display: String, var email: String?, val crns: MutableList<String>, val keys: MutableList<CourseKey>)
            val entries = LinkedHashMap<String, Entry>()
            for (s in sections) {
                for (i in s.instructors ?: emptyList()) {
                    val entry = entries.getOrPut(i.name) { Entry(i.displayName, i.email, mutableListOf(), mutableListOf()) }
                    entry.crns += s.crn
                    if (s.courseKey !in entry.keys) entry.keys += s.courseKey
                    if (entry.email == null) entry.email = i.email
                }
            }
            val list = entries.map { (name, e) ->
                InstructorSummary(name, e.display, e.email, e.crns, e.keys.sorted())
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            return list to list.associateBy { it.name }
        }

        fun sortedSections(list: List<CourseSection>): List<CourseSection> {
            val typeOrder = mapOf("Lecture" to 0, "Lab" to 1, "Recitation/Discussion" to 2, "Seminar" to 3)
            return list.sortedWith(compareBy<CourseSection> { typeOrder[it.classType] ?: 9 }.thenBy { it.classType }.thenBy { it.sectionNumber })
        }

        fun makeStats(list: List<CourseSection>): CourseStats {
            var sectionCount = 0
            var open = 0
            var seats = 0
            var capacity = 0
            var wait = 0
            val campuses = mutableSetOf<String>()
            val types = mutableSetOf<String>()
            var honors = false
            var online = false
            var inPerson = false
            var days = DaySet(0)
            val names = mutableListOf<String>()
            for (s in list) {
                sectionCount++
                if (s.isOpen) open++
                seats += s.seatsRemaining.coerceAtLeast(0)
                capacity += s.seatCapacity
                if (s.waitlistRemaining > 0) wait++
                if (s.campus.isNotEmpty()) campuses += s.campus
                types += s.classType
                honors = honors || s.honors
                if (s.isOnline) online = true else inPerson = true
                days = days.union(s.meetingDays)
                for (i in (s.instructors ?: emptyList()).sortedByDescending { it.primary }) {
                    if (i.displayName !in names) names += i.displayName
                }
            }
            return CourseStats(sectionCount, open, seats, capacity, wait, campuses, types, honors, online, inPerson, names, days)
        }

        fun makeDocument(course: Course, subjectName: String, stats: CourseStats?, sections: List<CourseSection>): SearchDocument =
            SearchDocument(
                course,
                subjectName,
                stats?.instructorNames ?: emptyList(),
                if (course.specialTopics) sections.map { it.title }.distinct() else emptyList(),
            )
    }
}
