// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.rooms

import app.termorganize.core.model.DaySet
import app.termorganize.core.model.TimeOfDay
import app.termorganize.logic.search.BuildingSummary
import app.termorganize.logic.search.RoomSchedule
import java.time.LocalDate
import java.time.ZoneId

object RoomAvailability {
    const val FIRST_CLASS_MINUTE = 7 * 60
    const val LAST_CLASS_MINUTE = 22 * 60

    class Moment(weekday: Int, minutes: Int, val date: LocalDate? = null) {
        val weekday: Int = ((weekday % 7) + 7) % 7
        val minutes: Int = minutes.coerceIn(0, 24 * 60 - 1)
        val isWithinClassHours: Boolean get() = this.minutes in FIRST_CLASS_MINUTE until LAST_CLASS_MINUTE
        val label: String get() = "${DaySet.FULL[this.weekday]} at ${TimeOfDay.label(this.minutes)}"

        override fun equals(other: Any?): Boolean =
            other is Moment && weekday == other.weekday && minutes == other.minutes && date == other.date

        override fun hashCode(): Int = 31 * (31 * weekday + minutes) + (date?.hashCode() ?: 0)

        companion object {
            fun now(zone: ZoneId = ZoneId.of("America/New_York"), clock: java.time.ZonedDateTime = java.time.ZonedDateTime.now(zone)): Moment {
                val weekday = clock.dayOfWeek.value % 7
                return Moment(weekday, clock.hour * 60 + clock.minute, clock.toLocalDate())
            }
        }
    }

    data class Occupancy(val booking: RoomSchedule.Booking, val until: Int, val freeUntil: Int?)

    sealed class Status {
        data class Free(val until: Int?) : Status()
        data class Busy(val occupancy: Occupancy) : Status()
        val isFree: Boolean get() = this is Free
        val summary: String
            get() = when (this) {
                is Free -> if (until == null) "Free for the rest of the day" else "Free until ${TimeOfDay.label(until)}"
                is Busy -> "In use until ${TimeOfDay.label(occupancy.until)}"
            }
        val detailedSummary: String
            get() = when (this) {
                is Free -> summary
                is Busy -> "In use: ${occupancy.booking.course.description} until ${TimeOfDay.label(occupancy.until)}"
            }

        fun minutesRemaining(at: Moment): Int = when (this) {
            is Free -> (until ?: 24 * 60) - at.minutes
            is Busy -> occupancy.until - at.minutes
        }

        fun isFreeOnlyBriefly(at: Moment): Boolean = isFree && minutesRemaining(at) < 30
    }

    data class Entry(val room: RoomSchedule, val status: Status)

    data class BuildingAvailability(
        val building: BuildingSummary,
        val free: List<Entry>,
        val busy: List<Entry>,
        val freeCount: Int,
        val roomCount: Int,
    )

    data class MinuteSpan(val start: Int, val endExclusive: Int)

    fun bookings(room: RoomSchedule, weekday: Int, date: java.time.LocalDate? = null): List<RoomSchedule.Booking> =
        room.bookings.filter { booking ->
            booking.days.contains(weekday) && (date?.let { booking.isActive(it) } != false) &&
                (date != null || !booking.isShortSession)
        }

    fun status(room: RoomSchedule, moment: Moment): Status {
        val todays = room.bookings.filter { booking ->
            booking.days.contains(moment.weekday) && moment.date?.let { booking.isActive(it) } != false &&
                (moment.date != null || !booking.isShortSession)
        }.sortedBy { it.start }
        val current = todays.filter { it.start <= moment.minutes && moment.minutes < it.end }
        if (current.isEmpty()) {
            val next = todays.firstOrNull { it.start > moment.minutes }?.start
            return Status.Free(next)
        }
        var until = current.maxOf { it.end }
        val rest = todays.filter { it.start >= until || (it.start <= until && it.end > until) }.sortedBy { it.start }
        for (booking in rest) {
            if (booking.start > until) break
            until = maxOf(until, booking.end)
        }
        val freeUntil = todays.firstOrNull { it.start >= until }?.start
        return Status.Busy(Occupancy(current.minBy { it.start }, until, freeUntil))
    }

    fun status(day: List<RoomSchedule.Booking>, minutes: Int): Status {
        val ordered = day.sortedWith(compareBy({ it.start }, { it.end }))
        val current = ordered.filter { it.start <= minutes && minutes < it.end }
        if (current.isEmpty()) return Status.Free(ordered.firstOrNull { it.start > minutes }?.start)
        var until = current.maxOf { it.end }
        for (booking in ordered) {
            if (booking.start <= until && booking.end > until) until = booking.end
        }
        return Status.Busy(Occupancy(current.minBy { it.start }, until, ordered.firstOrNull { it.start >= until }?.start))
    }

    fun freeStretches(day: List<RoomSchedule.Booking>, start: Int, end: Int): List<MinuteSpan> {
        if (start >= end) return emptyList()
        val stretches = mutableListOf<MinuteSpan>()
        var cursor = start
        for (booking in day.sortedWith(compareBy({ it.start }, { it.end }))) {
            if (booking.start >= end) break
            if (booking.start > cursor) stretches += MinuteSpan(cursor, booking.start)
            cursor = maxOf(cursor, booking.end)
            if (cursor >= end) return stretches
        }
        if (cursor < end) stretches += MinuteSpan(cursor, end)
        return stretches
    }

    fun freeRoomCounts(rooms: List<RoomSchedule>, weekday: Int, date: java.time.LocalDate? = null): List<Int> {
        val hours = (LAST_CLASS_MINUTE - FIRST_CLASS_MINUTE) / 60
        val counts = IntArray(hours)
        for (room in rooms) {
            val day = bookings(room, weekday, date)
            for (hour in 0 until hours) {
                val start = FIRST_CLASS_MINUTE + hour * 60
                val end = start + 60
                if (day.none { it.start < end && it.end > start }) counts[hour] += 1
            }
        }
        return counts.toList()
    }

    fun availability(building: BuildingSummary, moment: Moment): BuildingAvailability {
        val free = mutableListOf<Pair<Entry, Int>>()
        val busy = mutableListOf<Pair<Entry, Int>>()
        building.rooms.forEachIndexed { order, room ->
            val entry = Entry(room, status(room, moment))
            if (entry.status.isFree) free += entry to order else busy += entry to order
        }
        free.sortWith(compareByDescending<Pair<Entry, Int>> { it.first.status.minutesRemaining(moment) }.thenBy { it.second })
        busy.sortWith(compareBy<Pair<Entry, Int>> { it.first.status.minutesRemaining(moment) }.thenBy { it.second })
        return BuildingAvailability(building, free.map { it.first }, busy.map { it.first }, free.size, building.rooms.size)
    }

    fun availability(buildings: List<BuildingSummary>, moment: Moment): List<BuildingAvailability> =
        buildings.map { availability(it, moment) }

    fun filter(items: List<BuildingAvailability>, query: String): List<BuildingAvailability> {
        val tokens = query.split(Regex("\\s+")).map { fold(it) }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return items
        return items.mapNotNull { item ->
            val name = fold(item.building.name)
            val code = fold(item.building.code)
            val nameWords = name.split(Regex("[^\\p{Alnum}]+")).filter { it.isNotEmpty() }
            val buildingHits = tokens.map { token ->
                code.startsWith(token) || nameWords.any { it.startsWith(token) } || (token.length >= 3 && token in name)
            }
            if (buildingHits.all { it }) return@mapNotNull item
            fun matches(entry: Entry): Boolean {
                val number = fold(entry.room.room)
                return tokens.indices.all { i ->
                    buildingHits[i] || number.startsWith(tokens[i]) || (tokens[i].length >= 2 && tokens[i] in number)
                }
            }
            val free = item.free.filter(::matches)
            val busy = item.busy.filter(::matches)
            if (free.isEmpty() && busy.isEmpty()) null
            else item.copy(free = free, busy = busy)
        }
    }

    private fun fold(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(java.util.Locale.US)

    fun freeRooms(rooms: List<RoomSchedule>, moment: Moment, forMinutes: Int = 30): List<Pair<RoomSchedule, Status.Free>> =
        rooms.mapNotNull { room ->
            val state = status(room, moment)
            val free = state as? Status.Free ?: return@mapNotNull null
            val until = free.until
            if (until != null && until - moment.minutes < forMinutes) return@mapNotNull null
            room to free
        }
}

object RoomsText {
    fun freeSummary(free: Int, total: Int) = "$free of $total ${if (total == 1) "room" else "rooms"} free"

    fun followUpNote(status: RoomAvailability.Status, today: List<RoomSchedule.Booking>): String = when (status) {
        is RoomAvailability.Status.Free -> {
            val until = status.until
            val next = if (until == null) null else today.firstOrNull { it.start == until }
            if (next == null) {
                if (today.isEmpty()) "No classes are scheduled here today." else "No more classes today."
            } else "Next: ${next.course.description}, ${TimeOfDay.rangeLabel(next.start, next.end)}"
        }
        is RoomAvailability.Status.Busy -> {
            val freeUntil = status.occupancy.freeUntil
            if (freeUntil != null) "Then free until ${TimeOfDay.label(freeUntil)}" else "Then free for the rest of the day"
        }
    }
}

object SettingsFormat {
    fun hourLabel(hour: Int): String {
        val hour24 = ((hour % 24) + 24) % 24
        val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
        return "$hour12 ${if (hour24 < 12) "AM" else "PM"}"
    }
}

object LegalText {
    const val LICENSE_URL = "https://www.gnu.org/licenses/agpl-3.0.html"
    const val PLATFORM_NAME = "Android"
    val SEARCH_NEU_SUMMARY =
        "This app is an independent $PLATFORM_NAME adaptation of SearchNEU, the course search built by Sandbox at Northeastern University (sandboxnu). Portions of this app — including the Banner scraping logic, requisite parsing and schedule generation — are derived from SearchNEU's source code."
}
