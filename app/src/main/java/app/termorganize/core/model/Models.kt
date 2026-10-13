// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Android port of Term Organize (https://github.com/AlexmChadwick/Northeastern-Term-Organize).
// Portions of the requisite model are derived from SearchNEU (AGPL-3.0). See NOTICE.md.

package app.termorganize.core.model

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs

@Serializable
data class Term(
    val code: String,
    val bannerDescription: String,
) : Comparable<Term> {
    val id: String get() = code

    val name: String
        get() {
            var s = bannerDescription.replace("(View Only)", "", ignoreCase = true).trim()
            when (college) {
                College.NEU -> s = s.replace(" Semester", "")
                College.CPS, College.LAW -> {
                    for (marker in listOf(" CPS ", " Law ")) {
                        val i = s.indexOf(marker)
                        if (i >= 0) {
                            val head = s.substring(0, i)
                            val tail = s.substring(i).trim()
                            s = "$head · $tail"
                            break
                        }
                    }
                }
            }
            return s.trim()
        }

    val shortName: String get() = name.substringBefore(" · ")

    val isViewOnly: Boolean get() = bannerDescription.contains("view only", ignoreCase = true)

    val college: College
        get() = when (code.lastOrNull()) {
            '4', '5' -> College.CPS
            '2', '8' -> College.LAW
            else -> College.NEU
        }

    enum class College(val displayName: String) {
        NEU("Northeastern"),
        CPS("Professional Studies"),
        LAW("School of Law"),
    }

    enum class Season(val displayName: String) {
        FALL("Fall"),
        WINTER("Winter"),
        SPRING("Spring"),
        SUMMER1("Summer 1"),
        SUMMER_FULL("Summer"),
        SUMMER2("Summer 2"),
    }

    val season: Season
        get() {
            if (code.length != 6) return Season.FALL
            return when (code[4]) {
                '1' -> Season.FALL
                '2' -> Season.WINTER
                '3' -> Season.SPRING
                '4' -> Season.SUMMER1
                '6' -> Season.SUMMER2
                else -> Season.SUMMER_FULL
            }
        }

    val calendarYear: Int
        get() {
            val year = code.take(4).toIntOrNull() ?: 0
            return if (season == Season.FALL) year - 1 else year
        }

    override fun compareTo(other: Term): Int = code.compareTo(other.code)
}

@Serializable
data class CourseKey(
    val subject: String,
    val number: String,
) : Comparable<CourseKey> {
    val description: String get() = "$subject $number"
    val level: Int? get() = number.firstOrNull()?.digitToIntOrNull()
    val numericValue: Int get() = number.takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    override fun compareTo(other: CourseKey): Int {
        if (subject != other.subject) return subject.compareTo(other.subject)
        if (numericValue != other.numericValue) return numericValue.compareTo(other.numericValue)
        return number.compareTo(other.number)
    }

    companion object {
        fun parse(string: String): CourseKey? {
            val cleaned = string.uppercase(Locale.US).replace('-', ' ')
            val letters = cleaned.takeWhile { it.isLetter() }
            val rest = cleaned.drop(letters.length).trim()
            if (letters.isEmpty() || rest.isEmpty() || !rest.all { it.isLetter() || it.isDigit() }) return null
            return CourseKey(letters, rest)
        }
    }
}

@Serializable
data class Course(
    val subject: String,
    val number: String,
    val title: String,
    val description: String = "",
    val minCredits: Double = 0.0,
    val maxCredits: Double = 0.0,
    val attributes: List<String> = emptyList(),
    val prereqs: Requisite? = null,
    val coreqs: Requisite? = null,
    val postreqs: List<CourseKey> = emptyList(),
    val specialTopics: Boolean = false,
    val college: String? = null,
    val department: String? = null,
    val detailsLoaded: Boolean = false,
) {
    val key: CourseKey get() = CourseKey(subject, number)
    val code: String get() = key.description
    val creditsLabel: String get() = CreditFormatter.label(minCredits, maxCredits)
    val nupaths: List<NUPath> get() = NUPath.from(attributes)
}

object CreditFormatter {
    fun number(value: Double): String =
        if (abs(value % 1.0) < 1e-9) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

    fun label(min: Double, max: Double): String {
        val text = if (min == max) number(min) else "${number(min)}\u2013${number(max)}"
        val word = if (max == 1.0 && min == 1.0) "credit" else "credits"
        return "$text $word"
    }
}

@Serializable
data class Subject(val code: String, val name: String) {
    val id: String get() = code
}

@Serializable
data class AttributeInfo(val code: String, val name: String) {
    val id: String get() = code
}

@Serializable
data class Campus(val code: String, val name: String) {
    val id: String get() = code
    val isOnline: Boolean get() = name.contains("online", ignoreCase = true)
}

enum class NUPath(val attributeCode: String, val short: String, val title: String) {
    ND("NCND", "ND", "Natural & Designed World"),
    EI("NCEI", "EI", "Creative Expression & Innovation"),
    IC("NCIC", "IC", "Interpreting Culture"),
    FQ("NCFQ", "FQ", "Formal & Quantitative Reasoning"),
    SI("NCSI", "SI", "Societies & Institutions"),
    AD("NCAD", "AD", "Analyzing & Using Data"),
    DD("NCDD", "DD", "Difference & Diversity"),
    ER("NCER", "ER", "Ethical Reasoning"),
    WF("NCW1", "WF", "First-Year Writing"),
    WI("NCWI", "WI", "Writing Intensive"),
    WD("NCW3", "WD", "Advanced Writing in the Disciplines"),
    EX("NCEX", "EX", "Integration Experience"),
    CE("NCCE", "CE", "Capstone Experience"),
    ;

    val id: String get() = attributeCode

    companion object {
        fun from(attributeCodes: List<String>): List<NUPath> {
            val set = attributeCodes.toSet()
            return entries.filter { set.contains(it.attributeCode) }
        }

        fun fromShort(short: String): NUPath? =
            entries.firstOrNull { it.short.equals(short, ignoreCase = true) }
    }
}

const val HONORS_ATTRIBUTE_CODE = "GNHN"

@Serializable(with = DaySetSerializer::class)
data class DaySet(val rawValue: Int) {
    constructor(indices: Iterable<Int>) : this(indices.fold(0) { acc, i ->
        if (i in 0..6) acc or (1 shl i) else acc
    } and 0x7F)

    fun contains(day: Int): Boolean = day in 0..6 && rawValue and (1 shl day) != 0
    val indices: List<Int> get() = (0..6).filter { contains(it) }
    val count: Int get() = Integer.bitCount(rawValue and 0x7F)
    val isEmpty: Boolean get() = (rawValue and 0x7F) == 0
    fun isSubsetOf(other: DaySet): Boolean = rawValue and other.rawValue == rawValue
    fun isDisjoint(other: DaySet): Boolean = rawValue and other.rawValue == 0
    fun union(other: DaySet): DaySet = DaySet((rawValue or other.rawValue) and 0x7F)
    fun minus(dayBit: DaySet): DaySet = DaySet(rawValue and dayBit.rawValue.inv() and 0x7F)

    val compactLabel: String get() = indices.joinToString("") { LETTERS[it] }
    val shortLabel: String get() = indices.joinToString(", ") { SHORT[it] }

    companion object {
        val SUNDAY = DaySet(1 shl 0)
        val MONDAY = DaySet(1 shl 1)
        val TUESDAY = DaySet(1 shl 2)
        val WEDNESDAY = DaySet(1 shl 3)
        val THURSDAY = DaySet(1 shl 4)
        val FRIDAY = DaySet(1 shl 5)
        val SATURDAY = DaySet(1 shl 6)
        val WEEKDAYS = DaySet(listOf(1, 2, 3, 4, 5))
        val ALL = DaySet(0x7F)
        val LETTERS = listOf("U", "M", "T", "W", "R", "F", "S")
        val SHORT = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val FULL = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        fun of(index: Int) = DaySet(listOf(index))
    }
}

@Serializable
data class Meeting(
    val days: DaySet = DaySet(0),
    val start: Int? = null,
    val end: Int? = null,
    val buildingCode: String? = null,
    val building: String? = null,
    val room: String? = null,
    val campusCode: String? = null,
    val kind: String = "Class",
    val startDate: String? = null,
    val endDate: String? = null,
) {
    val isFinalExam: Boolean get() = kind.contains("final", ignoreCase = true)
    val hasTime: Boolean get() = start != null && end != null && !days.isEmpty
    val timeLabel: String get() {
        val s = start ?: return "Time TBA"
        val e = end ?: return "Time TBA"
        return TimeOfDay.rangeLabel(s, e)
    }
    val locationLabel: String get() {
        val b = buildingCode ?: building
        return when {
            b != null && room != null -> "$b $room"
            b != null -> b
            else -> "Location TBA"
        }
    }
    val longLocationLabel: String get() {
        val b = building ?: buildingCode
        return when {
            b != null && room != null -> "$b $room"
            b != null -> b
            else -> "Location TBA"
        }
    }
    val isOnline: Boolean get() {
        val b = (building ?: "") + (buildingCode ?: "")
        return b.contains("online", ignoreCase = true) || campusCode == "VTL"
    }
    val duration: Int? get() = if (start != null && end != null) end - start else null

    fun overlaps(other: Meeting): Boolean {
        val s = start ?: return false
        val e = end ?: return false
        val os = other.start ?: return false
        val oe = other.end ?: return false
        if (days.isDisjoint(other.days) || s >= oe || os >= e) return false
        return dateWindowsOverlap(startDate, endDate, other.startDate, other.endDate)
    }

    companion object {
        fun dateWindowsOverlap(aStart: String?, aEnd: String?, bStart: String?, bEnd: String?): Boolean =
            (aStart ?: "0000-00-00") <= (bEnd ?: "9999-99-99") && (bStart ?: "0000-00-00") <= (aEnd ?: "9999-99-99")
    }
}

@Serializable
data class Instructor(
    val name: String,
    val primary: Boolean = false,
    val email: String? = null,
) {
    val displayName: String get() = displayName(name)

    companion object {
        fun displayName(raw: String): String {
            val parts = raw.split(",", limit = 2).map { it.trim() }
            if (parts.size != 2 || parts[1].isEmpty()) return raw.trim()
            return "${parts[1]} ${parts[0]}"
        }
    }
}

@Serializable
data class CourseSection(
    val crn: String,
    val subject: String,
    val number: String,
    val title: String = "",
    val sectionNumber: String = "",
    val partOfTerm: String = "1",
    val seatCapacity: Int = 0,
    val seatsRemaining: Int = 0,
    val enrollment: Int = 0,
    val waitlistCapacity: Int = 0,
    val waitlistCount: Int = 0,
    val waitlistRemaining: Int = 0,
    val classType: String = "Lecture",
    val honors: Boolean = false,
    val campus: String = "",
    val instructionMethod: String? = null,
    val meetings: List<Meeting> = emptyList(),
    val instructors: List<Instructor>? = null,
    val crossListCRNs: List<String> = emptyList(),
    val attributes: List<String> = emptyList(),
    val description: String? = null,
    val prereqs: Requisite? = null,
    val coreqs: Requisite? = null,
) {
    val id: String get() = crn
    val courseKey: CourseKey get() = CourseKey(subject, number)
    val isOpen: Boolean get() = seatsRemaining > 0
    val hasWaitlist: Boolean get() = waitlistCapacity > 0
    val isFull: Boolean get() = seatsRemaining <= 0
    val fillFraction: Double
        get() = if (seatCapacity <= 0) 1.0 else (seatCapacity - seatsRemaining).toDouble().coerceIn(0.0, seatCapacity.toDouble()) / seatCapacity
    val isOnline: Boolean
        get() = campus.contains("online", ignoreCase = true) ||
            (instructionMethod?.contains("online", ignoreCase = true) == true)
    val weeklyMeetings: List<Meeting> get() = meetings.filter { !it.isFinalExam && it.hasTime }
    val finalExam: Meeting? get() = meetings.firstOrNull { it.isFinalExam }
    val meetingDays: DaySet get() = weeklyMeetings.fold(DaySet(0)) { acc, m -> acc.union(m.days) }
    val instructorLabel: String
        get() {
            val list = instructors ?: return "Instructor unavailable"
            if (list.isEmpty()) return "TBA"
            return list.sortedByDescending { it.primary }.joinToString(", ") { it.displayName }
        }
    val scheduleSummary: String
        get() {
            val weekly = weeklyMeetings
            if (weekly.isEmpty()) return if (isOnline) "Asynchronous / online" else "Times TBA"
            return weekly.joinToString(" · ") { "${it.days.compactLabel} ${it.timeLabel}" }
        }
    val seatsLabel: String get() = "${seatsRemaining.coerceAtLeast(0)} of $seatCapacity seats"

    fun conflicts(other: CourseSection): Boolean {
        val mine = weeklyMeetings
        val theirs = other.weeklyMeetings
        return mine.any { a -> theirs.any { a.overlaps(it) } }
    }
}

object TimeOfDay {
    fun minutesFromHHMM(value: Int): Int = (value / 100) * 60 + value % 100
    fun hhmm(minutes: Int): Int = (minutes / 60) * 100 + minutes % 60

    fun label(minutes: Int, includePeriod: Boolean = true): String {
        val h24 = (minutes / 60) % 24
        val m = minutes % 60
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val period = if (h24 < 12) "AM" else "PM"
        val base = String.format(Locale.US, "%d:%02d", h12, m)
        return if (includePeriod) "$base $period" else base
    }

    fun rangeLabel(start: Int, end: Int): String {
        val samePeriod = (start / 60 < 12) == (end / 60 < 12)
        return "${label(start, includePeriod = !samePeriod)} \u2013 ${label(end)}"
    }

    fun hourAxisLabel(hour: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "$h12${if (hour < 12 || hour == 24) "a" else "p"}"
    }
}

@Serializable
data class TermCatalog(
    val version: Int = 1,
    val term: Term,
    val generatedAt: Double = 0.0,
    val sectionsUpdatedAt: Double = 0.0,
    val courses: List<Course> = emptyList(),
    val sections: List<CourseSection> = emptyList(),
    val subjects: List<Subject> = emptyList(),
    val attributes: List<AttributeInfo> = emptyList(),
    val campuses: List<Campus> = emptyList(),
) {
    fun recomputePostreqs(): TermCatalog {
        val map = mutableMapOf<CourseKey, MutableSet<CourseKey>>()
        for (course in courses) {
            val prereqs = course.prereqs ?: continue
            for (key in prereqs.referencedCourses) {
                map.getOrPut(key) { mutableSetOf() }.add(course.key)
            }
        }
        val updated = courses.map { course ->
            val post = (map[course.key] ?: emptySet()).sorted()
            course.copy(postreqs = post)
        }
        return copy(courses = updated)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class CourseInfo(
    val key: CourseKey,
    val title: String,
    val minCredits: Double? = null,
    val maxCredits: Double? = null,
    val prerequisites: Requisite? = null,
    val corequisites: Requisite? = null,
    val equivalents: List<CourseKey> = emptyList(),
    val sourceTerm: String,
    val isRetired: Boolean? = null,
    val fetchedAt: Double = 0.0,
) {
    val creditsLabel: String?
        get() {
            val low = minCredits ?: maxCredits ?: return null
            return CreditFormatter.label(low, maxCredits ?: low)
        }
}
