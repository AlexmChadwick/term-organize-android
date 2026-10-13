// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Schedule generation is derived in part from SearchNEU's scheduler (AGPL-3.0). See NOTICE.md.

package app.termorganize.logic.schedule

import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Meeting
import app.termorganize.core.model.TimeOfDay
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID
import kotlin.math.min

@Serializable
enum class PreferenceStrength { PREFERRED, REQUIRED }
@Serializable
enum class TimeBand(val start: Int, val endExclusive: Int, val title: String, val detail: String) {
    EARLY_MORNING(0, 9 * 60, "Early morning", "Before 9 AM"),
    MORNING(9 * 60, 12 * 60, "Morning", "9 AM – noon"),
    MIDDAY(12 * 60, 14 * 60, "Midday", "Noon – 2 PM"),
    AFTERNOON(14 * 60, 17 * 60, "Afternoon", "2 – 5 PM"),
    EVENING(17 * 60, 19 * 60, "Evening", "5 – 7 PM"),
    NIGHT(19 * 60, 24 * 60, "Night", "After 7 PM"),
    ;
    fun overlap(startMin: Int, endMin: Int): Int = maxOf(0, min(endMin, endExclusive) - maxOf(startMin, start))
}
@Serializable
enum class TimePreference { AVOID, OKAY, PREFER }
@Serializable
enum class DayLayout { NO_PREFERENCE, FEWER_DAYS, SPREAD_OUT }
@Serializable
enum class GapPreference { COMPACT, NO_PREFERENCE, FINE }
@Serializable
enum class BreakPreference { FINE, AVOID_BACK_TO_BACK, AT_LEAST }
@Serializable
enum class FormatPreference { NO_PREFERENCE, IN_PERSON, HYBRID, ONLINE }
@Serializable
enum class SeatPolicy { ANY, WAITLIST_OK, OPEN_ONLY }
enum class ScheduleSortOption(val label: String) {
    BEST("Best overall"), FEWEST_DAYS("Fewest days on campus"), LATEST_START("Latest start"),
    EARLIEST_FINISH("Earliest finish"), FEWEST_GAPS("Fewest gaps"), MOST_SEATS("Most open seats"),
}

@Serializable
data class TimeLimit(val minutes: Int, val strength: PreferenceStrength = PreferenceStrength.REQUIRED)
@Serializable
data class LunchBreak(val start: Int = 11 * 60 + 30, val end: Int = 14 * 60, val minutes: Int = 30)
@Serializable
data class BlockedTime(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val days: DaySet,
    val start: Int,
    val end: Int,
    val strength: PreferenceStrength = PreferenceStrength.REQUIRED,
    val isEnabled: Boolean = true,
) {
    val isValid: Boolean get() = !days.isEmpty && end > start && start >= 0 && end <= 24 * 60
    val isActive: Boolean get() = isEnabled && isValid
    fun overlaps(meetingDays: DaySet, meetingStart: Int, meetingEnd: Int): Boolean =
        !days.isDisjoint(meetingDays) && meetingStart < end && start < meetingEnd
    val summary: String get() = "${days.shortLabel} ${TimeOfDay.rangeLabel(start, end)}"
}
@Serializable
data class CreditTarget(val lower: Int, val upper: Int) {
    val label: String get() = if (lower == upper) "$lower credits" else "$lower–$upper credits"
}

@Serializable
data class SchedulePreferences(
    val timeOfDay: Map<TimeBand, TimePreference> = emptyMap(),
    val earliestStart: TimeLimit? = null,
    val latestEnd: TimeLimit? = null,
    val daysOff: DaySet = DaySet(0),
    val daysOffStrength: PreferenceStrength = PreferenceStrength.PREFERRED,
    val dayLayout: DayLayout = DayLayout.NO_PREFERENCE,
    val maxClassesPerDay: Int? = null,
    val maxMinutesPerDay: Int? = null,
    val gaps: GapPreference = GapPreference.NO_PREFERENCE,
    val breaks: BreakPreference = BreakPreference.FINE,
    val minimumBreakMinutes: Int = 15,
    val minimumBreakStrength: PreferenceStrength = PreferenceStrength.PREFERRED,
    val lunch: LunchBreak? = null,
    val blockedTimes: List<BlockedTime> = emptyList(),
    val format: FormatPreference = FormatPreference.NO_PREFERENCE,
    val formatStrength: PreferenceStrength = PreferenceStrength.PREFERRED,
    val campus: String? = null,
    val campusStrength: PreferenceStrength = PreferenceStrength.PREFERRED,
    val seats: SeatPolicy = SeatPolicy.ANY,
    val preferredInstructors: List<String> = emptyList(),
    val avoidedInstructors: List<String> = emptyList(),
    val creditTarget: CreditTarget? = null,
) {
    fun band(band: TimeBand): TimePreference = timeOfDay[band] ?: TimePreference.OKAY
    fun withBand(band: TimeBand, preference: TimePreference) =
        copy(timeOfDay = if (preference == TimePreference.OKAY) timeOfDay - band else timeOfDay + (band to preference))

    val preferredBands get() = TimeBand.entries.filter { band(it) == TimePreference.PREFER }
    val avoidedBands get() = TimeBand.entries.filter { band(it) == TimePreference.AVOID }
    val hasTimeOfDayPreference get() = timeOfDay.isNotEmpty()
    val activeBlockedTimes get() = blockedTimes.filter { it.isActive }
    val activeCount: Int
        get() {
            var n = 0
            if (hasTimeOfDayPreference) n++
            if (earliestStart != null) n++
            if (latestEnd != null) n++
            if (!daysOff.isEmpty) n++
            if (dayLayout != DayLayout.NO_PREFERENCE) n++
            if (maxClassesPerDay != null || maxMinutesPerDay != null) n++
            if (gaps != GapPreference.NO_PREFERENCE) n++
            if (breaks != BreakPreference.FINE) n++
            if (lunch != null) n++
            n += activeBlockedTimes.size
            if (format != FormatPreference.NO_PREFERENCE) n++
            if (campus != null) n++
            if (seats != SeatPolicy.ANY) n++
            if (preferredInstructors.isNotEmpty() || avoidedInstructors.isNotEmpty()) n++
            if (creditTarget != null) n++
            return n
        }
    val hasWishes get() = activeCount > 0
    val isDefault get() = this == SchedulePreferences()

    fun normalized(): SchedulePreferences {
        fun clamp(limit: TimeLimit?, range: IntRange): TimeLimit? {
            limit ?: return null
            return limit.copy(minutes = limit.minutes.coerceIn(range))
        }
        var p = copy(
            earliestStart = clamp(earliestStart, 0..(23 * 60 + 45)),
            latestEnd = clamp(latestEnd, 30..(24 * 60)),
            maxClassesPerDay = maxClassesPerDay?.takeIf { it in 1..8 },
            maxMinutesPerDay = maxMinutesPerDay?.takeIf { it in 60..(12 * 60) },
            minimumBreakMinutes = minimumBreakMinutes.coerceIn(5, 90),
        )
        p.lunch?.let { lunch ->
            val start = lunch.start.coerceIn(9 * 60, 15 * 60)
            val end = lunch.end.coerceIn(start + 30, 17 * 60)
            val minutes = lunch.minutes.coerceIn(15, end - start)
            p = p.copy(lunch = LunchBreak(start, end, minutes))
        }
        val seen = HashSet<String>()
        val blocks = p.blockedTimes.mapNotNull { block ->
            var label = block.label.trim().ifEmpty { "Blocked time" }
            val start = block.start.coerceIn(0, 24 * 60)
            val end = block.end.coerceIn(0, 24 * 60)
            val next = block.copy(label = label, start = start, end = end)
            if (!next.isValid) return@mapNotNull null
            val key = "${label.lowercase(Locale.US)}|${next.days.rawValue}|${next.start}|${next.end}"
            if (!seen.add(key)) null else next
        }.take(20)
        val campus = p.campus?.trim()?.ifEmpty { null }
        fun cleaned(names: List<String>): List<String> {
            val seenNames = HashSet<String>()
            return names.map { it.trim() }.filter { it.isNotEmpty() && seenNames.add(it.lowercase(Locale.US)) }.take(30)
        }
        val preferred = cleaned(p.preferredInstructors)
        val preferredSet = preferred.map { it.lowercase(Locale.US) }.toSet()
        val avoided = cleaned(p.avoidedInstructors).filter { it.lowercase(Locale.US) !in preferredSet }
        val credits = p.creditTarget?.let {
            val low = it.lower.coerceIn(1, 30)
            CreditTarget(low, it.upper.coerceIn(low, 30))
        }
        return p.copy(blockedTimes = blocks, campus = campus, preferredInstructors = preferred, avoidedInstructors = avoided, creditTarget = credits)
    }

    val summaryParts: List<String>
        get() {
            val parts = mutableListOf<String>()
            timePhrase()?.let { parts += it }
            earliestStart?.let {
                parts += if (it.strength == PreferenceStrength.REQUIRED) "Nothing before ${shortTime(it.minutes)}" else "Prefer after ${shortTime(it.minutes)}"
            }
            latestEnd?.let {
                parts += if (it.strength == PreferenceStrength.REQUIRED) "Done by ${shortTime(it.minutes)}" else "Prefer done by ${shortTime(it.minutes)}"
            }
            if (!daysOff.isEmpty) parts += daysOffPhrase(daysOff)
            when (dayLayout) {
                DayLayout.NO_PREFERENCE -> Unit
                DayLayout.FEWER_DAYS -> parts += "Fewer days"
                DayLayout.SPREAD_OUT -> parts += "Spread out"
            }
            val blocked = activeBlockedTimes.size
            if (blocked > 0) parts += if (blocked == 1) "1 blocked time" else "$blocked blocked times"
            when (gaps) {
                GapPreference.NO_PREFERENCE -> Unit
                GapPreference.COMPACT -> parts += "Short gaps"
                GapPreference.FINE -> parts += "Gaps are fine"
            }
            when (breaks) {
                BreakPreference.FINE -> Unit
                BreakPreference.AVOID_BACK_TO_BACK -> parts += "No back-to-back"
                BreakPreference.AT_LEAST -> parts += "$minimumBreakMinutes min to walk"
            }
            if (lunch != null) parts += "Lunch break"
            maxClassesPerDay?.let { parts += "Up to $it a day" }
            if (format != FormatPreference.NO_PREFERENCE) parts += format.title()
            campus?.let { parts += it }
            when (seats) {
                SeatPolicy.ANY -> Unit
                SeatPolicy.WAITLIST_OK -> parts += "Waitlists OK"
                SeatPolicy.OPEN_ONLY -> parts += "Open seats"
            }
            return parts
        }

    fun summary(limit: Int = 3): String {
        val parts = summaryParts
        if (parts.isEmpty()) return "No preferences yet"
        if (parts.size <= limit) return parts.joinToString(" · ")
        return (parts.take(limit) + "+${parts.size - limit}").joinToString(" · ")
    }

    private fun timePhrase(): String? {
        if (preferredBands.isNotEmpty()) {
            val text = bandPhrase(preferredBands)
            return text.replaceFirstChar { it.titlecase(Locale.US) }
        }
        if (avoidedBands.isNotEmpty()) return "No " + bandPhrase(avoidedBands)
        return null
    }

    companion object {
        const val BACK_TO_BACK_MINUTES = 30
        fun formatOf(section: CourseSection): SectionFormat {
            if (section.isOnline) return SectionFormat.ONLINE
            val method = (section.instructionMethod ?: "").lowercase(Locale.US)
            return when {
                "hybrid" in method -> SectionFormat.HYBRID
                "live cast" in method || "video" in method || "remote" in method -> SectionFormat.ONLINE
                "traditional" in method -> SectionFormat.IN_PERSON
                else -> SectionFormat.OTHER
            }
        }
        fun shortTime(minutes: Int) = TimeOfDay.label(minutes).replace(":00", "")
        fun daysOffPhrase(days: DaySet): String {
            val indices = days.indices
            return when (indices.size) {
                0 -> ""
                1 -> "${DaySet.FULL[indices[0]]}s off"
                else -> {
                    val names = indices.map { DaySet.SHORT[it] }
                    names.dropLast(1).joinToString(", ") + " & " + names.last() + " off"
                }
            }
        }
        fun bandPhrase(bands: List<TimeBand>): String {
            val set = bands.toSet()
            val items = mutableListOf<String>()
            if (TimeBand.MORNING in set) items += "mornings" else if (TimeBand.EARLY_MORNING in set) items += "early mornings"
            if (TimeBand.MIDDAY in set) items += "midday"
            if (TimeBand.AFTERNOON in set) items += "afternoons"
            if (TimeBand.EVENING in set) items += "evenings" else if (TimeBand.NIGHT in set) items += "nights"
            return when (items.size) {
                0 -> ""
                1 -> items[0]
                else -> items.dropLast(1).joinToString(", ") + " & " + items.last()
            }
        }
    }
}

private fun FormatPreference.title() = when (this) {
    FormatPreference.NO_PREFERENCE -> "No preference"
    FormatPreference.IN_PERSON -> "In person"
    FormatPreference.HYBRID -> "Hybrid"
    FormatPreference.ONLINE -> "Online"
}

enum class SectionFormat { IN_PERSON, HYBRID, ONLINE, OTHER }

fun SectionFormat.mismatch(preference: FormatPreference): Double = when (preference to this) {
    FormatPreference.NO_PREFERENCE to SectionFormat.IN_PERSON,
    FormatPreference.NO_PREFERENCE to SectionFormat.HYBRID,
    FormatPreference.NO_PREFERENCE to SectionFormat.ONLINE,
    FormatPreference.NO_PREFERENCE to SectionFormat.OTHER,
    FormatPreference.IN_PERSON to SectionFormat.OTHER,
    FormatPreference.ONLINE to SectionFormat.OTHER,
    FormatPreference.HYBRID to SectionFormat.OTHER,
    -> 0.0
    FormatPreference.IN_PERSON to SectionFormat.IN_PERSON,
    FormatPreference.ONLINE to SectionFormat.ONLINE,
    FormatPreference.HYBRID to SectionFormat.HYBRID,
    -> 0.0
    FormatPreference.IN_PERSON to SectionFormat.HYBRID,
    FormatPreference.ONLINE to SectionFormat.HYBRID,
    FormatPreference.HYBRID to SectionFormat.IN_PERSON,
    FormatPreference.HYBRID to SectionFormat.ONLINE,
    -> 0.5
    else -> 1.0
}

enum class SchedulePreset(val title: String, val detail: String) {
    MORNING_PERSON("Morning person", "Prefer classes before noon and avoid evenings."),
    NIGHT_OWL("Night owl", "Avoid mornings and prefer afternoons and evenings."),
    LONG_WEEKENDS("Long weekends", "Prefer Fridays off and as few days on campus as possible."),
    COMPACT_DAYS("Compact days", "Prefer few days on campus and short gaps between classes."),
    ;
    fun isApplied(p: SchedulePreferences): Boolean = when (this) {
        MORNING_PERSON -> p.timeOfDay == mapOf(
            TimeBand.EARLY_MORNING to TimePreference.PREFER, TimeBand.MORNING to TimePreference.PREFER,
            TimeBand.EVENING to TimePreference.AVOID, TimeBand.NIGHT to TimePreference.AVOID,
        )
        NIGHT_OWL -> p.timeOfDay == mapOf(
            TimeBand.EARLY_MORNING to TimePreference.AVOID, TimeBand.MORNING to TimePreference.AVOID,
            TimeBand.AFTERNOON to TimePreference.PREFER, TimeBand.EVENING to TimePreference.PREFER, TimeBand.NIGHT to TimePreference.PREFER,
        )
        LONG_WEEKENDS -> p.daysOff.contains(5) && p.dayLayout == DayLayout.FEWER_DAYS
        COMPACT_DAYS -> p.dayLayout == DayLayout.FEWER_DAYS && p.gaps == GapPreference.COMPACT
    }
    fun toggled(p: SchedulePreferences): SchedulePreferences = if (isApplied(p)) clear(p) else apply(p)
    fun apply(p: SchedulePreferences): SchedulePreferences = when (this) {
        MORNING_PERSON -> p.copy(timeOfDay = mapOf(
            TimeBand.EARLY_MORNING to TimePreference.PREFER, TimeBand.MORNING to TimePreference.PREFER,
            TimeBand.EVENING to TimePreference.AVOID, TimeBand.NIGHT to TimePreference.AVOID,
        ))
        NIGHT_OWL -> p.copy(timeOfDay = mapOf(
            TimeBand.EARLY_MORNING to TimePreference.AVOID, TimeBand.MORNING to TimePreference.AVOID,
            TimeBand.AFTERNOON to TimePreference.PREFER, TimeBand.EVENING to TimePreference.PREFER, TimeBand.NIGHT to TimePreference.PREFER,
        ))
        LONG_WEEKENDS -> p.copy(daysOff = p.daysOff.union(DaySet.FRIDAY), dayLayout = DayLayout.FEWER_DAYS)
        COMPACT_DAYS -> p.copy(dayLayout = DayLayout.FEWER_DAYS, gaps = GapPreference.COMPACT)
    }
    fun clear(p: SchedulePreferences): SchedulePreferences = when (this) {
        MORNING_PERSON, NIGHT_OWL -> p.copy(timeOfDay = emptyMap())
        LONG_WEEKENDS -> p.copy(daysOff = p.daysOff.minus(DaySet.FRIDAY), dayLayout = if (p.gaps != GapPreference.COMPACT) DayLayout.NO_PREFERENCE else p.dayLayout)
        COMPACT_DAYS -> p.copy(gaps = GapPreference.NO_PREFERENCE, dayLayout = if (!p.daysOff.contains(5)) DayLayout.NO_PREFERENCE else p.dayLayout)
    }
}

@Serializable
data class ScheduleFilters(
    val earliestStart: Int? = null,
    val latestEnd: Int? = null,
    val freeDays: DaySet = DaySet(0),
    val includeHonors: Boolean = true,
    val includeOnline: Boolean = true,
    val hideFullSections: Boolean = false,
    val minSeats: Int = 0,
    val campus: String? = null,
    val courseCount: Int? = null,
) {
    val activeCount: Int
        get() {
            var n = 0
            if (earliestStart != null) n++
            if (latestEnd != null) n++
            if (!freeDays.isEmpty) n++
            if (!includeHonors) n++
            if (!includeOnline) n++
            if (hideFullSections || minSeats > 0) n++
            if (campus != null) n++
            if (courseCount != null) n++
            return n
        }
}

class ScheduleSlotMask {
    private val words = LongArray(32)
    fun insert(meeting: Meeting) {
        val start = meeting.start ?: return
        val end = meeting.end ?: return
        for (day in meeting.days.indices) insert(day, start, end)
    }
    fun insert(day: Int, start: Int, end: Int) {
        if (day !in 0..6 || end <= start) return
        val first = maxOf(0, start / MINUTES)
        val lastExclusive = min(SLOTS_PER_DAY, maxOf(first + 1, (end + MINUTES - 1) / MINUTES))
        if (first >= lastExclusive) return
        for (slot in first until lastExclusive) {
            val bit = day * SLOTS_PER_DAY + slot
            words[bit shr 6] = words[bit shr 6] or (1L shl (bit and 63))
        }
    }
    fun conflicts(other: ScheduleSlotMask): Boolean {
        for (i in words.indices) if (words[i] and other.words[i] != 0L) return true
        return false
    }
    companion object {
        const val MINUTES = 5
        const val SLOTS_PER_DAY = 24 * 60 / 5
        fun of(section: CourseSection) = ScheduleSlotMask().also { mask -> section.weeklyMeetings.forEach { mask.insert(it) } }
    }
}

data class ScheduleCourseInput(
    val key: CourseKey,
    val isRequired: Boolean,
    val sections: List<CourseSection>,
    val excludedCRNs: Set<String> = emptySet(),
    val minCredits: Int = 0,
    val maxCredits: Int = 0,
)

data class ScheduleReason(val kind: Kind, val text: String) {
    enum class Kind { BENEFIT, CAVEAT }
    val isCaveat get() = kind == Kind.CAVEAT
}

data class GeneratedSchedule(
    val crns: List<String>,
    val sections: List<CourseSection>,
    val daysOnCampus: Int,
    val classDays: Int,
    val earliestStart: Int?,
    val latestEnd: Int?,
    val totalGapMinutes: Int,
    val minSeats: Int,
    val totalSeats: Int,
    val fullSections: Int,
    val score: Double,
    val reasons: List<ScheduleReason> = emptyList(),
) {
    val sortedCRNs: List<String> = crns.sorted()
    val id: String = sortedCRNs.joinToString("-")
}

data class ScheduleDiagnostics(
    val courses: List<CourseReport> = emptyList(),
    val conflictingPairs: List<Pair<CourseKey, CourseKey>> = emptyList(),
    val countProblem: String? = null,
    val suggestions: List<String> = emptyList(),
) {
    data class CourseReport(
        val key: CourseKey,
        val isRequired: Boolean,
        val totalSections: Int,
        val remainingSections: Int,
        val rejections: Map<String, Int>,
    ) {
        val isBlocked get() = remainingSections == 0
    }
}

data class ScheduleGenerationResult(
    val schedules: List<GeneratedSchedule> = emptyList(),
    val totalFound: Int = 0,
    val isExhaustive: Boolean = true,
    val diagnostics: ScheduleDiagnostics = ScheduleDiagnostics(),
)

object ScheduleGenerator {
    data class Limits(val maxResults: Int = 5_000, val maxNodes: Int = 1_500_000)

    fun hasNoCampus(section: CourseSection) = section.campus.contains("no campus", ignoreCase = true)

    fun generate(
        courses: List<ScheduleCourseInput>,
        filters: ScheduleFilters,
        preferences: SchedulePreferences = SchedulePreferences(),
        sort: ScheduleSortOption = ScheduleSortOption.BEST,
        limits: Limits = Limits(),
    ): ScheduleGenerationResult {
        val prefs = preferences.normalized()
        if (courses.isEmpty()) return ScheduleGenerationResult()
        val kept = mutableListOf<List<CourseSection>>()
        val reports = mutableListOf<ScheduleDiagnostics.CourseReport>()
        for (course in courses) {
            val remaining = mutableListOf<CourseSection>()
            val rejections = mutableMapOf<String, Int>()
            for (section in course.sections) {
                val reasons = rejectionReasons(section, filters, prefs, course.excludedCRNs)
                if (reasons.isEmpty()) remaining += section
                else reasons.forEach { rejections[it] = (rejections[it] ?: 0) + 1 }
            }
            kept += remaining
            reports += ScheduleDiagnostics.CourseReport(course.key, course.isRequired, course.sections.size, remaining.size, rejections)
        }
        var diagnostics = ScheduleDiagnostics(reports)
        val required = courses.indices.filter { courses[it].isRequired }
        val optional = courses.indices.filter { !courses[it].isRequired && kept[it].isNotEmpty() }
        val target = filters.courseCount?.takeIf { it > 0 }
        if (required.any { kept[it].isEmpty() }) {
            diagnostics = diagnostics.copy(suggestions = suggestions(courses, kept, filters, prefs))
            return ScheduleGenerationResult(diagnostics = diagnostics)
        }
        if (target != null) {
            if (required.size > target) {
                return ScheduleGenerationResult(diagnostics = diagnostics.copy(countProblem = "More required courses (${required.size}) than the ${target} you asked for."))
            }
            if (required.size + optional.size < target) {
                return ScheduleGenerationResult(diagnostics = diagnostics.copy(countProblem = "Only ${required.size + optional.size} courses can be scheduled, and you asked for $target."))
            }
        }
        val order = required.sortedBy { kept[it].size } + optional.sortedBy { kept[it].size }
        if (order.isEmpty()) return ScheduleGenerationResult(diagnostics = diagnostics)
        data class Slot(val courseIndex: Int, val sections: List<CourseSection>, val masks: List<ScheduleSlotMask>, val required: Boolean)
        val slots = order.map { i ->
            val sections = kept[i]
            Slot(i, sections, sections.map { ScheduleSlotMask.of(it) }, courses[i].isRequired)
        }
        val found = mutableListOf<GeneratedSchedule>()
        var total = 0
        var nodes = 0
        var exhaustive = true
        val chosen = IntArray(slots.size) { -1 }
        fun conflictsWith(slot: Int, sectionIndex: Int): Boolean {
            val mask = slots[slot].masks[sectionIndex]
            val meetings = slots[slot].sections[sectionIndex].weeklyMeetings
            for (s in 0 until slot) {
                val pick = chosen[s]
                if (pick < 0) continue
                if (mask.conflicts(slots[s].masks[pick]) && meetingsConflict(meetings, slots[s].sections[pick].weeklyMeetings)) return true
                if (prefs.breaks == BreakPreference.AT_LEAST && prefs.minimumBreakStrength == PreferenceStrength.REQUIRED) {
                    if (SchedulePreferenceRules.tooClose(slots[slot].sections[sectionIndex], meetings, slots[s].sections[pick], slots[s].sections[pick].weeklyMeetings, prefs.minimumBreakMinutes)) return true
                }
            }
            return false
        }
        fun record() {
            val picks = mutableListOf<Pair<Int, CourseSection>>()
            for (s in slots.indices) {
                val pick = chosen[s]
                if (pick < 0) continue
                picks += slots[s].courseIndex to slots[s].sections[pick]
            }
            if (target != null && picks.size != target) return
            if (target == null && prefs.creditTarget == null) {
                for (s in slots.indices) {
                    if (chosen[s] >= 0 || slots[s].required) continue
                    if (slots[s].sections.indices.any { !conflictsWith(s, it) }) return
                }
            }
            val sections = picks.sortedBy { it.first }.map { it.second }
            total++
            if (found.size < limits.maxResults) found += materialize(sections, prefs)
            else exhaustive = false
        }
        fun dfs(slot: Int) {
            if (nodes++ > limits.maxNodes) { exhaustive = false; return }
            if (slot == slots.size) { record(); return }
            val current = slots[slot]
            if (!current.required) {
                chosen[slot] = -1
                dfs(slot + 1)
                if (!exhaustive && nodes > limits.maxNodes) return
            }
            for (i in current.sections.indices) {
                if (conflictsWith(slot, i)) continue
                chosen[slot] = i
                dfs(slot + 1)
                if (!exhaustive && nodes > limits.maxNodes) return
            }
            chosen[slot] = -1
        }
        dfs(0)
        val pairs = mutableListOf<Pair<CourseKey, CourseKey>>()
        if (total == 0 && exhaustive) {
            for (a in slots.indices) for (b in slots.indices) if (b > a && slots[a].required && slots[b].required) {
                val ok = slots[a].sections.indices.any { i ->
                    slots[b].sections.indices.any { j ->
                        val ma = slots[a].masks[i]
                        val mb = slots[b].masks[j]
                        !(ma.conflicts(mb) && meetingsConflict(slots[a].sections[i].weeklyMeetings, slots[b].sections[j].weeklyMeetings))
                    }
                }
                if (!ok) pairs += courses[slots[a].courseIndex].key to courses[slots[b].courseIndex].key
            }
        }
        val suggestions = if (total == 0) suggestions(courses, kept, filters, prefs) else emptyList()
        val sorted = sortSchedules(found, sort)
        return ScheduleGenerationResult(sorted, total, exhaustive, diagnostics.copy(conflictingPairs = pairs, suggestions = suggestions))
    }

    private fun meetingsConflict(a: List<Meeting>, b: List<Meeting>) = a.any { x -> b.any { x.overlaps(it) } }

    fun rejectionReasons(section: CourseSection, filters: ScheduleFilters, preferences: SchedulePreferences, excluded: Set<String>): List<String> {
        val reasons = mutableListOf<String>()
        if (section.crn in excluded) reasons += "excluded"
        val weekly = section.weeklyMeetings
        if (filters.earliestStart != null && weekly.any { (it.start ?: Int.MAX_VALUE) < filters.earliestStart }) reasons += "starts too early"
        if (filters.latestEnd != null && weekly.any { (it.end ?: 0) > filters.latestEnd }) reasons += "ends too late"
        if (!filters.freeDays.isEmpty && weekly.any { !it.days.isDisjoint(filters.freeDays) }) reasons += "free day"
        if (!filters.includeHonors && section.honors) reasons += "honors"
        if (!filters.includeOnline && section.isOnline) reasons += "online"
        if (filters.hideFullSections && section.seatsRemaining <= 0) reasons += "full"
        if (filters.minSeats > 0 && section.seatsRemaining < filters.minSeats) reasons += "seats"
        if (filters.campus != null && !section.isOnline && !hasNoCampus(section) && section.campus != filters.campus) reasons += "campus"
        if (!preferences.isDefault) reasons += SchedulePreferenceRules.rejectionLabels(section, preferences)
        return reasons
    }

    private fun materialize(sections: List<CourseSection>, preferences: SchedulePreferences): GeneratedSchedule {
        val days = mutableSetOf<Int>()
        val classDays = mutableSetOf<Int>()
        var earliest = Int.MAX_VALUE
        var latest = Int.MIN_VALUE
        var minSeats = Int.MAX_VALUE
        var totalSeats = 0
        var full = 0
        val byDay = mutableMapOf<Int, MutableList<Pair<Int, Int>>>()
        for (section in sections) {
            val seats = section.seatsRemaining.coerceAtLeast(0)
            minSeats = min(minSeats, seats)
            totalSeats += seats
            if (seats == 0 && section.seatCapacity > 0) full++
            for (m in section.weeklyMeetings) {
                val s = m.start ?: continue
                val e = m.end ?: continue
                earliest = min(earliest, s)
                latest = maxOf(latest, e)
                for (d in m.days.indices) {
                    classDays += d
                    if (!section.isOnline && !m.isOnline) days += d
                    byDay.getOrPut(d) { mutableListOf() }.add(s to e)
                }
            }
        }
        var gaps = 0
        for (list in byDay.values) {
            val sorted = list.sortedBy { it.first }
            for (i in 1 until sorted.size) {
                val gap = sorted[i].first - sorted[i - 1].second
                if (gap >= 20) gaps += gap
            }
        }
        val score = score(days.size, classDays.size, earliest, latest, gaps, full, if (minSeats == Int.MAX_VALUE) 0 else minSeats, sections, preferences)
        val reasons = SchedulePreferenceRules.reasons(sections, preferences)
        return GeneratedSchedule(
            sections.map { it.crn }, sections, days.size, classDays.size,
            if (earliest == Int.MAX_VALUE) null else earliest,
            if (classDays.isEmpty()) null else latest,
            gaps, if (minSeats == Int.MAX_VALUE) 0 else minSeats, totalSeats, full, score, reasons,
        )
    }

    private fun score(campusDays: Int, classDays: Int, earliest: Int, latest: Int, gaps: Int, full: Int, minSeats: Int, sections: List<CourseSection>, p: SchedulePreferences): Double {
        var s = 100.0 - campusDays * 4.0 - full * 8.0 + minSeats * 0.15 - gaps * 0.02
        if (earliest != Int.MAX_VALUE) s += earliest / 120.0
        if (latest != Int.MIN_VALUE) s -= latest / 240.0
        if (!p.isDefault) s += sections.sumOf { SchedulePreferenceRules.sectionScore(it, p) }
        if (p.dayLayout == DayLayout.FEWER_DAYS) s -= classDays * 6
        if (p.dayLayout == DayLayout.SPREAD_OUT) s += classDays * 3
        if (p.gaps == GapPreference.COMPACT) s -= gaps * 0.05
        return s
    }

    private fun sortSchedules(list: List<GeneratedSchedule>, sort: ScheduleSortOption): List<GeneratedSchedule> {
        val cmp = when (sort) {
            ScheduleSortOption.BEST -> compareByDescending<GeneratedSchedule> { it.score }.thenBy { it.id }
            ScheduleSortOption.FEWEST_DAYS -> compareBy<GeneratedSchedule> { it.daysOnCampus }.thenByDescending { it.score }
            ScheduleSortOption.LATEST_START -> compareByDescending<GeneratedSchedule> { it.earliestStart ?: 0 }.thenByDescending { it.score }
            ScheduleSortOption.EARLIEST_FINISH -> compareBy<GeneratedSchedule> { it.latestEnd ?: Int.MAX_VALUE }.thenByDescending { it.score }
            ScheduleSortOption.FEWEST_GAPS -> compareBy<GeneratedSchedule> { it.totalGapMinutes }.thenByDescending { it.score }
            ScheduleSortOption.MOST_SEATS -> compareByDescending<GeneratedSchedule> { it.totalSeats }.thenByDescending { it.score }
        }
        return list.sortedWith(cmp)
    }

    fun suggestions(courses: List<ScheduleCourseInput>, kept: List<List<CourseSection>>, filters: ScheduleFilters, prefs: SchedulePreferences): List<String> {
        val out = mutableListOf<String>()
        courses.forEachIndexed { i, course ->
            if (course.isRequired && kept.getOrNull(i)?.isEmpty() == true) {
                out += "${course.key.description} has no section that fits the current constraints."
            }
        }
        if (filters.earliestStart != null) out += "Try an earlier start than ${TimeOfDay.label(filters.earliestStart)}."
        if (filters.latestEnd != null) out += "Try a later finish than ${TimeOfDay.label(filters.latestEnd)}."
        if (!filters.freeDays.isEmpty) out += "Allow classes on ${filters.freeDays.shortLabel}."
        if (filters.hideFullSections || filters.minSeats > 0 || prefs.seats == SeatPolicy.OPEN_ONLY) out += "Include full sections or a waitlist."
        if (prefs.activeBlockedTimes.isNotEmpty()) out += "Shorten or turn off a blocked time."
        if (out.isEmpty()) out += "Two required courses overlap in every combination. Move one to another semester if your degree plan allows it."
        return out.distinct()
    }
}

object SchedulePreferenceRules {
    fun rejectionLabels(section: CourseSection, p: SchedulePreferences): List<String> {
        val reasons = mutableListOf<String>()
        val weekly = section.weeklyMeetings
        if (p.earliestStart?.strength == PreferenceStrength.REQUIRED && weekly.any { (it.start ?: Int.MAX_VALUE) < p.earliestStart.minutes }) reasons += "before earliest start"
        if (p.latestEnd?.strength == PreferenceStrength.REQUIRED && weekly.any { (it.end ?: 0) > p.latestEnd.minutes }) reasons += "after latest end"
        if (p.daysOffStrength == PreferenceStrength.REQUIRED && !p.daysOff.isEmpty && weekly.any { !it.days.isDisjoint(p.daysOff) }) reasons += "day off"
        if (p.blockedTimes.any { it.isActive && it.strength == PreferenceStrength.REQUIRED && overlaps(it, weekly) }) reasons += "blocked time"
        if (p.format != FormatPreference.NO_PREFERENCE && p.formatStrength == PreferenceStrength.REQUIRED && SchedulePreferences.formatOf(section).mismatch(p.format) > 0) reasons += "format"
        if (p.campus != null && p.campusStrength == PreferenceStrength.REQUIRED && !section.isOnline && !ScheduleGenerator.hasNoCampus(section) && section.campus != p.campus) reasons += "campus preference"
        when (p.seats) {
            SeatPolicy.ANY -> Unit
            SeatPolicy.OPEN_ONLY -> if (section.seatCapacity > 0 && section.seatsRemaining <= 0) reasons += "seats"
            SeatPolicy.WAITLIST_OK -> if (section.seatCapacity > 0 && section.seatsRemaining <= 0 && section.waitlistRemaining <= 0) reasons += "seats"
        }
        if (p.maxMinutesPerDay != null) {
            val perDay = IntArray(7)
            for (m in weekly) {
                val dur = (m.end ?: 0) - (m.start ?: 0)
                for (d in m.days.indices) perDay[d] += dur
            }
            if (perDay.any { it > p.maxMinutesPerDay }) reasons += "too long a day"
        }
        return reasons
    }

    fun tooClose(a: CourseSection, aMeetings: List<Meeting>, b: CourseSection, bMeetings: List<Meeting>, minutes: Int): Boolean {
        if (minutes <= 0 || a.isOnline || b.isOnline) return false
        for (x in aMeetings) {
            if (x.isOnline) continue
            val xs = x.start ?: continue
            val xe = x.end ?: continue
            for (y in bMeetings) {
                if (y.isOnline) continue
                val ys = y.start ?: continue
                val ye = y.end ?: continue
                if (x.days.isDisjoint(y.days)) continue
                val gap = if (ys >= xe) ys - xe else if (xs >= ye) xs - ye else -1
                if (gap < 0 || gap >= minutes) continue
                if (!Meeting.dateWindowsOverlap(x.startDate, x.endDate, y.startDate, y.endDate)) continue
                val bx = x.buildingCode ?: x.building
                val by = y.buildingCode ?: y.building
                if (bx != null && by != null && bx == by) continue
                return true
            }
        }
        return false
    }

    private fun overlaps(block: BlockedTime, meetings: List<Meeting>) =
        meetings.any { m ->
            val s = m.start ?: return@any false
            val e = m.end ?: return@any false
            block.overlaps(m.days, s, e)
        }

    fun sectionScore(section: CourseSection, p: SchedulePreferences): Double {
        var score = 0.0
        val weekly = section.weeklyMeetings
        if (p.hasTimeOfDayPreference) {
            for (m in weekly) {
                val s = m.start ?: continue
                val e = m.end ?: continue
                val days = m.days.count
                for (band in TimeBand.entries) {
                    val pref = p.band(band)
                    if (pref == TimePreference.OKAY) continue
                    val minutes = band.overlap(s, e) * days
                    if (minutes <= 0) continue
                    val perHour = if (pref == TimePreference.PREFER) 3.0 else -6.0
                    score += perHour * minutes / 60.0
                }
            }
        }
        val early = p.earliestStart
        if (early != null && early.strength == PreferenceStrength.PREFERRED) {
            for (m in weekly) if ((m.start ?: Int.MAX_VALUE) < early.minutes) score -= min(20.0, 4.0 + (early.minutes - (m.start ?: 0)) / 10.0)
        }
        val late = p.latestEnd
        if (late != null && late.strength == PreferenceStrength.PREFERRED) {
            for (m in weekly) if ((m.end ?: 0) > late.minutes) score -= min(20.0, 4.0 + ((m.end ?: 0) - late.minutes) / 10.0)
        }
        if (p.format != FormatPreference.NO_PREFERENCE) score -= 8.0 * SchedulePreferences.formatOf(section).mismatch(p.format)
        if (p.campus != null && p.campusStrength == PreferenceStrength.PREFERRED && !section.isOnline && !ScheduleGenerator.hasNoCampus(section) && section.campus != p.campus) score -= 10
        val names = (section.instructors ?: emptyList()).map { it.name.lowercase(Locale.US) }.toSet()
        if (names.isNotEmpty()) {
            if (p.preferredInstructors.any { it.lowercase(Locale.US) in names }) score += 5
            if (p.avoidedInstructors.any { it.lowercase(Locale.US) in names }) score -= 15
        }
        return score
    }

    fun reasons(sections: List<CourseSection>, p: SchedulePreferences): List<ScheduleReason> {
        if (p.isDefault || sections.isEmpty()) return emptyList()
        val out = mutableListOf<ScheduleReason>()
        val days = sections.flatMap { it.weeklyMeetings }.fold(DaySet(0)) { acc, m -> acc.union(m.days) }
        if (!p.daysOff.isEmpty && days.isDisjoint(p.daysOff)) out += ScheduleReason(ScheduleReason.Kind.BENEFIT, SchedulePreferences.daysOffPhrase(p.daysOff))
        else if (!p.daysOff.isEmpty && p.daysOffStrength == PreferenceStrength.PREFERRED && !days.isDisjoint(p.daysOff)) {
            out += ScheduleReason(ScheduleReason.Kind.CAVEAT, "Meets on a day you'd rather have off")
        }
        p.earliestStart?.let { limit ->
            val earliest = sections.flatMap { it.weeklyMeetings }.mapNotNull { it.start }.minOrNull()
            if (earliest != null && earliest >= limit.minutes) out += ScheduleReason(ScheduleReason.Kind.BENEFIT, "Nothing before ${SchedulePreferences.shortTime(limit.minutes)}")
            else if (earliest != null && limit.strength == PreferenceStrength.PREFERRED) out += ScheduleReason(ScheduleReason.Kind.CAVEAT, "Starts ${TimeOfDay.label(earliest)}")
        }
        p.latestEnd?.let { limit ->
            val latest = sections.flatMap { it.weeklyMeetings }.mapNotNull { it.end }.maxOrNull()
            if (latest != null && latest <= limit.minutes) out += ScheduleReason(ScheduleReason.Kind.BENEFIT, "Done by ${SchedulePreferences.shortTime(limit.minutes)}")
            else if (latest != null && limit.strength == PreferenceStrength.PREFERRED) out += ScheduleReason(ScheduleReason.Kind.CAVEAT, "Ends ${TimeOfDay.label(latest)}")
        }
        if (p.preferredBands.isNotEmpty()) out += ScheduleReason(ScheduleReason.Kind.BENEFIT, SchedulePreferences.bandPhrase(p.preferredBands).replaceFirstChar { it.titlecase(Locale.US) })
        if (p.format == FormatPreference.ONLINE && sections.all { it.isOnline }) out += ScheduleReason(ScheduleReason.Kind.BENEFIT, "Online")
        if (p.format == FormatPreference.IN_PERSON && sections.none { it.isOnline }) out += ScheduleReason(ScheduleReason.Kind.BENEFIT, "In person")
        return out.take(6)
    }
}

object ScheduleExporter {
    fun ics(termName: String, planName: String, sections: List<CourseSection>, termStart: String?, termEnd: String?): String {
        val b = StringBuilder()
        b.appendLine("BEGIN:VCALENDAR")
        b.appendLine("VERSION:2.0")
        b.appendLine("PRODID:-//Term Organize//Android//EN")
        b.appendLine("CALSCALE:GREGORIAN")
        b.appendLine("X-WR-CALNAME:${escape(planName)} ($termName)")
        for (section in sections) {
            for (meeting in section.weeklyMeetings) {
                val start = meeting.start ?: continue
                val end = meeting.end ?: continue
                val day = meeting.days.indices.firstOrNull() ?: continue
                val until = (meeting.endDate ?: termEnd ?: "20261220").replace("-", "")
                val dtStart = firstDate(meeting.startDate ?: termStart ?: "20260909", day, start)
                val dtEnd = firstDate(meeting.startDate ?: termStart ?: "20260909", day, end)
                val byday = meeting.days.indices.joinToString(",") { listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")[it] }
                b.appendLine("BEGIN:VEVENT")
                b.appendLine("UID:${section.crn}-$day-$start@termorganize")
                b.appendLine("SUMMARY:${escape(section.courseKey.description)} ${escape(section.title)}")
                b.appendLine("LOCATION:${escape(meeting.locationLabel)}")
                b.appendLine("DESCRIPTION:CRN ${section.crn}\\n${escape(section.instructorLabel)}")
                b.appendLine("DTSTART;TZID=America/New_York:$dtStart")
                b.appendLine("DTEND;TZID=America/New_York:$dtEnd")
                b.appendLine("RRULE:FREQ=WEEKLY;BYDAY=$byday;UNTIL=${until}T235959Z")
                b.appendLine("END:VEVENT")
            }
        }
        b.appendLine("END:VCALENDAR")
        return b.toString()
    }

    private fun firstDate(iso: String, weekday: Int, minutes: Int): String {
        val parts = iso.split("-")
        var y = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        var m = parts.getOrNull(1)?.toIntOrNull() ?: 9
        var d = parts.getOrNull(2)?.toIntOrNull() ?: 1
        val date = java.time.LocalDate.of(y, m, d)
        val current = date.dayOfWeek.value % 7
        val delta = (weekday - current + 7) % 7
        val day = date.plusDays(delta.toLong())
        val hh = minutes / 60
        val mm = minutes % 60
        return "%04d%02d%02dT%02d%02d00".format(Locale.US, day.year, day.monthValue, day.dayOfMonth, hh, mm)
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")
    private fun StringBuilder.appendLine(s: String) = append(s).append("\r\n")
}
