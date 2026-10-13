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
    ) {
        val id: String get() = if (instance == 0) key.description else "${key.description} (${instance + 1})"
        val isCompleted: Boolean get() = semester == null
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
        val totalCount: Int get() = ruleResults.size + countedChecks.size
        fun result(ruleID: String) = ruleResults.firstOrNull { it.rule.id == ruleID }
        fun ruleCounting(key: CourseKey) = ruleResults.firstOrNull { result -> result.used.any { it.key == key } }
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

    fun fewestCreditsChoice(uses: List<CourseUse>, reaching: Double): Set<String>? = fewest(uses, reaching)?.map { it.id }?.toSet()

    fun run(
        plan: DegreePlan,
        requirements: ProgramRequirements,
        equivalences: CourseEquivalences = CourseEquivalences.none,
        attributes: DegreeCourseAttributes = DegreeCourseAttributes.none,
    ): Result = Engine(plan, requirements, equivalences.merging(plan.declaredEquivalences), attributes).run()

    private fun fewest(uses: List<CourseUse>, need: Double): List<CourseUse>? {
        if (need <= 0) return emptyList()
        if (uses.sumOf { it.credits } < need) return null
        val n = uses.size
        if (n > 18) {
            val sorted = uses.sortedByDescending { it.credits }
            val picked = mutableListOf<CourseUse>()
            var sum = 0.0
            for (use in sorted) {
                if (sum >= need) break
                picked += use
                sum += use.credits
            }
            return picked
        }
        var best: List<CourseUse>? = null
        var bestSum = Double.POSITIVE_INFINITY
        var bestCount = Int.MAX_VALUE
        var bestCompleted = -1
        val limit = 1 shl n
        for (mask in 1 until limit) {
            var sum = 0.0
            var count = 0
            var completed = 0
            val picked = mutableListOf<CourseUse>()
            for (i in 0 until n) if (mask and (1 shl i) != 0) {
                val use = uses[i]
                sum += use.credits
                count++
                if (use.isCompleted) completed++
                picked += use
            }
            if (sum + 1e-9 < need) continue
            val better = sum < bestSum - 1e-9 ||
                (kotlin.math.abs(sum - bestSum) < 1e-9 && (completed > bestCompleted || (completed == bestCompleted && count < bestCount)))
            if (better) {
                best = picked
                bestSum = sum
                bestCount = count
                bestCompleted = completed
            }
        }
        return best
    }

    private class Engine(
        val plan: DegreePlan,
        val requirements: ProgramRequirements,
        val equivalences: CourseEquivalences,
        val attributes: DegreeCourseAttributes,
    ) {
        val sections = requirements.applicableSections(plan.concentrationID)
        val rules = sections.flatMap { it.groups.flatMap { g -> g.rules } }
        val pool = LinkedHashMap<String, CourseUse>()
        val instances = LinkedHashMap<CourseKey, MutableList<String>>()
        val available = HashSet<String>()
        val used = LinkedHashMap<String, MutableList<CourseUse>>()
        val substitutionsByRule = LinkedHashMap<String, MutableList<RequirementSubstitution>>()
        val unmatched = mutableListOf<RequirementSubstitution>()
        val placements = LinkedHashMap<String, String>()
        val effective = HashMap<String, RequirementRule.Kind?>()
        val waived = HashSet<String>()

        init {
            var order = 0
            fun offer(course: DegreePlanCourse, semester: String?) {
                val key = course.key ?: run { order++; return }
                var use = CourseUse(key, course.title, course.credits, semester, order)
                val earlier = instances[key].orEmpty().mapNotNull { pool[it] }
                val repeated = earlier.firstOrNull { !DegreeCourseRepeats.countsSeparately(use.title, use.credits, it.title, it.credits) }
                if (repeated != null) {
                    val better = if (repeated.isCompleted == use.isCompleted) use.credits > repeated.credits else use.isCompleted
                    if (better) pool[repeated.id] = use.copy(instance = repeated.instance)
                    order++
                    return
                }
                use = use.copy(instance = earlier.size)
                pool[use.id] = use
                instances.getOrPut(key) { mutableListOf() }.add(use.id)
                order++
            }
            plan.completed.forEach { offer(it, null) }
            plan.terms.forEach { term -> term.courses.forEach { offer(it, term.name) } }
            available += pool.keys
            val ids = rules.map { it.id }.toSet()
            val byName = HashMap<String, MutableSet<String>>()
            for (rule in rules) {
                requirements.titleOf(rule.id)?.let { byName.getOrPut(it.lowercase(Locale.US)) { mutableSetOf() }.add(rule.id) }
                byName.getOrPut(rule.label.lowercase(Locale.US)) { mutableSetOf() }.add(rule.id)
            }
            for (sub in plan.substitutions) {
                val target = when {
                    sub.ruleID in ids -> sub.ruleID
                    byName[sub.ruleTitle.lowercase(Locale.US)]?.size == 1 -> byName.getValue(sub.ruleTitle.lowercase(Locale.US)).first()
                    else -> null
                }
                if (target == null) unmatched += sub else {
                    substitutionsByRule.getOrPut(target) { mutableListOf() }.add(sub)
                    placements[sub.id] = target
                }
            }
            for (rule in rules) effective[rule.id] = rule.kind
            for ((ruleId, subs) in substitutionsByRule) {
                for (sub in subs) {
                    when (val change = sub.change) {
                        SubstitutionChange.Waive -> waived += ruleId
                        is SubstitutionChange.CountCourse -> Unit
                        is SubstitutionChange.Replace -> effective[ruleId] = replacementKind(change.replacement, ruleId)
                    }
                }
            }
        }

        fun run(): Result {
            for ((ruleId, subs) in substitutionsByRule) {
                for (sub in subs) {
                    val change = sub.change as? SubstitutionChange.CountCourse ?: continue
                    val id = take(change.key) ?: run { unmatched += sub; continue }
                    val use = pool.getValue(id).copy(bySubstitution = true)
                    pool[id] = use
                    used.getOrPut(ruleId) { mutableListOf() }.add(use)
                }
            }
            val required = rules.mapIndexedNotNull { index, rule ->
                val kind = effective[rule.id] as? RequirementRule.Kind.Required ?: return@mapIndexedNotNull null
                Triple(index, rule, kind.option)
            }.sortedWith(compareBy<Triple<Int, RequirementRule, CourseOption>> { it.third.alternatives.size }.thenBy { it.first })
            for ((_, rule, option) in required) fillRequired(rule, option)
            val choices = rules.mapIndexedNotNull { index, rule ->
                val kind = effective[rule.id] as? RequirementRule.Kind.Choose ?: return@mapIndexedNotNull null
                Quad(index, rule, kind, kind.ranges.isNotEmpty())
            }
            for (item in choices.filter { !it.ranged }.sortedWith(compareBy<Quad> { it.kind.options.size }.thenBy { it.index })) fillChoice(item.rule, item.kind)
            for (item in choices.filter { it.ranged && it.kind.count != null }) fillChoice(item.rule, item.kind)
            for (item in choices.filter { it.ranged && it.kind.count == null }) fillChoice(item.rule, item.kind)
            val general = mutableListOf<CourseUse>()
            for (rule in rules) {
                val kind = effective[rule.id] as? RequirementRule.Kind.GeneralElectives ?: continue
                val counted = used[rule.id].orEmpty().sumOf { it.credits }
                val candidates = remaining().filter { it.credits > 0 }
                val chosen = fewest(candidates, kind.hours - counted)?.map { it.id }?.toSet() ?: candidates.map { it.id }.toSet()
                for (use in candidates) if (use.id in chosen) consume(use.id, rule.id)
                general += used[rule.id].orEmpty()
            }
            val results = sections.map { section ->
                SectionResult(section, section.groups.map { group -> GroupResult(group, group.rules.map { result(it) }) })
            }
            val countedIds = used.values.flatten().map { it.id }.toSet() + general.map { it.id }
            val unused = pool.values.filter { it.id !in countedIds && it.id in available }.sortedBy { it.order }
            val checks = noteChecks()
            val concentrationChosen = plan.concentrationID != null && requirements.concentrations.any { it.id == plan.concentrationID }
            return Result(
                requirements, results, general, unused,
                pool.values.filter { it.isCompleted }.sumOf { maxOf(0.0, it.credits) },
                pool.values.filter { !it.isCompleted }.sumOf { maxOf(0.0, it.credits) },
                requirements.totalHours, unmatched, placements,
                requirements.concentrations.isNotEmpty() && !concentrationChosen,
                checks, attributes,
            )
        }

        private fun fillRequired(rule: RequirementRule, option: CourseOption) {
            if (rule.id in waived || isMetRequired(rule.id, option)) return
            val already = used[rule.id].orEmpty().map { it.satisfies ?: it.key }.toSet()
            val alternatives = option.alternatives.sortedBy { alt ->
                val missing = alt.count { it !in already && find(it) == null }
                val completed = alt.count { key -> find(key)?.let { found -> pool[found.id]?.isCompleted } == true }
                missing * 10 - completed
            }
            for (alt in alternatives) {
                if (alt.all { it in already || find(it) != null }) {
                    for (key in alt) {
                        if (key in already) continue
                        val found = find(key) ?: continue
                        consume(found.id, rule.id, satisfies = found.satisfies)
                    }
                    return
                }
            }
        }

        private fun fillChoice(rule: RequirementRule, kind: RequirementRule.Kind.Choose) {
            if (rule.id in waived) return
            val pinned = used[rule.id].orEmpty()
            if (kind.options.isNotEmpty() && kind.ranges.isEmpty() && kind.hours == null) {
                var counted = pinned.size
                for (option in kind.options) {
                    if (kind.count != null && counted >= kind.count) break
                    val alt = option.alternatives.firstOrNull { keys -> keys.all { find(it) != null } } ?: continue
                    if (alt.any { key -> used[rule.id].orEmpty().any { (it.satisfies ?: it.key) == key } }) continue
                    for (key in alt) {
                        val found = find(key) ?: continue
                        consume(found.id, rule.id, satisfies = found.satisfies)
                    }
                    counted++
                }
                return
            }
            val fitting = remaining().filter { fits(it, kind) }
            if (kind.count != null && kind.hours == null) {
                val need = kind.count - pinned.size
                val picked = fitting.filter { it.credits >= 1 || !it.title.contains("lab", true) }.sortedWith(compareBy<CourseUse> { !it.isCompleted }.thenBy { it.order }).take(need.coerceAtLeast(0))
                picked.forEach { consume(it.id, rule.id) }
            } else if (kind.hours != null) {
                val have = used[rule.id].orEmpty().sumOf { it.credits }
                val choice = fewest(fitting, kind.hours - have) ?: emptyList()
                choice.forEach { consume(it.id, rule.id) }
            }
        }

        private fun fits(use: CourseUse, kind: RequirementRule.Kind.Choose): Boolean {
            if (kind.options.any { option -> option.alternatives.any { it.size == 1 && it[0] == use.key } }) return true
            if (kind.ranges.any { it.contains(use.key) }) return true
            if (use.satisfies != null && kind.options.any { option -> option.allCourses.contains(use.satisfies) }) return true
            return false
        }

        private fun result(rule: RequirementRule): RuleResult {
            val subs = substitutionsByRule[rule.id].orEmpty()
            val list = used[rule.id].orEmpty().sortedBy { it.order }
            if (rule.id in waived) return RuleResult(rule, null, Status.WAIVED, list, substitutions = subs)
            val kind = effective[rule.id]
            val status: Status
            var remainingCount: Int? = null
            var remainingHours: Double? = null
            when (kind) {
                is RequirementRule.Kind.Required -> {
                    val met = isMetRequired(rule.id, kind.option)
                    status = when {
                        !met -> if (list.isEmpty()) Status.MISSING else Status.PARTIAL
                        list.all { it.isCompleted } -> Status.COMPLETE
                        else -> Status.PLANNED
                    }
                }
                is RequirementRule.Kind.Choose -> {
                    if (kind.count != null && (kind.hours == null || kind.ranges.isEmpty() && kind.options.isNotEmpty())) {
                        val counted = if (kind.ranges.isEmpty()) optionsCounted(rule.id, kind) else list.size
                        remainingCount = (kind.count - counted).coerceAtLeast(0)
                        status = when {
                            counted >= kind.count && list.all { it.isCompleted } -> Status.COMPLETE
                            counted >= kind.count -> Status.PLANNED
                            counted > 0 -> Status.PARTIAL
                            else -> Status.MISSING
                        }
                    } else {
                        val need = kind.hours ?: 0.0
                        val have = list.sumOf { it.credits }
                        remainingHours = (need - have).coerceAtLeast(0.0).let { if (it == 0.0) 0.0 else it }
                        status = when {
                            have + 1e-9 >= need && need > 0 && list.all { it.isCompleted } -> Status.COMPLETE
                            have + 1e-9 >= need && need > 0 -> Status.PLANNED
                            have > 0 -> Status.PARTIAL
                            else -> Status.MISSING
                        }
                        if (status == Status.COMPLETE || status == Status.PLANNED) remainingHours = 0.0
                    }
                }
                is RequirementRule.Kind.GeneralElectives -> {
                    val have = list.sumOf { it.credits }
                    remainingHours = (kind.hours - have).coerceAtLeast(0.0)
                    status = when {
                        have + 1e-9 >= kind.hours && list.all { it.isCompleted } -> Status.COMPLETE
                        have + 1e-9 >= kind.hours -> Status.PLANNED
                        have > 0 -> Status.PARTIAL
                        else -> Status.MISSING
                    }
                }
                null -> status = Status.MISSING
            }
            return RuleResult(rule, kind, status, list, remainingCount, remainingHours, subs)
        }

        private fun optionsCounted(ruleId: String, kind: RequirementRule.Kind.Choose): Int {
            val keys = used[ruleId].orEmpty().map { it.satisfies ?: it.key }.toSet()
            return kind.options.count { option -> option.alternatives.any { alt -> alt.all { it in keys } } }
        }

        private fun isMetRequired(ruleId: String, option: CourseOption): Boolean {
            val keys = used[ruleId].orEmpty().map { it.satisfies ?: it.key }.toSet()
            return option.alternatives.any { alt -> alt.all { it in keys } }
        }

        private data class Found(val id: String, val satisfies: CourseKey?)

        private fun find(key: CourseKey): Found? {
            instances[key]?.firstOrNull { it in available }?.let { return Found(it, null) }
            for (other in equivalences.group(key)) {
                if (other == key) continue
                instances[other]?.firstOrNull { it in available }?.let { return Found(it, key) }
            }
            return null
        }

        private fun take(key: CourseKey): String? = find(key)?.also { consume(it.id, "", satisfies = it.satisfies) }?.id.also {
            if (it != null) used.remove("")
        }

        private fun consume(id: String, ruleId: String, satisfies: CourseKey? = null) {
            if (id !in available && ruleId.isNotEmpty()) return
            available.remove(id)
            if (ruleId.isEmpty()) return
            var use = pool.getValue(id)
            if (satisfies != null) use = use.copy(satisfies = satisfies)
            pool[id] = use
            used.getOrPut(ruleId) { mutableListOf() }.add(use)
        }

        private fun remaining() = pool.values.filter { it.id in available }.sortedBy { it.order }

        private fun replacementKind(replacement: RequirementReplacement, ruleId: String): RequirementRule.Kind {
            val like = replacement.likeRuleID?.let { requirements.rule(it) }?.kind as? RequirementRule.Kind.Choose
            var ranges = like?.ranges ?: emptyList()
            when (replacement.courseNumbers) {
                CourseNumberLimit.AnyNumber -> ranges = ranges.map { it.copy(lowest = 0, label = "Any ${it.subject} course") }
                is CourseNumberLimit.AtLeast -> ranges = ranges.map { it.copy(lowest = replacement.courseNumbers.minimum) }
                else -> Unit
            }
            val options = (like?.options ?: emptyList()) + replacement.courses.map { CourseOption(listOf(listOf(it))) }
            return RequirementRule.Kind.Choose(replacement.count, replacement.hours, options, ranges)
        }

        private fun noteChecks(): List<NoteCheck> {
            val checks = mutableListOf<NoteCheck>()
            for (section in requirements.sections) {
                for (note in section.notes) {
                    majorHours(note)?.let { checks += majorCheck(section.id, note, it) }
                    if (isCoopNote(note)) checks += coopCheck(section.id, note)
                    if (Regex("""(?i)nupath requirements""").containsMatchIn(note)) nupathCheck(section.id, note)?.let { checks += it }
                    totalHours(note)?.let { checks += hoursCheck(section.id, note, it, NoteCheck.Kind.TotalHours(it)) }
                }
            }
            return checks
        }

        private fun majorHours(note: String) =
            Regex("""(?i)(\d+(?:\.\d+)?)\s+(?:semester\s+)?(?:credit\s+)?hours\s+(?:in|within)\s+the\s+major""").find(note)?.groupValues?.get(1)?.toDoubleOrNull()

        private fun totalHours(note: String) =
            Regex("""(?i)(\d+(?:\.\d+)?)\s+total\s+semester\s+hours\s+required""").find(note)?.groupValues?.get(1)?.toDoubleOrNull()

        private fun majorCheck(sectionId: String, note: String, required: Double): NoteCheck {
            val counted = LinkedHashMap<String, CourseUse>()
            for (section in sections) {
                val writing = section.title.contains("writing", true)
                for (group in section.groups) {
                    if (writing || (group.title ?: "").contains("writing", true)) continue
                    for (rule in group.rules) {
                        if (effective[rule.id] is RequirementRule.Kind.GeneralElectives) continue
                        used[rule.id].orEmpty().forEach { counted[it.id] = it }
                    }
                }
            }
            val completed = counted.values.filter { it.isCompleted }.sumOf { maxOf(0.0, it.credits) }
            val planned = counted.values.filter { !it.isCompleted }.sumOf { maxOf(0.0, it.credits) }
            val status = when {
                completed >= required -> Status.COMPLETE
                completed + planned >= required -> Status.PLANNED
                completed + planned > 0 -> Status.PARTIAL
                else -> Status.MISSING
            }
            var detail = "${hours(completed + planned)} of ${hours(required)} semester hours"
            if (planned > 0) detail += " · ${hours(planned)} planned"
            return NoteCheck(sectionId, note, NoteCheck.Kind.MajorHours(required), status, detail)
        }

        private fun coopCheck(sectionId: String, note: String): NoteCheck {
            val coops = pool.values.filter { isCoop(it) }.sortedBy { it.order }
            val done = coops.firstOrNull { it.isCompleted }
            val next = coops.firstOrNull()
            return when {
                done != null -> NoteCheck(sectionId, note, NoteCheck.Kind.Coop, Status.COMPLETE, "${done.key.description} completed")
                next != null -> NoteCheck(sectionId, note, NoteCheck.Kind.Coop, Status.PLANNED, "${next.key.description} planned${next.semester?.let { " · $it" } ?: ""}")
                else -> NoteCheck(sectionId, note, NoteCheck.Kind.Coop, Status.MISSING, "No co-op in the plan")
            }
        }

        private fun isCoop(use: CourseUse) =
            use.key.subject == "COOP" || Regex("""(?i)co-?op\s+(?:work\s+)?experience|cooperative\s+education""").containsMatchIn(use.title)

        private fun nupathCheck(sectionId: String, note: String): NoteCheck? {
            if (attributes.isEmpty) return null
            val courses = mutableListOf<NUpathMatching.Course>()
            val unknown = mutableListOf<CourseUse>()
            for (use in pool.values.sortedBy { it.order }) {
                val paths = nupaths(use)
                if (paths == null) unknown += use
                else if (paths.isNotEmpty()) courses += NUpathMatching.Course(use.id, paths, use.isCompleted)
            }
            val coops = pool.values.filter { isCoop(it) }.map { NUpathMatching.Course(it.id, emptySet(), it.isCompleted) }
            val coopCounts = HashMap<NUPath, List<NUpathMatching.Course>>()
            if (coops.isNotEmpty()) {
                for (section in requirements.sections) {
                    for (text in section.notes + section.groups.flatMap { it.notes }) {
                        NUpathRequirement.satisfiedThroughCoop(text)?.let { coopCounts[it] = coops }
                    }
                }
            }
            val matching = NUpathMatching(courses, coopCounts = coopCounts)
            val items = NUpathRequirement.all.map { requirement ->
                val chosen = matching.courses(requirement.path).mapNotNull { pool[it] }
                val status: Status?
                val detail: String
                if (chosen.size >= requirement.count) {
                    status = if (chosen.all { it.isCompleted }) Status.COMPLETE else Status.PLANNED
                    detail = describe(chosen)
                } else if (unknown.isNotEmpty()) {
                    status = null
                    detail = if (chosen.isEmpty()) "Not found among the courses with NUpath data" else "${chosen.size} of ${requirement.count}"
                } else if (chosen.isEmpty()) {
                    status = Status.MISSING
                    detail = if (requirement.count == 1) "No course in the plan has it" else "Needs ${requirement.count} courses"
                } else {
                    status = Status.PARTIAL
                    detail = "${chosen.size} of ${requirement.count} · ${describe(chosen)}"
                }
                NoteItem(requirement.path.attributeCode, requirement.title, status, detail, chosen.map { it.id })
            }
            val met = items.filter { it.status?.isSatisfied == true }
            val open = items.filter { it.status?.isSatisfied != true }
            val status = when {
                open.isEmpty() -> if (met.all { it.status == Status.COMPLETE }) Status.COMPLETE else Status.PLANNED
                met.isEmpty() && items.all { it.courses.isEmpty() } -> Status.MISSING
                else -> Status.PARTIAL
            }
            return NoteCheck(sectionId, note, NoteCheck.Kind.Nupath, status, "${met.size} of ${items.size} met", items, counts = items.none { it.status == null })
        }

        private fun nupaths(use: CourseUse): Set<NUPath>? {
            attributes.nupaths[use.key]?.let { return it }
            if (isCoop(use)) return emptySet()
            for (other in equivalences.group(use.key)) if (other != use.key) attributes.nupaths[other]?.let { return it }
            return null
        }

        private fun describe(uses: List<CourseUse>) = uses.joinToString(" · ") { use ->
            if (use.isCompleted) "${use.key.description} completed" else "${use.key.description} planned${use.semester?.let { " · $it" } ?: ""}"
        }

        private fun hoursCheck(sectionId: String, note: String, required: Double, kind: NoteCheck.Kind): NoteCheck {
            val completed = pool.values.filter { it.isCompleted }.sumOf { maxOf(0.0, it.credits) }
            val planned = pool.values.filter { !it.isCompleted }.sumOf { maxOf(0.0, it.credits) }
            val status = when {
                completed >= required -> Status.COMPLETE
                completed + planned >= required -> Status.PLANNED
                completed + planned > 0 -> Status.PARTIAL
                else -> Status.MISSING
            }
            return NoteCheck(sectionId, note, kind, status, "${hours(completed + planned)} of ${hours(required)} semester hours")
        }

        private fun hours(value: Double) = if (kotlin.math.abs(value % 1.0) < 1e-9) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
    }

    private data class Quad(val index: Int, val rule: RequirementRule, val kind: RequirementRule.Kind.Choose, val ranged: Boolean)
}
