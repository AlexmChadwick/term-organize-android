// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.degree

import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseEquivalences
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.Requisite
import app.termorganize.core.model.Term
import app.termorganize.logic.search.CatalogIndex
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class DegreePlanImportException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { UNREADABLE, NOT_A_DEGREE_PLAN }
}

data class DegreePlanParseResult(val plan: DegreePlan, val skippedLines: List<String>)

object DegreePlanFormat {
    val separator: String = "-".repeat(115)
    const val TITLE_LENGTH = 30

    fun parse(text: String, fileName: String? = null): DegreePlanParseResult {
        var plan = DegreePlan(name = "", importedFrom = fileName)
        val skipped = mutableListOf<String>()
        var sawStructure = false
        var section = Section.HEADER
        for (raw in text.split('\n')) {
            val line = normalize(raw)
            if (line.isEmpty() || line == "-" || line == "•") continue
            val printed = headerValue(line, "Printed:")
            if (printed != null) {
                plan = plan.copy(printedAt = parsePrintedDate(printed))
                continue
            }
            val studentId = headerValue(line, "StudentID:") ?: headerValue(line, "Student ID:")
            if (studentId != null) {
                plan = plan.copy(studentID = studentId.ifBlank { null })
                continue
            }
            val student = headerValue(line, "Student:")
            if (student != null) {
                plan = plan.copy(studentName = student.ifBlank { null })
                continue
            }
            val planName = headerValue(line, "Degree Audit Plan Name:") ?: headerValue(line, "Plan Name:")
            if (planName != null) {
                plan = plan.copy(name = planName)
                sawStructure = true
                continue
            }
            val program = headerValue(line, "Program:")
            if (program != null) {
                plan = plan.copy(program = program.ifBlank { null })
                continue
            }
            if (line.lowercase(Locale.US).startsWith("completed courses")) {
                section = Section.COMPLETED
                sawStructure = true
                continue
            }
            if (isSeparator(line)) {
                section = Section.BETWEEN
                continue
            }
            val term = parseTermHeader(line)
            if (term != null) {
                plan = plan.copy(terms = plan.terms + term)
                section = Section.TERM
                sawStructure = true
                continue
            }
            val course = parseCourseLine(line)
            if (course != null) {
                plan = when (section) {
                    Section.TERM, Section.BETWEEN -> {
                        if (section == Section.BETWEEN && plan.terms.isEmpty()) plan.copy(completed = plan.completed + course)
                        else {
                            val terms = plan.terms.toMutableList()
                            val last = terms.removeAt(terms.lastIndex)
                            terms += last.copy(courses = last.courses + course)
                            plan.copy(terms = terms)
                        }
                    }
                    Section.HEADER, Section.COMPLETED -> plan.copy(completed = plan.completed + course)
                }
                continue
            }
            skipped += line
        }
        if (!sawStructure || (plan.completed.isEmpty() && plan.terms.isEmpty() && plan.name.isEmpty())) {
            throw DegreePlanImportException(
                DegreePlanImportException.Kind.NOT_A_DEGREE_PLAN,
                "This doesn't look like a degree audit plan. In the degree audit planner, open your plan and choose View Plan, then import the PDF it creates.",
            )
        }
        if (plan.name.isEmpty()) plan = plan.copy(name = "Imported Plan")
        return DegreePlanParseResult(plan.sorted(), skipped)
    }

    private enum class Section { HEADER, COMPLETED, TERM, BETWEEN }

    fun parseTermHeader(line: String): DegreePlanTerm? {
        val match = Regex("""^(.+?)\s+(\d{4})\s*\(\s*\d+(?:\.\d+)?\s*Hours?\s*\)$""", RegexOption.IGNORE_CASE).matchEntire(line) ?: return null
        val season = PlanSeason.fromExportName(match.groupValues[1]) ?: return null
        val year = match.groupValues[2].toIntOrNull() ?: return null
        return DegreePlanTerm(season = season, year = year)
    }

    fun parseCourseLine(line: String): DegreePlanCourse? {
        val match = Regex("""^(.+?)\s*\(\s*(\d+(?:\.\d+)?)\s*Hours?\s*\)\s*(.*)$""", RegexOption.IGNORE_CASE).matchEntire(line) ?: return null
        val credits = match.groupValues[2].toDoubleOrNull() ?: return null
        val code = match.groupValues[1].trim()
        val title = match.groupValues[3].trim()
        parseCourseCode(code)?.let { return DegreePlanCourse.of(it, title, credits) }
        if (code.length > 40 || code.contains(":")) return null
        return DegreePlanCourse.of(null, title, credits, label = code)
    }

    fun parseCourseCode(code: String): CourseKey? {
        val match = Regex("""([A-Z]{2,5})\s*(\d{4}[A-Z]?)""").matchEntire(code.uppercase(Locale.US)) ?: return null
        return CourseKey(match.groupValues[1], match.groupValues[2])
    }

    fun normalize(line: String): String {
        val scalars = StringBuilder()
        for (ch in line) {
            val type = Character.getType(ch).toByte()
            when (type) {
                Character.CONTROL, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> scalars.append(' ')
                Character.FORMAT -> Unit
                else -> scalars.append(ch)
            }
        }
        var text = scalars.toString().replace(Regex(" {2,}"), " ").trim()
        val bullets = setOf('-', '•', '–', '—')
        if (text.isNotEmpty() && text[0] in bullets && !isSeparator(text)) {
            text = text.dropWhile { it in bullets }.trim()
        }
        return text
    }

    private fun headerValue(line: String, label: String): String? {
        if (!line.lowercase(Locale.US).startsWith(label.lowercase(Locale.US))) return null
        return line.drop(label.length).trim()
    }

    private fun isSeparator(line: String) = line.length >= 10 && line.all { it == '-' || it == '_' || it == '=' }

    fun parsePrintedDate(value: String): Long? {
        val zone = ZoneId.of("America/New_York")
        val patterns = listOf("MM/dd/yyyy HH:mm", "MM/dd/yyyy h:mm a", "MM/dd/yyyy")
        for (pattern in patterns) {
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.US)
            try {
                if (pattern == "MM/dd/yyyy") {
                    return LocalDate.parse(value, formatter).atStartOfDay(zone).toInstant().toEpochMilli()
                }
                return LocalDateTime.parse(value, formatter).atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun hours(credits: Double): String {
        val number = String.format(Locale.US, "%.1f", credits)
        return "($number ${if (credits == 1.0) "Hour" else "Hours"})"
    }

    fun exportCode(course: DegreePlanCourse): String {
        val key = course.key ?: return course.label
        val subject = if (key.subject.length < 4) key.subject.padEnd(4, ' ') else key.subject
        return subject + key.number
    }

    fun exportTitle(title: String) = title.take(TITLE_LENGTH)

    fun courseLine(course: DegreePlanCourse) = "${exportCode(course)} ${hours(course.credits)} ${exportTitle(course.title)}"

    fun termHeader(term: DegreePlanTerm): String {
        val gap = if (' ' in term.season.exportName) " " else "  "
        return "${term.season.exportName}$gap${term.year} ${hours(term.credits)} "
    }

    fun printedLine(epochMillis: Long): String {
        val formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm", Locale.US).withZone(ZoneId.of("America/New_York"))
        return "Printed:  ${formatter.format(Instant.ofEpochMilli(epochMillis))}"
    }

    fun headerLines(plan: DegreePlan, printedAt: Long, includeStudent: Boolean): List<String> {
        val lines = mutableListOf(printedLine(printedAt))
        if (includeStudent) {
            lines += "Student:  ${plan.studentName ?: ""}"
            lines += "StudentID:  ${plan.studentID ?: ""}"
        }
        lines += "Degree Audit Plan Name:\t${plan.name}"
        lines += "Program:\t\t${plan.program ?: ""}"
        return lines
    }

    fun bodyLines(plan: DegreePlan): List<Pair<Int, String>> {
        val lines = mutableListOf(1 to "Completed Courses:  ")
        plan.completed.forEach { lines += 2 to courseLine(it) }
        for (term in plan.terms) {
            lines += 1 to separator
            lines += 1 to termHeader(term)
            term.courses.forEach { lines += 2 to courseLine(it) }
        }
        return lines
    }

    fun text(plan: DegreePlan, printedAt: Long = System.currentTimeMillis(), includeStudent: Boolean): String {
        val header = headerLines(plan, printedAt, includeStudent)
        val body = bodyLines(plan).map { "  ".repeat(it.first) + it.second }
        return (header + body).joinToString("\n")
    }
}

object DegreeCourseRepeats {
    fun countsSeparately(title: String, credits: Double, otherTitle: String, otherCredits: Double): Boolean {
        if (credits <= 0 || otherCredits <= 0) return false
        val first = normalizedTitle(title)
        val second = normalizedTitle(otherTitle)
        if (first.isNotEmpty() && second.isNotEmpty() && first != second) return true
        return "topic" in first || "topic" in second
    }

    fun countsSeparately(a: DegreePlanCourse, b: DegreePlanCourse) =
        countsSeparately(a.title, a.credits, b.title, b.credits)

    private fun normalizedTitle(title: String): String =
        title.lowercase(Locale.US).replace("&", " and ").filter { it.isLetterOrDigit() }
}

object DegreePlanAnalysis {
    sealed class PrerequisiteStatus {
        data object Met : PrerequisiteStatus()
        data class Missing(val text: String) : PrerequisiteStatus()
        data object Unknown : PrerequisiteStatus()
        val isMissing get() = this is Missing
    }

    fun coursesBefore(termIndex: Int, plan: DegreePlan): Set<CourseKey> {
        val keys = plan.completedKeys.toMutableSet()
        for (term in plan.terms.take(maxOf(0, termIndex))) keys += term.courses.mapNotNull { it.key }
        return keys
    }

    fun status(requisite: Requisite?, satisfied: Set<CourseKey>, equivalences: CourseEquivalences = CourseEquivalences.none): PrerequisiteStatus {
        if (requisite == null) return PrerequisiteStatus.Met
        val missing = unmetSummary(requisite, satisfied, equivalences) ?: return PrerequisiteStatus.Met
        return PrerequisiteStatus.Missing(missing)
    }

    fun unmetSummary(requisite: Requisite, satisfied: Set<CourseKey>, equivalences: CourseEquivalences = CourseEquivalences.none): String? =
        unmet(requisite, equivalences.expanding(satisfied))

    private fun unmet(requisite: Requisite, satisfied: Set<CourseKey>): String? = when (requisite) {
        is Requisite.CourseReq -> if (requisite.key in satisfied) null else requisite.key.description
        is Requisite.TestReq -> null
        is Requisite.And -> {
            val parts = unique(requisite.items.mapNotNull { unmet(it, satisfied) })
            when (parts.size) {
                0 -> null
                1 -> parts.first()
                2, 3 -> parts.joinToString(" and ") { parenthesizedIfCompound(it) }
                else -> parts.take(2).joinToString(", ") { parenthesizedIfCompound(it) } + " and ${parts.size - 2} more"
            }
        }
        is Requisite.Or -> {
            if (requisite.items.any { unmet(it, satisfied) == null }) null
            else {
                val courses = requisite.items.mapNotNull { (it as? Requisite.CourseReq)?.key }
                if (courses.size == requisite.items.size) {
                    val names = unique(courses.map { it.description })
                    if (names.size <= 3) names.joinToString(" or ")
                    else "${names[0]}, ${names[1]} or ${names.size - 2} others"
                } else {
                    val best = requisite.items.minByOrNull { count(it, satisfied) }
                    val text = best?.let { unmet(it, satisfied) } ?: requisite.summary
                    "${parenthesizedIfCompound(text)} or another option"
                }
            }
        }
    }

    fun missingCount(requisite: Requisite, satisfied: Set<CourseKey>, equivalences: CourseEquivalences = CourseEquivalences.none): Int =
        count(requisite, equivalences.expanding(satisfied))

    private fun count(requisite: Requisite, satisfied: Set<CourseKey>): Int = when (requisite) {
        is Requisite.CourseReq -> if (requisite.key in satisfied) 0 else 1
        is Requisite.TestReq -> 0
        is Requisite.And -> requisite.items.sumOf { count(it, satisfied) }
        is Requisite.Or -> requisite.items.minOfOrNull { count(it, satisfied) } ?: 0
    }

    private fun parenthesizedIfCompound(text: String) =
        if (" or " in text || " and " in text || text.endsWith(" more")) "($text)" else text

    private fun unique(texts: List<String>): List<String> {
        val seen = HashSet<String>()
        return texts.filter { seen.add(it) }
    }

    fun duplicateKeys(plan: DegreePlan): Set<CourseKey> {
        val planned = plan.terms.flatMap { it.courses }
        val duplicates = mutableSetOf<CourseKey>()
        planned.forEachIndexed { index, course ->
            val key = course.key ?: return@forEachIndexed
            val repeatsPlanned = planned.take(index).any { it.key == key && !DegreeCourseRepeats.countsSeparately(it, course) }
            val repeatsCompleted = plan.completed.any { it.key == key && !DegreeCourseRepeats.countsSeparately(it, course) }
            if (repeatsPlanned || repeatsCompleted) duplicates += key
        }
        return duplicates
    }
}

data class ScheduleAutofillSuggestion(
    val source: Source,
    val planName: String,
    val semester: String?,
    val title: String,
    val detail: String,
    val isExactTerm: Boolean,
    val courses: List<CourseKey>,
    val unavailable: List<CourseKey>,
) {
    sealed class Source {
        data class Degree(val planId: String, val termId: String) : Source()
        data class Schedule(val planId: String) : Source()
    }
    val id: String
        get() = when (source) {
            is Source.Degree -> "degree-${source.planId}-${source.termId}"
            is Source.Schedule -> "schedule-${source.planId}"
        }
}

object DegreePlanAutofill {
    fun monthIndex(term: Term): Int {
        val season = PlanSeason.fromTerm(term.season)
        return term.calendarYear * 12 + season.startMonth - 1
    }

    fun scheduleSuggestions(
        term: Term,
        degreePlans: List<DegreePlan>,
        schedulePlans: List<SchedulePlan>,
        excludingPlanID: String? = null,
        alreadyInPlan: Set<CourseKey> = emptySet(),
        index: CatalogIndex? = null,
    ): List<ScheduleAutofillSuggestion> {
        val target = monthIndex(term)
        val exact = mutableListOf<ScheduleAutofillSuggestion>()
        val closest = mutableListOf<ScheduleAutofillSuggestion>()
        fun split(keys: List<CourseKey>): Pair<List<CourseKey>, List<CourseKey>> {
            if (index == null || index.term.code != term.code) return keys to emptyList()
            val offered = mutableListOf<CourseKey>()
            val unavailable = mutableListOf<CourseKey>()
            for (key in keys) if (index.sections(key).isEmpty()) unavailable += key else offered += key
            return offered to unavailable
        }
        val ordered = degreePlans.sortedWith(compareBy<DegreePlan> { if (it.isPrimary) 0 else 1 }.thenByDescending { it.updatedAt })
        for (plan in ordered) {
            val done = plan.completedKeys
            val candidates = plan.terms.mapNotNull { planTerm ->
                val seen = HashSet<CourseKey>()
                val keys = planTerm.courses.mapNotNull { it.key }.filter { it !in done && it !in alreadyInPlan && seen.add(it) }
                if (keys.isEmpty()) null else Triple(planTerm, keys, abs(planTerm.monthIndex - target))
            }
            val best = candidates.minWithOrNull(compareBy<Triple<DegreePlanTerm, List<CourseKey>, Int>> { it.third }.thenByDescending { it.first.sortKey }) ?: continue
            val (offered, unavailable) = split(best.second)
            if (offered.isEmpty() && unavailable.isEmpty()) continue
            val isExact = best.first.matches(term)
            val suggestion = ScheduleAutofillSuggestion(
                ScheduleAutofillSuggestion.Source.Degree(plan.id, best.first.id),
                plan.name,
                best.first.shortName,
                "From “${plan.name}” · ${best.first.shortName}",
                if (isExact) "Your plan for this term" else if (best.third == 0) "Planned for this time" else "Closest semester you planned",
                isExact,
                offered,
                unavailable,
            )
            if (isExact) exact += suggestion else closest += suggestion
        }
        val copies = schedulePlans
            .filter { it.termCode == term.code && it.id != excludingPlanID && it.courses.isNotEmpty() }
            .sortedByDescending { it.updatedAt }
            .take(2)
            .mapNotNull { plan ->
                val keys = plan.courses.map { it.key }.filter { it !in alreadyInPlan }
                if (keys.isEmpty()) null
                else ScheduleAutofillSuggestion(
                    ScheduleAutofillSuggestion.Source.Schedule(plan.id),
                    plan.name, null, "Copy “${plan.name}”",
                    if (plan.isPrimary) "Your schedule for this term" else "Another schedule for this term",
                    true, keys, emptyList(),
                )
            }
        return exact + copies + closest
    }

    fun newDegreePlanDraft(
        name: String,
        source: DegreePlan?,
        schedulePlans: List<SchedulePlan>,
        catalogIndex: (String) -> CatalogIndex?,
        now: Instant = Instant.now(),
    ): DegreePlan {
        var draft = DegreePlan(name = name)
        if (source != null) {
            draft = draft.copy(
                program = source.program,
                studentName = source.studentName,
                studentID = source.studentID,
                catalogYear = source.catalogYear,
                catalogProgramPath = source.catalogProgramPath,
                catalogProgramTitle = source.catalogProgramTitle,
                concentrationID = source.concentrationID,
                substitutions = source.substitutions.map { it.copy(id = UUID.randomUUID().toString()) },
                courseEquivalences = source.courseEquivalences.map { it.copy(id = UUID.randomUUID().toString()) },
                completed = source.completed.map { it.copy(id = UUID.randomUUID().toString()) },
                terms = source.terms.map { term ->
                    term.copy(id = UUID.randomUUID().toString(), courses = term.courses.map { it.copy(id = UUID.randomUUID().toString()) })
                },
            )
        }
        val done = draft.completedKeys
        for (plan in schedulePlans) {
            if (!plan.isPrimary || plan.courses.isEmpty()) continue
            val window = TermCalendar.window(plan.termCode)
            if (window != null && window.endInclusive.isBefore(now)) continue
            val term = Term(plan.termCode, "")
            val season = PlanSeason.fromTerm(term.season)
            val index = catalogIndex(plan.termCode)
            val terms = draft.terms.toMutableList()
            val existing = terms.indexOfFirst { it.season == season && it.year == term.calendarYear }
            val position = if (existing >= 0) existing else {
                terms += DegreePlanTerm(season = season, year = term.calendarYear)
                terms.lastIndex
            }
            val present = terms[position].courses.mapNotNull { it.key }.toSet()
            val added = terms[position].courses.toMutableList()
            for (planCourse in plan.courses) {
                if (planCourse.key in present || planCourse.key in done) continue
                val course: Course? = index?.course(planCourse.key)
                added += DegreePlanCourse.of(
                    planCourse.key,
                    DegreePlanFormat.exportTitle(course?.title ?: planCourse.key.description),
                    course?.maxCredits ?: 4.0,
                )
            }
            terms[position] = terms[position].copy(courses = added)
            draft = draft.copy(terms = terms)
        }
        return draft.sorted()
    }

    fun nextSemesters(plan: DegreePlan, count: Int = 4, now: Instant = Instant.now()): List<DegreePlanTerm> {
        val order = listOf(PlanSeason.SPRING, PlanSeason.SUMMER_A, PlanSeason.SUMMER_B, PlanSeason.FALL)
        val date = now.atZone(TermCalendar.newYork)
        var year = date.year
        var seasonIndex = when (date.monthValue) {
            in 1..4 -> 0
            in 5..6 -> 1
            in 7..8 -> 2
            else -> 3
        }
        plan.terms.lastOrNull()?.let { last ->
            val i = order.indexOf(last.season)
            if (i >= 0) {
                year = last.year
                seasonIndex = i + 1
            }
        }
        val existing = plan.terms.map { "${it.season}-${it.year}" }.toSet()
        val result = mutableListOf<DegreePlanTerm>()
        while (result.size < count) {
            if (seasonIndex >= order.size) {
                seasonIndex = 0
                year += 1
            }
            val candidate = DegreePlanTerm(season = order[seasonIndex], year = year)
            if ("${candidate.season}-${candidate.year}" !in existing) result += candidate
            seasonIndex += 1
        }
        return result
    }
}
