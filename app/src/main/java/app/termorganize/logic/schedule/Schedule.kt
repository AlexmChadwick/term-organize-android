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

data class ScheduleReason(val kind: Kind, val text: String, val symbol: String = "") {
    enum class Kind { BENEFIT, CAVEAT }
    val isCaveat get() = kind == Kind.CAVEAT
    val id: String get() = (if (kind == Kind.BENEFIT) "+" else "-") + text
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

    companion object {
        fun measured(sections: List<CourseSection>, preferences: SchedulePreferences = SchedulePreferences()): GeneratedSchedule =
            ScheduleEngine.measure(sections, preferences)
    }
}

enum class ScheduleFilterReason {
    EXCLUDED_BY_USER,
    STARTS_TOO_EARLY,
    ENDS_TOO_LATE,
    FREE_DAY,
    HONORS,
    ONLINE,
    SEATS,
    CAMPUS,
    BLOCKED_TIME,
    PREF_STARTS_TOO_EARLY,
    PREF_ENDS_TOO_LATE,
    PREF_DAY_OFF,
    PREF_FORMAT,
    PREF_SEATS,
    PREF_CAMPUS,
    PREF_WORKLOAD,
}

enum class ConflictReason { OVERLAP, TRAVEL_TIME }

data class ConflictPair(
    val first: CourseKey,
    val second: CourseKey,
    val reason: ConflictReason = ConflictReason.OVERLAP,
)

sealed class CountProblem {
    data class TooManyRequired(val required: Int, val wanted: Int) : CountProblem()
    data class TooFewCourses(val available: Int, val wanted: Int) : CountProblem()
    data class CreditsOverTarget(val required: Int, val limit: Int) : CountProblem()
    data class CreditsUnderTarget(val available: Int, val wanted: Int) : CountProblem()
}

data class ScheduleDiagnostics(
    val courses: List<CourseReport> = emptyList(),
    val conflictingPairs: List<ConflictPair> = emptyList(),
    val countProblem: CountProblem? = null,
    val suggestions: List<String> = emptyList(),
    val filters: ScheduleFilters = ScheduleFilters(),
    val preferences: SchedulePreferences = SchedulePreferences(),
) {
    data class CourseReport(
        val key: CourseKey,
        val isRequired: Boolean,
        val totalSections: Int,
        val remainingSections: Int,
        val rejections: Map<ScheduleFilterReason, Int>,
    ) {
        val isBlocked get() = remainingSections == 0
    }

    val hasNoCourses: Boolean get() = courses.isEmpty()
    val blockedRequiredCourses: List<CourseReport> get() = courses.filter { it.isRequired && it.isBlocked }
}

data class ScheduleGenerationResult(
    val schedules: List<GeneratedSchedule> = emptyList(),
    val totalFound: Int = 0,
    val isExhaustive: Boolean = true,
    val diagnostics: ScheduleDiagnostics = ScheduleDiagnostics(),
    val wasCancelled: Boolean = false,
    val elapsedNanos: Long = 0,
) {
    val elapsedSeconds: Double get() = elapsedNanos / 1_000_000_000.0
    val isTruncated: Boolean get() = !isExhaustive || schedules.size < totalFound
}

sealed class PreferenceRelaxation {
    data object SoftenEarliestStart : PreferenceRelaxation()
    data object SoftenLatestEnd : PreferenceRelaxation()
    data object SoftenDaysOff : PreferenceRelaxation()
    data class SoftenBlockedTime(val id: String, val label: String) : PreferenceRelaxation()
    data object SoftenFormat : PreferenceRelaxation()
    data object SoftenCampus : PreferenceRelaxation()
    data object AllowFullSections : PreferenceRelaxation()
    data object ClearMaxClassesPerDay : PreferenceRelaxation()
    data object ClearMaxMinutesPerDay : PreferenceRelaxation()
    data object SoftenMinimumBreak : PreferenceRelaxation()
    data object ClearCreditTarget : PreferenceRelaxation()

    val title: String
        get() = when (this) {
            SoftenEarliestStart -> "Allow earlier classes if needed"
            SoftenLatestEnd -> "Allow later classes if needed"
            SoftenDaysOff -> "Allow classes on your days off if needed"
            is SoftenBlockedTime -> "Allow classes during “${label}” if needed"
            SoftenFormat -> "Allow other formats if needed"
            SoftenCampus -> "Allow other campuses if needed"
            AllowFullSections -> "Include full sections"
            ClearMaxClassesPerDay -> "Remove the classes-a-day limit"
            ClearMaxMinutesPerDay -> "Remove the hours-a-day limit"
            SoftenMinimumBreak -> "Don’t require time to walk between classes"
            ClearCreditTarget -> "Remove the credit target"
        }

    fun apply(p: SchedulePreferences): SchedulePreferences = when (this) {
        SoftenEarliestStart -> p.copy(earliestStart = p.earliestStart?.copy(strength = PreferenceStrength.PREFERRED))
        SoftenLatestEnd -> p.copy(latestEnd = p.latestEnd?.copy(strength = PreferenceStrength.PREFERRED))
        SoftenDaysOff -> p.copy(daysOffStrength = PreferenceStrength.PREFERRED)
        is SoftenBlockedTime -> p.copy(blockedTimes = p.blockedTimes.map { if (it.id == id) it.copy(strength = PreferenceStrength.PREFERRED) else it })
        SoftenFormat -> p.copy(formatStrength = PreferenceStrength.PREFERRED)
        SoftenCampus -> p.copy(campusStrength = PreferenceStrength.PREFERRED)
        AllowFullSections -> p.copy(seats = SeatPolicy.ANY)
        ClearMaxClassesPerDay -> p.copy(maxClassesPerDay = null)
        ClearMaxMinutesPerDay -> p.copy(maxMinutesPerDay = null)
        SoftenMinimumBreak -> p.copy(minimumBreakStrength = PreferenceStrength.PREFERRED)
        ClearCreditTarget -> p.copy(creditTarget = null)
    }

    companion object {
        fun available(p: SchedulePreferences): List<PreferenceRelaxation> {
            val result = ArrayList<PreferenceRelaxation>()
            if (p.earliestStart?.strength == PreferenceStrength.REQUIRED) result += SoftenEarliestStart
            if (p.latestEnd?.strength == PreferenceStrength.REQUIRED) result += SoftenLatestEnd
            if (!p.daysOff.isEmpty && p.daysOffStrength == PreferenceStrength.REQUIRED) result += SoftenDaysOff
            for (block in p.activeBlockedTimes) {
                if (block.strength == PreferenceStrength.REQUIRED) result += SoftenBlockedTime(block.id, block.label)
            }
            if (p.format != FormatPreference.NO_PREFERENCE && p.formatStrength == PreferenceStrength.REQUIRED) result += SoftenFormat
            if (p.campus != null && p.campusStrength == PreferenceStrength.REQUIRED) result += SoftenCampus
            if (p.seats != SeatPolicy.ANY) result += AllowFullSections
            if (p.maxClassesPerDay != null) result += ClearMaxClassesPerDay
            if (p.maxMinutesPerDay != null) result += ClearMaxMinutesPerDay
            if (p.breaks == BreakPreference.AT_LEAST && p.minimumBreakStrength == PreferenceStrength.REQUIRED) result += SoftenMinimumBreak
            if (p.creditTarget != null) result += ClearCreditTarget
            return result
        }
    }
}

sealed class ScheduleRelaxation {
    data object ClearEarliestStart : ScheduleRelaxation()
    data object ClearLatestEnd : ScheduleRelaxation()
    data object ClearFreeDays : ScheduleRelaxation()
    data object AllowHonors : ScheduleRelaxation()
    data object AllowOnline : ScheduleRelaxation()
    data object AllowFullSections : ScheduleRelaxation()
    data object ClearCampus : ScheduleRelaxation()
    data object ClearCourseCount : ScheduleRelaxation()
    data class MakeOptional(val key: CourseKey) : ScheduleRelaxation()
    data class Preference(val relaxation: PreferenceRelaxation) : ScheduleRelaxation()

    val id: String
        get() = when (this) {
            is MakeOptional -> "optional-$key"
            is Preference -> "preference-$relaxation"
            else -> toString()
        }

    val title: String
        get() = when (this) {
            ClearEarliestStart -> "Remove start time limit"
            ClearLatestEnd -> "Remove end time limit"
            ClearFreeDays -> "Allow classes on every day"
            AllowHonors -> "Include honors sections"
            AllowOnline -> "Include online sections"
            AllowFullSections -> "Include full sections"
            ClearCampus -> "Allow any campus"
            ClearCourseCount -> "Use any number of courses"
            is MakeOptional -> "Make ${key.description} optional"
            is Preference -> relaxation.title
        }

    fun apply(filters: ScheduleFilters): ScheduleFilters = when (this) {
        ClearEarliestStart -> filters.copy(earliestStart = null)
        ClearLatestEnd -> filters.copy(latestEnd = null)
        ClearFreeDays -> filters.copy(freeDays = DaySet(0))
        AllowHonors -> filters.copy(includeHonors = true)
        AllowOnline -> filters.copy(includeOnline = true)
        AllowFullSections -> filters.copy(hideFullSections = false, minSeats = 0)
        ClearCampus -> filters.copy(campus = null)
        ClearCourseCount -> filters.copy(courseCount = null)
        is MakeOptional, is Preference -> filters
    }

    fun apply(preferences: SchedulePreferences): SchedulePreferences = when (this) {
        is Preference -> relaxation.apply(preferences)
        else -> preferences
    }
}

data class ScheduleSuggestion(
    val relaxation: ScheduleRelaxation,
    val scheduleCount: Int,
    val isLowerBound: Boolean,
) {
    val id: String get() = relaxation.id
}

object ScheduleGenerator {
    class Limits(
        val maxResults: Int = 5_000,
        val maxExhaustiveLeaves: Int = 2_500_000,
        val maxNodes: Int = 12_000_000,
        timeBudget: Double = 0.9,
        val sampleLeaves: Int = 150_000,
        sampleTimeBudget: Double = 0.35,
        val allowSampling: Boolean = true,
    ) {
        val timeBudgetNanos: Long = (timeBudget * 1_000_000_000.0).toLong()
        val sampleTimeBudgetNanos: Long = (sampleTimeBudget * 1_000_000_000.0).toLong()

        companion object {
            val standard = Limits()
            val probe = Limits(
                maxResults = 1,
                maxExhaustiveLeaves = 300,
                maxNodes = 300_000,
                timeBudget = 0.25,
                sampleLeaves = 0,
                sampleTimeBudget = 0.0,
                allowSampling = false,
            )
        }
    }

    fun hasNoCampus(section: CourseSection) = section.campus.contains("no campus", ignoreCase = true)

    fun rejectionReasons(
        section: CourseSection,
        filters: ScheduleFilters,
        preferences: SchedulePreferences = SchedulePreferences(),
        excluded: Set<String> = emptySet(),
    ): Set<ScheduleFilterReason> {
        val reasons = LinkedHashSet<ScheduleFilterReason>()
        if (section.crn in excluded) reasons += ScheduleFilterReason.EXCLUDED_BY_USER
        val weekly = section.weeklyMeetings
        if (filters.earliestStart != null && weekly.any { (it.start ?: Int.MAX_VALUE) < filters.earliestStart }) reasons += ScheduleFilterReason.STARTS_TOO_EARLY
        if (filters.latestEnd != null && weekly.any { (it.end ?: 0) > filters.latestEnd }) reasons += ScheduleFilterReason.ENDS_TOO_LATE
        if (!filters.freeDays.isEmpty && weekly.any { !it.days.isDisjoint(filters.freeDays) }) reasons += ScheduleFilterReason.FREE_DAY
        if (!filters.includeHonors && section.honors) reasons += ScheduleFilterReason.HONORS
        if (!filters.includeOnline && section.isOnline) reasons += ScheduleFilterReason.ONLINE
        if (filters.hideFullSections && section.seatsRemaining <= 0) reasons += ScheduleFilterReason.SEATS
        if (filters.minSeats > 0 && section.seatsRemaining < filters.minSeats) reasons += ScheduleFilterReason.SEATS
        if (filters.campus != null && !section.isOnline && !hasNoCampus(section) && section.campus != filters.campus) reasons += ScheduleFilterReason.CAMPUS
        if (!preferences.isDefault) reasons += SchedulePreferenceRules.rejectionReasons(section, preferences)
        return reasons
    }

    fun passesFilters(section: CourseSection, filters: ScheduleFilters) = rejectionReasons(section, filters).isEmpty()

    fun generate(
        courses: List<ScheduleCourseInput>,
        filters: ScheduleFilters,
        preferences: SchedulePreferences = SchedulePreferences(),
        sort: ScheduleSortOption = ScheduleSortOption.BEST,
        limits: Limits = Limits.standard,
    ): ScheduleGenerationResult = ScheduleEngine.generate(courses, filters, preferences.normalized(), sort, limits)

    fun suggestRelaxations(
        courses: List<ScheduleCourseInput>,
        filters: ScheduleFilters,
        preferences: SchedulePreferences = SchedulePreferences(),
        includeCourseRelaxations: Boolean = true,
    ): List<ScheduleSuggestion> = ScheduleEngine.suggestRelaxations(courses, filters, preferences.normalized(), includeCourseRelaxations)

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
    fun rejectionReasons(section: CourseSection, p: SchedulePreferences): Set<ScheduleFilterReason> {
        val reasons = LinkedHashSet<ScheduleFilterReason>()
        val weekly = section.weeklyMeetings
        val earliest = p.earliestStart
        if (earliest != null && earliest.strength == PreferenceStrength.REQUIRED && weekly.any { (it.start ?: Int.MAX_VALUE) < earliest.minutes }) {
            reasons += ScheduleFilterReason.PREF_STARTS_TOO_EARLY
        }
        val latest = p.latestEnd
        if (latest != null && latest.strength == PreferenceStrength.REQUIRED && weekly.any { (it.end ?: 0) > latest.minutes }) {
            reasons += ScheduleFilterReason.PREF_ENDS_TOO_LATE
        }
        if (p.daysOffStrength == PreferenceStrength.REQUIRED && !p.daysOff.isEmpty && weekly.any { !it.days.isDisjoint(p.daysOff) }) {
            reasons += ScheduleFilterReason.PREF_DAY_OFF
        }
        if (p.blockedTimes.any { it.isActive && it.strength == PreferenceStrength.REQUIRED && overlaps(it, weekly) }) {
            reasons += ScheduleFilterReason.BLOCKED_TIME
        }
        if (p.format != FormatPreference.NO_PREFERENCE && p.formatStrength == PreferenceStrength.REQUIRED &&
            SchedulePreferences.formatOf(section).mismatch(p.format) > 0
        ) {
            reasons += ScheduleFilterReason.PREF_FORMAT
        }
        if (p.campus != null && p.campusStrength == PreferenceStrength.REQUIRED && !section.isOnline &&
            !ScheduleGenerator.hasNoCampus(section) && section.campus != p.campus
        ) {
            reasons += ScheduleFilterReason.PREF_CAMPUS
        }
        when (p.seats) {
            SeatPolicy.ANY -> Unit
            SeatPolicy.OPEN_ONLY -> if (isFull(section)) reasons += ScheduleFilterReason.PREF_SEATS
            SeatPolicy.WAITLIST_OK -> if (isFull(section) && section.waitlistRemaining <= 0) reasons += ScheduleFilterReason.PREF_SEATS
        }
        p.maxMinutesPerDay?.let { cap ->
            val perDay = IntArray(7)
            for (interval in ScheduleInterval.intervals(section)) perDay[interval.day] += interval.end - interval.start
            if (perDay.any { it > cap }) reasons += ScheduleFilterReason.PREF_WORKLOAD
        }
        return reasons
    }

    fun isFull(section: CourseSection) = section.seatCapacity > 0 && section.seatsRemaining <= 0

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

    fun sectionScore(section: CourseSection, intervals: List<ScheduleInterval>, p: SchedulePreferences): Double {
        var score = 0.0
        if (p.hasTimeOfDayPreference) {
            for (interval in intervals) {
                for (band in TimeBand.entries) {
                    val preference = p.band(band)
                    if (preference == TimePreference.OKAY) continue
                    val minutes = band.overlap(interval.start, interval.end)
                    if (minutes <= 0) continue
                    val perHour = if (preference == TimePreference.PREFER) ScheduleScoring.PREFERRED_BAND_PER_HOUR else ScheduleScoring.AVOIDED_BAND_PER_HOUR
                    score += perHour * minutes / 60.0
                }
            }
        }
        val early = p.earliestStart
        if (early != null && early.strength == PreferenceStrength.PREFERRED) {
            for (interval in intervals) {
                if (interval.start < early.minutes) score -= softLimitPenalty(early.minutes - interval.start)
            }
        }
        val late = p.latestEnd
        if (late != null && late.strength == PreferenceStrength.PREFERRED) {
            for (interval in intervals) {
                if (interval.end > late.minutes) score -= softLimitPenalty(interval.end - late.minutes)
            }
        }
        for (block in p.blockedTimes) {
            if (!block.isActive || block.strength != PreferenceStrength.PREFERRED) continue
            for (interval in intervals) {
                if (block.overlaps(DaySet.of(interval.day), interval.start, interval.end)) score -= ScheduleScoring.SOFT_BLOCKED_PER_MEETING
            }
        }
        if (p.format != FormatPreference.NO_PREFERENCE) {
            score -= ScheduleScoring.FORMAT_MISMATCH_PER_SECTION * SchedulePreferences.formatOf(section).mismatch(p.format)
        }
        if (p.campus != null && p.campusStrength == PreferenceStrength.PREFERRED && !section.isOnline &&
            !ScheduleGenerator.hasNoCampus(section) && section.campus != p.campus
        ) {
            score -= ScheduleScoring.CAMPUS_MISMATCH_PER_SECTION
        }
        if (p.preferredInstructors.isNotEmpty() || p.avoidedInstructors.isNotEmpty()) {
            val names = (section.instructors ?: emptyList()).map { it.name.lowercase(Locale.US) }.toSet()
            if (names.isNotEmpty()) {
                if (p.preferredInstructors.any { it.lowercase(Locale.US) in names }) score += ScheduleScoring.PREFERRED_INSTRUCTOR_PER_SECTION
                if (p.avoidedInstructors.any { it.lowercase(Locale.US) in names }) score += ScheduleScoring.AVOIDED_INSTRUCTOR_PER_SECTION
            }
        }
        return score
    }

    private fun softLimitPenalty(minutesOutside: Int) =
        min(ScheduleScoring.SOFT_LIMIT_CAP, ScheduleScoring.SOFT_LIMIT_BASE + minutesOutside / 10.0)

    fun reasons(
        sections: List<CourseSection>,
        p: SchedulePreferences,
        maxBenefits: Int = 4,
        maxCaveats: Int = 2,
    ): List<ScheduleReason> {
        if (p.isDefault || sections.isEmpty()) return emptyList()
        val table = ScheduleBuildingTable()
        val intervals = ArrayList<ScheduleInterval>()
        for (section in sections) intervals += ScheduleInterval.intervals(section, table)
        val options = ScheduleMeasureOptions()
        if (p.breaks == BreakPreference.AVOID_BACK_TO_BACK) options.backToBackBelow = SchedulePreferences.BACK_TO_BACK_MINUTES
        if (p.breaks == BreakPreference.AT_LEAST) options.travelMinutes = p.minimumBreakMinutes
        options.lunch = p.lunch
        val measure = ScheduleMeasure.measure(intervals, options)
        val hasClasses = measure.classDays > 0
        val good = ArrayList<ScheduleReason>()
        val bad = ArrayList<ScheduleReason>()
        fun pro(text: String, symbol: String) { good += ScheduleReason(ScheduleReason.Kind.BENEFIT, text, symbol) }
        fun con(text: String, symbol: String) { bad += ScheduleReason(ScheduleReason.Kind.CAVEAT, text, symbol) }

        if (hasClasses && !p.daysOff.isEmpty) {
            val used = DaySet(measure.classDayMask and p.daysOff.rawValue)
            val free = DaySet(p.daysOff.rawValue and used.rawValue.inv() and 0x7F)
            if (!free.isEmpty) pro(SchedulePreferences.daysOffPhrase(free), "calendar.badge.minus")
            if (!used.isEmpty && p.daysOffStrength == PreferenceStrength.PREFERRED) {
                con("Class on ${used.indices.joinToString(", ") { DaySet.SHORT[it] }}", "calendar.badge.exclamationmark")
            }
        }
        val earliestLimit = p.earliestStart
        if (hasClasses && earliestLimit != null) {
            if (measure.earliestStart >= earliestLimit.minutes) pro("No classes before ${SchedulePreferences.shortTime(earliestLimit.minutes)}", "sunrise")
            else if (earliestLimit.strength == PreferenceStrength.PREFERRED) con("Starts ${TimeOfDay.label(measure.earliestStart)}", "sunrise")
        }
        val latestLimit = p.latestEnd
        if (hasClasses && latestLimit != null) {
            if (measure.latestEnd <= latestLimit.minutes) pro("Done by ${SchedulePreferences.shortTime(latestLimit.minutes)}", "sunset")
            else if (latestLimit.strength == PreferenceStrength.PREFERRED) con("Ends ${TimeOfDay.label(measure.latestEnd)}", "sunset")
        }
        if (hasClasses) {
            when (p.dayLayout) {
                DayLayout.NO_PREFERENCE -> Unit
                DayLayout.FEWER_DAYS -> {
                    val days = if (measure.campusDays > 0) measure.campusDays else measure.classDays
                    if (days <= 3) pro(if (days == 1) "1 day on campus" else "$days days on campus", "calendar")
                }
                DayLayout.SPREAD_OUT -> if (measure.classDays >= 4) pro("Spread over ${measure.classDays} days", "calendar")
            }
            if (p.gaps == GapPreference.COMPACT && measure.gapMinutes <= 30) pro("Short gaps", "rectangle.compress.vertical")
        }
        if (hasClasses && measure.totalMinutes > 0) {
            fun minutesIn(bands: List<TimeBand>): Int = intervals.sumOf { interval ->
                bands.sumOf { it.overlap(interval.start, interval.end) }
            }
            val preferred = p.preferredBands
            if (preferred.isNotEmpty()) {
                val share = minutesIn(preferred).toDouble() / measure.totalMinutes
                val name = SchedulePreferences.bandPhrase(preferred)
                if (share >= 0.999) pro("All $name", "sun.max")
                else if (share >= 0.6) pro("Mostly $name", "sun.max")
            }
            val avoided = p.avoidedBands
            if (avoided.isNotEmpty()) {
                val name = SchedulePreferences.bandPhrase(avoided)
                if (minutesIn(avoided) == 0) pro("No $name", "nosign") else con("Includes $name", "clock")
            }
        }
        if (hasClasses && p.lunch != null) {
            val free = measure.classDays - measure.lunchMisses
            when {
                measure.lunchMisses == 0 -> pro("Lunch free every day", "fork.knife")
                free * 2 >= measure.classDays -> pro("Lunch free most days", "fork.knife")
                else -> con("Lunch often busy", "fork.knife")
            }
        }
        if (hasClasses) {
            when (p.breaks) {
                BreakPreference.FINE -> Unit
                BreakPreference.AVOID_BACK_TO_BACK -> {
                    if (measure.backToBack == 0) pro("No back-to-back classes", "figure.walk")
                    else con(if (measure.backToBack == 1) "1 back-to-back" else "${measure.backToBack} back-to-back", "figure.walk")
                }
                BreakPreference.AT_LEAST -> {
                    if (measure.tightTransitions == 0) pro("Time to walk between classes", "figure.walk")
                    else con("Tight between buildings", "figure.walk")
                }
            }
        }
        if (p.preferredInstructors.isNotEmpty() || p.avoidedInstructors.isNotEmpty()) {
            fun names(wanted: List<String>): List<String> {
                val set = wanted.map { it.lowercase(Locale.US) }.toSet()
                val result = ArrayList<String>()
                for (section in sections) {
                    for (instructor in section.instructors ?: emptyList()) {
                        if (instructor.name.lowercase(Locale.US) in set && instructor.displayName !in result) result += instructor.displayName
                    }
                }
                return result
            }
            val liked = names(p.preferredInstructors)
            if (liked.size == 1) pro("With ${liked[0]}", "person.fill")
            else if (liked.size > 1) pro("With ${liked.size} preferred instructors", "person.2.fill")
            val disliked = names(p.avoidedInstructors)
            if (disliked.isNotEmpty()) {
                con(if (disliked.size == 1) "With ${disliked[0]}" else "With ${disliked.size} avoided instructors", "person.fill.xmark")
            }
        }
        if (p.format != FormatPreference.NO_PREFERENCE) {
            val formats = sections.map { SchedulePreferences.formatOf(it) }
            val deliveries = formats.filter { it != SectionFormat.OTHER }
            if (deliveries.isNotEmpty()) {
                if (deliveries.all { it.mismatch(p.format) == 0.0 }) pro("All ${formatTitle(p.format).lowercase(Locale.US)}", if (p.format == FormatPreference.ONLINE) "wifi" else "building.2")
                else if (p.formatStrength == PreferenceStrength.PREFERRED) con("Not all ${formatTitle(p.format).lowercase(Locale.US)}", "wifi")
            }
        }
        if (p.campus != null) {
            val campus = p.campus
            val onCampus = sections.filter { !it.isOnline && !ScheduleGenerator.hasNoCampus(it) }
            if (onCampus.isNotEmpty()) {
                if (onCampus.all { it.campus == campus }) pro("$campus campus", "mappin.and.ellipse")
                else if (p.campusStrength == PreferenceStrength.PREFERRED) con("Includes another campus", "mappin.and.ellipse")
            }
        }
        when (p.seats) {
            SeatPolicy.ANY -> Unit
            SeatPolicy.OPEN_ONLY -> pro("All sections open", "chair.lounge")
            SeatPolicy.WAITLIST_OK -> {
                val waitlisted = sections.count { isFull(it) }
                if (waitlisted == 0) pro("All sections open", "chair.lounge")
                else con(if (waitlisted == 1) "1 waitlisted section" else "$waitlisted waitlisted sections", "chair.lounge")
            }
        }
        for (block in p.blockedTimes) {
            if (!block.isActive || block.strength != PreferenceStrength.PREFERRED) continue
            val touches = intervals.any { block.overlaps(DaySet.of(it.day), it.start, it.end) }
            if (touches) con("Overlaps ${block.label}", "calendar.badge.exclamationmark")
        }
        fun unique(reasons: List<ScheduleReason>): List<ScheduleReason> {
            val seen = HashSet<String>()
            return reasons.filter { seen.add(it.id) }
        }
        return unique(good).take(maxBenefits) + unique(bad).take(maxCaveats)
    }

    private fun overlaps(block: BlockedTime, meetings: List<Meeting>) = meetings.any { meeting ->
        val start = meeting.start ?: return@any false
        val end = meeting.end ?: return@any false
        block.overlaps(meeting.days, start, end)
    }
}

private fun formatTitle(format: FormatPreference) = when (format) {
    FormatPreference.NO_PREFERENCE -> "No preference"
    FormatPreference.IN_PERSON -> "In person"
    FormatPreference.HYBRID -> "Hybrid"
    FormatPreference.ONLINE -> "Online"
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
