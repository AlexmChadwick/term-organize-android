// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Seat-alert rules are derived from SearchNEU's section updater (AGPL-3.0). See NOTICE.md.

package app.termorganize.logic.watchlist

import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.logic.degree.WatchItem

data class WatchlistAlert(
    val kind: Kind,
    val termCode: String,
    val crn: String,
    val courseKey: CourseKey,
    val courseTitle: String,
    val sectionName: String,
    val seatsRemaining: Int,
    val seatCapacity: Int,
    val waitlistRemaining: Int,
) {
    enum class Kind { SEATS, WAITLIST }
    val title: String
        get() = when (kind) {
            Kind.SEATS -> "Seats open in ${courseKey.description}"
            Kind.WAITLIST -> "Waitlist spot open in ${courseKey.description}"
        }
    val body: String
        get() = when (kind) {
            Kind.SEATS -> {
                val seats = if (seatCapacity > 0) "$seatsRemaining of $seatCapacity seats" else if (seatsRemaining == 1) "1 seat" else "$seatsRemaining seats"
                "$seats in $sectionName (CRN $crn). Register soon!"
            }
            Kind.WAITLIST -> {
                val spots = if (waitlistRemaining == 1) "1 waitlist spot" else "$waitlistRemaining waitlist spots"
                "$spots open in $sectionName (CRN $crn). Join soon!"
            }
        }
    val identifier: String get() = "watch-${kind.name.lowercase()}-${termCode}-$crn"
    val url: String get() = "termorganize://section/$termCode/$crn"
}

object WatchlistComparison {
    data class Outcome(
        val items: List<WatchItem>,
        val alerts: List<WatchlistAlert>,
        val missingIDs: Set<String>,
        val checkedTermCodes: Set<String>,
    )

    fun compare(items: List<WatchItem>, live: Map<String, Map<CourseKey, List<CourseSection>>>, now: Long): Outcome {
        val updated = items.toMutableList()
        val alerts = mutableListOf<WatchlistAlert>()
        val missing = mutableSetOf<String>()
        for (index in updated.indices) {
            val item = updated[index]
            val termSections = live[item.termCode] ?: continue
            val section = termSections[item.courseKey].orEmpty().firstOrNull { it.crn == item.crn }
            if (section == null) {
                missing += item.id
                updated[index] = item.copy(lastCheckedAt = now)
                continue
            }
            val seatsOpened = item.lastSeatsRemaining <= 0 && section.seatsRemaining > 0
            val waitlistOpened = item.notifyOnWaitlist && !seatsOpened && section.seatsRemaining <= 0 &&
                item.lastWaitlistRemaining <= 0 && section.waitlistRemaining > 0
            var next = item.copy(
                lastSeatsRemaining = section.seatsRemaining,
                lastWaitlistRemaining = section.waitlistRemaining,
                seatCapacity = section.seatCapacity,
                lastCheckedAt = now,
            )
            if (seatsOpened || waitlistOpened) {
                next = next.copy(lastNotifiedAt = now)
                alerts += WatchlistAlert(
                    if (seatsOpened) WatchlistAlert.Kind.SEATS else WatchlistAlert.Kind.WAITLIST,
                    item.termCode, item.crn, item.courseKey, item.courseTitle, sectionName(section),
                    section.seatsRemaining, section.seatCapacity, section.waitlistRemaining,
                )
            }
            updated[index] = next
        }
        return Outcome(updated, alerts, missing, live.keys)
    }

    fun sectionName(section: CourseSection): String {
        val number = section.sectionNumber.trim()
        return if (number.isEmpty()) section.classType else "${section.classType} $number"
    }
}

enum class WatchlistStatus {
    OPEN, WAITLIST, FULL, MISSING;

    companion object {
        fun of(item: WatchItem, isMissing: Boolean): WatchlistStatus = when {
            isMissing -> MISSING
            item.lastSeatsRemaining > 0 -> OPEN
            item.lastWaitlistRemaining > 0 -> WAITLIST
            else -> FULL
        }
    }

    fun detail(item: WatchItem): String = when (this) {
        OPEN -> if (item.seatCapacity > 0) "${item.lastSeatsRemaining} of ${item.seatCapacity} open" else "${item.lastSeatsRemaining} open"
        WAITLIST -> {
            val spots = item.lastWaitlistRemaining
            "Full · $spots waitlist ${if (spots == 1) "spot" else "spots"} open"
        }
        FULL -> "Full"
        MISSING -> "No longer listed"
    }

    fun spokenDetail(item: WatchItem): String {
        if (this != OPEN) return detail(item)
        val seats = item.lastSeatsRemaining
        return if (item.seatCapacity > 0) "$seats of ${item.seatCapacity} seats open" else "$seats ${if (seats == 1) "seat" else "seats"} open"
    }
}

object WatchlistFormat {
    fun ago(date: Long, now: Long): String {
        val seconds = maxOf(0, (now - date) / 1000)
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86400 -> "${seconds / 3600} hr ago"
            else -> {
                val days = seconds / 86400
                if (days == 1L) "yesterday" else "$days days ago"
            }
        }
    }
}
