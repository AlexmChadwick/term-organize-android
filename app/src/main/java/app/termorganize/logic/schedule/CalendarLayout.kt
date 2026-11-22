// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.schedule

import app.termorganize.core.model.DaySet
import app.termorganize.core.model.TimeOfDay

/** Which days and hours the week grid shows, and how overlapping blocks share a day column. */
object CalendarLayout {
    /** A block with its lane among the blocks it overlaps. [count] is the lane count of its overlap group only. */
    data class Laned(val event: CalendarEvent, val index: Int, val count: Int)

    /** Hours shown on the grid axis, from [start] up to but not including [end]. */
    data class HourRange(val start: Int, val end: Int)

    /** Monday to Friday, plus Saturday and Sunday when asked for or when a block falls on them. */
    fun visibleDays(events: List<CalendarEvent>, showWeekends: Boolean): List<Int> {
        val days = mutableListOf(1, 2, 3, 4, 5)
        val eventDays = events.mapTo(HashSet()) { it.day }
        if (showWeekends || 6 in eventDays) days += 6
        if (showWeekends || 0 in eventDays) days.add(0, 0)
        return days
    }

    /** The configured hours, widened so every block fits whole. */
    fun hourRange(events: List<CalendarEvent>, startHour: Int, endHour: Int): HourRange {
        if (events.isEmpty()) {
            val start = startHour.coerceIn(0, 23)
            return HourRange(start, maxOf(start + 1, endHour).coerceAtMost(24))
        }
        val firstHour = events.minOf { it.start } / 60
        val lastHour = (events.maxOf { it.end } + 59) / 60
        val lower = minOf(startHour, firstHour).coerceAtLeast(0)
        val upper = maxOf(endHour, lastHour, lower + 4).coerceAtMost(24)
        return HourRange(lower.coerceAtMost(upper - 1), upper)
    }

    /**
     * Side-by-side lanes for the blocks of one day. Lane counts are per group of connected overlapping
     * blocks, so a lone block keeps the full column even when another hour is crowded.
     */
    fun lanes(dayEvents: List<CalendarEvent>): List<Laned> {
        val result = ArrayList<Laned>(dayEvents.size)
        val group = ArrayList<Pair<CalendarEvent, Int>>()
        val laneEnds = ArrayList<Int>()
        var groupEnd = Int.MIN_VALUE
        fun flush() {
            val count = laneEnds.size.coerceAtLeast(1)
            group.forEach { (event, lane) -> result += Laned(event, lane, count) }
            group.clear()
            laneEnds.clear()
        }
        for (event in dayEvents.sortedWith(compareBy({ it.start }, { it.end }))) {
            if (event.start >= groupEnd) flush()
            var lane = laneEnds.indexOfFirst { it <= event.start }
            if (lane >= 0) {
                laneEnds[lane] = event.end
            } else {
                laneEnds += event.end
                lane = laneEnds.size - 1
            }
            group += event to lane
            groupEnd = maxOf(groupEnd, event.end)
        }
        flush()
        return result
    }

    /** One spoken description of a block: course, day, time, room or Online, and conflict state. */
    fun accessibilityLabel(event: CalendarEvent): String = buildString {
        append(event.title)
        append(", ").append(DaySet.FULL[event.day]).append(' ').append(TimeOfDay.rangeLabel(event.start, event.end))
        if (event.subtitle.isNotBlank()) append(", ").append(event.subtitle)
        if (event.isConflict) append(", conflicts with another class")
    }
}
