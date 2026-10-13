// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Petition course-number questions, ported from DegreePetitionQuestions.swift.

package app.termorganize.logic.degree

import app.termorganize.core.model.CourseEquivalences
import app.termorganize.core.model.CourseKey
import java.util.Locale

internal fun formatHours(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (kotlin.math.abs(rounded - rounded.toInt()) < 1e-9) rounded.toInt().toString()
    else String.format(Locale.US, "%.1f", rounded)
}

internal fun formatGpa(value: Double): String {
    val text = String.format(Locale.US, "%.3f", value).trimEnd('0')
    return if (text.endsWith('.')) text + "0" else text
}

internal fun englishList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> "${items[0]} and ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
}

fun CourseRange.applying(limit: CourseNumberLimit?): CourseRange {
    if (limit == null || limit is CourseNumberLimit.SameAsRequirement) return this
    val nextLowest = when (limit) {
        CourseNumberLimit.AnyNumber -> 0
        is CourseNumberLimit.AtLeast -> limit.minimum
        else -> lowest
    }
    return copy(lowest = nextLowest).let { it.copy(label = it.describedLabel) }
}

val CourseRange.describedLabel: String
    get() {
        var text = when {
            lowest > 0 && highest != null -> "$subject $lowest to $subject $highest"
            lowest > 0 -> "$subject $lowest or higher"
            highest != null -> "$subject courses up to $subject $highest"
            else -> "Any $subject course"
        }
        if (except.isNotEmpty()) text += ", except " + except.joinToString(", ") { it.description }
        return text
    }

fun CourseRange.reasonExcluding(key: CourseKey): String? {
    if (key.subject != subject || contains(key)) return null
    if (key in except) return "${key.description} is excluded"
    val number = key.number.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
    if (number < lowest) return "Below $subject $lowest"
    if (highest != null && number > highest) return "Above $subject $highest"
    return null
}

val List<CourseRange>.subjectsText: String
    get() {
        val seen = LinkedHashSet<String>()
        ranges().forEach { seen += it }
        return englishList(seen.toList()).replace(" and ", " or ")
    }

private fun List<CourseRange>.ranges() = map { it.subject }

val List<CourseRange>.minimumsText: String?
    get() {
        val minimums = filter { it.lowest > 0 }.map { "${it.subject} ${it.lowest}" }
        if (minimums.isEmpty()) return null
        return englishList(minimums).replace(" and ", " or ")
    }

fun RequirementReplacement.likeRanges(requirements: ProgramRequirements): List<CourseRange> {
    val like = requirements.degreeRule(likeRuleID, likeRuleTitle) ?: return emptyList()
    val kind = like.kind as? RequirementRule.Kind.Choose ?: return emptyList()
    return kind.ranges
}

fun RequirementReplacement.asksForCourseNumbers(requirements: ProgramRequirements) =
    likeRanges(requirements).any { it.lowest > 0 }

enum class CourseNumberChoice {
    SAME_AS_REQUIREMENT, ANY_NUMBER, AT_LEAST;

    fun limit(minimum: Int): CourseNumberLimit? = when (this) {
        SAME_AS_REQUIREMENT -> CourseNumberLimit.SameAsRequirement
        ANY_NUMBER -> CourseNumberLimit.AnyNumber
        AT_LEAST -> if (isValidMinimum(minimum)) CourseNumberLimit.AtLeast(minimum) else null
    }

    fun title(likeTitle: String) = when (this) {
        SAME_AS_REQUIREMENT -> "Same as $likeTitle"
        ANY_NUMBER -> "Any course number"
        AT_LEAST -> "A different minimum"
    }

    fun detail(ranges: List<CourseRange>, minimum: Int) = when (this) {
        SAME_AS_REQUIREMENT -> ranges.joinToString(" · ") { it.label }
        ANY_NUMBER -> "Any ${ranges.subjectsText} course"
        AT_LEAST -> if (isValidMinimum(minimum)) ranges.joinToString(" · ") { it.applying(CourseNumberLimit.AtLeast(minimum)).label }
        else "Enter a course number, like 2000"
    }

    companion object {
        fun from(limit: CourseNumberLimit?): CourseNumberChoice? = when (limit) {
            CourseNumberLimit.SameAsRequirement -> SAME_AS_REQUIREMENT
            CourseNumberLimit.AnyNumber -> ANY_NUMBER
            is CourseNumberLimit.AtLeast -> AT_LEAST
            null -> null
        }

        fun isValidMinimum(minimum: Int) = minimum in 1..9999

        fun suggestedMinimum(ranges: List<CourseRange>, saved: CourseNumberLimit?): Int {
            if (saved is CourseNumberLimit.AtLeast) return saved.minimum
            val lowest = ranges.map { it.lowest }.filter { it > 0 }.minOrNull() ?: 2000
            return maxOf(1000, lowest / 1000 * 1000)
        }
    }
}

data class DegreePetitionQuestion(
    val substitution: RequirementSubstitution,
    val ruleID: String,
    val ruleTitle: String,
    val likeTitle: String,
    val ranges: List<CourseRange>,
    val courses: List<CourseKey>,
    val satisfied: Int,
    val satisfiedWithoutMinimum: Int,
    val total: Int,
) {
    val id: String get() = substitution.id
    val question: String get() = "Does your $ruleTitle petition accept any ${ranges.subjectsText} course?"
    val matters: Boolean get() = satisfiedWithoutMinimum > satisfied
    val explanation: String
        get() {
            val parts = mutableListOf<String>()
            ranges.minimumsText?.let { parts += "$likeTitle start at $it." }
            if (!matters) {
                parts += "Your petition decides whether that minimum applies here."
                return parts.joinToString(" ")
            }
            val gained = satisfiedWithoutMinimum - satisfied
            val effect = if (satisfiedWithoutMinimum >= total) "every requirement is met"
            else "$gained more ${if (gained == 1) "requirement is" else "requirements are"} met"
            val courseList = englishList(courses.map { it.description })
            parts += if (courses.isEmpty()) "With any number, $effect."
            else "With any number, $courseList ${if (courses.size == 1) "counts" else "count"} and $effect."
            return parts.joinToString(" ")
        }
}

object DegreePetitionQuestions {
    fun questions(
        plan: DegreePlan,
        requirements: ProgramRequirements,
        equivalences: CourseEquivalences = CourseEquivalences.none,
        audit: DegreeAudit.Result? = null,
    ): List<DegreePetitionQuestion> {
        val candidates = plan.substitutions.filter { isUnanswered(it, requirements) }
        if (candidates.isEmpty()) return emptyList()
        val current = audit ?: DegreeAudit.run(plan, requirements, equivalences)
        return candidates.mapNotNull { question(it, plan, requirements, equivalences, current) }.filter { it.matters }
    }

    fun question(
        substitution: RequirementSubstitution,
        plan: DegreePlan,
        requirements: ProgramRequirements,
        equivalences: CourseEquivalences = CourseEquivalences.none,
        audit: DegreeAudit.Result,
    ): DegreePetitionQuestion? {
        val ruleID = audit.placements[substitution.id] ?: substitution.ruleID
        val replacement = (substitution.change as? SubstitutionChange.Replace)?.replacement ?: return null
        if (!isUnanswered(substitution, requirements) || audit.result(ruleID) == null) return null
        val relaxed = plan.setCourseNumbers(CourseNumberLimit.AnyNumber, substitution.id)
        val without = DegreeAudit.run(relaxed, requirements, equivalences, audit.attributes)
        val ranges = replacement.likeRanges(requirements)
        val newly = without.result(ruleID)?.used.orEmpty().map { it.key }.filter { key ->
            ranges.none { it.contains(key) } && key !in replacement.courses
        }
        return DegreePetitionQuestion(
            substitution,
            ruleID,
            requirements.titleOf(ruleID) ?: substitution.ruleTitle,
            replacement.likeRuleTitle ?: replacement.likeRuleID?.let { requirements.titleOf(it) } ?: "That requirement",
            ranges,
            newly,
            audit.satisfiedCount,
            without.satisfiedCount,
            audit.totalCount,
        )
    }

    fun isUnanswered(substitution: RequirementSubstitution, requirements: ProgramRequirements): Boolean {
        val replacement = (substitution.change as? SubstitutionChange.Replace)?.replacement ?: return false
        return replacement.courseNumbers == null && replacement.asksForCourseNumbers(requirements)
    }
}
