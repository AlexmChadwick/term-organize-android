// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.rooms

import app.termorganize.core.model.DaySet
import app.termorganize.core.model.TimeOfDay
import app.termorganize.logic.search.RoomSchedule
import java.time.LocalDate
import java.time.ZoneId

object RoomAvailability {
    const val FIRST_CLASS_MINUTE = 7 * 60
    const val LAST_CLASS_MINUTE = 22 * 60

    data class Moment(val weekday: Int, val minutes: Int, val date: LocalDate? = null) {
        val isWithinClassHours: Boolean get() = minutes in FIRST_CLASS_MINUTE until LAST_CLASS_MINUTE
        val label: String get() = "${DaySet.FULL[weekday]} at ${TimeOfDay.label(minutes)}"

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

        fun isFreeOnlyBriefly(at: Moment): Boolean {
            val free = this as? Free ?: return false
            val until = free.until ?: return false
            return until - at.minutes < 30
        }
    }

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

    fun freeRooms(rooms: List<RoomSchedule>, moment: Moment, forMinutes: Int = 30): List<Pair<RoomSchedule, Status.Free>> =
        rooms.mapNotNull { room ->
            val state = status(room, moment)
            val free = state as? Status.Free ?: return@mapNotNull null
            val until = free.until
            if (until != null && until - moment.minutes < forMinutes) return@mapNotNull null
            room to free
        }
}
