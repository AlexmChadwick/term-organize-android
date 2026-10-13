// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Ports the Swift schedule scoring, ranking, sampler, and reason tests.

package app.termorganize

import app.termorganize.core.model.AppJson
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Instructor
import app.termorganize.core.model.Meeting
import app.termorganize.logic.degree.PlanCourse
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.degree.UserData
import app.termorganize.logic.degree.UserDataStore
import app.termorganize.logic.degree.UserJson
import app.termorganize.logic.nl.DraftBlockedTime
import app.termorganize.logic.nl.DraftBreaks
import app.termorganize.logic.nl.DraftFormat
import app.termorganize.logic.nl.DraftLayout
import app.termorganize.logic.nl.DraftSeats
import app.termorganize.logic.nl.DraftTime
import app.termorganize.logic.nl.DraftWeekday
import app.termorganize.logic.nl.ScheduleAssistant
import app.termorganize.logic.nl.SchedulePreferencesDraft
import app.termorganize.logic.schedule.BlockedTime
import app.termorganize.logic.schedule.PreferenceField
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
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.degree.PrerequisiteLookup
import app.termorganize.logic.offering.OfferingHistory
import app.termorganize.logic.schedule.ScheduleBlockerAnalysis
import app.termorganize.logic.schedule.ScheduleCourseInput
import app.termorganize.logic.schedule.SchedulePreferencesContext
import app.termorganize.logic.schedule.ScheduleReorderOption
import app.termorganize.logic.schedule.ScheduleReorderSuggestions
import app.termorganize.logic.schedule.analyzeBlockers
import app.termorganize.logic.schedule.analyzeEmptyResult
import app.termorganize.core.model.Term
import kotlinx.coroutines.runBlocking
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

class SchedulePreferencesModelTests {
    @Test fun defaultsHaveNoOpinions() {
        val p = SchedulePreferences()
        assertTrue(p.isDefault)
        assertEquals(0, p.activeCount)
        assertTrue(p.summaryParts.isEmpty())
        assertEquals("No preferences yet", p.summary())
    }

    @Test fun settingABandBackToOkayRestoresTheDefault() {
        var p = SchedulePreferences().withBand(TimeBand.MORNING, TimePreference.PREFER)
        assertFalse(p.isDefault)
        p = p.withBand(TimeBand.MORNING, TimePreference.OKAY)
        assertTrue(p.isDefault)
    }

    @Test fun summaryReadsLikeTheBrief() {
        var p = SchedulePreferences(
            timeOfDay = mapOf(TimeBand.EARLY_MORNING to TimePreference.PREFER, TimeBand.MORNING to TimePreference.PREFER),
            daysOff = DaySet.FRIDAY,
            blockedTimes = listOf(
                BlockedTime(label = "Club", days = DaySet.TUESDAY.union(DaySet.THURSDAY), start = 18 * 60, end = 20 * 60),
                BlockedTime(label = "Work", days = DaySet.MONDAY, start = 13 * 60, end = 17 * 60),
            ),
        )
        assertEquals(listOf("Mornings", "Fridays off", "2 blocked times"), p.summaryParts)
        assertEquals("Mornings · Fridays off · 2 blocked times", p.summary())
        p = p.copy(dayLayout = DayLayout.FEWER_DAYS)
        assertEquals("Mornings · Fridays off · Fewer days · +1", p.summary(3))
    }

    @Test fun summaryNamesTheDaysAndTimes() {
        var p = SchedulePreferences(
            daysOff = DaySet.MONDAY.union(DaySet.FRIDAY),
            earliestStart = TimeLimit(10 * 60),
            latestEnd = TimeLimit(17 * 60 + 30, PreferenceStrength.PREFERRED),
        )
        assertEquals(listOf("Nothing before 10 AM", "Prefer done by 5:30 PM", "Mon & Fri off"), p.summaryParts)
        p = p.withBand(TimeBand.EVENING, TimePreference.AVOID).withBand(TimeBand.NIGHT, TimePreference.AVOID)
        assertEquals("No evenings", p.summaryParts.first())
    }

    @Test fun disabledBlocksDontCount() {
        val p = SchedulePreferences(blockedTimes = listOf(BlockedTime(label = "Work", days = DaySet.MONDAY, start = 600, end = 700, isEnabled = false)))
        assertTrue(p.activeBlockedTimes.isEmpty())
        assertEquals(0, p.activeCount)
        assertTrue(p.summaryParts.isEmpty())
        assertFalse(p.isDefault)
    }

    @Test fun everyWishShowsUpInTheSummary() {
        val wishes = listOf(
            SchedulePreferences().withBand(TimeBand.NIGHT, TimePreference.AVOID),
            SchedulePreferences(earliestStart = TimeLimit(600)),
            SchedulePreferences(latestEnd = TimeLimit(1_020, PreferenceStrength.PREFERRED)),
            SchedulePreferences(daysOff = DaySet.FRIDAY),
            SchedulePreferences(dayLayout = DayLayout.SPREAD_OUT),
            SchedulePreferences(maxClassesPerDay = 2),
            SchedulePreferences(maxMinutesPerDay = 240),
            SchedulePreferences(gaps = GapPreference.FINE),
            SchedulePreferences(gaps = GapPreference.COMPACT),
            SchedulePreferences(breaks = BreakPreference.AVOID_BACK_TO_BACK),
            SchedulePreferences(breaks = BreakPreference.AT_LEAST),
            SchedulePreferences(lunch = LunchBreak()),
            SchedulePreferences(blockedTimes = listOf(BlockedTime(label = "Work", days = DaySet.MONDAY, start = 600, end = 700))),
            SchedulePreferences(format = FormatPreference.ONLINE),
            SchedulePreferences(campus = "Boston"),
            SchedulePreferences(seats = SeatPolicy.WAITLIST_OK),
            SchedulePreferences(preferredInstructors = listOf("Smith, Alex")),
            SchedulePreferences(avoidedInstructors = listOf("Jones, Sam")),
            SchedulePreferences(creditTarget = CreditTarget(16, 18)),
        )
        for (p in wishes) {
            assertTrue(p.hasWishes)
            assertTrue(p.summaryParts.isNotEmpty())
            assertTrue(p.activeCount > 0)
        }
        assertFalse(SchedulePreferences().hasWishes)
    }

    @Test fun presetsToggleAndCombine() {
        var p = SchedulePreset.MORNING_PERSON.toggled(SchedulePreferences())
        assertTrue(SchedulePreset.MORNING_PERSON.isApplied(p))
        assertEquals(TimePreference.PREFER, p.band(TimeBand.MORNING))
        assertEquals(TimePreference.AVOID, p.band(TimeBand.NIGHT))
        p = SchedulePreset.LONG_WEEKENDS.toggled(p)
        assertTrue(SchedulePreset.LONG_WEEKENDS.isApplied(p))
        assertTrue(SchedulePreset.MORNING_PERSON.isApplied(p))
        assertTrue(p.daysOff.contains(5))
        assertEquals(DayLayout.FEWER_DAYS, p.dayLayout)
        p = SchedulePreset.NIGHT_OWL.toggled(p)
        assertTrue(SchedulePreset.NIGHT_OWL.isApplied(p))
        assertFalse(SchedulePreset.MORNING_PERSON.isApplied(p))
        assertEquals(TimePreference.AVOID, p.band(TimeBand.MORNING))
        assertEquals(TimePreference.PREFER, p.band(TimeBand.EVENING))
        p = SchedulePreset.NIGHT_OWL.toggled(p)
        p = SchedulePreset.LONG_WEEKENDS.toggled(p)
        assertTrue(p.isDefault)
    }

    @Test fun compactDaysSetsLayoutAndGaps() {
        var p = SchedulePreset.COMPACT_DAYS.toggled(SchedulePreferences())
        assertEquals(DayLayout.FEWER_DAYS, p.dayLayout)
        assertEquals(GapPreference.COMPACT, p.gaps)
        p = SchedulePreset.COMPACT_DAYS.toggled(p)
        assertTrue(p.isDefault)
    }

    @Test fun normalizingDropsWhatCantBeApplied() {
        val p = SchedulePreferences(
            earliestStart = TimeLimit(600),
            latestEnd = TimeLimit(540),
            maxClassesPerDay = 0,
            maxMinutesPerDay = 10,
            minimumBreakMinutes = 1_000,
            lunch = LunchBreak(700, 705, 600),
            blockedTimes = listOf(
                BlockedTime(label = "  ", days = DaySet.MONDAY, start = 600, end = 700),
                BlockedTime(label = "No days", days = DaySet(0), start = 600, end = 700),
                BlockedTime(label = "Backwards", days = DaySet.MONDAY, start = 700, end = 600),
                BlockedTime(label = "Club", days = DaySet.TUESDAY, start = 600, end = 700),
                BlockedTime(label = "club", days = DaySet.TUESDAY, start = 600, end = 700),
            ),
            preferredInstructors = listOf("Smith, Alex", " smith, alex ", ""),
            avoidedInstructors = listOf("Smith, Alex", "Jones, Sam"),
            creditTarget = CreditTarget(40, 50),
        )
        val n = p.normalized()
        assertEquals(TimeLimit(540), n.latestEnd)
        assertNull(n.maxClassesPerDay)
        assertNull(n.maxMinutesPerDay)
        assertEquals(90, n.minimumBreakMinutes)
        assertTrue(n.lunch!!.end - n.lunch.start >= n.lunch.minutes)
        assertEquals(listOf("Blocked time", "Club"), n.blockedTimes.map { it.label })
        assertEquals(listOf("Smith, Alex"), n.preferredInstructors)
        assertEquals(listOf("Jones, Sam"), n.avoidedInstructors)
        assertEquals(30, n.creditTarget?.upper)
    }

    @Test fun changedFieldsListWhatDiffers() {
        val old = SchedulePreferences()
        val block = BlockedTime(label = "Club", days = DaySet.TUESDAY, start = 1_080, end = 1_200)
        val updated = old.withBand(TimeBand.MORNING, TimePreference.AVOID).copy(
            earliestStart = TimeLimit(600),
            daysOff = DaySet.FRIDAY,
            blockedTimes = listOf(block),
            lunch = LunchBreak(),
        )
        assertEquals(
            setOf(PreferenceField.Band(TimeBand.MORNING), PreferenceField.EarliestStart, PreferenceField.DaysOff, PreferenceField.Blocked(block.id), PreferenceField.Lunch),
            updated.changedFields(old),
        )
        assertTrue(updated.changedFields(updated).isEmpty())
    }

    @Test fun roundTrips() {
        val original = fullyLoaded()
        val decoded = AppJson.user.decodeFromString(SchedulePreferences.serializer(), AppJson.user.encodeToString(SchedulePreferences.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test fun anEmptyObjectDecodesToTheDefaults() {
        val decoded = AppJson.user.decodeFromString(SchedulePreferences.serializer(), "{}")
        assertTrue(decoded.isDefault)
    }

    @Test fun unknownAndDamagedValuesFallBackInsteadOfFailing() {
        val json = """
        {
          "timeOfDay": {"morning": "prefer", "brunch": "prefer", "night": "sometimes"},
          "dayLayout": "teleport",
          "earliestStart": {"minutes": "soon"},
          "daysOff": 32,
          "maxClassesPerDay": 3,
          "blockedTimes": [
            {"label": "Club", "days": 20, "start": 1080, "end": 1200},
            "garbage",
            {"label": 7}
          ],
          "futureField": [1, 2, 3]
        }
        """
        val decoded = AppJson.user.decodeFromString(SchedulePreferences.serializer(), json)
        assertEquals(TimePreference.PREFER, decoded.band(TimeBand.MORNING))
        assertEquals(TimePreference.OKAY, decoded.band(TimeBand.NIGHT))
        assertEquals(DayLayout.NO_PREFERENCE, decoded.dayLayout)
        assertNull(decoded.earliestStart)
        assertEquals(DaySet.FRIDAY, decoded.daysOff)
        assertEquals(3, decoded.maxClassesPerDay)
        assertEquals(DaySet(20), decoded.blockedTimes.first { it.label == "Club" }.days)
        assertEquals(listOf("Club"), decoded.activeBlockedTimes.map { it.label })
    }

    @Test fun plansSavedBeforePreferencesExistStillLoad() {
        val plan = SchedulePlan(name = "Plan A", termCode = "202730", courses = listOf(PlanCourse(CourseKey("CS", "3000"), colorIndex = 0)))
        val encoded = AppJson.user.encodeToJsonElement(SchedulePlan.serializer(), plan).jsonObject
        assertNull(encoded["preferences"])
        val decoded = AppJson.user.decodeFromJsonElement(SchedulePlan.serializer(), encoded)
        assertEquals("Plan A", decoded.name)
        assertNull(decoded.preferences)
        assertEquals(1, decoded.courses.size)
    }

    @Test fun planPreferencesSurviveASaveAndLoad() {
        val preferences = fullyLoaded()
        val plan = SchedulePlan(name = "Plan B", termCode = "202730", preferences = preferences)
        val decoded = UserJson.decode(UserJson.encode(UserData(plans = listOf(plan))))
        assertEquals(1, decoded.plans.size)
        assertEquals(preferences, decoded.plans.first().preferences)
    }

    @Test fun theStoreSavesPreferencesWithThePlan() {
        val preferences = fullyLoaded()
        val store = UserDataStore()
        val plan = store.createPlan("Fall", "202710")
        val other = store.createPlan("Other", "202710")
        store.updatePlan(plan.id) { it.copy(preferences = preferences) }
        val reloaded = UserDataStore(UserJson.decode(UserJson.encode(store.data)))
        assertEquals(preferences, reloaded.plan(plan.id)?.preferences)
        assertNull(reloaded.plan(other.id)?.preferences)
        val copy = reloaded.duplicatePlan(plan.id)!!
        assertEquals(preferences, reloaded.plan(copy.id)?.preferences)
    }

    @Test fun preferencesOfAnyShapeNeverTakeTheScheduleDown() {
        val plan = SchedulePlan(name = "Plan D", termCode = "202730")
        val encoded = AppJson.user.encodeToJsonElement(SchedulePlan.serializer(), plan).jsonObject
        for (garbage in listOf(JsonPrimitive(7), JsonPrimitive("text"), JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), JsonPrimitive(true))) {
            val decoded = AppJson.user.decodeFromJsonElement(SchedulePlan.serializer(), JsonObject(encoded + ("preferences" to garbage)))
            assertEquals("Plan D", decoded.name)
            assertTrue(decoded.preferences?.isDefault ?: true)
        }
        val other = JsonObject(encoded + ("preferences" to JsonPrimitive("text")) + ("name" to JsonPrimitive("Other")))
        val file = buildJsonObject {
            put("version", 1)
            put("plans", kotlinx.serialization.json.buildJsonArray { add(encoded); add(other) })
        }
        val decoded = UserJson.decodeElement(file)
        assertEquals(2, decoded.plans.size)
    }

    @Test fun damagedPreferencesNeverTakeTheScheduleDown() {
        val plan = SchedulePlan(name = "Plan C", termCode = "202730")
        val encoded = AppJson.user.encodeToJsonElement(SchedulePlan.serializer(), plan).jsonObject.toMutableMap()
        encoded["preferences"] = buildJsonObject {
            put("timeOfDay", 7)
            put("blockedTimes", "nope")
            put("campus", 3)
        }
        val decoded = AppJson.user.decodeFromJsonElement(SchedulePlan.serializer(), JsonObject(encoded))
        assertEquals("Plan C", decoded.name)
        assertEquals(true, decoded.preferences?.isDefault)
    }
}

class ScheduleDraftTests {
    @Test fun timesAreReadTheWayAModelWritesThem() {
        val parse = SchedulePreferencesDraft.Companion::minutes
        assertEquals(18 * 60, parse("18:00"))
        assertEquals(18 * 60 + 30, parse("6:30 PM"))
        assertEquals(18 * 60, parse("6pm"))
        assertEquals(18 * 60, parse("6 p.m."))
        assertEquals(10 * 60, parse("10"))
        assertEquals(18 * 60, parse("1800"))
        assertEquals(9 * 60 + 30, parse("930"))
        assertEquals(0, parse("12am"))
        assertEquals(12 * 60, parse("12pm"))
        assertEquals(12 * 60, parse("noon"))
        assertEquals(24 * 60, parse("midnight"))
        assertEquals(24 * 60, parse("24:00"))
        assertNull(parse("25:00"))
        assertNull(parse("13pm"))
        assertNull(parse("soon"))
        assertNull(parse(""))
        assertNull(parse("7:75"))
    }

    @Test fun theBriefsExampleBecomesTheExpectedSurvey() {
        val draft = SchedulePreferencesDraft(
            earliestStart = "10:00",
            daysOff = listOf(DraftWeekday.FRIDAY),
            wantsLunchBreak = true,
            blockedTimes = listOf(DraftBlockedTime("Robotics club", listOf(DraftWeekday.TUESDAY, DraftWeekday.THURSDAY), "18:00", "20:00")),
        )
        val applied = draft.applied(SchedulePreferences())
        assertEquals(TimeLimit(600, PreferenceStrength.REQUIRED), applied.earliestStart)
        assertEquals(DaySet.FRIDAY, applied.daysOff)
        assertEquals(PreferenceStrength.PREFERRED, applied.daysOffStrength)
        assertTrue(applied.lunch != null)
        assertEquals(1, applied.blockedTimes.size)
        val block = applied.blockedTimes.first()
        assertEquals("Robotics club", block.label)
        assertEquals(DaySet.TUESDAY.union(DaySet.THURSDAY), block.days)
        assertEquals(18 * 60, block.start)
        assertEquals(20 * 60, block.end)
        assertEquals(PreferenceStrength.REQUIRED, block.strength)
        assertEquals("Nothing before 10 AM · Fridays off · 1 blocked time · Lunch break", applied.summary(4))
    }

    @Test fun bandsAndLayoutsMap() {
        val draft = SchedulePreferencesDraft(
            preferredTimes = listOf(DraftTime.MORNING, DraftTime.EARLY_MORNING),
            avoidedTimes = listOf(DraftTime.NIGHT, DraftTime.MORNING),
            layout = DraftLayout.SPREAD_OUT,
            maxClassesPerDay = 3,
            maxHoursPerDay = 5,
            breaks = DraftBreaks.TIME_TO_WALK,
            format = DraftFormat.HYBRID,
            seats = DraftSeats.WAITLIST_OK,
        )
        val p = draft.applied(SchedulePreferences())
        assertEquals(TimePreference.PREFER, p.band(TimeBand.EARLY_MORNING))
        assertEquals(TimePreference.AVOID, p.band(TimeBand.MORNING))
        assertEquals(TimePreference.AVOID, p.band(TimeBand.NIGHT))
        assertEquals(DayLayout.SPREAD_OUT, p.dayLayout)
        assertEquals(3, p.maxClassesPerDay)
        assertEquals(300, p.maxMinutesPerDay)
        assertEquals(BreakPreference.AT_LEAST, p.breaks)
        assertEquals(15, p.minimumBreakMinutes)
        assertEquals(PreferenceStrength.PREFERRED, p.minimumBreakStrength)
        assertEquals(FormatPreference.HYBRID, p.format)
        assertEquals(SeatPolicy.WAITLIST_OK, p.seats)
    }

    @Test fun flexibleLimitsAndRequiredDaysOff() {
        val draft = SchedulePreferencesDraft(
            earliestStart = "9:30",
            latestEnd = "5pm",
            timeLimitsAreFlexible = true,
            daysOff = listOf(DraftWeekday.MONDAY, DraftWeekday.FRIDAY),
            daysOffAreRequired = true,
        )
        val p = draft.applied(SchedulePreferences())
        assertEquals(TimeLimit(9 * 60 + 30, PreferenceStrength.PREFERRED), p.earliestStart)
        assertEquals(TimeLimit(17 * 60, PreferenceStrength.PREFERRED), p.latestEnd)
        assertEquals(DaySet.MONDAY.union(DaySet.FRIDAY), p.daysOff)
        assertEquals(PreferenceStrength.REQUIRED, p.daysOffStrength)
    }

    @Test fun anEndBeforeTheStartIsRepairedAsTheAfternoonOrEvening() {
        val draft = SchedulePreferencesDraft(blockedTimes = listOf(
            DraftBlockedTime("Lunch", listOf(DraftWeekday.MONDAY), "11:00", "01:00"),
            DraftBlockedTime("Choir", listOf(DraftWeekday.WEDNESDAY), "18:00", "08:00"),
            DraftBlockedTime("Run", listOf(DraftWeekday.FRIDAY), "06:00", "08:00"),
        ))
        val blocks = draft.applied(SchedulePreferences()).blockedTimes
        assertEquals(listOf("Lunch", "Choir", "Run"), blocks.map { it.label })
        assertEquals(11 * 60, blocks[0].start)
        assertEquals(13 * 60, blocks[0].end)
        assertEquals(18 * 60, blocks[1].start)
        assertEquals(20 * 60, blocks[1].end)
        assertEquals(6 * 60, blocks[2].start)
        assertEquals(8 * 60, blocks[2].end)
    }

    @Test fun aFinishBeforeTheStartIsNotBelieved() {
        val draft = SchedulePreferencesDraft(earliestStart = "10:00", latestEnd = "09:00")
        val p = draft.applied(SchedulePreferences())
        assertEquals(10 * 60, p.earliestStart?.minutes)
        assertNull(p.latestEnd)
        val current = SchedulePreferences(latestEnd = TimeLimit(15 * 60))
        assertEquals(15 * 60, SchedulePreferencesDraft(earliestStart = "16:00").applied(current).latestEnd?.minutes)
    }

    @Test fun unusableAnswersAreDroppedNotGuessedAt() {
        val draft = SchedulePreferencesDraft(
            earliestStart = "whenever",
            latestEnd = "26:00",
            maxClassesPerDay = 40,
            maxHoursPerDay = 0,
            blockedTimes = listOf(
                DraftBlockedTime("No days", emptyList(), "10:00", "11:00"),
                DraftBlockedTime("No time", listOf(DraftWeekday.MONDAY), "later", "11:00"),
                DraftBlockedTime("Backwards", listOf(DraftWeekday.MONDAY), "17:00", "16:00"),
            ),
        )
        assertTrue(draft.applied(SchedulePreferences()).isDefault)
    }

    @Test fun aDescriptionAddsToTheSurveyInsteadOfReplacingIt() {
        val current = SchedulePreferences(
            timeOfDay = mapOf(TimeBand.AFTERNOON to TimePreference.PREFER),
            daysOff = DaySet.MONDAY,
            blockedTimes = listOf(BlockedTime(label = "Work", days = DaySet.WEDNESDAY, start = 13 * 60, end = 17 * 60)),
        )
        val draft = SchedulePreferencesDraft(
            daysOff = listOf(DraftWeekday.FRIDAY),
            blockedTimes = listOf(
                DraftBlockedTime("work", listOf(DraftWeekday.WEDNESDAY), "13:00", "17:00"),
                DraftBlockedTime("Choir", listOf(DraftWeekday.THURSDAY), "18:00", "19:30"),
            ),
        )
        val merged = draft.applied(current)
        assertEquals(TimePreference.PREFER, merged.band(TimeBand.AFTERNOON))
        assertEquals(DaySet.FRIDAY, merged.daysOff)
        assertEquals(listOf("Work", "Choir"), merged.blockedTimes.map { it.label })
        val changed = merged.changedFields(current)
        assertTrue(PreferenceField.DaysOff in changed)
        assertFalse(changed.any { it is PreferenceField.Band && it.band == TimeBand.AFTERNOON })
        assertEquals(2, changed.size)
    }

    @Test fun anEmptyDraftIsEmpty() {
        assertTrue(SchedulePreferencesDraft().isEmpty)
        assertTrue(SchedulePreferencesDraft(wantsLunchBreak = false).isEmpty)
        assertFalse(SchedulePreferencesDraft(wantsLunchBreak = true).isEmpty)
        assertTrue(SchedulePreferencesDraft().applied(SchedulePreferences()).isDefault)
        assertEquals(2, SchedulePreferencesDraft(preferredTimes = listOf(DraftTime.MORNING), daysOff = listOf(DraftWeekday.FRIDAY)).filledCount)
    }

    private val briefsExample = "No classes before 10, I have robotics club Tuesdays and Thursdays 6–8pm, I’d like Fridays off and a lunch break"

    private fun overeagerDraft() = SchedulePreferencesDraft(
        earliestStart = "10:00",
        timeLimitsAreFlexible = true,
        daysOff = listOf(DraftWeekday.FRIDAY),
        daysOffAreRequired = true,
        layout = DraftLayout.FEWER_DAYS,
        maxClassesPerDay = 3,
        maxHoursPerDay = 5,
        breaks = DraftBreaks.AVOID_BACK_TO_BACK,
        wantsLunchBreak = true,
        blockedTimes = listOf(DraftBlockedTime("Robotics club", listOf(DraftWeekday.TUESDAY, DraftWeekday.THURSDAY), "18:00", "20:00")),
        format = DraftFormat.ONLINE,
        seats = DraftSeats.OPEN_SEATS_ONLY,
    )

    @Test fun answersTheStudentNeverMentionedAreDropped() {
        val grounded = overeagerDraft().grounded(briefsExample)
        assertNull(grounded.format)
        assertNull(grounded.seats)
        assertNull(grounded.breaks)
        assertNull(grounded.layout)
        assertNull(grounded.maxClassesPerDay)
        assertNull(grounded.maxHoursPerDay)
        assertEquals("10:00", grounded.earliestStart)
        assertEquals(true, grounded.wantsLunchBreak)
        assertEquals(listOf(DraftWeekday.FRIDAY), grounded.daysOff)
        assertEquals(1, grounded.blockedTimes.size)
    }

    @Test fun firmnessFollowsTheWordsNotTheModel() {
        val applied = overeagerDraft().grounded(briefsExample).applied(SchedulePreferences())
        assertEquals(TimeLimit(600, PreferenceStrength.REQUIRED), applied.earliestStart)
        assertEquals(PreferenceStrength.PREFERRED, applied.daysOffStrength)
        assertEquals("Nothing before 10 AM · Fridays off · 1 blocked time · Lunch break", applied.summary(4))
    }

    @Test fun commitmentsAndSoftenersAreRecognized() {
        val work = SchedulePreferencesDraft(daysOff = listOf(DraftWeekday.FRIDAY), daysOffAreRequired = false).grounded("I work Fridays so I need them off")
        assertEquals(true, work.daysOffAreRequired)
        val never = SchedulePreferencesDraft(daysOff = listOf(DraftWeekday.MONDAY)).grounded("No classes on Mondays please")
        assertEquals(true, never.daysOffAreRequired)
        val wish = SchedulePreferencesDraft(daysOff = listOf(DraftWeekday.MONDAY), daysOffAreRequired = true).grounded("Mondays off would be great")
        assertEquals(false, wish.daysOffAreRequired)
        val soft = SchedulePreferencesDraft(earliestStart = "10:00", timeLimitsAreFlexible = false).grounded("Ideally nothing before 10 in the morning")
        assertEquals(true, soft.timeLimitsAreFlexible)
        val firm = SchedulePreferencesDraft(earliestStart = "10:00", timeLimitsAreFlexible = true).grounded("I can’t do classes before 10")
        assertEquals(false, firm.timeLimitsAreFlexible)
    }

    @Test fun limitsNeedANumberAndADay() {
        val draft = SchedulePreferencesDraft(maxClassesPerDay = 3, maxHoursPerDay = 5)
        assertEquals(3, draft.grounded("I don’t want more than three classes a day").maxClassesPerDay)
        assertNull(draft.grounded("I don’t want too many classes").maxClassesPerDay)
        assertEquals(5, draft.grounded("No more than 5 hours of class each day").maxHoursPerDay)
    }

    @Test fun otherLanguagesAreLeftToTheModel() {
        val draft = SchedulePreferencesDraft(timeLimitsAreFlexible = true, daysOff = listOf(DraftWeekday.FRIDAY), daysOffAreRequired = true, format = DraftFormat.ONLINE)
        val text = "Quiero las mañanas libres y los viernes sin clase, porque trabajo por las tardes"
        assertFalse(ScheduleAssistant.isEnglish(text))
        assertEquals(draft, draft.grounded(text))
        assertTrue(ScheduleAssistant.isEnglish(briefsExample))
    }

    @Test fun tooManyBlocksAreCapped() {
        val blocks = (0 until 20).map { DraftBlockedTime("Block $it", listOf(DraftWeekday.MONDAY), "${8 + it % 10}:00", "${9 + it % 10}:00") }
        val p = SchedulePreferencesDraft(blockedTimes = blocks).applied(SchedulePreferences())
        assertTrue(p.blockedTimes.size <= SchedulePreferencesDraft.MAX_BLOCKED_TIMES)
    }

    @Test fun failuresHaveHumanMessages() {
        val failures = listOf(
            ScheduleAssistant.Failure.EmptyDescription,
            ScheduleAssistant.Failure.DescriptionTooLong,
            ScheduleAssistant.Failure.Guardrail,
            ScheduleAssistant.Failure.ContextTooLarge,
            ScheduleAssistant.Failure.UnsupportedLanguage,
            ScheduleAssistant.Failure.Busy,
            ScheduleAssistant.Failure.TimedOut,
            ScheduleAssistant.Failure.NothingFound,
            ScheduleAssistant.Failure.Failed("x"),
            ScheduleAssistant.Failure.Unavailable(ScheduleAssistant.Availability.NOT_ENABLED),
        )
        assertTrue(failures.all { it.message.isNotEmpty() })
        assertTrue(ScheduleAssistant.Availability.DEVICE_NOT_ELIGIBLE.note!!.contains("survey"))
        assertTrue(ScheduleAssistant.Availability.NOT_ENABLED.note!!.contains("Settings"))
        assertTrue(ScheduleAssistant.Availability.NOT_READY.note!!.contains("later"))
        assertNull(ScheduleAssistant.Availability.AVAILABLE.note)
    }
}

class ScheduleBlockerTests {
    @Test fun findsTheCourseThatAlwaysClashes() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0))),
            course("B", "1", listOf(section("B1", "B", "1", listOf(1), 9.5, 10.5))),
            course("C", "1", listOf(section("C1", "C", "1", listOf(2), 9.0, 10.0))),
        )
        val result = generate(courses)
        assertEquals(0, result.totalFound)
        val analysis = ScheduleGenerator.analyzeBlockers(courses, ScheduleFilters(), diagnostics = result.diagnostics)
        assertEquals(3, analysis.requiredCount)
        assertEquals(setOf(listOf("A 1"), listOf("B 1")), analysis.options.map { it.courses.map { course -> course.description } }.toSet())
        assertEquals(setOf("A 1", "B 1"), analysis.allBlockingCourses.map { it.description }.toSet())
        assertEquals(listOf(CourseKey("B", "1")), analysis.clashes(CourseKey("A", "1")))
        assertTrue(analysis.options.all { it.scheduleCount == 1 })
    }

    @Test fun aCourseWithNoSectionsAlwaysHasToGo() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0))),
            course("B", "1", emptyList()),
            course("C", "1", listOf(section("C1", "C", "1", listOf(2), 9.0, 10.0))),
        )
        val analysis = ScheduleGenerator.analyzeBlockers(courses, ScheduleFilters())
        assertEquals(listOf("B 1"), analysis.notOffered.map { it.description })
        assertEquals(listOf(listOf("B 1")), analysis.options.map { it.courses.map { course -> course.description } })
    }

    @Test fun coursesTheRulesRuleOutAreReportedSeparately() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(5), 9.0, 10.0))),
            course("B", "1", listOf(section("B1", "B", "1", listOf(2), 9.0, 10.0))),
        )
        val preferences = SchedulePreferences(daysOff = DaySet.FRIDAY, daysOffStrength = PreferenceStrength.REQUIRED)
        val analysis = ScheduleGenerator.analyzeBlockers(courses, ScheduleFilters(), preferences)
        assertEquals(listOf("A 1"), analysis.filteredOut.map { it.description })
        assertTrue(analysis.notOffered.isEmpty())
        assertEquals(listOf(listOf("A 1")), analysis.options.map { it.courses.map { course -> course.description } })
    }

    @Test fun looksAtPairsWhenNoSingleCourseIsToBlame() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0))),
            course("B", "1", listOf(section("B1", "B", "1", listOf(1), 9.0, 10.0))),
            course("C", "1", listOf(section("C1", "C", "1", listOf(2), 9.0, 10.0))),
            course("D", "1", listOf(section("D1", "D", "1", listOf(2), 9.0, 10.0))),
        )
        val analysis = ScheduleGenerator.analyzeBlockers(courses, ScheduleFilters())
        assertEquals(4, analysis.options.size)
        assertTrue(analysis.options.all { it.courses.size == 2 })
        val sets = analysis.options.map { it.courses.map { course -> course.description }.toSet() }.toSet()
        assertEquals(
            setOf(setOf("A 1", "C 1"), setOf("A 1", "D 1"), setOf("B 1", "C 1"), setOf("B 1", "D 1")),
            sets,
        )
    }

    @Test fun countProblemsAreNotCourseProblems() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0))),
            course("B", "1", listOf(section("B1", "B", "1", listOf(2), 9.0, 10.0))),
        )
        val filters = ScheduleFilters(courseCount = 1)
        val result = ScheduleGenerator.generate(courses, filters)
        val analysis = ScheduleGenerator.analyzeBlockers(courses, filters, diagnostics = result.diagnostics)
        assertTrue(analysis.options.isEmpty())
    }

    @Test fun makingTheBlockersOptionalProducesSchedules() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0))),
            course("B", "1", listOf(section("B1", "B", "1", listOf(1), 9.5, 10.5))),
        )
        val analysis = ScheduleGenerator.analyzeBlockers(courses, ScheduleFilters())
        val drop = analysis.blockingCourses.toSet()
        val relaxed = courses.map { input -> if (input.key in drop) input.copy(isRequired = false) else input }
        assertTrue(ScheduleGenerator.generate(relaxed, ScheduleFilters()).totalFound > 0)
    }

    @Test fun noResultsAnalysisCombinesBlockersAndRelaxations() {
        val courses = listOf(
            course("A", "1", listOf(section("A1", "A", "1", listOf(1), 9.0, 10.0))),
            course("B", "1", listOf(section("B1", "B", "1", listOf(1), 9.5, 10.5))),
        )
        val result = ScheduleGenerator.generate(courses, ScheduleFilters())
        val analysis = ScheduleGenerator.analyzeEmptyResult(courses, ScheduleFilters(), SchedulePreferences(), result.diagnostics)
        assertTrue(analysis.blockers.options.isNotEmpty())
        assertTrue(analysis.suggestions.isEmpty())
    }
}

class SchedulePreferencesContextTests {
    @Test fun campusesInstructorsAndCreditsComeFromThePlansCourses() {
        val index = LogicTests.bundledIndex()
        val plan = SchedulePlan(
            name = "Plan",
            termCode = "202710",
            courses = listOf(
                PlanCourse(key = CourseKey("CS", "3000"), colorIndex = 0),
                PlanCourse(key = CourseKey("MATH", "2331"), isRequired = false, colorIndex = 1),
            ),
        )
        val context = SchedulePreferencesContext.of(plan, index)
        assertTrue(context.campusNames.contains("Boston"))
        assertTrue(context.campusNames.all { !it.contains("online", true) && !it.contains("no campus", true) })
        assertEquals(1, context.optionalCourseCount)
        assertTrue(context.requiredCredits > 0)
        assertTrue(context.totalCredits > context.requiredCredits)
        assertTrue(context.instructors.isNotEmpty())
        assertTrue(context.instructors.all { it.courses.isNotEmpty() })
        assertEquals(context.instructors.map { it.name }.sortedBy { it.lowercase() }, context.instructors.map { it.name })
        assertTrue(SchedulePreferencesContext.of(plan, null).instructors.isEmpty())
    }

    @Test fun excludedSectionsDontOfferTheirInstructors() {
        val index = LogicTests.bundledIndex()
        val key = CourseKey("CS", "3000")
        val plan = SchedulePlan(name = "Plan", termCode = "202710", courses = listOf(PlanCourse(key = key, colorIndex = 0)))
        val everyone = SchedulePreferencesContext.of(plan, index).instructors
        val excluded = plan.copy(courses = listOf(plan.courses[0].copy(excludedCRNs = index.sections(key).map { it.crn }.toSet())))
        assertTrue(SchedulePreferencesContext.of(excluded, index).instructors.isEmpty())
        assertTrue(everyone.isNotEmpty())
    }
}

class ScheduleReorderBridgeTests {
    private fun planned(code: String) = DegreePlanCourse.of(CourseKey.parse(code), code, 4.0)
    private val terms = listOf("202710", "202630", "202610", "202530", "202510")
    private val spring = setOf("202630", "202530")
    private val fall = setOf("202710", "202610", "202510")

    private fun degreePlan() = DegreePlan(
        name = "Plan",
        terms = listOf(
            DegreePlanTerm(season = PlanSeason.FALL, year = 2026, courses = listOf(planned("CS 3386"), planned("CS 3800"))),
            DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = listOf(planned("CS 4100"))),
        ),
    )

    private fun history() = OfferingHistory(
        checkedTerms = terms,
        offered = mapOf(
            CourseKey.parse("CS 3386")!! to spring,
            CourseKey.parse("CS 3800")!! to fall,
            CourseKey.parse("CS 4100")!! to (fall + spring),
        ),
    )

    @Test fun aSuggestionBecomesAnOptionThatMovesTheCourseOutOfTheSchedule() {
        val plan = degreePlan()
        val origin = plan.terms[0]
        val found = app.termorganize.logic.degree.DegreePlanReordering.suggestions(
            plan, origin.id, listOf(CourseKey.parse("CS 3386")!!), history(), { PrerequisiteLookup.None }, app.termorganize.core.model.CourseEquivalences.none,
        )
        val single = found.first { it.moves.size == 1 }
        val option = ScheduleReorderSuggestions.option(single, origin)
        assertEquals("Move CS 3386 to Spring 2027", option.title)
        assertEquals("Moved CS 3386 to Spring 2027", option.appliedText)
        assertEquals(listOf(CourseKey.parse("CS 3386")), option.movedAway)
        assertTrue(option.movedIn.isEmpty())
        assertEquals("CS 3386 comes out of this schedule.", option.scheduleEffect)
        assertEquals("Offered every spring", option.reasons.first())
        assertFalse(option.addsSemester)
        val changed = option.apply(plan)
        assertEquals(listOf("CS 3800"), changed.terms[0].courses.map { it.key?.description })
        assertEquals(listOf("CS 4100", "CS 3386"), changed.terms[1].courses.map { it.key?.description })
        assertEquals(2, plan.terms[0].courses.size)
    }

    @Test fun aSwapAlsoBringsACourseIn() {
        val plan = degreePlan()
        val origin = plan.terms[0]
        val found = app.termorganize.logic.degree.DegreePlanReordering.suggestions(
            plan, origin.id, listOf(CourseKey.parse("CS 3386")!!), history(), { PrerequisiteLookup.None }, app.termorganize.core.model.CourseEquivalences.none,
        )
        val swap = found.first { it.moves.size == 2 }
        val option = ScheduleReorderSuggestions.option(swap, origin)
        assertEquals(listOf(CourseKey.parse("CS 3386")), option.movedAway)
        assertEquals(listOf(CourseKey.parse("CS 4100")), option.movedIn)
        assertEquals("CS 3386 comes out of this schedule. CS 4100 will be added to it if it\u2019s offered this term.", option.scheduleEffect)
    }

    @Test fun aNewSemesterIsFlagged() {
        val plan = degreePlan().copy(terms = listOf(DegreePlanTerm(season = PlanSeason.FALL, year = 2026, courses = listOf(planned("CS 3386")))))
        val origin = plan.terms[0]
        val found = app.termorganize.logic.degree.DegreePlanReordering.suggestions(
            plan, origin.id, listOf(CourseKey.parse("CS 3386")!!), history(), { PrerequisiteLookup.None }, app.termorganize.core.model.CourseEquivalences.none,
        )
        val option = ScheduleReorderSuggestions.option(found.first(), origin)
        assertTrue(option.addsSemester)
        val changed = option.apply(plan)
        assertEquals(2, changed.terms.size)
        assertEquals(listOf("CS 3386"), changed.terms.last().courses.map { it.key?.description })
    }

    @Test fun nothingIsLookedUpWhenNoBlockingCourseIsInTheSemester() = runBlocking {
        val plan = degreePlan()
        val fall2026 = Term("202710", "Fall 2026 Semester")
        val result = ScheduleReorderSuggestions.load(
            listOf(CourseKey.parse("CS 9001")!!), plan, fall2026, emptyList(),
            historyOf = { _ -> error("looked up") },
        )
        assertTrue(result.options.isEmpty())
        val summer = Term("202750", "Summer Full 2027 Semester")
        val other = ScheduleReorderSuggestions.load(
            listOf(CourseKey.parse("CS 3386")!!), plan, summer, emptyList(),
            historyOf = { _ -> error("looked up") },
        )
        assertTrue(other.options.isEmpty())
    }

    @Test fun titlesThatAreNotMovesStillReadSensibly() {
        val option = ScheduleReorderOption("x", "Something else", emptyList(), false, emptyList(), emptyList()) { it }
        assertEquals("Updated your degree plan", option.appliedText)
        assertEquals("", option.scheduleEffect)
    }
}

private fun fullyLoaded() = SchedulePreferences(
    timeOfDay = mapOf(TimeBand.EARLY_MORNING to TimePreference.AVOID, TimeBand.AFTERNOON to TimePreference.PREFER),
    earliestStart = TimeLimit(600, PreferenceStrength.PREFERRED),
    latestEnd = TimeLimit(1_020),
    daysOff = DaySet.FRIDAY.union(DaySet.MONDAY),
    daysOffStrength = PreferenceStrength.REQUIRED,
    dayLayout = DayLayout.SPREAD_OUT,
    maxClassesPerDay = 3,
    maxMinutesPerDay = 300,
    gaps = GapPreference.COMPACT,
    breaks = BreakPreference.AT_LEAST,
    minimumBreakMinutes = 20,
    minimumBreakStrength = PreferenceStrength.REQUIRED,
    lunch = LunchBreak(690, 840, 45),
    blockedTimes = listOf(
        BlockedTime(label = "Robotics club", days = DaySet.TUESDAY.union(DaySet.THURSDAY), start = 18 * 60, end = 20 * 60),
        BlockedTime(label = "Work", days = DaySet.MONDAY, start = 13 * 60, end = 17 * 60, strength = PreferenceStrength.PREFERRED, isEnabled = false),
    ),
    format = FormatPreference.HYBRID,
    formatStrength = PreferenceStrength.REQUIRED,
    campus = "Boston",
    campusStrength = PreferenceStrength.REQUIRED,
    seats = SeatPolicy.WAITLIST_OK,
    preferredInstructors = listOf("Smith, Alex"),
    avoidedInstructors = listOf("Jones, Sam"),
    creditTarget = CreditTarget(16, 18),
)
