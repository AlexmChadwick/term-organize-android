// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.schedule

import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.Meeting

/** A block on the week grid. Day zero is Sunday and times are minutes after midnight. */
data class CalendarEvent(
    val id: String,
    val title: String,
    val subtitle: String,
    val detail: String,
    val day: Int,
    val start: Int,
    val end: Int,
    val color: Int,
    val payload: String,
    val startDate: String?,
    val endDate: String?,
    val isConflict: Boolean = false,
) {
    companion object {
        private data class Block(val day: Int, val start: Int, val end: Int, val location: String)

        fun forSection(section: CourseSection, color: Int): List<CalendarEvent> = buildList {
            val seen = mutableSetOf<Block>()
            section.weeklyMeetings.forEachIndexed { offset, meeting ->
                val start = meeting.start ?: return@forEachIndexed
                val end = meeting.end ?: return@forEachIndexed
                val location = meeting.locationLabel
                for (day in meeting.days.indices) {
                    if (!seen.add(Block(day, start, end, location))) continue
                    add(CalendarEvent(
                        id = "${section.crn}-$offset-$day-$start",
                        title = section.courseKey.description,
                        subtitle = if (meeting.isOnline) "Online" else location,
                        detail = section.classType,
                        day = day,
                        start = start,
                        end = end,
                        color = color,
                        payload = section.crn,
                        startDate = meeting.startDate,
                        endDate = meeting.endDate,
                    ))
                }
            }
        }

        fun markingConflicts(events: List<CalendarEvent>): List<CalendarEvent> = events.map { event ->
            val conflicts = events.any { other ->
                event.payload != other.payload && event.day == other.day &&
                    event.start < other.end && other.start < event.end &&
                    Meeting.dateWindowsOverlap(event.startDate, event.endDate, other.startDate, other.endDate)
            }
            if (conflicts) event.copy(isConflict = true) else event
        }
    }
}
