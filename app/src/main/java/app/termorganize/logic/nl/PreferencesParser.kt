// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Deterministic stand-in for the Apple Intelligence preferences survey. The draft,
// grounding cues, and applied() rules follow ScheduleAssistant.swift.

package app.termorganize.logic.nl

import app.termorganize.core.model.DaySet
import app.termorganize.logic.schedule.BlockedTime
import app.termorganize.logic.schedule.BreakPreference
import app.termorganize.logic.schedule.DayLayout
import app.termorganize.logic.schedule.FormatPreference
import app.termorganize.logic.schedule.LunchBreak
import app.termorganize.logic.schedule.PreferenceStrength
import app.termorganize.logic.schedule.SchedulePreferences
import app.termorganize.logic.schedule.SeatPolicy
import app.termorganize.logic.schedule.TimeBand
import app.termorganize.logic.schedule.TimeLimit
import app.termorganize.logic.schedule.TimePreference
import java.util.Locale

enum class DraftWeekday(val day: DaySet) {
    SUNDAY(DaySet.SUNDAY), MONDAY(DaySet.MONDAY), TUESDAY(DaySet.TUESDAY), WEDNESDAY(DaySet.WEDNESDAY),
    THURSDAY(DaySet.THURSDAY), FRIDAY(DaySet.FRIDAY), SATURDAY(DaySet.SATURDAY),
}

enum class DraftTime(val band: TimeBand) {
    EARLY_MORNING(TimeBand.EARLY_MORNING), MORNING(TimeBand.MORNING), MIDDAY(TimeBand.MIDDAY),
    AFTERNOON(TimeBand.AFTERNOON), EVENING(TimeBand.EVENING), NIGHT(TimeBand.NIGHT),
}

enum class DraftLayout { FEWER_DAYS, SPREAD_OUT }
enum class DraftBreaks { AVOID_BACK_TO_BACK, TIME_TO_WALK }
enum class DraftFormat { IN_PERSON, ONLINE, HYBRID }
enum class DraftSeats { OPEN_SEATS_ONLY, WAITLIST_OK }

data class DraftBlockedTime(val label: String, val days: List<DraftWeekday>, val start: String, val end: String)

data class SchedulePreferencesDraft(
    val preferredTimes: List<DraftTime> = emptyList(),
    val avoidedTimes: List<DraftTime> = emptyList(),
    val earliestStart: String? = null,
    val latestEnd: String? = null,
    val timeLimitsAreFlexible: Boolean? = null,
    val daysOff: List<DraftWeekday> = emptyList(),
    val daysOffAreRequired: Boolean? = null,
    val layout: DraftLayout? = null,
    val maxClassesPerDay: Int? = null,
    val maxHoursPerDay: Int? = null,
    val breaks: DraftBreaks? = null,
    val wantsLunchBreak: Boolean? = null,
    val blockedTimes: List<DraftBlockedTime> = emptyList(),
    val format: DraftFormat? = null,
    val seats: DraftSeats? = null,
) {
    val isEmpty: Boolean
        get() = preferredTimes.isEmpty() && avoidedTimes.isEmpty() && earliestStart == null && latestEnd == null &&
            daysOff.isEmpty() && layout == null && maxClassesPerDay == null && maxHoursPerDay == null &&
            breaks == null && wantsLunchBreak != true && blockedTimes.isEmpty() && format == null && seats == null

    fun applied(base: SchedulePreferences): SchedulePreferences {
        var p = base
        val times = p.timeOfDay.toMutableMap()
        preferredTimes.forEach { times[it.band] = TimePreference.PREFER }
        avoidedTimes.forEach { times[it.band] = TimePreference.AVOID }
        p = p.copy(timeOfDay = times)
        val strength = if (timeLimitsAreFlexible == true) PreferenceStrength.PREFERRED else PreferenceStrength.REQUIRED
        minutes(earliestStart)?.takeIf { it < 24 * 60 }?.let { p = p.copy(earliestStart = TimeLimit(it, strength)) }
        minutes(latestEnd)?.takeIf { it > (p.earliestStart?.minutes ?: 0) }?.let { p = p.copy(latestEnd = TimeLimit(it, strength)) }
        if (daysOff.isNotEmpty()) {
            val days = daysOff.fold(DaySet(0)) { acc, day -> acc.union(day.day) }
            p = p.copy(daysOff = days, daysOffStrength = if (daysOffAreRequired == true) PreferenceStrength.REQUIRED else PreferenceStrength.PREFERRED)
        }
        p = when (layout) {
            DraftLayout.FEWER_DAYS -> p.copy(dayLayout = DayLayout.FEWER_DAYS)
            DraftLayout.SPREAD_OUT -> p.copy(dayLayout = DayLayout.SPREAD_OUT)
            null -> p
        }
        if (maxClassesPerDay != null && maxClassesPerDay in 1..8) p = p.copy(maxClassesPerDay = maxClassesPerDay)
        if (maxHoursPerDay != null && maxHoursPerDay in 1..12) p = p.copy(maxMinutesPerDay = maxHoursPerDay * 60)
        p = when (breaks) {
            DraftBreaks.AVOID_BACK_TO_BACK -> p.copy(breaks = BreakPreference.AVOID_BACK_TO_BACK)
            DraftBreaks.TIME_TO_WALK -> p.copy(breaks = BreakPreference.AT_LEAST, minimumBreakMinutes = 15, minimumBreakStrength = PreferenceStrength.PREFERRED)
            null -> p
        }
        if (wantsLunchBreak == true && p.lunch == null) p = p.copy(lunch = LunchBreak())
        val blocks = p.blockedTimes.toMutableList()
        for (block in blockedTimes.take(8)) {
            val start = minutes(block.start) ?: continue
            var end = minutes(block.end) ?: continue
            if (end <= start && end < 12 * 60 && end + 12 * 60 <= 24 * 60) end += 12 * 60
            val days = block.days.fold(DaySet(0)) { acc, day -> acc.union(day.day) }
            val candidate = BlockedTime(label = block.label, days = days, start = start, end = end)
            if (!candidate.isValid) continue
            val label = candidate.label.trim().lowercase(Locale.US)
            val duplicate = blocks.any { it.label.lowercase(Locale.US) == label && it.days == candidate.days && it.start == candidate.start && it.end == candidate.end }
            if (!duplicate) blocks += candidate
        }
        p = p.copy(blockedTimes = blocks)
        p = when (format) {
            DraftFormat.IN_PERSON -> p.copy(format = FormatPreference.IN_PERSON)
            DraftFormat.ONLINE -> p.copy(format = FormatPreference.ONLINE)
            DraftFormat.HYBRID -> p.copy(format = FormatPreference.HYBRID)
            null -> p
        }
        p = when (seats) {
            DraftSeats.OPEN_SEATS_ONLY -> p.copy(seats = SeatPolicy.OPEN_ONLY)
            DraftSeats.WAITLIST_OK -> p.copy(seats = SeatPolicy.WAITLIST_OK)
            null -> p
        }
        return p.normalized()
    }

    fun grounded(description: String): SchedulePreferencesDraft {
        if (!isEnglish(description)) return this
        val text = " ${description.lowercase(Locale.US)} "
        fun mentions(cues: List<String>) = cues.any { it in text }
        var copy = this
        if (!mentions(Cues.format)) copy = copy.copy(format = null)
        if (!mentions(Cues.seats)) copy = copy.copy(seats = null)
        if (!mentions(Cues.breaks)) copy = copy.copy(breaks = null)
        if (!mentions(Cues.lunch)) copy = copy.copy(wantsLunchBreak = null)
        if (!mentions(Cues.layout)) copy = copy.copy(layout = null)
        val perDay = mentions(Cues.perDay) && hasQuantity(text)
        if (!(perDay && "class" in text)) copy = copy.copy(maxClassesPerDay = null)
        if (!(perDay && "hour" in text)) copy = copy.copy(maxHoursPerDay = null)
        val clauses = clauses(text)
        fun has(cues: List<String>, clause: String) = cues.any { it in clause }
        val limitClauses = clauses.filter { has(Cues.timeLimit, it) }
        val offClauses = clauses.filter { has(Cues.weekday, it) && has(Cues.off, it) }
        return copy.copy(
            timeLimitsAreFlexible = limitClauses.isNotEmpty() && limitClauses.all { has(Cues.soft, it) && !has(Cues.firm, it) },
            daysOffAreRequired = offClauses.any { has(Cues.firm, it) || has(Cues.noClassesOn, it) },
        )
    }

    companion object {
        fun minutes(text: String?): Int? {
            if (text == null) return null
            var s = text.lowercase(Locale.US).filter { !it.isWhitespace() && it != '.' }
            when (s) {
                "noon" -> return 12 * 60
                "midnight" -> return 24 * 60
            }
            var meridiem: Boolean? = null
            if (s.endsWith("am")) { meridiem = false; s = s.dropLast(2) }
            else if (s.endsWith("pm")) { meridiem = true; s = s.dropLast(2) }
            if (s.isEmpty()) return null
            val hour: Int
            var minute = 0
            val colon = s.indexOf(':')
            if (colon >= 0) {
                hour = s.substring(0, colon).toIntOrNull() ?: return null
                minute = s.substring(colon + 1).toIntOrNull() ?: return null
            } else if (s.length in 3..4 && s.all { it.isDigit() }) {
                val number = s.toInt()
                hour = number / 100
                minute = number % 100
            } else {
                hour = s.toIntOrNull() ?: return null
            }
            if (hour !in 0..24 || minute !in 0..59) return null
            var h = hour
            if (meridiem != null) {
                if (h !in 1..12) return null
                if (meridiem && h < 12) h += 12
                if (!meridiem && h == 12) h = 0
            }
            val total = h * 60 + minute
            return if (total <= 24 * 60) total else null
        }

        private fun isEnglish(text: String): Boolean {
            val letters = text.count { it.isLetter() }
            if (letters == 0) return true
            val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' || it == ' ' }
            return latin >= letters / 2
        }

        private fun hasQuantity(text: String): Boolean {
            if (text.any { it.isDigit() }) return true
            return listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "twelve", "couple", "no more than", "at most", "max").any { it in text }
        }

        private fun clauses(text: String): List<String> {
            var parts = listOf(text)
            for (separator in listOf(",", ";", ".", "\n", " and ", " but ")) {
                parts = parts.flatMap { it.split(separator) }
            }
            return parts.map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}

private object Cues {
    val format = listOf("online", "remote", "virtual", "zoom", "in person", "in-person", "on campus", "on-campus", "hybrid", "face to face", "face-to-face")
    val seats = listOf("seat", "waitlist", "wait list", "wait-list", "capacity", "full class", "full section", "open section", "open class", "closed class")
    val breaks = listOf("back to back", "back-to-back", "walk", "between class", "between building", "break between", "gap", "rush", "far apart", "time to get", "travel")
    val lunch = listOf("lunch", "meal", " eat ")
    val perDay = listOf("per day", "a day", "each day", "every day", "in a day", "in one day", "daily", "same day")
    val layout = listOf("few days", "fewer days", "fewest days", "spread", "evenly", "compact", "long weekend", "bunch", "pack ", "cram", "consolidat", "every day", "each day", "all week", "days a week", "days per week", "days on campus")
    val timeLimit = listOf("before", "after", "earlier than", "later than", "start", "finish", "end by", "done by", "out by", "no classes", "nothing")
    val weekday = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    val off = listOf(" off", "free", "no class", "without class", "skip", "nothing on")
    val noClassesOn = listOf("no class on", "no classes on", "nothing on")
    val firm = listOf("must", "have to", "has to", "need to", "i need", "can't", "can’t", "cannot", "can not", "no way", "never", "always", "required", "i work", "my job", "my shift", "shifts", "only available")
    val soft = listOf("ideally", "prefer", "if possible", "if i can", "would like", "i'd like", "i’d like", "id like", "rather", "hopefully", "try to", "nice if", "would be nice", "wish")
}

object PreferencesParser {
    fun parse(description: String, base: SchedulePreferences = SchedulePreferences()): SchedulePreferences {
        val draft = interpret(description).grounded(description)
        return draft.applied(base)
    }

    fun interpret(description: String): SchedulePreferencesDraft {
        val text = " ${description.lowercase(Locale.US)} "
        val preferred = mutableListOf<DraftTime>()
        val avoided = mutableListOf<DraftTime>()
        fun avoid(band: DraftTime) { if (Regex("""(no|avoid|not|without|hate|skip)\s+(the\s+)?${band.name.lowercase().replace('_', ' ')}""").containsMatchIn(text) || "no ${band.name.lowercase().replace('_', ' ')}" in text) avoided += band }
        if (Regex("""early mornings?""").containsMatchIn(text) && "no early" !in text && "avoid early" !in text) preferred += DraftTime.EARLY_MORNING
        if (Regex("""\bmornings?\b""").containsMatchIn(text) && !Regex("""no mornings?|avoid mornings?|not mornings?""").containsMatchIn(text)) preferred += DraftTime.MORNING
        if (Regex("""\bmidday\b|noon\b""").containsMatchIn(text) && "no midday" !in text) preferred += DraftTime.MIDDAY
        if (Regex("""\bafternoons?\b""").containsMatchIn(text) && !Regex("""no afternoons?|avoid afternoons?""").containsMatchIn(text)) preferred += DraftTime.AFTERNOON
        if (Regex("""\bevenings?\b""").containsMatchIn(text) && !Regex("""no evenings?|avoid evenings?""").containsMatchIn(text)) preferred += DraftTime.EVENING
        if (Regex("""\bnights?\b|night owl""").containsMatchIn(text) && !Regex("""no nights?|avoid nights?""").containsMatchIn(text)) preferred += DraftTime.NIGHT
        if ("night owl" in text) {
            avoided += DraftTime.EARLY_MORNING
            avoided += DraftTime.MORNING
            preferred += listOf(DraftTime.AFTERNOON, DraftTime.EVENING, DraftTime.NIGHT)
        }
        if ("morning person" in text) {
            preferred += listOf(DraftTime.EARLY_MORNING, DraftTime.MORNING)
            avoided += listOf(DraftTime.EVENING, DraftTime.NIGHT)
        }
        avoid(DraftTime.EARLY_MORNING); avoid(DraftTime.MORNING); avoid(DraftTime.AFTERNOON); avoid(DraftTime.EVENING); avoid(DraftTime.NIGHT)
        var earliest: String? = null
        var latest: String? = null
        Regex("""(?:nothing|no classes?|not|start(?:ing)?|classes)\s+(?:before|earlier than)\s+([0-9:]+\s*(?:am|pm)?|noon|midnight)""").find(text)?.let { earliest = it.groupValues[1].trim() }
        Regex("""(?<!nothing\s)(?<!no\s)(?:after|no earlier than|start(?:ing)? after)\s+([0-9:]+\s*(?:am|pm)?)""").find(text)?.let { if (earliest == null) earliest = it.groupValues[1].trim() }
        Regex("""(?:done by|out by|end by|finish by|before|no later than|nothing after)\s+([0-9:]+\s*(?:am|pm)?|noon|midnight)""").find(text)?.let { latest = it.groupValues[1].trim() }
        val days = mutableListOf<DraftWeekday>()
        val names = listOf(
            DraftWeekday.MONDAY to listOf("monday", "mondays", "mon"),
            DraftWeekday.TUESDAY to listOf("tuesday", "tuesdays", "tue"),
            DraftWeekday.WEDNESDAY to listOf("wednesday", "wednesdays", "wed"),
            DraftWeekday.THURSDAY to listOf("thursday", "thursdays", "thu"),
            DraftWeekday.FRIDAY to listOf("friday", "fridays", "fri"),
            DraftWeekday.SATURDAY to listOf("saturday", "saturdays", "sat"),
            DraftWeekday.SUNDAY to listOf("sunday", "sundays", "sun"),
        )
        for ((day, words) in names) {
            if (words.any { word -> Regex("""\b$word\b.{0,24}\b(off|free)\b|\b(off|free)\b.{0,12}\b$word\b|no classes? on $word|nothing on $word""").containsMatchIn(text) }) days += day
        }
        if ("long weekend" in text && DraftWeekday.FRIDAY !in days) days += DraftWeekday.FRIDAY
        val layout = when {
            listOf("fewer days", "few days", "fewest days", "compact", "long weekend", "cram", "pack ").any { it in text } -> DraftLayout.FEWER_DAYS
            listOf("spread", "evenly").any { it in text } -> DraftLayout.SPREAD_OUT
            else -> null
        }
        val maxClasses = Regex("""(?:no more than|at most|max(?:imum)?|up to)\s+(\d+|one|two|three|four|five)\s+class""").find(text)?.let { number(it.groupValues[1]) }
        val breaks = when {
            "back to back" in text || "back-to-back" in text -> DraftBreaks.AVOID_BACK_TO_BACK
            listOf("time to walk", "time to get", "between building").any { it in text } -> DraftBreaks.TIME_TO_WALK
            else -> null
        }
        val lunch = "lunch" in text || " meal" in text
        val format = when {
            "hybrid" in text -> DraftFormat.HYBRID
            listOf("online", "remote", "virtual", "zoom").any { it in text } -> DraftFormat.ONLINE
            listOf("in person", "in-person", "on campus", "face to face", "face-to-face").any { it in text } -> DraftFormat.IN_PERSON
            else -> null
        }
        val seats = when {
            listOf("open seat", "open section", "open class").any { it in text } -> DraftSeats.OPEN_SEATS_ONLY
            "waitlist" in text || "wait list" in text || "wait-list" in text -> DraftSeats.WAITLIST_OK
            else -> null
        }
        val blocked = mutableListOf<DraftBlockedTime>()
        val block = Regex("""(?:work|job|club|practice|rehearsal|shift|lab meeting)\s+(?:on\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?\s+([0-9:]+\s*(?:am|pm)?)\s*(?:to|-)\s*([0-9:]+\s*(?:am|pm)?)""")
        for (match in block.findAll(text)) {
            val day = names.firstOrNull { pair -> pair.second.any { it.startsWith(match.groupValues[1].take(3)) } }?.first ?: continue
            blocked += DraftBlockedTime(match.value.substringBefore(' ').replaceFirstChar { it.titlecase(Locale.US) }, listOf(day), match.groupValues[2].trim(), match.groupValues[3].trim())
        }
        return SchedulePreferencesDraft(
            preferred.distinct(), avoided.distinct(), earliest, latest, null, days.distinct(), null, layout,
            maxClasses, null, breaks, if (lunch) true else null, blocked, format, seats,
        )
    }

    private fun number(token: String) = token.toIntOrNull() ?: mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5)[token]
}
