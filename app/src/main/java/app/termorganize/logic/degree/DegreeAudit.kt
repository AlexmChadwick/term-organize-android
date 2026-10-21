// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Degree audit: each course counts once. Substitutions apply first, then required
// courses, option lists, ranges, and general electives. NUpath uses the catalog's
// "at most two areas" rule.

package app.termorganize.logic.degree

import app.termorganize.core.model.CourseEquivalences
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.NUPath
import java.util.Locale

data class DegreeCourseAttributes(
    val nupaths: Map<CourseKey, Set<NUPath>> = emptyMap(),
    val loading: Set<CourseKey> = emptySet(),
) {
    val isEmpty: Boolean get() = nupaths.isEmpty() && loading.isEmpty()

    /** [preferred] wins when both know a course. A known course is no longer loading. */
    fun overlay(preferred: DegreeCourseAttributes): DegreeCourseAttributes {
        val paths = nupaths + preferred.nupaths
        return DegreeCourseAttributes(paths, (loading + preferred.loading) - paths.keys)
    }

    companion object { val none = DegreeCourseAttributes() }
}

data class NUpathRequirement(val path: NUPath, val count: Int, val isLimited: Boolean) {
    val title: String get() = "${path.title} (${path.short})"
    companion object {
        val all: List<NUpathRequirement> =
            listOf(NUPath.ND, NUPath.EI, NUPath.IC, NUPath.FQ, NUPath.SI, NUPath.AD, NUPath.DD, NUPath.ER).map { NUpathRequirement(it, 1, true) } +
                listOf(
                    NUpathRequirement(NUPath.WF, 1, false),
                    NUpathRequirement(NUPath.WI, 2, false),
                    NUpathRequirement(NUPath.WD, 1, false),
                    NUpathRequirement(NUPath.EX, 1, false),
                    NUpathRequirement(NUPath.CE, 1, false),
                )
        const val AREAS_PER_COURSE = 2

        fun satisfiedThroughCoop(note: String): NUPath? {
            if (!DegreeAudit.isCoopNote(note)) return null
            val names = mapOf(
                NUPath.EX to listOf("Integrating Knowledge and Skills Through Experience", "Integration Experience", "Integration of Experience"),
                NUPath.CE to listOf("Demonstrating Thought and Action in a Capstone", "Capstone Experience"),
            )
            for ((path, candidates) in names) if (candidates.any { note.contains(it, true) }) return path
            return NUPath.entries.firstOrNull { Regex("\\b${it.short}\\b", RegexOption.IGNORE_CASE).containsMatchIn(note) }
        }
    }
}

class NUpathMatching(
    courses: List<Course>,
    requirements: List<NUpathRequirement> = NUpathRequirement.all,
    coopCounts: Map<NUPath, List<Course>> = emptyMap(),
) {
    data class Course(val id: String, val paths: Set<NUPath>, val isCompleted: Boolean)
    private val assigned: Map<NUPath, List<String>>

    init {
        val limited = requirements.filter { it.isLimited }.map { it.path }
        val map = matchLimited(limited, courses).toMutableMap()
        for (requirement in requirements.filter { !it.isLimited }) {
            val candidates = courses.filter { requirement.path in it.paths } + (coopCounts[requirement.path] ?: emptyList())
            val seen = HashSet<String>()
            val ordered = (candidates.filter { it.isCompleted } + candidates.filter { !it.isCompleted }).filter { seen.add(it.id) }
            map[requirement.path] = ordered.take(requirement.count).map { it.id }
        }
        assigned = map
    }

    fun courses(path: NUPath): List<String> = assigned[path] ?: emptyList()

    private fun matchLimited(areas: List<NUPath>, courses: List<Course>): Map<NUPath, List<String>> {
        val load = MutableList(courses.size) { mutableListOf<NUPath>() }
        val owner = HashMap<NUPath, Int>()
        fun augment(area: NUPath, allowed: (Int) -> Boolean, visited: MutableSet<Int>): Boolean {
            for (index in courses.indices) {
                if (!allowed(index) || area !in courses[index].paths || index in visited) continue
                visited += index
                if (load[index].size < NUpathRequirement.AREAS_PER_COURSE) {
                    load[index] += area
                    owner[area] = index
                    return true
                }
                for (other in load[index].toList()) {
                    if (augment(other, allowed, visited)) {
                        load[index].remove(other)
                        load[index] += area
                        owner[area] = index
                        return true
                    }
                }
            }
            return false
        }
        val passes = listOf<(Int) -> Boolean>({ courses[it].isCompleted }, { true })
        for (allowed in passes) {
            for (area in areas) if (area !in owner) augment(area, allowed, HashSet())
        }
        return owner.mapValues { listOf(courses[it.value].id) }
    }
}

object DegreeAudit {
    enum class Status { MISSING, PARTIAL, PLANNED, COMPLETE, WAIVED;
        val isSatisfied: Boolean get() = this == PLANNED || this == COMPLETE || this == WAIVED
    }

    data class CourseUse(
        val key: CourseKey,
        val title: String,
        val credits: Double,
        val semester: String?,
        val order: Int,
        val bySubstitution: Boolean = false,
        val satisfies: CourseKey? = null,
        val instance: Int = 0,
        val crn: String? = null,
    ) {
        val id: String get() = if (instance == 0) key.description else "${key.description} (${instance + 1})"
        val isCompleted: Boolean get() = semester == null
        val viaEquivalence: Boolean get() = satisfies != null
        val countsAsLabel: String? get() = satisfies?.let { "counts as ${it.description}" }
    }

    data class RuleResult(
        val rule: RequirementRule,
        val effectiveKind: RequirementRule.Kind?,
        val status: Status,
        val used: List<CourseUse>,
        val remainingCount: Int? = null,
        val remainingHours: Double? = null,
        val substitutions: List<RequirementSubstitution> = emptyList(),
    ) {
        val id: String get() = rule.id

        /** The course counted toward [required]: itself, or the equivalent that stands in for it. */
        fun use(required: CourseKey): CourseUse? =
            used.firstOrNull { it.key == required && it.satisfies == null } ?: used.firstOrNull { it.satisfies == required }
    }

    data class GroupResult(val group: RequirementGroup, val rules: List<RuleResult>) {
        val id: String get() = group.id
        val status: Status get() = rules.minByOrNull { it.status.ordinal }?.status ?: Status.COMPLETE
    }

    data class SectionResult(val section: RequirementSection, val groups: List<GroupResult>) {
        val id: String get() = section.id
        val rules: List<RuleResult> get() = groups.flatMap { it.rules }
        val status: Status get() = rules.minByOrNull { it.status.ordinal }?.status ?: Status.COMPLETE
        val isConcentration: Boolean get() = section.isConcentration
    }

    data class NoteItem(
        val id: String,
        val title: String,
        val status: Status?,
        val detail: String,
        val courses: List<String> = emptyList(),
    )

    data class NoteCheck(
        val sectionID: String,
        val note: String,
        val kind: Kind,
        val status: Status,
        val detail: String,
        val items: List<NoteItem> = emptyList(),
        val counts: Boolean = true,
    ) {
        sealed class Kind {
            data class MajorHours(val required: Double) : Kind()
            data object Coop : Kind()
            data object Nupath : Kind()
            data object Universitywide : Kind()
            data class TotalHours(val required: Double) : Kind()
        }
        val id: String get() = "$sectionID|$note"
    }

    data class Result(
        val requirements: ProgramRequirements,
        val sections: List<SectionResult>,
        val generalElectives: List<CourseUse>,
        val unused: List<CourseUse>,
        val completedHours: Double,
        val plannedHours: Double,
        val requiredHours: Double?,
        val unmatchedSubstitutions: List<RequirementSubstitution>,
        val placements: Map<String, String>,
        val needsConcentration: Boolean,
        val noteChecks: List<NoteCheck>,
        val attributes: DegreeCourseAttributes,
    ) {
        val ruleResults: List<RuleResult> get() = sections.flatMap { it.rules }
        val countedChecks: List<NoteCheck> get() = noteChecks.filter { it.counts }
        val satisfiedCount: Int get() = ruleResults.count { it.status.isSatisfied } + countedChecks.count { it.status.isSatisfied }
        val completeCount: Int
            get() = ruleResults.count { it.status == Status.COMPLETE || it.status == Status.WAIVED } +
                countedChecks.count { it.status == Status.COMPLETE }
        val totalCount: Int get() = ruleResults.size + countedChecks.size
        fun result(ruleID: String) = ruleResults.firstOrNull { it.rule.id == ruleID }
        fun ruleCounting(key: CourseKey) = ruleResults.firstOrNull { result -> result.used.any { it.key == key } }
        fun noteCheck(sectionID: String, note: String) = noteChecks.firstOrNull { it.sectionID == sectionID && it.note == note }

        /** Courses that nearly fit a short ranged requirement, completed ones first. */
        fun nearMisses(ruleID: String): List<NearMiss> {
            val result = result(ruleID) ?: return emptyList()
            if (result.status.isSatisfied) return emptyList()
            val kind = result.effectiveKind as? RequirementRule.Kind.Choose ?: return emptyList()
            if (kind.ranges.isEmpty()) return emptyList()
            val candidates = (generalElectives + unused).filter { it.credits > 0 && (kind.count == null || it.credits >= 2) }
            return candidates.mapNotNull { use ->
                if (kind.ranges.any { it.contains(use.key) }) return@mapNotNull null
                for (range in kind.ranges) {
                    val reason = range.reasonExcluding(use.key) ?: continue
                    return@mapNotNull NearMiss(use, range, reason)
                }
                null
            }.sortedWith(compareBy({ if (it.course.isCompleted) 0 else 1 }, { it.course.order }))
        }
    }

    data class NearMiss(val course: CourseUse, val range: CourseRange, val reason: String) {
        val id: String get() = course.id
    }

    fun isCoopNote(note: String) = Regex("""satisfied\s+(?:through|by|with)\s+(?:a\s+)?co-?op""", RegexOption.IGNORE_CASE).containsMatchIn(note)

    fun uncheckedReason(note: String): String? {
        if (Regex("""(?i)\bGPA\b|grade\s+point|minimum\s+grade|grade\s+of\s+[A-D]""").containsMatchIn(note)) return "Not checked: plans don’t include grades"
        if (Regex("""(?i)residen|(?:at|from)\s+Northeastern|Northeastern\s+University\s+semester\s+hours""").containsMatchIn(note)) {
            return "Not checked: a plan doesn’t say where credits were earned"
        }
        if (Regex("""(?i)(?:pick|choose|select|complete)\s+(?:one|a)\s+(?:of\s+the\s+following\s+)?concentrations?""").containsMatchIn(note)) {
            return "Checked through the concentration you choose"
        }
        return null
    }

    fun fewestCreditsChoice(uses: List<CourseUse>, reaching: Double): Set<String>? = fewestCredits(uses, reaching)

    fun run(
        plan: DegreePlan,
        requirements: ProgramRequirements,
        equivalences: CourseEquivalences = CourseEquivalences.none,
        attributes: DegreeCourseAttributes = DegreeCourseAttributes.none,
    ): Result = AuditEngine(plan, requirements, equivalences.merging(plan.declaredEquivalences), attributes).run()
}
