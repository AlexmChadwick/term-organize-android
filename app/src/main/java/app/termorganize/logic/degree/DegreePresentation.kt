// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.degree

import app.termorganize.core.model.CourseKey
import app.termorganize.logic.degree.DegreeAudit.CourseUse
import app.termorganize.logic.degree.DegreeAudit.NoteCheck
import app.termorganize.logic.degree.DegreeAudit.RuleResult
import app.termorganize.logic.degree.DegreeAudit.Status
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which requirements a page shows. */
enum class DegreeRequirementsFilter {
    ALL, REMAINING, DONE,
    ;

    fun includes(status: Status): Boolean = when (this) {
        ALL -> true
        REMAINING -> status.ordinal <= Status.PLANNED.ordinal
        DONE -> status.ordinal >= Status.COMPLETE.ordinal
    }

    /** A check that does not count yet shows in All only. */
    fun includes(check: NoteCheck): Boolean = if (check.counts) includes(check.status) else this == ALL
}

data class DegreeNoteEntry(val sectionID: String, val text: String, val check: NoteCheck?) {
    val id: String get() = "$sectionID|$text"
}

object DegreeRequirementNotes {
    fun entries(
        section: RequirementSection,
        audit: DegreeAudit.Result,
        filter: DegreeRequirementsFilter,
        statesTotalHours: Boolean,
    ): List<DegreeNoteEntry> {
        val seen = HashSet<String>()
        fun entry(note: String, showsUnchecked: Boolean): DegreeNoteEntry? {
            val built = DegreeNoteEntry(section.id, note, audit.noteCheck(section.id, note))
            if (!seen.add(built.id)) return null
            val check = built.check
            if (check != null) return if (filter.includes(check)) built else null
            val restated = statesTotalHours && note.contains("total semester hours", ignoreCase = true)
            return if (filter == DegreeRequirementsFilter.ALL && showsUnchecked && !restated) built else null
        }
        val own = section.notes.mapNotNull { entry(it, true) }
        val grouped = section.groups.filter { it.rules.isEmpty() }.flatMap { it.notes }.mapNotNull { entry(it, false) }
        return own + grouped
    }
}

/** `Petition approved · Approved Sep 12, 2026`. */
fun substitutionCaption(substitution: RequirementSubstitution): String? {
    val parts = mutableListOf<String>()
    if (substitution.note.isNotBlank()) parts += substitution.note.trim()
    substitution.approvedOn?.let { parts += "Approved ${approvedDate(it)}" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private val approvedFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

private fun approvedDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("America/New_York")).format(approvedFormat)

data class DegreeListEntry(
    val id: Int,
    val alternatives: List<List<CourseKey>>,
    val category: String? = null,
) {
    val courses: List<CourseKey> get() = alternatives.flatten()
}

object DegreeRequirementsListing {
    fun isShown(use: CourseUse, listed: Set<CourseKey>): Boolean =
        use.key in listed || (use.satisfies?.let { it in listed } == true)

    fun hasHeaderRow(result: RuleResult): Boolean = when (result.effectiveKind) {
        null, is RequirementRule.Kind.Choose, is RequirementRule.Kind.GeneralElectives -> true
        is RequirementRule.Kind.Required -> isReplaced(result)
    }

    fun isReplaced(result: RuleResult): Boolean =
        result.substitutions.any { it.change is SubstitutionChange.Replace }

    fun collapsed(entries: List<DegreeListEntry>, extra: Int, isRelevant: (DegreeListEntry) -> Boolean): List<DegreeListEntry> {
        var others = 0
        return entries.filter { entry ->
            if (isRelevant(entry)) true
            else if (others < extra) {
                others += 1
                true
            } else false
        }
    }

    fun entries(options: List<CourseOption>, rule: RequirementRule, subjectName: (String) -> String?): List<DegreeListEntry> {
        val starts = when (val kind = rule.kind) {
            is RequirementRule.Kind.Choose -> {
                val categories = rule.optionCategories
                if (kind.options == options && categories != null && categories.size == options.size) {
                    categories.mapIndexedNotNull { index, category ->
                        if (category != null && (index == 0 || categories[index - 1] != category)) index to category else null
                    }.toMap()
                } else emptyMap()
            }
            else -> emptyMap()
        }
        return options.mapIndexed { index, option -> DegreeListEntry(index, option.alternatives, starts[index]) }
    }

    fun entries(option: CourseOption): List<DegreeListEntry> =
        option.alternatives.mapIndexed { index, alternative -> DegreeListEntry(index, listOf(alternative)) }

    fun listing(rule: RequirementRule): Pair<List<CourseOption>, List<CourseRange>> = when (val kind = rule.kind) {
        is RequirementRule.Kind.Required -> listOf(kind.option) to emptyList()
        is RequirementRule.Kind.Choose -> kind.options to kind.ranges
        is RequirementRule.Kind.GeneralElectives -> emptyList<CourseOption>() to emptyList()
    }
}

data class DegreeCourseRowInfo(
    val bySubstitution: Boolean = false,
    val caption: String? = null,
)

/** Counted courses the requirement does not already list, laid out under the requirement they replace. */
object DegreeSubstituteRows {
    data class Split(val substitutes: List<CourseUse>, val others: List<CourseUse>)

    fun split(result: RuleResult, listed: Set<CourseKey>): Split {
        val unlisted = result.used.filter { !DegreeRequirementsListing.isShown(it, listed) }
        return Split(unlisted.filter { it.bySubstitution }, unlisted.filter { !it.bySubstitution })
    }

    fun substitution(key: CourseKey, result: RuleResult): RequirementSubstitution? =
        result.substitutions.firstOrNull { (it.change as? SubstitutionChange.CountCourse)?.key == key }

    fun requirementName(result: RuleResult, requirements: ProgramRequirements): String =
        requirements.degreeShortName(result.rule.id) ?: result.rule.displayLabel

    fun note(key: CourseKey, result: RuleResult): String? =
        substitution(key, result)?.let { substitutionCaption(it) }

    fun caption(key: CourseKey, result: RuleResult, requirements: ProgramRequirements): String {
        val parts = mutableListOf("Counts for ${requirementName(result, requirements)} by substitution")
        note(key, result)?.let { parts += it }
        return parts.joinToString(" · ")
    }

    fun caption(info: DegreeCourseRowInfo, key: CourseKey, result: RuleResult, requirements: ProgramRequirements, isChild: Boolean): String? {
        if (!info.bySubstitution) return info.caption
        return if (isChild) note(key, result) else caption(key, result, requirements)
    }
}

data class DegreePlanCourseChoice(val key: CourseKey, val title: String)

/** What the substitution form decides, apart from how it looks. */
object DegreeSubstitutionEditing {
    enum class Kind { COUNT_COURSE, REPLACE, WAIVE }

    fun canSave(
        kind: Kind,
        course: CourseKey?,
        likeRuleID: String?,
        specificCourses: Set<CourseKey>,
        needsCourseNumbers: Boolean = false,
        courseNumbers: CourseNumberLimit? = null,
    ): Boolean = when (kind) {
        Kind.COUNT_COURSE -> course != null
        Kind.REPLACE -> (likeRuleID != null || specificCourses.isNotEmpty()) && (!needsCourseNumbers || likeRuleID == null || courseNumbers != null)
        Kind.WAIVE -> true
    }

    fun needsCourseNumbers(likeRuleID: String?, requirements: ProgramRequirements?): Boolean {
        if (likeRuleID == null || requirements == null) return false
        return RequirementReplacement(likeRuleID = likeRuleID).asksForCourseNumbers(requirements)
    }

    fun likeRanges(likeRuleID: String?, requirements: ProgramRequirements?): List<CourseRange> {
        if (likeRuleID == null || requirements == null) return emptyList()
        return RequirementReplacement(likeRuleID = likeRuleID).likeRanges(requirements)
    }

    fun sourceName(rule: RequirementRule, requirements: ProgramRequirements?): String =
        if (rule.degreeIsGeneralElectives) "General electives (any course)"
        else requirements?.degreeShortName(rule.id) ?: rule.displayLabel

    fun change(
        kind: Kind,
        course: CourseKey?,
        amountInHours: Boolean,
        count: Int,
        hours: Double,
        likeRuleID: String?,
        specificCourses: Set<CourseKey>,
        courseNumbers: CourseNumberLimit? = null,
        requirements: ProgramRequirements?,
        ruleTitle: String,
    ): SubstitutionChange? = when (kind) {
        Kind.COUNT_COURSE -> course?.let { SubstitutionChange.CountCourse(it) }
        Kind.REPLACE -> SubstitutionChange.Replace(
            RequirementReplacement(
                count = if (amountInHours) null else count,
                hours = if (amountInHours) hours else null,
                likeRuleID = likeRuleID,
                likeRuleTitle = likeRuleID?.let { id ->
                    requirements?.rule(id)?.let { sourceName(it, requirements) } ?: ruleTitle
                },
                courses = specificCourses.sorted(),
                courseNumbers = if (needsCourseNumbers(likeRuleID, requirements)) courseNumbers else null,
            ),
        )
        Kind.WAIVE -> SubstitutionChange.Waive
    }

    fun renumbering(substitution: RequirementSubstitution, requirements: ProgramRequirements?): CourseEquivalence? {
        val course = (substitution.change as? SubstitutionChange.CountCourse)?.key ?: return null
        val rule = requirements?.degreeRule(substitution.ruleID, substitution.ruleTitle) ?: return null
        val required = rule.kind as? RequirementRule.Kind.Required ?: return null
        if (required.option.alternatives.size != 1) return null
        val named = required.option.alternatives.first()
        if (named.size != 1 || named[0] == course) return null
        return CourseEquivalence(course = course, countsAs = named[0], note = "Renumbered")
    }

    fun convertToEquivalence(substitution: RequirementSubstitution, requirements: ProgramRequirements?, plan: DegreePlan): DegreePlan {
        val equivalence = renumbering(substitution, requirements) ?: return plan
        val without = plan.copy(substitutions = plan.substitutions.filter { it.id != substitution.id })
        return without.declareEquivalence(equivalence.course, equivalence.countsAs, equivalence.note)
    }

    data class LikelyRenumbering(
        val substitution: RequirementSubstitution,
        val course: CourseKey,
        val countsAs: CourseKey,
    ) {
        val id: String get() = substitution.id
        val question: String get() = "Is ${course.description} the new number of ${countsAs.description}?"
        val explanation: String
            get() = "${countsAs.description} isn’t offered anymore. If ${course.description} is the same course under a new number, it counts as ${countsAs.description} everywhere, prerequisites included, like other renumbered courses."
    }

    fun likelyRenumberings(
        plan: DegreePlan,
        requirements: ProgramRequirements?,
        isRetired: (CourseKey) -> Boolean?,
    ): List<LikelyRenumbering> = plan.substitutions.mapNotNull { substitution ->
        val equivalence = renumbering(substitution, requirements) ?: return@mapNotNull null
        if (isRetired(equivalence.countsAs) != true || isRetired(equivalence.course) == true) return@mapNotNull null
        if (plan.declaredEquivalences.areEquivalent(equivalence.course, equivalence.countsAs)) return@mapNotNull null
        LikelyRenumbering(substitution, equivalence.course, equivalence.countsAs)
    }

    fun store(substitution: RequirementSubstitution, plan: DegreePlan): DegreePlan {
        val index = plan.substitutions.indexOfFirst { it.id == substitution.id }
        val next = if (index >= 0) plan.substitutions.toMutableList().apply { this[index] = substitution }
        else plan.substitutions + substitution
        return plan.copy(substitutions = next)
    }

    fun isFree(countingRuleID: String?, ruleID: String, requirements: ProgramRequirements?): Boolean {
        if (countingRuleID == null) return true
        return countingRuleID == ruleID || requirements?.rule(countingRuleID)?.degreeIsGeneralElectives == true
    }

    fun caption(choice: DegreePlanCourseChoice, countingRuleID: String?, ruleID: String, requirements: ProgramRequirements?): String? {
        if (countingRuleID == null) return null
        if (countingRuleID == ruleID) return "Counts toward this requirement"
        if (requirements?.rule(countingRuleID)?.degreeIsGeneralElectives == true) return "Now a general elective"
        return "Counts toward ${requirements?.degreeCountingName(countingRuleID, choice.key) ?: "another requirement"}"
    }

    fun matching(choices: List<DegreePlanCourseChoice>, query: String): List<DegreePlanCourseChoice> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return choices
        val compact = trimmed.replace(" ", "")
        return choices.filter { choice ->
            choice.key.description.contains(trimmed, ignoreCase = true) ||
                "${choice.key.subject}${choice.key.number}".contains(compact, ignoreCase = true) ||
                choice.title.contains(trimmed, ignoreCase = true)
        }
    }
}

/** `CS 2100|CS 2510`, the same for either order. */
fun equivalencePairId(a: CourseKey, b: CourseKey): String =
    if (a < b) "${a.description}|${b.description}" else "${b.description}|${a.description}"

/**
 * A substitution turned into the equivalence it amounts to, with what Undo needs to put the plan back.
 */
data class DegreeRenumbering(
    val course: CourseKey,
    val countsAs: CourseKey,
    val substitution: RequirementSubstitution? = null,
    val substitutionIndex: Int = 0,
    val equivalenceId: String? = null,
) {
    val message: String get() = "${course.description} now counts as ${countsAs.description}"
    val canUndo: Boolean get() = substitution != null || equivalenceId != null

    fun undo(plan: DegreePlan): DegreePlan {
        val substitutions = plan.substitutions.toMutableList()
        val stored = substitution
        if (stored != null && substitutions.none { it.id == stored.id }) {
            substitutions.add(substitutionIndex.coerceIn(0, substitutions.size), stored)
        }
        val equivalences = if (equivalenceId == null) plan.courseEquivalences
        else plan.courseEquivalences.filter { it.id != equivalenceId }
        return plan.copy(substitutions = substitutions, courseEquivalences = equivalences)
    }

    companion object {
        fun convert(plan: DegreePlan, substitution: RequirementSubstitution, requirements: ProgramRequirements?): Pair<DegreePlan, DegreeRenumbering>? {
            val index = plan.substitutions.indexOfFirst { it.id == substitution.id }
            if (index < 0) return null
            val stored = plan.substitutions[index]
            val equivalence = DegreeSubstitutionEditing.renumbering(stored, requirements) ?: return null
            val known = plan.courseEquivalences.map { it.id }.toSet()
            val updated = DegreeSubstitutionEditing.convertToEquivalence(stored, requirements, plan)
            val declared = updated.courseEquivalences.firstOrNull { it.id !in known }?.id
            return updated to DegreeRenumbering(equivalence.course, equivalence.countsAs, stored, index, declared)
        }

        fun declare(plan: DegreePlan, course: CourseKey, countsAs: CourseKey): Pair<DegreePlan, DegreeRenumbering>? {
            if (course == countsAs) return null
            val known = plan.courseEquivalences.map { it.id }.toSet()
            val updated = plan.declareEquivalence(course, countsAs, "Renumbered")
            val declared = updated.courseEquivalences.firstOrNull { it.id !in known }?.id
            return updated to DegreeRenumbering(course, countsAs, equivalenceId = declared)
        }

        fun likely(
            plan: DegreePlan,
            requirements: ProgramRequirements?,
            dismissed: Set<String>,
            isRetired: (CourseKey) -> Boolean?,
        ): Map<String, DegreeSubstitutionEditing.LikelyRenumbering> {
            val found = LinkedHashMap<String, DegreeSubstitutionEditing.LikelyRenumbering>()
            for (item in DegreeSubstitutionEditing.likelyRenumberings(plan, requirements, isRetired)) {
                if (equivalencePairId(item.course, item.countsAs) in dismissed) continue
                found.putIfAbsent(item.substitution.id, item)
            }
            return found
        }

        fun renumberable(plan: DegreePlan, requirements: ProgramRequirements?): Set<String> =
            plan.substitutions.mapNotNull { substitution ->
                if (DegreeSubstitutionEditing.renumbering(substitution, requirements) == null) null else substitution.id
            }.toSet()

        fun coursesToLookUp(plan: DegreePlan, requirements: ProgramRequirements?): List<CourseKey> {
            val seen = LinkedHashSet<CourseKey>()
            for (substitution in plan.substitutions) {
                val equivalence = DegreeSubstitutionEditing.renumbering(substitution, requirements) ?: continue
                seen += equivalence.countsAs
                seen += equivalence.course
            }
            return seen.toList()
        }
    }
}
