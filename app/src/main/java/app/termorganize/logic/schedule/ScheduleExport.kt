// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.schedule

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.Meeting
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.degree.TermCalendar
import app.termorganize.logic.search.CatalogIndex
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** A class meeting (weekly) or final exam (single) ready to be written to a calendar. */
data class ScheduleExportEvent(
    val uid: String,
    val title: String,
    val location: String?,
    val notes: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val startMinute: Int?,
    val endMinute: Int?,
    val weekdays: List<Int> = emptyList(),
    val lastYear: Int? = null,
    val lastMonth: Int? = null,
    val lastDay: Int? = null,
    val isFinalExam: Boolean = false,
) {
    val isRecurring: Boolean get() = weekdays.isNotEmpty() && lastYear != null && lastMonth != null && lastDay != null
}

data class ScheduleExportSummary(
    var classCount: Int = 0,
    var weeklyMeetings: Int = 0,
    var finalExams: Int = 0,
    var skippedMeetings: Int = 0,
)

sealed class ScheduleCalendarOutcome {
    data class Added(val count: Int) : ScheduleCalendarOutcome()
    data object NothingToAdd : ScheduleCalendarOutcome()
    data object Denied : ScheduleCalendarOutcome()
    data class Failed(val message: String) : ScheduleCalendarOutcome()
}

private val weekdayCodes = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")

/** One event per weekly meeting of every chosen section, plus each final exam. */
fun ScheduleExporter.events(plan: SchedulePlan, index: CatalogIndex): Pair<List<ScheduleExportEvent>, ScheduleExportSummary> {
    val selected = LinkedHashMap<String, CourseSection>()
    for (crn in plan.selectedCRNs) {
        val section = index.section(crn) ?: continue
        selected.putIfAbsent(crn, section)
    }
    val titles = plan.courses.associate { it.key to it.key.description }
    val summary = ScheduleExportSummary()
    val events = ArrayList<ScheduleExportEvent>()
    for (section in selected.values) {
        summary.classCount += 1
        val code = section.courseKey.description
        val courseTitle = titles[section.courseKey] ?: section.title
        val notes = buildList {
            add(if (courseTitle.isBlank()) section.title else courseTitle)
            add("CRN ${section.crn} · ${section.sectionNumber.ifBlank { section.classType }}")
            val instructors = section.instructorLabel
            if (instructors != "Instructor unavailable" && instructors != "TBA") add("Instructor: $instructors")
            add("Added from Term Organize")
        }.joinToString("\n")
        section.weeklyMeetings.forEachIndexed { number, meeting ->
            val event = weeklyEvent(plan, section, meeting, number, code, notes)
            if (event == null) summary.skippedMeetings += 1
            else {
                summary.weeklyMeetings += 1
                events += event
            }
        }
        val exam = section.finalExam
        if (exam != null) {
            val parts = isoParts(exam.startDate)
            if (parts != null) {
                summary.finalExams += 1
                val timed = exam.start != null && exam.end != null
                events += ScheduleExportEvent(
                    uid = "${plan.id.lowercase(Locale.US)}-${section.crn}-final@neuclasssearch",
                    title = "$code Final Exam",
                    location = locationOf(exam, section),
                    notes = "Final exam\n$notes",
                    year = parts[0],
                    month = parts[1],
                    day = parts[2],
                    startMinute = if (timed) exam.start else null,
                    endMinute = if (timed) exam.end else null,
                    isFinalExam = true,
                )
            }
        }
    }
    return events to summary
}

fun ScheduleExporter.contentValues(event: ScheduleExportEvent, calendarId: Long): ContentValues {
    val values = ContentValues()
    values.put(CalendarContract.Events.CALENDAR_ID, calendarId)
    values.put(CalendarContract.Events.TITLE, event.title)
    event.location?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
    values.put(CalendarContract.Events.DESCRIPTION, event.notes)
    values.put(CalendarContract.Events.EVENT_TIMEZONE, "America/New_York")
    values.put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
    val start = event.startMinute
    val end = event.endMinute
    if (start != null && end != null) {
        values.put(CalendarContract.Events.ALL_DAY, 0)
        values.put(CalendarContract.Events.DTSTART, zonedMillis(event.year, event.month, event.day, start))
        if (event.isRecurring) {
            values.put(CalendarContract.Events.DURATION, duration(end - start))
            values.put(CalendarContract.Events.RRULE, rrule(event))
        } else {
            values.put(CalendarContract.Events.DTEND, zonedMillis(event.year, event.month, event.day, end))
        }
    } else {
        values.put(CalendarContract.Events.ALL_DAY, 1)
        values.put(CalendarContract.Events.DTSTART, zonedMillis(event.year, event.month, event.day, 0))
        values.put(CalendarContract.Events.DTEND, zonedMillis(event.year, event.month, event.day, 0) + 86_400_000L)
        if (event.isRecurring) values.put(CalendarContract.Events.RRULE, rrule(event))
    }
    return values
}

/** Inserts every meeting. Recurring events use DURATION, not DTEND, which Calendar requires. */
fun ScheduleExporter.insertAll(context: Context, events: List<ScheduleExportEvent>): ScheduleCalendarOutcome {
    if (events.isEmpty()) return ScheduleCalendarOutcome.NothingToAdd
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        return ScheduleCalendarOutcome.Denied
    }
    val calendarId = primaryCalendarId(context) ?: return ScheduleCalendarOutcome.Failed("No calendar is available for new events.")
    var added = 0
    try {
        for (event in events) {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ScheduleExporter.contentValues(event, calendarId))
            if (uri != null) added += 1
        }
    } catch (error: SecurityException) {
        return ScheduleCalendarOutcome.Denied
    } catch (error: Exception) {
        return ScheduleCalendarOutcome.Failed(error.message ?: "Couldn’t add the events.")
    }
    return if (added == 0) ScheduleCalendarOutcome.NothingToAdd else ScheduleCalendarOutcome.Added(added)
}

fun ScheduleExporter.calendarText(events: List<ScheduleExportEvent>, calendarName: String, nowMillis: Long = System.currentTimeMillis()): String {
    val lines = mutableListOf(
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//Term Organize//Schedule//EN",
        "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH",
        "X-WR-CALNAME:${escapeIcs(calendarName)}",
        "X-WR-TIMEZONE:America/New_York",
        "BEGIN:VTIMEZONE",
        "TZID:America/New_York",
        "X-LIC-LOCATION:America/New_York",
        "BEGIN:DAYLIGHT",
        "TZOFFSETFROM:-0500",
        "TZOFFSETTO:-0400",
        "TZNAME:EDT",
        "DTSTART:19700308T020000",
        "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU",
        "END:DAYLIGHT",
        "BEGIN:STANDARD",
        "TZOFFSETFROM:-0400",
        "TZOFFSETTO:-0500",
        "TZNAME:EST",
        "DTSTART:19701101T020000",
        "RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU",
        "END:STANDARD",
        "END:VTIMEZONE",
    )
    val stamp = utcStamp(nowMillis)
    for (event in events) {
        lines += "BEGIN:VEVENT"
        lines += "UID:${event.uid}"
        lines += "DTSTAMP:$stamp"
        val start = event.startMinute
        val end = event.endMinute
        if (start != null && end != null) {
            lines += "DTSTART;TZID=America/New_York:${localStamp(event.year, event.month, event.day, start)}"
            lines += "DTEND;TZID=America/New_York:${localStamp(event.year, event.month, event.day, end)}"
        } else {
            lines += "DTSTART;VALUE=DATE:${dateStamp(event.year, event.month, event.day)}"
            val next = LocalDate.of(event.year, event.month, event.day).plusDays(1)
            lines += "DTEND;VALUE=DATE:${dateStamp(next.year, next.monthValue, next.dayOfMonth)}"
        }
        if (event.isRecurring) {
            val days = event.weekdays.joinToString(",") { weekdayCodes[it] }
            lines += "RRULE:FREQ=WEEKLY;BYDAY=$days;UNTIL=${untilStamp(event.lastYear!!, event.lastMonth!!, event.lastDay!!)}"
        }
        lines += "SUMMARY:${escapeIcs(event.title)}"
        event.location?.let { lines += "LOCATION:${escapeIcs(it)}" }
        lines += "DESCRIPTION:${escapeIcs(event.notes)}"
        lines += "TRANSP:OPAQUE"
        lines += "END:VEVENT"
    }
    lines += "END:VCALENDAR"
    return lines.joinToString("\r\n") + "\r\n"
}

private fun weeklyEvent(
    plan: SchedulePlan,
    section: CourseSection,
    meeting: Meeting,
    number: Int,
    code: String,
    notes: String,
): ScheduleExportEvent? {
    val start = meeting.start ?: return null
    val end = meeting.end ?: return null
    val startParts = isoParts(meeting.startDate) ?: return null
    val endParts = isoParts(meeting.endDate) ?: return null
    val weekdays = meeting.days.indices
    if (weekdays.isEmpty()) return null
    val startDay = LocalDate.of(startParts[0], startParts[1], startParts[2])
    val endDay = LocalDate.of(endParts[0], endParts[1], endParts[2])
    val first = firstDay(startDay, weekdays) ?: return null
    if (first.isAfter(endDay)) return null
    return ScheduleExportEvent(
        uid = "${plan.id.lowercase(Locale.US)}-${section.crn}-$number@neuclasssearch",
        title = "$code ${section.classType}",
        location = locationOf(meeting, section),
        notes = notes,
        year = first.year,
        month = first.monthValue,
        day = first.dayOfMonth,
        startMinute = start,
        endMinute = end,
        weekdays = weekdays,
        lastYear = endParts[0],
        lastMonth = endParts[1],
        lastDay = endParts[2],
    )
}

private fun firstDay(start: LocalDate, weekdays: List<Int>): LocalDate? {
    var day = start
    repeat(7) {
        if (day.dayOfWeek.value % 7 in weekdays) return day
        day = day.plusDays(1)
    }
    return null
}

private fun locationOf(meeting: Meeting, section: CourseSection): String? {
    if (meeting.isOnline || section.isOnline) return "Online"
    val label = meeting.longLocationLabel
    return if (label == "Location TBA") null else label
}

private fun isoParts(iso: String?): IntArray? {
    if (iso.isNullOrBlank()) return null
    val parts = iso.split("-")
    if (parts.size < 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].take(2).toIntOrNull() ?: return null
    return intArrayOf(year, month, day)
}

private fun duration(minutes: Int): String {
    val whole = minutes.coerceAtLeast(0)
    val hours = whole / 60
    val mins = whole % 60
    return when {
        hours > 0 && mins > 0 -> "PT${hours}H${mins}M"
        hours > 0 -> "PT${hours}H"
        else -> "PT${mins}M"
    }
}

private fun rrule(event: ScheduleExportEvent): String {
    val days = event.weekdays.joinToString(",") { weekdayCodes[it] }
    val until = untilStamp(event.lastYear ?: event.year, event.lastMonth ?: event.month, event.lastDay ?: event.day)
    return "FREQ=WEEKLY;BYDAY=$days;UNTIL=$until"
}

private fun zonedMillis(year: Int, month: Int, day: Int, minute: Int): Long =
    ZonedDateTime.of(year, month, day, minute / 60, minute % 60, 0, 0, TermCalendar.newYork).toInstant().toEpochMilli()

private fun primaryCalendarId(context: Context): Long? {
    val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
    val cursor = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        "${CalendarContract.Calendars.VISIBLE}=1",
        null,
        null,
    ) ?: return null
    cursor.use {
        val idColumn = it.getColumnIndex(CalendarContract.Calendars._ID)
        val primaryColumn = it.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
        if (idColumn < 0) return null
        var fallback: Long? = null
        while (it.moveToNext()) {
            val id = it.getLong(idColumn)
            if (fallback == null) fallback = id
            if (primaryColumn >= 0 && !it.isNull(primaryColumn) && it.getInt(primaryColumn) == 1) return id
        }
        return fallback
    }
}

private fun escapeIcs(text: String) = text
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace("\r\n", "\\n")
    .replace("\n", "\\n")

private fun dateStamp(year: Int, month: Int, day: Int) = "%04d%02d%02d".format(Locale.US, year, month, day)

private fun localStamp(year: Int, month: Int, day: Int, minute: Int) =
    "${dateStamp(year, month, day)}T%02d%02d00".format(Locale.US, minute / 60, minute % 60)

private fun utcStamp(millis: Long): String {
    val zoned = java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC"))
    return "%04d%02d%02dT%02d%02d%02dZ".format(
        Locale.US, zoned.year, zoned.monthValue, zoned.dayOfMonth, zoned.hour, zoned.minute, zoned.second,
    )
}

private fun untilStamp(year: Int, month: Int, day: Int): String =
    utcStamp(zonedMillis(year, month, day, 23 * 60 + 59) + 59_000L)
