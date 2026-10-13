// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.widget

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@Serializable
data class WidgetMeeting(
    val id: String,
    val crn: String,
    val courseCode: String,
    val title: String,
    val location: String,
    val classType: String,
    val weekday: Int,
    val start: Int,
    val end: Int,
    val colorIndex: Int,
    val startDate: String? = null,
    val endDate: String? = null,
) {
    fun isActive(on: LocalDate): Boolean {
        if (startDate == null && endDate == null) return true
        val iso = on.toString()
        if (startDate != null && iso < startDate) return false
        if (endDate != null && iso > endDate) return false
        return true
    }
}

data class WidgetOccurrence(val meeting: WidgetMeeting, val start: ZonedDateTime, val end: ZonedDateTime)

@Serializable
data class WidgetScheduleData(
    val generatedAt: Long,
    val termCode: String,
    val termName: String,
    val planName: String,
    val classesStart: Long? = null,
    val classesEnd: Long? = null,
    val meetings: List<WidgetMeeting>,
) {
    fun occurrences(from: ZonedDateTime, limit: Int = 10): List<WidgetOccurrence> {
        if (meetings.isEmpty()) return emptyList()
        val zone = from.zone
        val startOfToday = from.toLocalDate()
        val results = mutableListOf<WidgetOccurrence>()
        val endLimit = classesEnd?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val startLimit = classesStart?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        for (offset in 0 until 14) {
            val day = startOfToday.plusDays(offset.toLong())
            if (endLimit != null && day.isAfter(endLimit)) break
            if (startLimit != null && day.isBefore(startLimit)) continue
            val weekday = day.dayOfWeek.value % 7
            val todays = meetings.filter { it.weekday == weekday && it.isActive(day) }.sortedBy { it.start }
            for (meeting in todays) {
                val start = day.atStartOfDay(zone).plusMinutes(meeting.start.toLong())
                val end = day.atStartOfDay(zone).plusMinutes(meeting.end.toLong())
                if (!end.isAfter(from)) continue
                results += WidgetOccurrence(meeting, start, end)
                if (results.size >= limit) return results
            }
        }
        return results
    }

    fun classes(on: ZonedDateTime): List<WidgetOccurrence> {
        val zone = on.zone
        val day = on.toLocalDate()
        val endLimit = classesEnd?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val startLimit = classesStart?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        if (startLimit != null && day.isBefore(startLimit)) return emptyList()
        if (endLimit != null && day.isAfter(endLimit)) return emptyList()
        val weekday = day.dayOfWeek.value % 7
        return meetings.filter { it.weekday == weekday && it.isActive(day) }.sortedBy { it.start }.map { meeting ->
            WidgetOccurrence(meeting, day.atStartOfDay(zone).plusMinutes(meeting.start.toLong()), day.atStartOfDay(zone).plusMinutes(meeting.end.toLong()))
        }
    }

    companion object {
        val zone: ZoneId = ZoneId.of("America/New_York")
    }
}
