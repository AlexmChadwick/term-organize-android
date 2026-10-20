// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Portions derived from SearchNEU Banner parsers (AGPL-3.0). See NOTICE.md.

package app.termorganize.core.banner

import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.HONORS_ATTRIBUTE_CODE
import app.termorganize.core.model.Instructor
import app.termorganize.core.model.Meeting
import app.termorganize.core.model.Requisite
import app.termorganize.core.model.Subject
import app.termorganize.core.model.TimeOfDay
import app.termorganize.core.model.nilIfBlank
import kotlinx.serialization.Serializable
import java.util.Locale

object HTMLText {
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "\u2013", "mdash" to "\u2014", "rsquo" to "\u2019", "lsquo" to "\u2018",
        "rdquo" to "\u201D", "ldquo" to "\u201C", "hellip" to "\u2026", "eacute" to "é",
        "copy" to "©", "reg" to "®", "middot" to "·", "bull" to "•",
    )

    fun decodeEntities(input: String): String {
        if ('&' !in input) return input
        val output = StringBuilder()
        var i = 0
        while (i < input.length) {
            if (input[i] != '&') {
                output.append(input[i]); i++; continue
            }
            val window = input.substring(i, minOf(input.length, i + 12))
            val semi = window.indexOf(';')
            if (semi < 0) {
                output.append(input[i]); i++; continue
            }
            val entity = input.substring(i + 1, i + semi)
            val replacement = when {
                entity.startsWith("#x", true) -> entity.drop(2).toIntOrNull(16)?.let { if (it in 0..0x10FFFF) it.toChar().toString() else null }
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let { if (it in 0..0x10FFFF) Character.toString(it) else null }
                else -> named[entity.lowercase(Locale.US)]
            }
            if (replacement != null) {
                output.append(replacement)
                i += semi + 1
            } else {
                output.append(input[i]); i++
            }
        }
        return output.toString()
    }

    fun plainText(html: String): String {
        var s = html
        s = s.replace(Regex("<!--[\\s\\S]*?-->"), "")
        s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<[^>]*>"), "")
        s = decodeEntities(decodeEntities(s))
        val lines = s.split('\n').map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        val result = mutableListOf<String>()
        for (line in lines) {
            if (line.isEmpty() && (result.lastOrNull()?.isEmpty() != false)) continue
            result += line
        }
        return result.joinToString("\n").trim()
    }

    fun tableRows(html: String): List<List<String>>? {
        val bodyStart = Regex("<tbody", RegexOption.IGNORE_CASE).find(html) ?: return null
        val bodyEnd = Regex("</tbody>", RegexOption.IGNORE_CASE).find(html, bodyStart.range.last) ?: return null
        val body = html.substring(bodyStart.range.last + 1, bodyEnd.range.first)
        val rows = Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE).findAll(body)
        return rows.map { row ->
            Regex("<td[^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE).findAll(row.groupValues[1])
                .map { plainText(it.groupValues[1]) }
                .toList()
        }.toList()
    }
}

object BannerParsers {
    class SubjectLookup(subjects: List<Subject>) {
        private val byName = buildMap {
            for (s in subjects) {
                put(s.name.lowercase(Locale.US), s.code)
                put(s.code.lowercase(Locale.US), s.code)
            }
        }
        fun code(name: String): String {
            val trimmed = name.trim()
            byName[trimmed.lowercase(Locale.US)]?.let { return it }
            val match = Regex("\\s+-\\s+[A-Z]{2,5}$").find(trimmed)
            if (match != null) {
                val head = trimmed.substring(0, match.range.first).lowercase(Locale.US)
                byName[head]?.let { return it }
            }
            return trimmed
        }
    }

    private class ConditionNode {
        var isAnd = false
        val items = mutableListOf<Item>()
        sealed class Item {
            data class Leaf(val r: Requisite) : Item()
            data class Node(val n: ConditionNode) : Item()
        }
        fun build(): Requisite? {
            val built = items.mapNotNull {
                when (it) {
                    is Item.Leaf -> it.r
                    is Item.Node -> it.n.build()
                }
            }
            if (built.isEmpty()) return null
            return if (isAnd) Requisite.And(built) else Requisite.Or(built)
        }
    }

    fun prerequisites(html: String, subjects: SubjectLookup): Requisite? {
        val rows = HTMLText.tableRows(html) ?: return null
        if (rows.isEmpty()) return null
        val root = ConditionNode()
        val stack = ArrayDeque<ConditionNode>()
        stack.add(root)
        var current = root
        for (cells in rows) {
            fun cell(i: Int) = if (i < cells.size) cells[i].trim() else ""
            if (cells.size < 6) continue
            when (cell(0).lowercase(Locale.US)) {
                "and" -> current.isAnd = true
                "or" -> current.isAnd = false
            }
            if (cell(1).isNotEmpty()) {
                val child = ConditionNode()
                current.items += ConditionNode.Item.Node(child)
                stack.add(child)
                current = child
            }
            val testName = cell(2)
            if (testName.isNotEmpty()) {
                current.items += ConditionNode.Item.Leaf(Requisite.TestReq(testName, cell(3).toDoubleOrNull()))
            }
            val subjectName = cell(4)
            val number = cell(5)
            if (subjectName.isNotEmpty() && number.isNotEmpty()) {
                current.items += ConditionNode.Item.Leaf(Requisite.CourseReq(CourseKey(subjects.code(subjectName), number)))
            }
            if (cell(8).isNotEmpty() && stack.size > 1) {
                stack.removeLast()
                current = stack.last()
            }
        }
        return root.build()?.simplified()
    }

    fun corequisites(html: String, subjects: SubjectLookup): Requisite? {
        val rows = HTMLText.tableRows(html) ?: return null
        val items = rows.mapNotNull { cells ->
            val (subjectName, number) = when {
                cells.size == 3 -> cells[0] to cells[1]
                cells.size >= 4 -> cells[1] to cells[2]
                else -> return@mapNotNull null
            }
            if (subjectName.isEmpty() || number.isEmpty()) null
            else Requisite.CourseReq(CourseKey(subjects.code(subjectName), number))
        }
        return when (items.size) {
            0 -> null
            1 -> items[0]
            else -> Requisite.And(items)
        }
    }

    fun description(html: String): String {
        val text = HTMLText.plainText(html)
        val lowered = text.lowercase(Locale.US)
        if (lowered.startsWith("no course description") || "no information available" in lowered) return ""
        return text
    }

    data class CatalogDetails(
        val title: String? = null,
        val college: String? = null,
        val department: String? = null,
        val minCredits: Double? = null,
        val maxCredits: Double? = null,
    )

    fun catalogDetails(html: String): CatalogDetails {
        val lines = HTMLText.plainText(html).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        fun value(label: String): String? {
            val i = lines.indexOfFirst { it.startsWith(label) }
            if (i < 0) return null
            val inline = lines[i].drop(label.length).trim()
            if (inline.isNotEmpty()) return inline
            if (i + 1 >= lines.size || lines[i + 1].endsWith(":")) return null
            return lines[i + 1]
        }
        fun strip(s: String?): String? {
            if (s == null) return null
            val parts = s.split(' ').toMutableList()
            val last = parts.lastOrNull()
            if (parts.size > 1 && last != null && last.length <= 4 && last == last.uppercase(Locale.US) && last.all { it.isLetter() || it.isDigit() }) {
                parts.removeAt(parts.lastIndex)
            }
            return parts.joinToString(" ")
        }
        val credits = value("Credit Hours:")?.let { creditRange(it) }
        return CatalogDetails(
            value("Title:"),
            strip(value("College:"))?.let { expandCollegeName(it) },
            strip(value("Department:"))?.let { expandCollegeName(it) },
            credits?.first,
            credits?.second,
        )
    }

    fun creditRange(text: String): Pair<Double, Double>? {
        val numbers = Regex("\\d+(?:\\.\\d+)?").findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList()
        val first = numbers.firstOrNull() ?: return null
        val second = if (numbers.size > 1) numbers[1] else first
        return minOf(first, second) to maxOf(first, second)
    }

    fun expandCollegeName(s: String): String {
        var out = " $s "
        val replacements = listOf(
            " Coll of " to " College of ", " Col of " to " College of ", " Coll " to " College ",
            " Comp Sciences " to " Computer Sciences ", " Hlth " to " Health ", " Sci " to " Science ",
            " Engr " to " Engineering ", " Prof " to " Professional ", " School Business " to " School of Business ",
            " Soc Sci " to " Social Sciences ",
        )
        for ((from, to) in replacements) out = out.replace(from, to)
        return out.trim()
    }

    fun catalogCodes(items: List<BannerCodeDescription>, course: CourseKey): Boolean {
        val wanted = normalizedCode("${course.subject}${course.number}")
        return items.any { normalizedCode(it.code) == wanted }
    }

    private fun normalizedCode(text: String) = text.filter { !it.isWhitespace() }.uppercase(Locale.US)

    fun mutualExclusions(html: String, subjects: SubjectLookup): List<CourseKey> {
        val text = HTMLText.plainText(html)
        if (text.contains("no mutual exclusion", ignoreCase = true)) return emptyList()
        val keys = mutableListOf<CourseKey>()
        fun add(key: CourseKey) { if (key !in keys) keys += key }
        for (cells in HTMLText.tableRows(html) ?: emptyList()) {
            val trimmed = cells.map { it.trim() }
            for (i in 0 until trimmed.size - 1) {
                if (isSubjectText(trimmed[i]) && isCourseNumber(trimmed[i + 1])) {
                    add(CourseKey(subjects.code(trimmed[i]), trimmed[i + 1]))
                    break
                }
            }
        }
        if (keys.isEmpty()) {
            for (match in Regex("\\b([A-Z]{2,6})\\s?(\\d{3,4}[A-Z]?)\\b").findAll(text)) {
                add(CourseKey(match.groupValues[1], match.groupValues[2]))
            }
        }
        return keys
    }

    private fun isSubjectText(text: String) = text.any { it.isLetter() } && !isCourseNumber(text)
    private fun isCourseNumber(text: String) =
        text.length in 3..5 && text.firstOrNull()?.isDigit() == true && text.all { it.isLetter() || it.isDigit() }

    data class CrossListedSection(val crn: String, val course: CourseKey, val title: String, val section: String)

    fun crossListedSections(html: String, subjects: SubjectLookup): List<CrossListedSection> {
        val rows = HTMLText.tableRows(html) ?: return emptyList()
        return rows.mapNotNull { cells ->
            if (cells.size < 4) return@mapNotNull null
            val crn = cells[0].trim()
            val subject = cells[1].trim()
            val number = cells[2].trim()
            if (crn.isEmpty() || !crn.all { it.isDigit() } || subject.isEmpty() || number.isEmpty()) return@mapNotNull null
            CrossListedSection(crn, CourseKey(subjects.code(subject), number), cells[3].trim(), if (cells.size > 4) cells[4].trim() else "")
        }
    }
}

@Serializable
data class BannerTerm(val code: String = "", val description: String = "")

@Serializable
data class BannerCodeDescription(val code: String = "", val description: String = "")

@Serializable
data class BannerSectionPage(val success: Boolean? = null, val totalCount: Int = 0, val data: List<BannerSection>? = null)

@Serializable
data class BannerSection(
    val term: String? = null,
    val courseReferenceNumber: String = "",
    val partOfTerm: String? = null,
    val courseNumber: String = "",
    val subject: String = "",
    val subjectDescription: String? = null,
    val sequenceNumber: String? = null,
    val campusDescription: String? = null,
    val scheduleTypeDescription: String? = null,
    val courseTitle: String? = null,
    val creditHours: Double? = null,
    val maximumEnrollment: Int? = null,
    val enrollment: Int? = null,
    val seatsAvailable: Int? = null,
    val waitCapacity: Int? = null,
    val waitCount: Int? = null,
    val waitAvailable: Int? = null,
    val crossList: String? = null,
    val crossListCapacity: Int? = null,
    val crossListCount: Int? = null,
    val crossListAvailable: Int? = null,
    val creditHourHigh: Double? = null,
    val creditHourLow: Double? = null,
    val openSection: Boolean? = null,
    val meetingsFaculty: List<BannerMeetingFaculty>? = null,
    val sectionAttributes: List<BannerSectionAttribute>? = null,
    val instructionalMethod: String? = null,
    val instructionalMethodDescription: String? = null,
)

@Serializable
data class BannerSectionAttribute(val code: String = "", val description: String? = null)

@Serializable
data class BannerMeetingFaculty(
    val courseReferenceNumber: String? = null,
    val faculty: List<BannerFaculty>? = null,
    val meetingTime: BannerMeetingTime? = null,
)

@Serializable
data class BannerFaculty(
    val displayName: String? = null,
    val emailAddress: String? = null,
    val primaryIndicator: Boolean? = null,
) {
    fun toInstructor(): Instructor? {
        val name = displayName?.let { HTMLText.decodeEntities(HTMLText.decodeEntities(it)) }.nilIfBlank() ?: return null
        return Instructor(name, primaryIndicator ?: false, Instructor.workEmail(emailAddress))
    }
}

@Serializable
data class BannerMeetingTime(
    val beginTime: String? = null,
    val endTime: String? = null,
    val building: String? = null,
    val buildingDescription: String? = null,
    val room: String? = null,
    val campus: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val meetingType: String? = null,
    val meetingTypeDescription: String? = null,
    val monday: Boolean? = null,
    val tuesday: Boolean? = null,
    val wednesday: Boolean? = null,
    val thursday: Boolean? = null,
    val friday: Boolean? = null,
    val saturday: Boolean? = null,
    val sunday: Boolean? = null,
) {
    val daySet: DaySet
        get() {
            var raw = 0
            if (sunday == true) raw = raw or DaySet.SUNDAY.rawValue
            if (monday == true) raw = raw or DaySet.MONDAY.rawValue
            if (tuesday == true) raw = raw or DaySet.TUESDAY.rawValue
            if (wednesday == true) raw = raw or DaySet.WEDNESDAY.rawValue
            if (thursday == true) raw = raw or DaySet.THURSDAY.rawValue
            if (friday == true) raw = raw or DaySet.FRIDAY.rawValue
            if (saturday == true) raw = raw or DaySet.SATURDAY.rawValue
            return DaySet(raw)
        }

    /** Search results and getFacultyMeetingTimes list the same meeting in different orders. */
    fun matches(other: BannerMeetingTime?): Boolean {
        if (other == null) return false
        return meetingType == other.meetingType && beginTime == other.beginTime && endTime == other.endTime &&
            monday == other.monday && tuesday == other.tuesday && wednesday == other.wednesday &&
            thursday == other.thursday && friday == other.friday && saturday == other.saturday && sunday == other.sunday
    }

    fun toMeeting(): Meeting {
        val start = beginTime?.toIntOrNull()?.let { TimeOfDay.minutesFromHHMM(it) }
        val end = endTime?.toIntOrNull()?.let { TimeOfDay.minutesFromHHMM(it) }
        val cleanRoom = room?.trim()?.takeIf { it.isNotEmpty() && !it.equals("ROOM", true) }
        val kind = if (meetingType == "FNEX") "Final Exam" else HTMLText.decodeEntities(meetingTypeDescription ?: "Class")
        return Meeting(
            days = daySet,
            start = start,
            end = end,
            buildingCode = building.nilIfBlank(),
            building = buildingDescription?.let { HTMLText.decodeEntities(it) }.nilIfBlank(),
            room = cleanRoom,
            campusCode = campus.nilIfBlank(),
            kind = kind,
            startDate = startDate?.let { BannerDate.iso(it) },
            endDate = endDate?.let { BannerDate.iso(it) },
        )
    }
}

/**
 * Banner’s class-search payload leaves `faculty` empty. Names come from
 * `getFacultyMeetingTimes`, which is not in the same order as the search row.
 */
fun BannerSection.withFacultyMeetings(fmt: List<BannerMeetingFaculty>): BannerSection {
    if (fmt.none { !it.faculty.isNullOrEmpty() }) return this
    val current = meetingsFaculty
    if (current.isNullOrEmpty()) return copy(meetingsFaculty = fmt)
    val used = BooleanArray(fmt.size)
    val merged = current.map { base ->
        val match = fmt.indices.firstOrNull { index -> !used[index] && base.meetingTime?.matches(fmt[index].meetingTime) == true }
        if (match == null) base
        else {
            used[match] = true
            val names = fmt[match].faculty?.takeIf { it.isNotEmpty() } ?: base.faculty
            base.copy(faculty = names)
        }
    }
    return copy(meetingsFaculty = merged)
}

@Serializable
data class BannerFacultyMeetingTimes(val fmt: List<BannerMeetingFaculty>? = null)

/**
 * `classSearch/getTerms` treats `offset` as a 1-based page index. A single
 * request with `max=40` is only the newest page and drops School of Law terms.
 */
object BannerTermList {
    const val PAGE_SIZE = 100

    suspend fun collect(pageSize: Int = PAGE_SIZE, load: suspend (page: Int) -> List<BannerTerm>): List<BannerTerm> {
        val all = LinkedHashMap<String, BannerTerm>()
        var page = 1
        while (page <= 30) {
            val batch = load(page)
            if (batch.isEmpty()) break
            for (term in batch) if (term.code.isNotEmpty()) all.putIfAbsent(term.code, term)
            if (batch.size < pageSize) break
            page += 1
        }
        return all.values.toList()
    }
}

object BannerDate {
    fun iso(banner: String): String? {
        val parts = banner.split("/")
        if (parts.size != 3) return null
        val m = parts[0].toIntOrNull() ?: return null
        val d = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        return "%04d-%02d-%02d".format(Locale.US, y, m, d)
    }
}

object CatalogBuilder {
    fun makeSections(banner: List<BannerSection>, previous: Map<String, app.termorganize.core.model.CourseSection> = emptyMap()): List<app.termorganize.core.model.CourseSection> {
        val crossLists = mutableMapOf<String, MutableList<String>>()
        for (s in banner) {
            val x = s.crossList.nilIfBlank() ?: continue
            crossLists.getOrPut(x) { mutableListOf() }.add(s.courseReferenceNumber)
        }
        return banner.map { s ->
            val attributes = (s.sectionAttributes ?: emptyList()).map { it.code }
            val honors = (s.sectionAttributes ?: emptyList()).any {
                it.code == HONORS_ATTRIBUTE_CODE || it.description?.trim() == "Honors"
            }
            val meetings = (s.meetingsFaculty ?: emptyList()).mapNotNull { it.meetingTime?.toMeeting() }
            val bannerInstructors = (s.meetingsFaculty ?: emptyList()).flatMap { it.faculty ?: emptyList() }.mapNotNull { it.toInstructor() }
            val prior = previous[s.courseReferenceNumber]
            val cross = s.crossList.nilIfBlank()?.let { crossLists[it] }?.filter { it != s.courseReferenceNumber } ?: emptyList()
            app.termorganize.core.model.CourseSection(
                crn = s.courseReferenceNumber,
                subject = s.subject,
                number = s.courseNumber,
                title = HTMLText.decodeEntities(HTMLText.decodeEntities(s.courseTitle ?: "")),
                sectionNumber = s.sequenceNumber ?: "",
                partOfTerm = s.partOfTerm ?: "1",
                seatCapacity = s.crossListCapacity ?: s.maximumEnrollment ?: 0,
                seatsRemaining = s.crossListAvailable ?: s.seatsAvailable ?: 0,
                enrollment = s.crossListCount ?: s.enrollment ?: 0,
                waitlistCapacity = s.waitCapacity ?: 0,
                waitlistCount = s.waitCount ?: 0,
                waitlistRemaining = s.waitAvailable ?: 0,
                classType = HTMLText.decodeEntities(s.scheduleTypeDescription ?: "Lecture"),
                honors = honors,
                campus = HTMLText.decodeEntities(s.campusDescription ?: ""),
                instructionMethod = s.instructionalMethodDescription?.let { HTMLText.decodeEntities(it) },
                meetings = meetings,
                instructors = if (bannerInstructors.isEmpty()) prior?.instructors else bannerInstructors,
                crossListCRNs = cross,
                attributes = attributes,
                description = prior?.description,
                prereqs = prior?.prereqs,
                coreqs = prior?.coreqs,
            )
        }
    }

    fun stubCourses(banner: List<BannerSection>): Pair<List<app.termorganize.core.model.Course>, List<app.termorganize.core.model.AttributeInfo>> {
        val courses = LinkedHashMap<CourseKey, app.termorganize.core.model.Course>()
        val attributes = LinkedHashMap<String, String>()
        for (s in banner) {
            val key = CourseKey(s.subject, s.courseNumber)
            val title = HTMLText.decodeEntities(HTMLText.decodeEntities(s.courseTitle ?: ""))
            val sectionAttributes = s.sectionAttributes ?: emptyList()
            for (a in sectionAttributes) attributes[a.code] = HTMLText.decodeEntities(a.description ?: a.code).trim()
            val isTopics = sectionAttributes.any { it.code == "TOPC" }
            val existing = courses[key]
            if (existing != null) {
                val codes = (existing.attributes + sectionAttributes.map { it.code }.filter { it != HONORS_ATTRIBUTE_CODE }).toSortedSet()
                var next = existing.copy(attributes = codes.toList())
                if (!next.specialTopics && isTopics) next = next.copy(specialTopics = true, title = "Special Topics")
                courses[key] = next
            } else {
                val special = title.contains("Special Topics", true) || isTopics
                val low = s.creditHourLow ?: s.creditHours ?: 0.0
                courses[key] = app.termorganize.core.model.Course(
                    subject = s.subject,
                    number = s.courseNumber,
                    title = if (special) "Special Topics" else title,
                    description = "",
                    minCredits = low,
                    maxCredits = s.creditHourHigh ?: low,
                    attributes = sectionAttributes.map { it.code }.filter { it != HONORS_ATTRIBUTE_CODE }.sorted(),
                    specialTopics = special,
                    detailsLoaded = false,
                )
            }
        }
        val attributeList = attributes.map { app.termorganize.core.model.AttributeInfo(it.key, it.value) }.sortedBy { it.code }
        return courses.values.toList() to attributeList
    }

    /**
     * Merges a fresh section scrape into an existing catalog.
     * Seats, times, and locations come from [fresh]. Instructors and descriptions already known
     * are kept. Course details can also be borrowed from [knowledge] (other terms).
     */
    fun merge(
        existing: app.termorganize.core.model.TermCatalog?,
        fresh: List<BannerSection>,
        term: app.termorganize.core.model.Term,
        subjects: List<app.termorganize.core.model.Subject>? = null,
        campuses: List<app.termorganize.core.model.Campus>? = null,
        attributes: List<app.termorganize.core.model.AttributeInfo>? = null,
        knowledge: Map<CourseKey, app.termorganize.core.model.Course> = emptyMap(),
        now: Double = System.currentTimeMillis() / 1000.0,
    ): app.termorganize.core.model.TermCatalog {
        val previousSections = (existing?.sections ?: emptyList()).associateBy { it.crn }
        val previousCourses = (existing?.courses ?: emptyList()).associateBy { it.key }
        val sections = makeSections(fresh, previousSections)
        val (stubs, freshAttributes) = stubCourses(fresh)
        val courses = stubs.map { stub ->
            val known = previousCourses[stub.key]
            if (known != null) {
                return@map known.copy(
                    minCredits = stub.minCredits,
                    maxCredits = stub.maxCredits,
                    attributes = stub.attributes,
                    specialTopics = known.specialTopics || stub.specialTopics,
                )
            }
            val borrowed = knowledge[stub.key]
            if (borrowed != null) {
                return@map borrowed.copy(
                    minCredits = stub.minCredits,
                    maxCredits = stub.maxCredits,
                    attributes = stub.attributes,
                    specialTopics = borrowed.specialTopics || stub.specialTopics,
                    postreqs = emptyList(),
                )
            }
            stub
        }
        val mergedAttributes = LinkedHashMap<String, app.termorganize.core.model.AttributeInfo>()
        for (attribute in existing?.attributes ?: emptyList()) mergedAttributes[attribute.code] = attribute
        for (attribute in (attributes ?: emptyList()) + freshAttributes) mergedAttributes[attribute.code] = attribute
        return app.termorganize.core.model.TermCatalog(
            term = term,
            generatedAt = existing?.generatedAt ?: now,
            sectionsUpdatedAt = now,
            courses = courses.sortedBy { it.key },
            sections = sections.sortedWith(compareBy({ it.courseKey }, { it.sectionNumber })),
            subjects = subjects ?: existing?.subjects ?: emptyList(),
            attributes = mergedAttributes.values.sortedBy { it.code },
            campuses = campuses ?: existing?.campuses ?: emptyList(),
        ).recomputePostreqs()
    }

    /** Replaces the sections of one course after a live refresh. */
    fun applyCourseRefresh(
        catalog: app.termorganize.core.model.TermCatalog,
        course: CourseKey,
        banner: List<BannerSection>,
        now: Double = System.currentTimeMillis() / 1000.0,
    ): app.termorganize.core.model.TermCatalog {
        val previous = catalog.sections.filter { it.courseKey == course }.associateBy { it.crn }
        val fresh = makeSections(banner, previous)
        val sections = (catalog.sections.filter { it.courseKey != course } + fresh)
            .sortedWith(compareBy({ it.courseKey }, { it.sectionNumber }))
        var courses = catalog.courses
        if (courses.none { it.key == course } && banner.isNotEmpty()) {
            val (stubs, _) = stubCourses(banner)
            courses = (courses + stubs).sortedBy { it.key }
        }
        return catalog.copy(sectionsUpdatedAt = now, courses = courses, sections = sections)
    }
}
