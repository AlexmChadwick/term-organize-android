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
    val laterWindows: List<DateWindow> = emptyList(),
) {
    /** A start and end date, either of which may be open. */
    data class DateWindow(val start: String?, val end: String?)

    /** Every date window this block repeats on: the first, then each folded repeat with a different range. */
    val dateWindows: List<DateWindow> get() = listOf(DateWindow(startDate, endDate)) + laterWindows

    companion object {
        private data class Block(val day: Int, val start: Int, val end: Int, val location: String)

        fun forSection(section: CourseSection, color: Int): List<CalendarEvent> {
            val folded = LinkedHashMap<Block, CalendarEvent>()
            section.weeklyMeetings.forEachIndexed { offset, meeting ->
                val start = meeting.start ?: return@forEachIndexed
                val end = meeting.end ?: return@forEachIndexed
                val location = meeting.locationLabel
                val window = DateWindow(meeting.startDate, meeting.endDate)
                for (day in meeting.days.indices) {
                    val key = Block(day, start, end, location)
                    val existing = folded[key]
                    if (existing != null) {
                        if (window !in existing.dateWindows) {
                            folded[key] = existing.copy(laterWindows = existing.laterWindows + window)
                        }
                        continue
                    }
                    folded[key] = CalendarEvent(
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
                    )
                }
            }
            return folded.values.toList()
        }

        fun markingConflicts(events: List<CalendarEvent>): List<CalendarEvent> = events.map { event ->
            val conflicts = events.any { other ->
                event.payload != other.payload && event.day == other.day &&
                    event.start < other.end && other.start < event.end &&
                    event.dateWindows.any { a ->
                        other.dateWindows.any { b -> Meeting.dateWindowsOverlap(a.start, a.end, b.start, b.end) }
                    }
            }
            if (conflicts) event.copy(isConflict = true) else event
        }
    }
}
