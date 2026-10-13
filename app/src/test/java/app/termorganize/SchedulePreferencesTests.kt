// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Ports the Swift schedule scoring, ranking, sampler, and reason tests.

package app.termorganize

import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Instructor
import app.termorganize.core.model.Meeting
import app.termorganize.logic.schedule.BlockedTime
import app.termorganize.logic.schedule.BreakPreference
import app.termorganize.logic.schedule.ConflictReason
import app.termorganize.logic.schedule.CountProblem
import app.termorganize.logic.schedule.CreditTarget
import app.termorganize.logic.schedule.DayLayout
import app.termorganize.logic.schedule.FormatPreference
import app.termorganize.logic.schedule.GapPreference
import app.termorganize.logic.schedule.GeneratedSchedule
import app.termorganize.logic.schedule.LunchBreak
import app.termorganize.logic.schedule.PreferenceRelaxation
import app.termorganize.logic.schedule.PreferenceStrength
import app.termorganize.logic.schedule.ScheduleCourseInput
import app.termorganize.logic.schedule.ScheduleFilterReason
import app.termorganize.logic.schedule.ScheduleFilters
import app.termorganize.logic.schedule.ScheduleGenerator
import app.termorganize.logic.schedule.ScheduleInterval
import app.termorganize.logic.schedule.ScheduleMeasure
import app.termorganize.logic.schedule.SchedulePreferenceRules
import app.termorganize.logic.schedule.SchedulePreferences
import app.termorganize.logic.schedule.SchedulePreset
import app.termorganize.logic.schedule.ScheduleRelaxation
import app.termorganize.logic.schedule.ScheduleScoring
import app.termorganize.logic.schedule.ScheduleSortOption
import app.termorganize.logic.schedule.SeatPolicy
import app.termorganize.logic.schedule.TimeBand
import app.termorganize.logic.schedule.TimeLimit
import app.termorganize.logic.schedule.TimePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.max

class ScheduleScoringTests {
    @Test fun weightTableFollowsThePreferences() {
        val defaults = ScheduleScoring.Weights()
        assertEquals(100.0, defaults.base, 0.0)
        assertEquals(8.0, defaults.perCampusDay, 0.0)
        assertEquals(2.0, defaults.perOnlineDay, 0.0)
        assertEquals(10.0, defaults.gapDivisor, 0.0)
        assertTrue(defaults.comfortHours)
        assertEquals(6.0, defaults.perFullSection, 0.0)
        assertEquals(2.0, defaults.lowSeatsPenalty, 0.0)
        assertEquals(0.0, defaults.perClassDayBonus, 0.0)
        assertEquals(0.0, defaults.perDayOffMissed, 0.0)
        assertEquals(0.0, defaults.perBackToBack, 0.0)
        assertEquals(0.0, defaults.perTightTransition, 0.0)
        assertEquals(0.0, defaults.perLunchMiss, 0.0)

        assertEquals(14.0, ScheduleScoring.Weights(SchedulePreferences(dayLayout = DayLayout.FEWER_DAYS)).perCampusDay, 0.0)
        val spread = ScheduleScoring.Weights(SchedulePreferences(dayLayout = DayLayout.SPREAD_OUT))
        assertEquals(0.0, spread.perCampusDay, 0.0)
        assertEquals(0.0, spread.perOnlineDay, 0.0)
        assertEquals(6.0, spread.perClassDayBonus, 0.0)
        assertEquals(0.1, spread.perImbalanceMinute, 0.0)
        assertEquals(10.0 / 3.0, ScheduleScoring.Weights(SchedulePreferences(gaps = GapPreference.COMPACT)).gapDivisor, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, ScheduleScoring.Weights(SchedulePreferences(gaps = GapPreference.FINE)).gapDivisor, 0.0)
        val lunch = ScheduleScoring.Weights(SchedulePreferences(lunch = LunchBreak()))
        assertEquals(20.0, lunch.gapDivisor, 0.0)
        assertEquals(8.0, lunch.perLunchMiss, 0.0)
        assertFalse(ScheduleScoring.Weights(SchedulePreferences(timeOfDay = mapOf(TimeBand.MORNING to TimePreference.PREFER))).comfortHours)
        assertFalse(ScheduleScoring.Weights(SchedulePreferences(earliestStart = TimeLimit(10 * 60))).comfortHours)
        assertFalse(ScheduleScoring.Weights(SchedulePreferences(latestEnd = TimeLimit(17 * 60))).comfortHours)
        assertEquals(3.0, ScheduleScoring.Weights(SchedulePreferences(seats = SeatPolicy.WAITLIST_OK)).perFullSection, 0.0)
        val off = ScheduleScoring.Weights(SchedulePreferences(daysOff = DaySet.FRIDAY))
        assertEquals(12.0, off.perDayOffMissed, 0.0)
        assertEquals(DaySet.FRIDAY, off.daysOff)
        assertEquals(0.0, ScheduleScoring.Weights(SchedulePreferences(daysOff = DaySet.FRIDAY, daysOffStrength = PreferenceStrength.REQUIRED)).perDayOffMissed, 0.0)
        assertEquals(10.0, ScheduleScoring.Weights(SchedulePreferences(breaks = BreakPreference.AVOID_BACK_TO_BACK)).perBackToBack, 0.0)
        assertEquals(10.0, ScheduleScoring.Weights(SchedulePreferences(breaks = BreakPreference.AT_LEAST)).perTightTransition, 0.0)
        assertEquals(0.0, ScheduleScoring.Weights(SchedulePreferences(breaks = BreakPreference.AT_LEAST, minimumBreakStrength = PreferenceStrength.REQUIRED)).perTightTransition, 0.0)
        assertEquals(SchedulePreferences.BACK_TO_BACK_MINUTES, ScheduleScoring.measureOptions(SchedulePreferences(breaks = BreakPreference.AVOID_BACK_TO_BACK)).backToBackBelow)
        val walk = ScheduleScoring.measureOptions(SchedulePreferences(breaks = BreakPreference.AT_LEAST, minimumBreakMinutes = 15))
        assertEquals(15, walk.travelMinutes)
        assertEquals(0, ScheduleScoring.measureOptions(SchedulePreferences(breaks = BreakPreference.AT_LEAST, minimumBreakStrength = PreferenceStrength.REQUIRED)).travelMinutes)
    }

    @Test fun theScoreOfADefaultScheduleMatchesTheOriginalFormula() {
        val sections = listOf(
            section("A1", "A", "1", listOf(1, 3), 8.0, 9.5),
            section("B1", "B", "1", listOf(2, 4), 13.0, 14.5),
            section("C1", "C", "1", listOf(1), 17.0, 18.5, seats = 0),
        )
        val schedule = GeneratedSchedule.measured(sections)
        val intervals = sections.flatMap { ScheduleInterval.intervals(it) }
        val measured = ScheduleMeasure.measure(intervals)
        var expected = 100.0
        expected -= 8.0 * measured.campusDays
        expected -= 2.0 * max(0, measured.classDays - measured.campusDays)
        expected -= measured.gapMinutes / 10.0
        if (measured.earliestStart < 9 * 60) expected -= (9 * 60 - measured.earliestStart) / 15.0
        if (measured.latestEnd > 17 * 60) expected -= (measured.latestEnd - 17 * 60) / 20.0
        expected -= 6.0
        assertEquals(expected, schedule.score, 0.0)
        assertEquals(expected, ScheduleScoring.score(measured, 1, 0), 0.0)
    }

    @Test fun perSectionPointsMatchTheTable() {
        val morning = section("M", "CS", "1", listOf(2, 4), 9.5, 11.0)
        val prefer = SchedulePreferences(timeOfDay = mapOf(TimeBand.MORNING to TimePreference.PREFER))
        val intervals = ScheduleInterval.intervals(morning)
        // 90 minutes on two days, entirely inside the morning band: +3 per hour.
        assertEquals(9.0, SchedulePreferenceRules.sectionScore(morning, intervals, prefer), 0.0)
        val avoid = SchedulePreferences(timeOfDay = mapOf(TimeBand.MORNING to TimePreference.AVOID))
        assertEquals(-18.0, SchedulePreferenceRules.sectionScore(morning, intervals, avoid), 0.0)
        val early = section("E", "CS", "1", listOf(1), 8.0, 9.0)
        val soft = SchedulePreferences(earliestStart = TimeLimit(10 * 60, PreferenceStrength.PREFERRED))
        // 120 minutes outside: 4 + 12 = 16, under the cap of 20.
        assertEquals(-16.0, SchedulePreferenceRules.sectionScore(early, ScheduleInterval.intervals(early), soft), 0.0)
        val loved = section("L", "CS", "1", listOf(1), 9.0, 10.0, instructors = listOf("Smith, Alex"))
        val people = SchedulePreferences(preferredInstructors = listOf("Smith, Alex"), avoidedInstructors = listOf("Jones, Sam"))
        assertEquals(5.0, SchedulePreferenceRules.sectionScore(loved, ScheduleInterval.intervals(loved), people), 0.0)
        val avoided = section("V", "CS", "1", listOf(1), 9.0, 10.0, instructors = listOf("Jones, Sam"))
        assertEquals(-15.0, SchedulePreferenceRules.sectionScore(avoided, ScheduleInterval.intervals(avoided), people), 0.0)
    }
}

class SchedulePreferencesFilterTests {
    @Test fun aBlockedTimeRemovesSectionsThatOverlapIt() {
        val course = course("CS", "3000", listOf(
            section("EVE", "CS", "3000", listOf(2, 4), 18.0, 19.5),
            section("DAY", "CS", "3000", listOf(1, 3, 5), 10.0, 11.0),
            section("LATE", "CS", "3000", listOf(2, 4), 20.0, 21.0),
        ))
        val prefs = SchedulePreferences(blockedTimes = listOf(BlockedTime(label = "Club meeting", days = DaySet(listOf(2, 4)), start = 18 * 60, end = 20 * 60)))
        assertEquals(setOf(setOf("DAY"), setOf("LATE")), crnSets(generate(listOf(course), prefs)))
        assertEquals(3, generate(listOf(course)).totalFound)
    }

    @Test fun blockedTimesOnlyApplyOnTheirDaysAndHours() {
        val course = course("CS", "3000", listOf(
            section("MON", "CS", "3000", listOf(1), 18.0, 19.5),
            section("TUE", "CS", "3000", listOf(2), 18.0, 19.5),
            section("EARLY", "CS", "3000", listOf(2), 16.0, 18.0),
            section("OVER", "CS", "3000", listOf(2), 19.5, 21.0),
        ))
        val prefs = SchedulePreferences(blockedTimes = listOf(BlockedTime(label = "Club", days = DaySet.TUESDAY, start = 18 * 60, end = 20 * 60)))
        assertEquals(setOf(setOf("MON"), setOf("EARLY")), crnSets(generate(listOf(course), prefs)))
    }

    @Test fun aSwitchedOffBlockIsIgnored() {
        val course = course("CS", "3000", listOf(section("A", "CS", "3000", listOf(2), 18.0, 19.0)))
        val prefs = SchedulePreferences(blockedTimes = listOf(BlockedTime(label = "Club", days = DaySet.TUESDAY, start = 18 * 60, end = 20 * 60, isEnabled = false)))
        assertEquals(1, generate(listOf(course), prefs).totalFound)
    }

    @Test fun aPreferredBlockOnlyRanksLower() {
        val course = course("CS", "3000", listOf(
            section("CLASH", "CS", "3000", listOf(2), 18.0, 19.0),
            section("FREE", "CS", "3000", listOf(3), 18.0, 19.0),
        ))
        val prefs = SchedulePreferences(blockedTimes = listOf(
            BlockedTime(label = "Club", days = DaySet.TUESDAY, start = 18 * 60, end = 20 * 60, strength = PreferenceStrength.PREFERRED),
        ))
        val result = generate(listOf(course), prefs)
        assertEquals(listOf(listOf("FREE"), listOf("CLASH")), result.schedules.map { it.crns })
        assertTrue(result.schedules.last().reasons.any { it.isCaveat && it.text == "Overlaps Club" })
    }

    @Test fun daysOffAreHardOnlyWhenRequired() {
        val course = course("CS", "3000", listOf(
            section("FRI", "CS", "3000", listOf(5), 10.0, 11.0),
            section("TUE", "CS", "3000", listOf(2), 10.0, 11.0),
        ))
        val required = SchedulePreferences(daysOff = DaySet.FRIDAY, daysOffStrength = PreferenceStrength.REQUIRED)
        assertEquals(listOf(listOf("TUE")), generate(listOf(course), required).schedules.map { it.crns })
        val preferred = required.copy(daysOffStrength = PreferenceStrength.PREFERRED)
        assertEquals(listOf(listOf("TUE"), listOf("FRI")), generate(listOf(course), preferred).schedules.map { it.crns })
    }

    @Test fun earliestStartAndLatestEndFilterWhenRequired() {
        val course = course("CS", "3000", listOf(
            section("EARLY", "CS", "3000", listOf(1), 8.0, 9.5),
            section("MID", "CS", "3000", listOf(2), 10.0, 11.5),
            section("LATE", "CS", "3000", listOf(3), 16.0, 18.5),
        ))
        var prefs = SchedulePreferences(earliestStart = TimeLimit(10 * 60))
        assertEquals(setOf(setOf("MID"), setOf("LATE")), crnSets(generate(listOf(course), prefs)))
        prefs = prefs.copy(latestEnd = TimeLimit(17 * 60))
        assertEquals(setOf(setOf("MID")), crnSets(generate(listOf(course), prefs)))
    }

    @Test fun softLimitsRankByHowFarOutsideTheyAre() {
        val course = course("CS", "3000", listOf(
            section("VERY", "CS", "3000", listOf(1), 8.0, 9.0),
            section("SLIGHTLY", "CS", "3000", listOf(2), 9.75, 11.0),
            section("OK", "CS", "3000", listOf(3), 10.0, 11.0),
        ))
        val prefs = SchedulePreferences(earliestStart = TimeLimit(10 * 60, PreferenceStrength.PREFERRED))
        assertEquals(listOf(listOf("OK"), listOf("SLIGHTLY"), listOf("VERY")), generate(listOf(course), prefs).schedules.map { it.crns })
    }

    @Test fun requiredFormatSeatsAndCampusFilter() {
        val course = course("CS", "3000", listOf(
            section("INPERSON", "CS", "3000", listOf(1), 9.0, 10.0),
            section("HYBRID", "CS", "3000", listOf(2), 9.0, 10.0, method = "Hybrid"),
            section("ONLINE", "CS", "3000", listOf(3), 9.0, 10.0, campus = "Online", method = "Online"),
            section("FULL", "CS", "3000", listOf(4), 9.0, 10.0, seats = 0, waitlist = 5),
            section("FULLNOLIST", "CS", "3000", listOf(5), 9.0, 10.0, seats = 0),
            section("OAKLAND", "CS", "3000", listOf(1), 12.0, 13.0, campus = "Oakland, CA"),
        ))
        var prefs = SchedulePreferences(format = FormatPreference.IN_PERSON, formatStrength = PreferenceStrength.REQUIRED)
        assertEquals(setOf(setOf("INPERSON"), setOf("FULL"), setOf("FULLNOLIST"), setOf("OAKLAND")), crnSets(generate(listOf(course), prefs)))
        prefs = prefs.copy(seats = SeatPolicy.OPEN_ONLY)
        assertEquals(setOf(setOf("INPERSON"), setOf("OAKLAND")), crnSets(generate(listOf(course), prefs)))
        prefs = prefs.copy(seats = SeatPolicy.WAITLIST_OK)
        assertEquals(setOf(setOf("INPERSON"), setOf("FULL"), setOf("OAKLAND")), crnSets(generate(listOf(course), prefs)))
        prefs = prefs.copy(campus = "Boston", campusStrength = PreferenceStrength.REQUIRED)
        assertEquals(setOf(setOf("INPERSON"), setOf("FULL")), crnSets(generate(listOf(course), prefs)))
    }

    @Test fun sectionsWithoutASeatLimitAreNeverFull() {
        val course = course("CS", "3000", listOf(section("OPEN", "CS", "3000", listOf(1), 9.0, 10.0, seats = 0, capacity = 0)))
        val prefs = SchedulePreferences(seats = SeatPolicy.OPEN_ONLY)
        assertEquals(1, generate(listOf(course), prefs).totalFound)
    }

    @Test fun maxClassesPerDayLimitsHowManySectionsShareADay() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 8.0, 9.0))),
            course("B", "1", listOf(
                section("B1", "B", "1", listOf(1), 10.0, 11.0),
                section("B2", "B", "1", listOf(2), 10.0, 11.0),
            )),
            course("C", "1", listOf(
                section("C1", "C", "1", listOf(1), 12.0, 13.0),
                section("C2", "C", "1", listOf(3), 12.0, 13.0),
            )),
        )
        assertEquals(4, generate(courses).totalFound)
        var prefs = SchedulePreferences(maxClassesPerDay = 2)
        assertEquals(setOf(setOf("A1", "B1", "C2"), setOf("A1", "B2", "C1"), setOf("A1", "B2", "C2")), crnSets(generate(courses, prefs)))
        prefs = prefs.copy(maxClassesPerDay = 1)
        assertEquals(setOf(setOf("A1", "B2", "C2")), crnSets(generate(courses, prefs)))
    }

    @Test fun maxMinutesPerDayLimitsClassTimeOnOneDay() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 11.0))),
            course("B", "1", listOf(
                section("B1", "B", "1", listOf(1), 13.0, 15.0),
                section("B2", "B", "1", listOf(2), 13.0, 15.0),
            )),
        )
        var prefs = SchedulePreferences(maxMinutesPerDay = 3 * 60)
        assertEquals(setOf(setOf("A1", "B2")), crnSets(generate(courses, prefs)))
        prefs = prefs.copy(maxMinutesPerDay = 90)
        val blocked = generate(courses, prefs)
        assertEquals(0, blocked.totalFound)
        assertEquals(1, blocked.diagnostics.courses.first().rejections[ScheduleFilterReason.PREF_WORKLOAD])
    }

    @Test fun requiredWalkingTimeOnlyMattersBetweenBuildings() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0, building = "RY"))),
            course("B", "1", listOf(
                section("SAME", "B", "1", listOf(1), 10.1, 11.0, building = "RY"),
                section("NEAR", "B", "1", listOf(1), 10.1, 11.0, building = "SL"),
                section("FAR", "B", "1", listOf(1), 10.5, 11.5, building = "SL"),
                section("OTHERDAY", "B", "1", listOf(2), 10.1, 11.0, building = "SL"),
            )),
        )
        val prefs = SchedulePreferences(breaks = BreakPreference.AT_LEAST, minimumBreakMinutes = 15, minimumBreakStrength = PreferenceStrength.REQUIRED)
        val blocked = generate(courses, prefs)
        assertEquals(setOf(setOf("A1", "SAME"), setOf("A1", "FAR"), setOf("A1", "OTHERDAY")), crnSets(blocked))
        assertTrue(blocked.diagnostics.conflictingPairs.isEmpty())
    }

    @Test fun aTightWalkIsReportedWhenItIsTheOnlyProblem() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0, building = "RY"))),
            course("B", "1", listOf(section("B1", "B", "1", listOf(1), 10.1, 11.0, building = "SL"))),
        )
        val required = SchedulePreferences(breaks = BreakPreference.AT_LEAST, minimumBreakMinutes = 15, minimumBreakStrength = PreferenceStrength.REQUIRED)
        val result = generate(courses, required)
        assertEquals(0, result.totalFound)
        assertEquals(listOf(ConflictReason.TRAVEL_TIME), result.diagnostics.conflictingPairs.map { it.reason })
        val relaxed = generate(courses, required.copy(minimumBreakStrength = PreferenceStrength.PREFERRED))
        assertEquals(1, relaxed.totalFound)
        assertTrue(relaxed.schedules.first().reasons.any { it.isCaveat && it.text == "Tight between buildings" })
    }

    @Test fun halfTermSectionsAtTheSameTimeStillDontConflictWithPreferences() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1, 3), 10.0, 11.5, startDate = "2026-09-09", endDate = "2026-10-23"))),
            course("B", "1", listOf(section("B1", "B", "1", listOf(1, 3), 10.0, 11.5, startDate = "2026-10-26", endDate = "2026-12-13"))),
        )
        val prefs = SchedulePreferences(
            timeOfDay = mapOf(TimeBand.MORNING to TimePreference.PREFER),
            maxClassesPerDay = 3,
            breaks = BreakPreference.AT_LEAST,
            minimumBreakStrength = PreferenceStrength.REQUIRED,
        )
        assertEquals(1, generate(courses, prefs).totalFound)
        assertEquals(1, generate(courses).totalFound)
    }

    @Test fun requiredCoursesAreStillRequired() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0))),
            course("B", "1", listOf(section("B1", "B", "1", listOf(1), 9.0, 10.0))),
        )
        val prefs = SchedulePreferences(timeOfDay = mapOf(TimeBand.MORNING to TimePreference.PREFER))
        assertEquals(0, generate(courses, prefs).totalFound)
    }

    @Test fun creditTargetsReplaceTheMaximalRule() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0))),
            course("B", "1", listOf(section("B1", "B", "1", listOf(2), 9.0, 10.0)), required = false),
            course("C", "1", listOf(section("C1", "C", "1", listOf(3), 9.0, 10.0)), required = false),
        )
        assertEquals(setOf(setOf("A1", "B1", "C1")), crnSets(generate(courses)))
        var prefs = SchedulePreferences(creditTarget = CreditTarget(8, 8))
        assertEquals(setOf(setOf("A1", "B1"), setOf("A1", "C1")), crnSets(generate(courses, prefs)))
        prefs = prefs.copy(creditTarget = CreditTarget(4, 8))
        assertEquals(setOf(setOf("A1"), setOf("A1", "B1"), setOf("A1", "C1")), crnSets(generate(courses, prefs)))
        prefs = prefs.copy(creditTarget = CreditTarget(12, 16))
        assertEquals(setOf(setOf("A1", "B1", "C1")), crnSets(generate(courses, prefs)))
    }

    @Test fun impossibleCreditTargetsAreExplained() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0))),
            course("B", "1", listOf(section("B1", "B", "1", listOf(2), 9.0, 10.0)), required = false),
        )
        var prefs = SchedulePreferences(creditTarget = CreditTarget(2, 3))
        assertEquals(CountProblem.CreditsOverTarget(4, 3), generate(courses, prefs).diagnostics.countProblem)
        prefs = prefs.copy(creditTarget = CreditTarget(12, 14))
        assertEquals(CountProblem.CreditsUnderTarget(8, 12), generate(courses, prefs).diagnostics.countProblem)
    }

    @Test fun diagnosticsNameThePreferenceThatRemovedSections() {
        val course = course("CS", "3000", listOf(
            section("A", "CS", "3000", listOf(2), 18.0, 19.0),
            section("B", "CS", "3000", listOf(4), 18.5, 19.5),
        ))
        val prefs = SchedulePreferences(blockedTimes = listOf(BlockedTime(label = "Club", days = DaySet(listOf(2, 4)), start = 18 * 60, end = 20 * 60)))
        val result = generate(listOf(course), prefs)
        assertEquals(0, result.totalFound)
        val report = result.diagnostics.courses.first()
        assertTrue(report.isBlocked)
        assertEquals(2, report.rejections[ScheduleFilterReason.BLOCKED_TIME])
    }

    @Test fun relaxationsOfferToSoftenWhatIsInTheWay() {
        val course = course("CS", "3000", listOf(section("A", "CS", "3000", listOf(2), 18.0, 19.0)))
        val block = BlockedTime(label = "Club", days = DaySet.TUESDAY, start = 18 * 60, end = 20 * 60)
        var prefs = SchedulePreferences(blockedTimes = listOf(block), earliestStart = TimeLimit(20 * 60))
        assertTrue(ScheduleGenerator.suggestRelaxations(listOf(course), ScheduleFilters(), prefs).isEmpty())
        prefs = prefs.copy(earliestStart = null)
        val single = ScheduleGenerator.suggestRelaxations(listOf(course), ScheduleFilters(), prefs)
        assertEquals(listOf(ScheduleRelaxation.Preference(PreferenceRelaxation.SoftenBlockedTime(block.id, "Club"))), single.map { it.relaxation })
        val relaxed = single[0].relaxation.apply(prefs)
        assertEquals(PreferenceStrength.PREFERRED, relaxed.blockedTimes.first().strength)
        assertEquals(1, generate(listOf(course), relaxed).totalFound)
    }
}

class SchedulePreferencesRankingTests {
    private val morningOrNight = course("CS", "3000", listOf(
        section("NIGHT", "CS", "3000", listOf(2, 4), 19.0, 20.5),
        section("MORNING", "CS", "3000", listOf(2, 4), 9.5, 11.0),
        section("AFTERNOON", "CS", "3000", listOf(2, 4), 14.5, 16.0),
    ))

    @Test fun withoutPreferencesTheDefaultOrderIsUnchanged() {
        val result = generate(listOf(morningOrNight))
        assertEquals("NIGHT", result.schedules.last().crns.first())
        assertTrue(result.schedules.all { it.reasons.isEmpty() })
    }

    @Test fun aMorningPersonSeesMorningsFirstAndANightOwlSeesNightsFirst() {
        val morning = SchedulePreset.MORNING_PERSON.toggled(SchedulePreferences())
        assertEquals(listOf("MORNING", "AFTERNOON", "NIGHT"), generate(listOf(morningOrNight), morning).schedules.map { it.crns.first() })
        val night = SchedulePreset.NIGHT_OWL.toggled(SchedulePreferences())
        val order = generate(listOf(morningOrNight), night).schedules.map { it.crns.first() }
        assertEquals("NIGHT", order.first())
        assertEquals("MORNING", order.last())
    }

    @Test fun avoidingEarlyMorningsPutsThemLast() {
        val course = course("CS", "3000", listOf(
            section("EIGHT", "CS", "3000", listOf(1, 3, 5), 8.0, 9.0),
            section("NOON", "CS", "3000", listOf(1, 3, 5), 12.0, 13.0),
        ))
        var prefs = SchedulePreferences(timeOfDay = mapOf(TimeBand.EARLY_MORNING to TimePreference.AVOID))
        assertEquals(listOf("NOON", "EIGHT"), generate(listOf(course), prefs).schedules.map { it.crns.first() })
        val eightOnly = course("CS", "3000", listOf(
            section("EIGHT", "CS", "3000", listOf(2), 8.0, 9.0),
            section("NINEFIFTEEN", "CS", "3000", listOf(2, 4, 5), 9.25, 10.25),
        ))
        prefs = SchedulePreferences(timeOfDay = mapOf(TimeBand.EARLY_MORNING to TimePreference.PREFER))
        assertEquals(listOf("EIGHT"), generate(listOf(eightOnly), prefs).schedules.first().crns)
    }

    @Test fun aWishedForDayOffBeatsEverythingElseTheDefaultRankingLikes() {
        val course = course("CS", "3000", listOf(
            section("ONEDAY", "CS", "3000", listOf(5), 9.0, 10.0),
            section("TWODAYS", "CS", "3000", listOf(2, 4), 9.0, 10.0),
        ))
        assertEquals(listOf("ONEDAY"), generate(listOf(course)).schedules.first().crns)
        val prefs = SchedulePreferences(daysOff = DaySet.FRIDAY)
        assertEquals(listOf("TWODAYS"), generate(listOf(course), prefs).schedules.first().crns)
    }

    @Test fun fewerDaysAndSpreadOutPullInOppositeDirections() {
        val course = course("CS", "3000", listOf(
            section("TWO", "CS", "3000", listOf(2, 4), 9.0, 11.0),
            section("FIVE", "CS", "3000", listOf(1, 2, 3, 4, 5), 9.0, 9.8),
        ))
        assertEquals(listOf("TWO"), generate(listOf(course), SchedulePreferences(dayLayout = DayLayout.FEWER_DAYS)).schedules.first().crns)
        assertEquals(listOf("FIVE"), generate(listOf(course), SchedulePreferences(dayLayout = DayLayout.SPREAD_OUT)).schedules.first().crns)
    }

    @Test fun protectingLunchRanksFreeLunchesFirst() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1, 3), 9.0, 10.5))),
            course("B", "1", listOf(
                section("BUSY", "B", "1", listOf(1, 3), 11.75, 13.75),
                section("FREE", "B", "1", listOf(1, 3), 14.0, 15.5),
            )),
        )
        val prefs = SchedulePreferences(lunch = LunchBreak(start = 11 * 60 + 30, end = 14 * 60, minutes = 45))
        val result = generate(courses, prefs)
        assertEquals(setOf("A1", "FREE"), result.schedules.first().crns.toSet())
        assertTrue(result.schedules.first().reasons.any { it.text == "Lunch free every day" })
        assertTrue(result.schedules.last().reasons.any { it.text == "Lunch often busy" })
    }

    @Test fun avoidingBackToBackPrefersRealBreaks() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0))),
            course("B", "1", listOf(
                section("TIGHT", "B", "1", listOf(1), 10.1, 11.0),
                section("SPACED", "B", "1", listOf(1), 11.0, 12.0),
            )),
        )
        val result = generate(courses, SchedulePreferences(breaks = BreakPreference.AVOID_BACK_TO_BACK))
        assertEquals(setOf("A1", "SPACED"), result.schedules.first().crns.toSet())
    }

    @Test fun keepingGapsShortPrefersCompactDays() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0))),
            course("B", "1", listOf(
                section("GAP", "B", "1", listOf(1), 13.0, 14.0),
                section("NOGAP", "B", "1", listOf(1), 10.25, 11.25),
            )),
        )
        var prefs = SchedulePreferences(gaps = GapPreference.COMPACT)
        assertEquals(setOf("A1", "NOGAP"), generate(courses, prefs).schedules.first().crns.toSet())
        prefs = prefs.copy(gaps = GapPreference.FINE)
        assertEquals(2, generate(courses, prefs).totalFound)
    }

    @Test fun preferredAndAvoidedInstructorsMoveSchedules() {
        val course = course("CS", "3000", listOf(
            section("NEUTRAL", "CS", "3000", listOf(1), 9.0, 10.0, instructors = listOf("Lee, Kim")),
            section("LOVED", "CS", "3000", listOf(2), 9.0, 10.0, instructors = listOf("Smith, Alex")),
            section("AVOIDED", "CS", "3000", listOf(3), 9.0, 10.0, instructors = listOf("Jones, Sam")),
        ))
        val prefs = SchedulePreferences(preferredInstructors = listOf("Smith, Alex"), avoidedInstructors = listOf("jones, sam"))
        val result = generate(listOf(course), prefs)
        assertEquals(listOf(listOf("LOVED"), listOf("NEUTRAL"), listOf("AVOIDED")), result.schedules.map { it.crns })
        assertTrue(result.schedules.first().reasons.any { it.text == "With Alex Smith" })
    }

    @Test fun preferredFormatAndCampusRankWithoutRemoving() {
        val course = course("CS", "3000", listOf(
            section("ONLINE", "CS", "3000", listOf(1), 9.0, 10.0, campus = "Online", method = "Online"),
            section("INPERSON", "CS", "3000", listOf(2), 9.0, 10.0),
            section("OAKLAND", "CS", "3000", listOf(3), 9.0, 10.0, campus = "Oakland, CA"),
        ))
        val prefs = SchedulePreferences(format = FormatPreference.IN_PERSON)
        assertEquals(listOf("INPERSON"), generate(listOf(course), prefs).schedules.first().crns)
        assertEquals(3, generate(listOf(course), prefs).totalFound)
        val inPerson = course("CS", "3000", listOf(
            section("BOSTON", "CS", "3000", listOf(2), 9.0, 10.0),
            section("OAKLAND", "CS", "3000", listOf(3), 9.0, 10.0, campus = "Oakland, CA"),
        ))
        assertEquals(listOf(listOf("OAKLAND"), listOf("BOSTON")), generate(listOf(inPerson), SchedulePreferences(campus = "Oakland, CA")).schedules.map { it.crns })
        assertEquals(listOf(listOf("BOSTON"), listOf("OAKLAND")), generate(listOf(inPerson), SchedulePreferences(campus = "Boston")).schedules.map { it.crns })
    }

    @Test fun aSearchThatRunsOutOfLeavesStillMeetsTheBestSectionsFirst() {
        fun night(crn: String, subject: String, day: Int) = section(crn, subject, "1", listOf(day), 19.0, 20.0)
        val a = course("A", "1", (1..8).map { night("A$it", "A", 1) } + section("AMORNING", "A", "1", listOf(1), 9.0, 10.0))
        val b = course("B", "1", (1..8).map { night("B$it", "B", 2) } + section("BMORNING", "B", "1", listOf(2), 9.0, 10.0))
        val prefs = SchedulePreferences(timeOfDay = mapOf(TimeBand.MORNING to TimePreference.PREFER, TimeBand.NIGHT to TimePreference.AVOID))
        val tiny = ScheduleGenerator.Limits(
            maxResults = 100,
            maxExhaustiveLeaves = 3,
            maxNodes = 1_000_000,
            timeBudget = 5.0,
            sampleLeaves = 0,
            sampleTimeBudget = 0.0,
            allowSampling = false,
        )
        val result = ScheduleGenerator.generate(listOf(a, b), ScheduleFilters(), prefs, limits = tiny)
        assertFalse(result.isExhaustive)
        assertEquals(setOf("AMORNING", "BMORNING"), result.schedules.first().crns.toSet())
    }

    @Test fun samplingStillReturnsSchedulesWhenTheSpaceIsTooBig() {
        fun night(crn: String, subject: String, day: Int) = section(crn, subject, "1", listOf(day), 19.0, 20.0)
        val a = course("A", "1", (1..6).map { night("A$it", "A", 1) })
        val b = course("B", "1", (1..6).map { night("B$it", "B", 2) })
        val limits = ScheduleGenerator.Limits(
            maxResults = 5,
            maxExhaustiveLeaves = 2,
            maxNodes = 1_000_000,
            timeBudget = 5.0,
            sampleLeaves = 8,
            sampleTimeBudget = 1.0,
            allowSampling = true,
        )
        val result = ScheduleGenerator.generate(listOf(a, b), ScheduleFilters(), limits = limits)
        assertFalse(result.isExhaustive)
        assertTrue(result.schedules.isNotEmpty())
        assertTrue(result.totalFound >= result.schedules.size)
    }

    @Test fun otherSortOrdersStillWorkWithPreferences() {
        val prefs = SchedulePreferences(timeOfDay = mapOf(TimeBand.MORNING to TimePreference.PREFER))
        val result = generate(listOf(morningOrNight), prefs, sort = ScheduleSortOption.LATEST_START)
        assertEquals(listOf("NIGHT"), result.schedules.first().crns)
    }

    @Test fun generatingWithEmptyPreferencesMatchesGeneratingWithout() {
        val courses = listOf(
            course("A", "1", listOf(
                section("A1", "A", "1", listOf(1, 3), 9.0, 10.0),
                section("A2", "A", "1", listOf(2, 4), 11.0, 12.0),
            )),
            course("B", "1", listOf(
                section("B1", "B", "1", listOf(1, 3), 9.5, 11.0),
                section("B2", "B", "1", listOf(5), 14.0, 17.0),
            )),
        )
        val plain = ScheduleGenerator.generate(courses, ScheduleFilters())
        val withEmpty = generate(courses, SchedulePreferences())
        assertEquals(plain.schedules.map { it.crns }, withEmpty.schedules.map { it.crns })
        assertEquals(plain.schedules.map { it.score }, withEmpty.schedules.map { it.score })
    }

    @Test fun randomPlansMatchAnObviouslyCorrectEnumeration() {
        val rng = SplitMix64(20_261_004L)
        var nonTrivial = 0
        repeat(400) { round ->
            val instance = randomInstance(rng)
            val expected = bruteForce(instance.courses, instance.filters, instance.preferences)
            val result = ScheduleGenerator.generate(instance.courses, instance.filters, instance.preferences)
            assertTrue("Round $round was not exhaustive", result.isExhaustive)
            val actual = crnSets(result)
            assertEquals(
                "Round $round: generator ${actual.size}, enumeration ${expected.size}. Missing ${expected.minus(actual).take(3)}, extra ${actual.minus(expected).take(3)}",
                expected,
                actual,
            )
            assertEquals(expected.size, result.totalFound)
            if (expected.isNotEmpty()) nonTrivial += 1
        }
        assertTrue(nonTrivial > 100)
    }
}

class ScheduleReasonTests {
    @Test fun reasonsAreEmptyWithoutPreferences() {
        val sections = listOf(section("A", "A", "1", listOf(1), 9.0, 10.0))
        assertTrue(SchedulePreferenceRules.reasons(sections, SchedulePreferences()).isEmpty())
    }

    @Test fun aScheduleThatMeetsTheWishesSaysSo() {
        val sections = listOf(
            section("A", "A", "1", listOf(2, 4), 10.5, 12.0),
            section("B", "B", "1", listOf(1, 3), 14.0, 15.5),
        )
        val prefs = SchedulePreferences(daysOff = DaySet.FRIDAY, earliestStart = TimeLimit(10 * 60), lunch = LunchBreak())
        val reasons = SchedulePreferenceRules.reasons(sections, prefs)
        assertEquals(listOf("Fridays off", "No classes before 10 AM", "Lunch free every day"), reasons.map { it.text })
        assertTrue(reasons.all { !it.isCaveat })
    }

    @Test fun wishesItMissesShowUpAsCaveats() {
        val sections = listOf(section("A", "A", "1", listOf(5), 8.0, 9.0))
        val prefs = SchedulePreferences(
            daysOff = DaySet.FRIDAY,
            earliestStart = TimeLimit(10 * 60, PreferenceStrength.PREFERRED),
            timeOfDay = mapOf(TimeBand.EARLY_MORNING to TimePreference.AVOID),
        )
        val reasons = SchedulePreferenceRules.reasons(sections, prefs)
        assertTrue(reasons.none { !it.isCaveat })
        assertEquals(listOf("Class on Fri", "Starts 8:00 AM"), reasons.filter { it.isCaveat }.map { it.text })
        val all = SchedulePreferenceRules.reasons(sections, prefs, maxCaveats = 5)
        assertEquals(listOf("Class on Fri", "Starts 8:00 AM", "Includes early mornings"), all.filter { it.isCaveat }.map { it.text })
    }

    @Test fun morningsAndFormatsAreDescribed() {
        val sections = listOf(
            section("A", "A", "1", listOf(1, 3), 9.5, 11.0),
            section("B", "B", "1", listOf(2, 4), 10.0, 11.5),
        )
        val prefs = SchedulePreferences(
            timeOfDay = mapOf(TimeBand.MORNING to TimePreference.PREFER, TimeBand.EVENING to TimePreference.AVOID),
            format = FormatPreference.IN_PERSON,
            dayLayout = DayLayout.FEWER_DAYS,
        )
        val reasons = SchedulePreferenceRules.reasons(sections, prefs).map { it.text }
        assertTrue(reasons.contains("All mornings"))
        assertTrue(reasons.contains("No evenings"))
        assertTrue(reasons.contains("All in person"))
    }

    @Test fun favoritesGetTheSameTreatment() {
        val sections = listOf(section("A", "A", "1", listOf(2, 4), 11.0, 12.5))
        val prefs = SchedulePreferences(daysOff = DaySet.FRIDAY)
        assertEquals(listOf("Fridays off"), GeneratedSchedule.measured(sections, prefs).reasons.map { it.text })
        assertTrue(GeneratedSchedule.measured(sections).reasons.isEmpty())
    }

    @Test fun theSameThingIsNeverSaidTwice() {
        val sections = listOf(section("A", "A", "1", listOf(2, 4), 18.0, 19.5))
        val prefs = SchedulePreferences(blockedTimes = listOf(
            BlockedTime(label = "Club", days = DaySet.TUESDAY, start = 17 * 60, end = 19 * 60, strength = PreferenceStrength.PREFERRED),
            BlockedTime(label = "Club", days = DaySet.THURSDAY, start = 17 * 60, end = 19 * 60, strength = PreferenceStrength.PREFERRED),
        ))
        val reasons = SchedulePreferenceRules.reasons(sections, prefs)
        assertEquals(listOf("Overlaps Club"), reasons.map { it.text })
        assertEquals(reasons.size, reasons.map { it.id }.toSet().size)
    }

    @Test fun campusesAreNamedPlainly() {
        val sections = listOf(section("A", "A", "1", listOf(1), 9.0, 10.0, campus = "Oakland, CA"))
        val prefs = SchedulePreferences(campus = "Oakland, CA")
        assertEquals(listOf("Oakland, CA campus"), SchedulePreferenceRules.reasons(sections, prefs).map { it.text })
    }

    @Test fun cardTagsAreCapped() {
        val sections = listOf(section("A", "A", "1", listOf(2, 4), 11.0, 12.5))
        val prefs = SchedulePreferences(
            daysOff = DaySet.FRIDAY,
            earliestStart = TimeLimit(600),
            latestEnd = TimeLimit(1_020),
            dayLayout = DayLayout.FEWER_DAYS,
            timeOfDay = mapOf(TimeBand.MORNING to TimePreference.PREFER),
            lunch = LunchBreak(),
            format = FormatPreference.IN_PERSON,
        )
        val reasons = SchedulePreferenceRules.reasons(sections, prefs)
        assertEquals(4, reasons.count { !it.isCaveat })
    }
}

class SchedulePreferencesCatalogTests {
    @Test fun preferencesRankAndFilterARealPlanQuickly() {
        val index = LogicTests.bundledIndex()
        fun inputs(codes: List<String>) = codes.map { code ->
            val parts = code.split(" ")
            val key = CourseKey(parts[0], parts[1])
            val course = index.course(key)
            ScheduleCourseInput(
                key = key,
                isRequired = true,
                sections = index.sections(key),
                minCredits = ceil(course?.minCredits ?: 0.0).toInt(),
                maxCredits = ceil(course?.maxCredits ?: 0.0).toInt(),
            )
        }
        val courses = inputs(listOf("CS 3000", "CS 3001", "MATH 2331", "PHYS 1151"))
        val plain = ScheduleGenerator.generate(courses, ScheduleFilters())
        assertTrue(plain.totalFound > 0)
        val prefs = SchedulePreset.MORNING_PERSON.toggled(
            SchedulePreset.LONG_WEEKENDS.toggled(SchedulePreferences()),
        ).copy(
            blockedTimes = listOf(BlockedTime(label = "Club", days = DaySet(listOf(2, 4)), start = 18 * 60, end = 20 * 60)),
            lunch = LunchBreak(),
            maxClassesPerDay = 3,
        )
        val ranked = ScheduleGenerator.generate(courses, ScheduleFilters(), prefs)
        assertTrue(ranked.totalFound <= plain.totalFound)
        assertTrue(ranked.elapsedSeconds < 10.0)
        val best = ranked.schedules.firstOrNull()
        if (best != null) {
            for (section in best.sections) {
                for (meeting in section.weeklyMeetings) {
                    val overlapsClub = !meeting.days.isDisjoint(DaySet(listOf(2, 4))) &&
                        (meeting.start ?: 0) < 20 * 60 && (meeting.end ?: 0) > 18 * 60
                    assertFalse(overlapsClub)
                }
            }
            assertTrue(best.reasons.isNotEmpty())
        }
        val scores = ranked.schedules.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }
}

private fun section(
    crn: String,
    subject: String,
    number: String,
    days: List<Int>,
    start: Double,
    end: Double,
    seats: Int = 10,
    capacity: Int = 40,
    waitlist: Int = 0,
    building: String = "WVH",
    campus: String = "Boston",
    method: String = "Traditional",
    instructors: List<String> = emptyList(),
    startDate: String = "2026-09-09",
    endDate: String = "2026-12-13",
) = CourseSection(
    crn = crn,
    subject = subject,
    number = number,
    title = "$subject $number",
    sectionNumber = "01",
    partOfTerm = "1",
    seatCapacity = capacity,
    seatsRemaining = seats,
    enrollment = capacity - seats,
    waitlistCapacity = waitlist,
    waitlistCount = 0,
    waitlistRemaining = waitlist,
    classType = "Lecture",
    honors = false,
    campus = campus,
    instructionMethod = method,
    meetings = listOf(Meeting(
        days = DaySet(days),
        start = (start * 60.0).toInt(),
        end = (end * 60.0).toInt(),
        buildingCode = building,
        building = "$building Hall",
        room = "210",
        campusCode = "BOS",
        kind = "Class",
        startDate = startDate,
        endDate = endDate,
    )),
    instructors = instructors.map { Instructor(it, primary = true) },
)

private fun course(
    subject: String,
    number: String,
    sections: List<CourseSection>,
    required: Boolean = true,
    credits: Int = 4,
) = ScheduleCourseInput(CourseKey(subject, number), required, sections, minCredits = credits, maxCredits = credits)

private fun generate(
    courses: List<ScheduleCourseInput>,
    preferences: SchedulePreferences = SchedulePreferences(),
    filters: ScheduleFilters = ScheduleFilters(),
    sort: ScheduleSortOption = ScheduleSortOption.BEST,
) = ScheduleGenerator.generate(courses, filters, preferences, sort)

private fun crnSets(result: app.termorganize.logic.schedule.ScheduleGenerationResult) =
    result.schedules.map { it.crns.toSet() }.toSet()

private class SplitMix64(var state: Long) {
    fun next(): Long {
        state += 0x9E3779B97F4A7C15u.toLong()
        var z = state
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9u.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBu.toLong()
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int = (next().ushr(1) % bound.toLong()).toInt()
    fun nextBool(): Boolean = nextInt(2) == 1
    fun <T> pick(items: List<T>): T = items[nextInt(items.size)]
}

private data class RandomInstance(
    val courses: List<ScheduleCourseInput>,
    val filters: ScheduleFilters,
    val preferences: SchedulePreferences,
)

private fun randomInstance(rng: SplitMix64): RandomInstance {
    val starts = listOf(8.0, 9.5, 11.0, 12.5, 14.0, 15.5, 17.0)
    val buildings = listOf("RY", "SL", "WVH")
    val dayChoices = listOf(listOf(1, 3), listOf(2, 4), listOf(1, 3, 5), listOf(1), listOf(2), listOf(3), listOf(4), listOf(5), listOf(2, 5))
    val windows = listOf("2026-09-09" to "2026-12-13", "2026-09-09" to "2026-10-23", "2026-10-26" to "2026-12-13")
    val courseCount = rng.nextInt(4) + 2
    val courses = (0 until courseCount).map { c ->
        val count = rng.nextInt(4) + 1
        val sections = (0 until count).map { s ->
            val start = rng.pick(starts)
            val window = rng.pick(windows)
            section(
                "C${c}S$s", "C$c", "1", rng.pick(dayChoices), start,
                start + rng.pick(listOf(0.75, 1.25, 1.5, 2.0)),
                building = rng.pick(buildings), startDate = window.first, endDate = window.second,
            )
        }
        course("C$c", "1", sections, required = rng.nextBool() || c == 0, credits = rng.nextInt(4) + 1)
    }
    val filters = if (rng.nextInt(6) == 0) ScheduleFilters(courseCount = rng.nextInt(courseCount) + 1) else ScheduleFilters()
    var prefs = SchedulePreferences()
    if (rng.nextBool()) prefs = prefs.copy(maxClassesPerDay = rng.nextInt(3) + 1)
    if (rng.nextBool()) prefs = prefs.copy(maxMinutesPerDay = rng.pick(listOf(90, 150, 210, 300)))
    if (rng.nextBool()) {
        prefs = prefs.copy(
            breaks = BreakPreference.AT_LEAST,
            minimumBreakMinutes = rng.pick(listOf(10, 20, 40)),
            minimumBreakStrength = PreferenceStrength.REQUIRED,
        )
    }
    if (rng.nextInt(3) == 0) {
        val lower = rng.nextInt(9) + 2
        prefs = prefs.copy(creditTarget = CreditTarget(lower, lower + rng.nextInt(6)))
    }
    if (rng.nextInt(3) == 0) {
        prefs = prefs.copy(blockedTimes = listOf(BlockedTime(label = "Block", days = DaySet(rng.pick(dayChoices)), start = 11 * 60, end = 14 * 60)))
    }
    return RandomInstance(courses, filters, prefs)
}

private fun bruteForce(
    courses: List<ScheduleCourseInput>,
    filters: ScheduleFilters,
    preferences: SchedulePreferences,
): Set<Set<String>> {
    val kept = courses.map { course ->
        course.sections.filter { ScheduleGenerator.rejectionReasons(it, filters, preferences, course.excludedCRNs).isEmpty() }
    }
    val result = HashSet<Set<String>>()
    val target = filters.courseCount?.takeIf { it > 0 }
    fun fits(section: CourseSection, picked: List<CourseSection>): Boolean {
        for (other in picked) {
            if (section.conflicts(other)) return false
            if (preferences.breaks == BreakPreference.AT_LEAST && preferences.minimumBreakStrength == PreferenceStrength.REQUIRED &&
                tooClose(section, other, preferences.minimumBreakMinutes)
            ) return false
        }
        val all = picked + section
        for (day in 0..6) {
            val onDay = all.filter { it.meetingDays.contains(day) }
            val classCap = preferences.maxClassesPerDay
            if (classCap != null && onDay.size > classCap) return false
            val minuteCap = preferences.maxMinutesPerDay
            if (minuteCap != null) {
                val minutes = onDay.sumOf { courseSection ->
                    val seen = HashSet<String>()
                    var sum = 0
                    for (meeting in courseSection.weeklyMeetings) {
                        if (!meeting.days.contains(day)) continue
                        val key = "${meeting.start ?: 0}-${meeting.end ?: 0}"
                        if (seen.add(key)) sum += (meeting.end ?: 0) - (meeting.start ?: 0)
                    }
                    sum
                }
                if (minutes > minuteCap) return false
            }
        }
        return true
    }
    fun finish(picks: List<Pair<Int, CourseSection>>) {
        if (picks.isEmpty()) return
        if (target != null && picks.size != target) return
        val credits = preferences.creditTarget
        if (credits != null) {
            val low = picks.sumOf { courses[it.first].minCredits }
            val high = picks.sumOf { max(courses[it.first].minCredits, courses[it.first].maxCredits) }
            if (low > credits.upper || high < credits.lower) return
        } else if (target == null) {
            val chosenCourses = picks.map { it.first }.toSet()
            val sections = picks.map { it.second }
            for (index in courses.indices) {
                if (index in chosenCourses) continue
                if (kept[index].any { fits(it, sections) }) return
            }
        }
        result += picks.map { it.second.crn }.toSet()
    }
    fun walk(index: Int, picks: List<Pair<Int, CourseSection>>) {
        if (index == courses.size) {
            finish(picks)
            return
        }
        if (!courses[index].isRequired) walk(index + 1, picks)
        for (section in kept[index]) {
            if (fits(section, picks.map { it.second })) walk(index + 1, picks + (index to section))
        }
    }
    walk(0, emptyList())
    return result
}

private fun tooClose(a: CourseSection, b: CourseSection, minutes: Int): Boolean {
    for (x in a.weeklyMeetings) {
        for (y in b.weeklyMeetings) {
            if (x.days.isDisjoint(y.days) || !Meeting.dateWindowsOverlap(x.startDate, x.endDate, y.startDate, y.endDate)) continue
            val xs = x.start ?: continue
            val xe = x.end ?: continue
            val ys = y.start ?: continue
            val ye = y.end ?: continue
            val gap = if (ys >= xe) ys - xe else if (xs >= ye) xs - ye else -1
            if (gap in 0 until minutes && x.buildingCode != y.buildingCode) return true
        }
    }
    return false
}
