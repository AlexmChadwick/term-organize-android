// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Interval and measurement code is derived from SearchNEU's scheduler (AGPL-3.0). See NOTICE.md.

package app.termorganize.logic.schedule

import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Meeting
import kotlin.math.max
import kotlin.math.min

/** Maps building codes to small numbers so intervals can say "same building" cheaply. 0 means unknown. */
class ScheduleBuildingTable {
    private val ids = HashMap<String, Int>()

    fun id(meeting: Meeting): Int {
        val code = meeting.buildingCode ?: meeting.building
        if (code.isNullOrEmpty()) return 0
        return ids.getOrPut(code) { ids.size + 1 }
    }
}

/** A weekly time block of a section on a single day. */
data class ScheduleInterval(
    val day: Int,
    val inPerson: Boolean,
    val start: Int,
    val end: Int,
    val building: Int = 0,
) {
    companion object {
        fun intervals(section: CourseSection): List<ScheduleInterval> = intervals(section) { 0 }

        fun intervals(section: CourseSection, buildings: ScheduleBuildingTable): List<ScheduleInterval> =
            intervals(section) { buildings.id(it) }

        private fun intervals(section: CourseSection, building: (Meeting) -> Int): List<ScheduleInterval> {
            val result = ArrayList<ScheduleInterval>()
            val seen = HashSet<ScheduleInterval>()
            for (meeting in section.weeklyMeetings) {
                val start = meeting.start ?: continue
                val end = meeting.end ?: continue
                val inPerson = !(section.isOnline || meeting.isOnline)
                val place = if (inPerson) building(meeting) else 0
                for (day in meeting.days.indices) {
                    val interval = ScheduleInterval(day, inPerson, start, end, place)
                    if (seen.add(interval)) result += interval
                }
            }
            return result
        }
    }
}

/** What to count besides the basics when measuring a schedule. The defaults count nothing extra. */
class ScheduleMeasureOptions(
    var backToBackBelow: Int = 0,
    var travelMinutes: Int = 0,
    var lunch: LunchBreak? = null,
) {
    companion object {
        val none = ScheduleMeasureOptions()
    }
}

/** The raw numbers measured from a set of weekly intervals. */
class ScheduleMeasure {
    var classDays: Int = 0
    var campusDays: Int = 0
    var earliestStart: Int = Int.MAX_VALUE
    var latestEnd: Int = Int.MIN_VALUE
    var gapMinutes: Int = 0
    var classDayMask: Int = 0
    var maxDayMinutes: Int = 0
    var totalMinutes: Int = 0
    var backToBack: Int = 0
    var tightTransitions: Int = 0
    var lunchMisses: Int = 0

    companion object {
        const val GAP_THRESHOLD_MINUTES = 20

        fun measure(intervals: List<ScheduleInterval>, options: ScheduleMeasureOptions = ScheduleMeasureOptions.none): ScheduleMeasure {
            if (intervals.isEmpty()) return ScheduleMeasure()
            val sorted = intervals.sortedWith(compareBy({ it.day }, { it.start }))
            val result = ScheduleMeasure()
            var k = 0
            val n = sorted.size
            while (k < n) {
                val day = sorted[k].day
                var runEnd = sorted[k].end
                var onCampus = sorted[k].inPerson
                var dayMinutes = sorted[k].end - sorted[k].start
                var previous = sorted[k]
                result.earliestStart = min(result.earliestStart, sorted[k].start)
                result.latestEnd = max(result.latestEnd, sorted[k].end)
                var m = k + 1
                while (m < n && sorted[m].day == day) {
                    val current = sorted[m]
                    val start = current.start
                    val gap = start - runEnd
                    if (gap >= GAP_THRESHOLD_MINUTES) result.gapMinutes += gap
                    if (gap >= 0) {
                        if (gap < options.backToBackBelow) result.backToBack += 1
                        if (gap < options.travelMinutes && previous.inPerson && current.inPerson &&
                            (previous.building == 0 || current.building == 0 || previous.building != current.building)
                        ) {
                            result.tightTransitions += 1
                        }
                    }
                    runEnd = max(runEnd, current.end)
                    dayMinutes += current.end - start
                    result.earliestStart = min(result.earliestStart, start)
                    result.latestEnd = max(result.latestEnd, current.end)
                    if (current.inPerson) onCampus = true
                    previous = current
                    m += 1
                }
                options.lunch?.let { lunch ->
                    if (!hasFreeStretch(sorted.subList(k, m), lunch)) result.lunchMisses += 1
                }
                result.classDays += 1
                result.classDayMask = result.classDayMask or (1 shl day)
                if (onCampus) result.campusDays += 1
                result.maxDayMinutes = max(result.maxDayMinutes, dayMinutes)
                result.totalMinutes += dayMinutes
                k = m
            }
            return result
        }

        private fun hasFreeStretch(dayIntervals: List<ScheduleInterval>, lunch: LunchBreak): Boolean {
            var cursor = lunch.start
            for (interval in dayIntervals) {
                val start = interval.start
                val end = interval.end
                if (end <= lunch.start || start >= lunch.end) continue
                if (start > cursor && min(start, lunch.end) - cursor >= lunch.minutes) return true
                cursor = max(cursor, min(end, lunch.end))
            }
            return lunch.end - cursor >= lunch.minutes
        }
    }
}

/**
 * Turns measurements into one number; higher is better.
 *
 * Leaf weights, in points (a campus day costs 8, so 8 is one day's worth of dislike):
 * a day with an in-person class is −8 (−14 with fewer days, 0 with spread out);
 * a day with only online classes is −2 (0 with spread out);
 * spread out adds +6 per class day and −0.1 per minute the heaviest day exceeds the average;
 * idle time (breaks of 20+ minutes) is −1 per 10 minutes (−1 per 10/3 with short gaps, none when gaps are fine,
 * −1 per 20 with a protected lunch);
 * before 9 AM / after 5 PM is −1 per 15 / 20 minutes, only without time-of-day wishes or limits;
 * a full section is −6 (−3 when waitlists are fine), and −2 when nothing is full but the tightest section has 3 seats or fewer;
 * a wished-for day off that still has class is −12;
 * back-to-back classes under 30 minutes are −10 with that wish;
 * too little time between buildings is −10 when the break is only preferred;
 * a class day without the lunch break is −8.
 *
 * Per section, added once: +3 / −6 per hour in a preferred / avoided band;
 * outside a soft limit, −(4 + 1 per 10 minutes) capped at −20 per meeting;
 * over a preferred block, −15 per meeting;
 * format mismatch −8 (hybrid counts half);
 * another campus −10;
 * a preferred instructor +5, an avoided one −15.
 */
object ScheduleScoring {
    class Weights {
        var base: Double = 100.0
        var perCampusDay: Double = 8.0
        var perOnlineDay: Double = 2.0
        var gapDivisor: Double = 10.0
        var comfortHours: Boolean = true
        var perFullSection: Double = 6.0
        var lowSeatsPenalty: Double = 2.0
        var perClassDayBonus: Double = 0.0
        var perImbalanceMinute: Double = 0.0
        var perDayOffMissed: Double = 0.0
        var daysOff: DaySet = DaySet(0)
        var perBackToBack: Double = 0.0
        var perTightTransition: Double = 0.0
        var perLunchMiss: Double = 0.0

        constructor()

        constructor(p: SchedulePreferences) {
            if (p.hasTimeOfDayPreference || p.earliestStart != null || p.latestEnd != null) comfortHours = false
            when (p.dayLayout) {
                DayLayout.NO_PREFERENCE -> Unit
                DayLayout.FEWER_DAYS -> perCampusDay = 14.0
                DayLayout.SPREAD_OUT -> {
                    perCampusDay = 0.0
                    perOnlineDay = 0.0
                    perClassDayBonus = 6.0
                    perImbalanceMinute = 0.1
                }
            }
            when (p.gaps) {
                GapPreference.NO_PREFERENCE -> if (p.lunch != null) gapDivisor = 20.0
                GapPreference.COMPACT -> gapDivisor = 10.0 / 3.0
                GapPreference.FINE -> gapDivisor = Double.POSITIVE_INFINITY
            }
            if (p.seats == SeatPolicy.WAITLIST_OK) perFullSection = 3.0
            if (!p.daysOff.isEmpty && p.daysOffStrength == PreferenceStrength.PREFERRED) {
                daysOff = p.daysOff
                perDayOffMissed = 12.0
            }
            when (p.breaks) {
                BreakPreference.FINE -> Unit
                BreakPreference.AVOID_BACK_TO_BACK -> perBackToBack = 10.0
                BreakPreference.AT_LEAST -> if (p.minimumBreakStrength == PreferenceStrength.PREFERRED) perTightTransition = 10.0
            }
            if (p.lunch != null) perLunchMiss = 8.0
        }

        fun score(measure: ScheduleMeasure, fullSections: Int, minSeats: Int): Double {
            var score = base
            score -= perCampusDay * measure.campusDays
            score -= perOnlineDay * max(0, measure.classDays - measure.campusDays)
            score -= measure.gapMinutes.toDouble() / gapDivisor
            if (comfortHours) {
                if (measure.earliestStart != Int.MAX_VALUE && measure.earliestStart < 9 * 60) {
                    score -= (9 * 60 - measure.earliestStart).toDouble() / 15.0
                }
                if (measure.latestEnd != Int.MIN_VALUE && measure.latestEnd > 17 * 60) {
                    score -= (measure.latestEnd - 17 * 60).toDouble() / 20.0
                }
            }
            score -= perFullSection * fullSections
            if (fullSections == 0 && minSeats <= 3) score -= lowSeatsPenalty
            if (perClassDayBonus != 0.0) score += perClassDayBonus * measure.classDays
            if (perImbalanceMinute != 0.0 && measure.classDays > 0) {
                val average = measure.totalMinutes.toDouble() / measure.classDays
                score -= perImbalanceMinute * max(0.0, measure.maxDayMinutes - average)
            }
            if (perDayOffMissed != 0.0) {
                score -= perDayOffMissed * Integer.bitCount(measure.classDayMask and daysOff.rawValue)
            }
            if (perBackToBack != 0.0) score -= perBackToBack * measure.backToBack
            if (perTightTransition != 0.0) score -= perTightTransition * measure.tightTransitions
            if (perLunchMiss != 0.0) score -= perLunchMiss * measure.lunchMisses
            return score
        }
    }

    fun score(measure: ScheduleMeasure, fullSections: Int, minSeats: Int): Double =
        Weights().score(measure, fullSections, minSeats)

    fun measureOptions(p: SchedulePreferences): ScheduleMeasureOptions {
        val options = ScheduleMeasureOptions()
        when (p.breaks) {
            BreakPreference.FINE -> Unit
            BreakPreference.AVOID_BACK_TO_BACK -> options.backToBackBelow = SchedulePreferences.BACK_TO_BACK_MINUTES
            BreakPreference.AT_LEAST -> if (p.minimumBreakStrength == PreferenceStrength.PREFERRED) {
                options.travelMinutes = p.minimumBreakMinutes
            }
        }
        options.lunch = p.lunch
        return options
    }

    const val PREFERRED_BAND_PER_HOUR = 3.0
    const val AVOIDED_BAND_PER_HOUR = -6.0
    const val SOFT_LIMIT_BASE = 4.0
    const val SOFT_LIMIT_CAP = 20.0
    const val SOFT_BLOCKED_PER_MEETING = 15.0
    const val FORMAT_MISMATCH_PER_SECTION = 8.0
    const val CAMPUS_MISMATCH_PER_SECTION = 10.0
    const val PREFERRED_INSTRUCTOR_PER_SECTION = 5.0
    const val AVOIDED_INSTRUCTOR_PER_SECTION = -15.0
}
