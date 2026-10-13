// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Faithful port of DegreeAudit.swift: substitutions, greedy fill, rebalance, and the
// exact course-to-requirement search.

package app.termorganize.logic.degree

import app.termorganize.core.model.CourseEquivalences
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.NUPath
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round

internal fun fewestCredits(uses: List<DegreeAudit.CourseUse>, need: Double): Set<String>? {
    if (need <= 0) return emptySet()
    val courses = uses.filter { it.credits > 0 }
    val weights = courses.map { max(1, round(it.credits * 2).toInt()) }
    val target = ceil(need * 2).toInt()
    if (weights.sum() < target) return null
    val largest = weights.maxOrNull() ?: return null
    val cap = target + largest
    class Pick(var completed: Int = 0, var count: Int = 0, var order: Int = 0, val chosen: MutableList<Int> = mutableListOf()) {
        fun isBetterThan(other: Pick) =
            completed > other.completed ||
                (completed == other.completed && (count < other.count || (count == other.count && order < other.order)))
        fun copy() = Pick(completed, count, order, chosen.toMutableList())
    }
    val best = arrayOfNulls<Pick>(cap + 1)
    best[0] = Pick()
    for ((index, item) in courses.withIndex()) {
        val weight = weights[index]
        if (weight > cap) continue
        for (sum in cap downTo weight) {
            val source = best[sum - weight] ?: continue
            val pick = source.copy()
            pick.completed += if (item.isCompleted) 1 else 0
            pick.count += 1
            pick.order += item.order
            pick.chosen += index
            val current = best[sum]
            if (current != null && !pick.isBetterThan(current)) continue
            best[sum] = pick
        }
    }
    for (sum in target..cap) {
        val pick = best[sum] ?: continue
        return pick.chosen.map { courses[it].id }.toSet()
    }
    return null
}

internal class AuditEngine(
    private val plan: DegreePlan,
    private val requirements: ProgramRequirements,
    private val equivalences: CourseEquivalences,
    private val attributes: DegreeCourseAttributes,
) {
    private sealed class Effective {
        data object Waived : Effective()
        data class Kind(val kind: RequirementRule.Kind, val anyCourse: Boolean) : Effective()
    }

    private val sections = requirements.applicableSections(plan.concentrationID)
    private val rules = sections.flatMap { it.groups.flatMap { group -> group.rules } }
    private val pool = LinkedHashMap<String, DegreeAudit.CourseUse>()
    private val instances = LinkedHashMap<CourseKey, MutableList<String>>()
    private val available = HashSet<String>()
    private val used = LinkedHashMap<String, MutableList<DegreeAudit.CourseUse>>()
    private val optionsCounted = HashMap<String, Int>()
    private val effective = HashMap<String, Effective>()
    private val demand = HashMap<CourseKey, Int>()
    private val singles = HashMap<String, MutableSet<String>>()
    private val unmatched = mutableListOf<RequirementSubstitution>()
    private val substitutionsByRule: Map<String, List<RequirementSubstitution>>
    private val rulesByName: Map<String, Set<String>>

    init {
        val byName = HashMap<String, MutableSet<String>>()
        for (rule in rules) {
            for (name in listOfNotNull(requirements.degreeShortName(rule.id), requirements.titleOf(rule.id))) {
                byName.getOrPut(name.lowercase(Locale.US)) { mutableSetOf() }.add(rule.id)
            }
        }
        rulesByName = byName
        val ids = rules.map { it.id }.toSet()
        val byRule = LinkedHashMap<String, MutableList<RequirementSubstitution>>()
        for (substitution in plan.substitutions) {
            val named = rulesByName[substitution.ruleTitle.lowercase(Locale.US)]
            val target = when {
                substitution.ruleID in ids -> substitution.ruleID
                named != null && named.size == 1 -> named.first()
                else -> null
            }
            if (target == null) unmatched += substitution
            else byRule.getOrPut(target) { mutableListOf() }.add(substitution)
        }
        substitutionsByRule = byRule

        var order = 0
        fun offer(course: DegreePlanCourse, semester: String?) {
            val key = course.key
            if (key == null) {
                order++
                return
            }
            var use = DegreeAudit.CourseUse(key, course.title, course.credits, semester, order)
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
    }

    fun run(): DegreeAudit.Result {
        resolveSubstitutions()
        applyCourseSubstitutions()

        val required = rules.mapIndexedNotNull { index, rule ->
            val kind = (effective[rule.id] as? Effective.Kind)?.kind as? RequirementRule.Kind.Required ?: return@mapIndexedNotNull null
            Triple(index, rule, kind.option)
        }.sortedWith(compareBy<Triple<Int, RequirementRule, CourseOption>> { it.third.alternatives.size }.thenBy { it.first })
        for ((_, rule, option) in required) fillRequired(rule, option)

        data class Choice(val index: Int, val rule: RequirementRule, val optionCount: Int, val ranged: Boolean)
        val choices = rules.mapIndexedNotNull { index, rule ->
            val current = effective[rule.id] as? Effective.Kind ?: return@mapIndexedNotNull null
            val kind = current.kind as? RequirementRule.Kind.Choose ?: return@mapIndexedNotNull null
            Choice(index, rule, kind.options.size, kind.ranges.isNotEmpty() || current.anyCourse)
        }
        for (choice in choices.filter { !it.ranged }.sortedWith(compareBy<Choice> { it.optionCount }.thenBy { it.index })) {
            fillChoice(choice.rule)
        }
        val ranged = choices.filter { it.ranged }
        for (choice in ranged) if (countsCourses(choice.rule.id)) fillChoice(choice.rule)
        for (choice in ranged) if (!countsCourses(choice.rule.id)) fillChoice(choice.rule)
        rebalance()
        if (ensureBestMatching()) {
            for (rule in rules) if (!isMet(rule.id)) {
                when (val current = effective[rule.id]) {
                    is Effective.Kind -> when (val kind = current.kind) {
                        is RequirementRule.Kind.Required -> fillRequired(rule, kind.option)
                        is RequirementRule.Kind.Choose -> fillChoice(rule)
                        is RequirementRule.Kind.GeneralElectives -> Unit
                    }
                    else -> Unit
                }
            }
        }
        trimSurplusHours()

        val general = mutableListOf<DegreeAudit.CourseUse>()
        for (rule in rules) {
            val kind = ((effective[rule.id] as? Effective.Kind)?.kind as? RequirementRule.Kind.GeneralElectives) ?: continue
            val counted = used[rule.id].orEmpty().sumOf { it.credits }
            val candidates = remaining().filter { it.credits > 0 }
            val chosen = fewestCredits(candidates, kind.hours - counted) ?: candidates.map { it.id }.toSet()
            for (use in candidates) if (use.id in chosen) consume(use.id, rule.id)
            general += used[rule.id].orEmpty()
        }

        val results = sections.map { section ->
            DegreeAudit.SectionResult(
                section,
                section.groups.map { group -> DegreeAudit.GroupResult(group, group.rules.map { result(it) }) },
            )
        }
        val placements = LinkedHashMap<String, String>()
        for ((ruleId, subs) in substitutionsByRule) for (sub in subs) placements[sub.id] = ruleId
        val concentrationChosen = plan.concentrationID?.let { id -> requirements.concentrations.any { it.id == id } } ?: false
        return DegreeAudit.Result(
            requirements,
            results,
            general,
            remaining(),
            pool.values.filter { it.isCompleted }.sumOf { maxOf(0.0, it.credits) },
            pool.values.filter { !it.isCompleted }.sumOf { maxOf(0.0, it.credits) },
            requirements.totalHours,
            unmatched.toList(),
            placements,
            requirements.concentrations.isNotEmpty() && !concentrationChosen,
            noteChecks(),
            attributes,
        )
    }

    private fun countsCourses(ruleId: String): Boolean {
        val kind = (effective[ruleId] as? Effective.Kind)?.kind as? RequirementRule.Kind.Choose ?: return false
        return kind.count != null
    }

    private fun resolveSubstitutions() {
        for (rule in rules) {
            val subs = substitutionsByRule[rule.id].orEmpty()
            val replacement = subs.mapNotNull { (it.change as? SubstitutionChange.Replace)?.replacement }.lastOrNull()
            effective[rule.id] = when {
                subs.any { it.change is SubstitutionChange.Waive } -> Effective.Waived
                replacement != null -> replacementKind(replacement)
                else -> Effective.Kind(rule.kind, false)
            }
        }
        for (rule in rules) {
            val kind = (effective[rule.id] as? Effective.Kind)?.kind ?: continue
            val listed = when (kind) {
                is RequirementRule.Kind.Required -> kind.option.allCourses
                is RequirementRule.Kind.Choose -> kind.options.flatMap { it.allCourses }
                is RequirementRule.Kind.GeneralElectives -> emptyList()
            }
            for (key in listed.toSet()) demand[key] = (demand[key] ?: 0) + 1
        }
    }

    private fun replacementKind(replacement: RequirementReplacement): Effective {
        var options = replacement.courses.map { CourseOption(listOf(listOf(it))) }
        var ranges = emptyList<CourseRange>()
        var anyCourse = false
        val named = replacement.likeRuleTitle?.lowercase(Locale.US)?.let { rulesByName[it] }?.let { if (it.size == 1) it.first() else null }
        if (replacement.likeRuleID?.let { requirements.rule(it) } == null && named == null &&
            replacement.likeRuleTitle?.lowercase(Locale.US)?.startsWith("general electives") == true
        ) {
            anyCourse = true
        }
        val like = replacement.likeRuleID?.let { requirements.rule(it) } ?: named?.let { requirements.rule(it) }
        if (like != null) {
            when (val kind = like.kind) {
                is RequirementRule.Kind.Required -> options = listOf(kind.option) + options
                is RequirementRule.Kind.Choose -> {
                    options = kind.options + options
                    ranges = kind.ranges.map { it.applying(replacement.courseNumbers) }
                }
                is RequirementRule.Kind.GeneralElectives -> anyCourse = true
            }
        }
        val count = replacement.count ?: if (replacement.hours == null) 1 else null
        return Effective.Kind(RequirementRule.Kind.Choose(count, replacement.hours, options, ranges), anyCourse)
    }

    private fun applyCourseSubstitutions() {
        for (rule in rules) {
            if (effective[rule.id] !is Effective.Kind) continue
            for (substitution in substitutionsByRule[rule.id].orEmpty()) {
                val key = (substitution.change as? SubstitutionChange.CountCourse)?.key ?: continue
                val taken = standIn(key) ?: run {
                    unmatched += substitution
                    continue
                }
                consume(taken, rule.id, bySubstitution = true, satisfying = key)
                optionsCounted[rule.id] = (optionsCounted[rule.id] ?: 0) + 1
            }
        }
    }

    private fun fillRequired(rule: RequirementRule, option: CourseOption) {
        if (used[rule.id].orEmpty().isNotEmpty()) return
        val complete = option.alternatives.filter { isComplete(it) }
        val best = complete.minWithOrNull(::compareScores)
        if (best != null) {
            for ((required, taken) in assign(best)) consume(taken, rule.id, satisfying = required)
            return
        }
        val partial = option.alternatives.map { assign(it) }.maxByOrNull { it.size }.orEmpty()
        for ((required, taken) in partial) consume(taken, rule.id, satisfying = required)
    }

    private fun fillChoice(rule: RequirementRule) {
        val current = effective[rule.id] as? Effective.Kind ?: return
        val kind = current.kind as? RequirementRule.Kind.Choose ?: return
        fun done() = isSatisfied(rule.id, kind.count, kind.hours)
        data class Cand(val alternative: List<CourseKey>, val s0: Int, val s1: Int, val index: Int)
        val candidates = kind.options.mapIndexedNotNull { index, option ->
            val complete = option.alternatives.filter { isComplete(it) }
            val best = complete.minWithOrNull(::compareScores) ?: return@mapIndexedNotNull null
            val score = score(best)
            Cand(best, score.first, score.second, index)
        }.sortedWith(compareBy<Cand> { it.s0 }.thenBy { it.s1 }.thenBy { it.index })
        for (candidate in candidates) {
            if (done()) break
            if (!isComplete(candidate.alternative)) continue
            val pairs = assign(candidate.alternative)
            for ((required, taken) in pairs) consume(taken, rule.id, satisfying = required)
            if (pairs.size == 1) singles.getOrPut(rule.id) { mutableSetOf() }.add(pairs[0].second)
            optionsCounted[rule.id] = (optionsCounted[rule.id] ?: 0) + 1
        }
        if (done() || (kind.ranges.isEmpty() && !current.anyCourse)) return
        val matches = remaining().mapNotNull { use ->
            if (use.credits <= 0 || (kind.count != null && use.credits < 2)) return@mapNotNull null
            if (current.anyCourse) return@mapNotNull use to null
            val inRange = courseInRange(use.key, kind.ranges) ?: return@mapNotNull null
            use to if (inRange == use.key) null else inRange
        }
        for ((use, via) in matches) {
            if (done()) break
            consume(use.id, rule.id, satisfying = via)
            optionsCounted[rule.id] = (optionsCounted[rule.id] ?: 0) + 1
            singles.getOrPut(rule.id) { mutableSetOf() }.add(use.id)
        }
    }

    private fun trimSurplusHours() {
        for (rule in rules) {
            val kind = (effective[rule.id] as? Effective.Kind)?.kind as? RequirementRule.Kind.Choose ?: continue
            val hours = kind.hours ?: continue
            if (kind.count != null || !choiceIsSatisfied(rule.id)) continue
            val uses = used[rule.id].orEmpty()
            val movableIds = singles[rule.id].orEmpty()
            val movable = uses.filter { !it.bySubstitution && it.id in movableIds }
            val movableIdSet = movable.map { it.id }.toSet()
            val fixed = uses.filter { it.id !in movableIdSet }.sumOf { it.credits }
            val unused = remaining().mapNotNull { use -> singleMatch(use, rule.id)?.let { use to it } }
            val keep = fewestCredits(movable + unused.map { it.first }, hours - fixed) ?: continue
            for (use in movable) if (use.id !in keep) release(use.id, rule.id)
            for ((use, named) in unused) if (use.id in keep) place(use.id, rule.id, named)
        }
    }

    private fun rebalance() {
        for (rule in rules) {
            if (choiceIsSatisfied(rule.id)) continue
            val budget = intArrayOf(2_000)
            while (!choiceIsSatisfied(rule.id)) {
                var moved = false
                for (steps in 0..3) {
                    val next = adding(rule.id, 0.0, steps, setOf(rule.id), budget) ?: continue
                    replaceWith(next)
                    moved = true
                    break
                }
                if (!moved) break
            }
        }
    }

    private fun adding(ruleID: String, minCredits: Double, steps: Int, avoiding: Set<String>, budget: IntArray): AuditEngine? {
        for (use in remaining()) {
            if (use.credits < minCredits) continue
            val named = singleMatch(use, ruleID) ?: continue
            val next = fork()
            next.place(use.id, ruleID, named)
            return next
        }
        if (steps <= 0) return null
        for (donor in rules) {
            if (donor.id in avoiding || !choiceIsSatisfied(donor.id)) continue
            for (use in used[donor.id].orEmpty()) {
                if (use.credits < minCredits || use.id !in singles[donor.id].orEmpty()) continue
                if (budget[0] <= 0) return null
                budget[0] -= 1
                val named = singleMatch(use, ruleID) ?: continue
                val next = fork()
                next.release(use.id, donor.id)
                next.place(use.id, ruleID, named)
                if (next.choiceIsSatisfied(donor.id)) return next
                val refilled = next.adding(donor.id, next.shortfall(donor.id), steps - 1, avoiding + setOf(ruleID, donor.id), budget)
                if (refilled != null && refilled.choiceIsSatisfied(donor.id)) return refilled
            }
        }
        for (donor in rules) {
            if (donor.id in avoiding) continue
            val option = ((effective[donor.id] as? Effective.Kind)?.kind as? RequirementRule.Kind.Required)?.option ?: continue
            if (option.alternatives.size <= 1) continue
            val current = used[donor.id].orEmpty()
            if (current.isEmpty() || current.any { it.bySubstitution }) continue
            for (use in current) {
                if (use.credits < minCredits) continue
                if (budget[0] <= 0) return null
                budget[0] -= 1
                val named = singleMatch(use, ruleID) ?: continue
                val next = fork()
                next.releaseAll(donor.id)
                next.available.remove(use.id)
                val complete = option.alternatives.filter { next.isComplete(it) }
                val best = complete.minWithOrNull(next::compareScores) ?: continue
                val pairs = next.assign(best)
                next.available.add(use.id)
                for ((required, taken) in pairs) next.consume(taken, donor.id, satisfying = required)
                next.place(use.id, ruleID, named)
                return next
            }
        }
        return null
    }

    private fun singleMatch(use: DegreeAudit.CourseUse, ruleID: String): CourseKey? {
        val current = effective[ruleID] as? Effective.Kind ?: return null
        val kind = current.kind as? RequirementRule.Kind.Choose ?: return null
        if (kind.count == null && use.credits <= 0) return null
        val counted = used[ruleID].orEmpty().map { it.satisfies ?: it.key }.toSet()
        for (option in kind.options) {
            if (option.allCourses.any { it in counted }) continue
            for (alternative in option.alternatives) {
                if (alternative.size == 1 && equivalences.areEquivalent(alternative[0], use.key)) return alternative[0]
            }
        }
        if (use.credits <= 0 || (kind.count != null && use.credits < 2)) return null
        if (current.anyCourse) return use.key
        if (kind.ranges.isEmpty()) return null
        return courseInRange(use.key, kind.ranges)
    }

    private fun choiceIsSatisfied(ruleID: String): Boolean {
        val kind = (effective[ruleID] as? Effective.Kind)?.kind as? RequirementRule.Kind.Choose ?: return true
        return isSatisfied(ruleID, kind.count, kind.hours)
    }

    private fun shortfall(ruleID: String): Double {
        val kind = (effective[ruleID] as? Effective.Kind)?.kind as? RequirementRule.Kind.Choose ?: return 0.0
        val hours = kind.hours ?: return 0.0
        if (kind.count != null) return 0.0
        return max(0.0, hours - used[ruleID].orEmpty().sumOf { it.credits })
    }

    private fun place(id: String, ruleID: String, named: CourseKey) {
        consume(id, ruleID, satisfying = named)
        optionsCounted[ruleID] = (optionsCounted[ruleID] ?: 0) + 1
        singles.getOrPut(ruleID) { mutableSetOf() }.add(id)
    }

    private fun release(id: String, ruleID: String) {
        val list = used[ruleID] ?: return
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list.removeAt(index)
        optionsCounted[ruleID] = (optionsCounted[ruleID] ?: 0) - 1
        singles[ruleID]?.remove(id)
        available.add(id)
    }

    private fun releaseAll(ruleID: String) {
        for (use in used[ruleID].orEmpty()) available.add(use.id)
        used[ruleID] = mutableListOf()
        optionsCounted[ruleID] = 0
        singles[ruleID] = mutableSetOf()
    }

    private fun isSatisfied(ruleID: String, count: Int?, hours: Double?): Boolean {
        val counted = optionsCounted[ruleID] ?: 0
        if (count != null) return counted >= count
        if (hours != null) return used[ruleID].orEmpty().sumOf { it.credits } >= hours
        return counted >= 1
    }

    private fun score(alternative: List<CourseKey>): Pair<Int, Int> {
        val taken = assign(alternative).mapNotNull { pool[it.second] }
        return taken.count { !it.isCompleted } to taken.sumOf { contest(it.key) }
    }

    private fun compareScores(a: List<CourseKey>, b: List<CourseKey>): Int {
        val left = score(a)
        val right = score(b)
        if (left.first != right.first) return left.first - right.first
        return left.second - right.second
    }

    private fun contest(key: CourseKey) = equivalences.group(key).sumOf { demand[it] ?: 0 }

    private fun consume(id: String, ruleID: String, bySubstitution: Boolean = false, satisfying: CourseKey? = null) {
        if (id !in available) return
        val original = pool[id] ?: return
        val use = original.copy(
            bySubstitution = bySubstitution,
            satisfies = if (satisfying == null || satisfying == original.key) null else satisfying,
        )
        available.remove(id)
        used.getOrPut(ruleID) { mutableListOf() }.add(use)
    }

    private fun standIn(required: CourseKey, claimed: Set<String> = emptySet()): String? {
        fun free(id: String) = id in available && id !in claimed
        instances[required].orEmpty().filter(::free).minWithOrNull(::comesFirst)?.let { return it }
        if (equivalences.isEmpty) return null
        return equivalences.equivalents(required).flatMap { instances[it].orEmpty() }.filter(::free).minWithOrNull(::comesFirst)
    }

    private fun comesFirst(a: String, b: String): Int {
        fun rank(id: String) = (if (pool[id]?.isCompleted == true) 0 else 1) to (pool[id]?.order ?: Int.MAX_VALUE)
        val left = rank(a)
        val right = rank(b)
        val compared = when {
            left.first != right.first -> left.first - right.first
            left.second != right.second -> left.second - right.second
            else -> 0
        }
        return if (compared != 0) compared else a.compareTo(b)
    }

    private fun assign(alternative: List<CourseKey>): List<Pair<CourseKey, String>> {
        val named = HashSet<CourseKey>()
        val claimed = HashSet<String>()
        val matches = mutableListOf<Pair<CourseKey, String>>()
        for (required in alternative) {
            if (!named.add(required)) continue
            val taken = standIn(required, claimed) ?: continue
            claimed += taken
            matches += required to taken
        }
        return matches
    }

    private fun isComplete(alternative: List<CourseKey>) = assign(alternative).size == alternative.toSet().size

    private fun courseInRange(key: CourseKey, ranges: List<CourseRange>): CourseKey? {
        if (ranges.any { it.contains(key) }) return key
        return equivalences.equivalents(key).sorted().firstOrNull { equivalent -> ranges.any { it.contains(equivalent) } }
    }

    private fun remaining(): List<DegreeAudit.CourseUse> =
        available.mapNotNull { pool[it] }.sortedWith(compareBy({ if (it.isCompleted) 0 else 1 }, { it.order }))

    private fun result(rule: RequirementRule): DegreeAudit.RuleResult {
        val uses = used[rule.id].orEmpty()
        val subs = substitutionsByRule[rule.id].orEmpty()
        val current = effective[rule.id]
        if (current !is Effective.Kind) {
            return DegreeAudit.RuleResult(rule, null, DegreeAudit.Status.WAIVED, emptyList(), substitutions = subs)
        }
        val hoursDone = uses.sumOf { it.credits }
        var remainingCount: Int? = null
        var remainingHours: Double? = null
        val satisfied = when (val kind = current.kind) {
            is RequirementRule.Kind.Required -> {
                val keys = uses.map { it.satisfies ?: it.key }.toSet()
                uses.any { it.bySubstitution } || kind.option.alternatives.any { alt -> alt.all { it in keys } }
            }
            is RequirementRule.Kind.Choose -> {
                val met = isSatisfied(rule.id, kind.count, kind.hours)
                if (kind.count != null) remainingCount = max(0, kind.count - (optionsCounted[rule.id] ?: 0))
                else if (kind.hours != null) remainingHours = max(0.0, kind.hours - hoursDone)
                else remainingCount = if (met) 0 else 1
                met
            }
            is RequirementRule.Kind.GeneralElectives -> {
                remainingHours = max(0.0, kind.hours - hoursDone)
                hoursDone >= kind.hours
            }
        }
        val status = when {
            satisfied && uses.all { it.isCompleted } -> DegreeAudit.Status.COMPLETE
            satisfied -> DegreeAudit.Status.PLANNED
            uses.isEmpty() -> DegreeAudit.Status.MISSING
            else -> DegreeAudit.Status.PARTIAL
        }
        return DegreeAudit.RuleResult(rule, current.kind, status, uses, remainingCount, remainingHours, subs)
    }

    private fun fork(): AuditEngine {
        val copy = AuditEngine(plan, requirements, equivalences, attributes)
        copy.pool.clear()
        copy.pool.putAll(pool)
        copy.instances.clear()
        instances.forEach { (key, ids) -> copy.instances[key] = ids.toMutableList() }
        copy.available.clear()
        copy.available.addAll(available)
        copy.used.clear()
        used.forEach { (id, list) -> copy.used[id] = list.toMutableList() }
        copy.optionsCounted.clear()
        copy.optionsCounted.putAll(optionsCounted)
        copy.effective.clear()
        copy.effective.putAll(effective)
        copy.demand.clear()
        copy.demand.putAll(demand)
        copy.singles.clear()
        singles.forEach { (id, set) -> copy.singles[id] = set.toMutableSet() }
        copy.unmatched.clear()
        copy.unmatched += unmatched
        return copy
    }

    private fun replaceWith(next: AuditEngine) {
        available.clear()
        available.addAll(next.available)
        used.clear()
        next.used.forEach { (id, list) -> used[id] = list.toMutableList() }
        optionsCounted.clear()
        optionsCounted.putAll(next.optionsCounted)
        singles.clear()
        next.singles.forEach { (id, set) -> singles[id] = set.toMutableSet() }
    }

    private fun isMet(ruleID: String): Boolean = when (val current = effective[ruleID]) {
        is Effective.Kind -> when (val kind = current.kind) {
            is RequirementRule.Kind.Required -> {
                val uses = used[ruleID].orEmpty()
                val keys = uses.map { it.satisfies ?: it.key }.toSet()
                uses.any { it.bySubstitution } || kind.option.alternatives.any { alt -> alt.all { it in keys } }
            }
            is RequirementRule.Kind.Choose -> isSatisfied(ruleID, kind.count, kind.hours)
            is RequirementRule.Kind.GeneralElectives -> used[ruleID].orEmpty().sumOf { it.credits } >= kind.hours
        }
        else -> true
    }

    // MARK: Exact matching

    private data class CourseBits(val low: Long = 0, val high: Long = 0) {
        fun contains(other: CourseBits) = (low and other.low) == other.low && (high and other.high) == other.high
        fun intersects(other: CourseBits) = (low and other.low) != 0L || (high and other.high) != 0L
        fun has(index: Int) = if (index < 64) (low and (1L shl index)) != 0L else (high and (1L shl (index - 64))) != 0L
        fun union(other: CourseBits) = CourseBits(low or other.low, high or other.high)
        fun subtracting(other: CourseBits) = CourseBits(low and other.low.inv(), high and other.high.inv())

        companion object {
            fun of(index: Int) = if (index < 64) CourseBits(1L shl index, 0) else CourseBits(0, 1L shl (index - 64))
        }
    }

    private class Unit(
        val courses: List<String>,
        val named: List<CourseKey>,
        val bits: CourseBits,
        val option: Int,
        val credits: Double,
        val isCompleted: Boolean,
        val order: Int,
    )

    private class Need(val ruleID: String, val amount: Amount, val units: List<Unit>) {
        sealed class Amount {
            data object Alternative : Amount()
            data class Count(val count: Int) : Amount()
            data class Hours(val hours: Double) : Amount()
        }
    }

    private data class SearchKey(val depth: Int, val low: Long, val high: Long)

    private fun ensureBestMatching(): Boolean {
        val pinned = used.values.flatten().filter { it.bySubstitution }.map { it.id }.toSet()
        val free = pool.keys.filter { it !in pinned }.sortedWith(::comesFirst)
        if (free.isEmpty() || free.size > 128) return false
        val index = free.withIndex().associate { it.value to it.index }
        val credits = free.map { maxOf(0.0, pool[it]?.credits ?: 0.0) }
        var all = CourseBits()
        for (i in free.indices) all = all.union(CourseBits.of(i))

        val needs = mutableListOf<Need>()
        val electives = mutableListOf<Double>()
        for (rule in rules) {
            when (val current = effective[rule.id]) {
                is Effective.Kind -> when (val kind = current.kind) {
                    is RequirementRule.Kind.GeneralElectives -> {
                        val counted = used[rule.id].orEmpty().filter { it.bySubstitution }.sumOf { it.credits }
                        if (counted < kind.hours) electives += kind.hours - counted
                    }
                    else -> need(rule.id, kind, current.anyCourse, free, index)?.let { needs += it }
                }
                else -> Unit
            }
        }
        val electiveScale = rules.size + 1
        fun electivesMet(bits: CourseBits): Int {
            if (electives.isEmpty()) return 0
            var left = free.indices.sumOf { i -> if (bits.has(i)) credits[i] else 0.0 }
            var met = 0
            for (hours in electives) if (left >= hours) {
                met++
                left -= hours
            }
            return met
        }
        var now = CourseBits()
        for (id in available) index[id]?.let { now = now.union(CourseBits.of(it)) }
        val currentSpecific = needs.count { isMet(it.ruleID) }
        val currentElectives = electivesMet(now)
        val current = currentSpecific * electiveScale + currentElectives
        val mayImprove = needs.any { !isMet(it.ruleID) && canStillMeet(it, all, credits) } || electivesMet(all) > electivesMet(now)
        if (!mayImprove) return false

        val order = needs.indices.sortedWith(compareBy({ fills(needs[it], all, 400).size }, { it }))
        var best: Map<Int, List<Int>>? = null
        var bestScore = current
        var bestSpecific = currentSpecific
        var bestElectives = currentElectives
        val chosen = HashMap<Int, List<Int>>()
        val visited = HashMap<SearchKey, Int>()
        var nodes = 0

        fun visit(depth: Int, bits: CourseBits, met: Int) {
            nodes++
            if (nodes > 20_000) return
            val key = SearchKey(depth, bits.low, bits.high)
            val earlier = visited[key]
            if (earlier != null && earlier >= met) return
            visited[key] = met
            var optimistic = met
            for (later in order.drop(depth)) if (canStillMeet(needs[later], bits, credits)) optimistic++
            val electivesAtBest = electivesMet(bits)
            if (optimistic * electiveScale + electivesAtBest <= bestScore) return
            if (depth >= order.size) {
                bestScore = optimistic * electiveScale + electivesAtBest
                bestSpecific = optimistic
                bestElectives = electivesAtBest
                best = chosen.toMap()
                return
            }
            val n = order[depth]
            for (fill in fills(needs[n], bits, 400)) {
                val taken = fill.fold(CourseBits()) { acc, unit -> acc.union(needs[n].units[unit].bits) }
                chosen[n] = fill
                visit(depth + 1, bits.subtracting(taken), met + 1)
                chosen.remove(n)
                if (nodes > 20_000) return
            }
            visit(depth + 1, bits, met)
        }
        visit(0, all, 0)
        val found = best ?: return false
        val meetsEverything = bestSpecific == needs.size && bestElectives == electives.size
        if (bestSpecific <= currentSpecific && !meetsEverything) return false
        adopt(found, needs, free)
        return true
    }

    private fun need(
        ruleID: String,
        kind: RequirementRule.Kind,
        anyCourse: Boolean,
        free: List<String>,
        index: Map<String, Int>,
    ): Need? {
        val pinned = used[ruleID].orEmpty().filter { it.bySubstitution }
        return when (kind) {
            is RequirementRule.Kind.Required -> {
                if (pinned.isNotEmpty()) null
                else {
                    val units = kind.option.alternatives.flatMap { alternative ->
                        assignments(alternative, free).map { unit(it, 0, index) }
                    }.sortedWith(::preferUnit)
                    Need(ruleID, Need.Amount.Alternative, units)
                }
            }
            is RequirementRule.Kind.Choose -> {
                val counted = pinned.map { it.satisfies ?: it.key }.toSet()
                val units = mutableListOf<Unit>()
                kind.options.forEachIndexed { number, option ->
                    if (option.allCourses.any { it in counted }) return@forEachIndexed
                    for (alternative in option.alternatives) units += assignments(alternative, free).map { unit(it, number, index) }
                }
                if (kind.ranges.isNotEmpty() || anyCourse) {
                    var tag = kind.options.size
                    for (id in free) {
                        val use = pool[id] ?: continue
                        if (use.credits <= 0 || (kind.count != null && use.credits < 2)) continue
                        val named = if (anyCourse) use.key else courseInRange(use.key, kind.ranges) ?: continue
                        units += unit(listOf(named to id), tag, index)
                        tag++
                    }
                }
                val amount = when {
                    kind.count != null -> {
                        val left = kind.count - pinned.size
                        if (left <= 0) return null
                        Need.Amount.Count(left)
                    }
                    kind.hours != null -> {
                        val left = kind.hours - pinned.sumOf { it.credits }
                        if (left <= 0) return null
                        units.removeAll { it.credits <= 0 }
                        Need.Amount.Hours(left)
                    }
                    else -> {
                        if (pinned.isNotEmpty()) return null
                        Need.Amount.Count(1)
                    }
                }
                Need(ruleID, amount, units.sortedWith(::preferUnit))
            }
            is RequirementRule.Kind.GeneralElectives -> null
        }
    }

    private fun assignments(alternative: List<CourseKey>, free: List<String>): List<List<Pair<CourseKey, String>>> {
        val freeSet = free.toSet()
        val seen = HashSet<CourseKey>()
        val named = alternative.filter { seen.add(it) }
        val results = mutableListOf<List<Pair<CourseKey, String>>>()
        val current = mutableListOf<Pair<CourseKey, String>>()
        val claimed = HashSet<String>()
        fun extend(position: Int) {
            if (results.size >= 4) return
            if (position >= named.size) {
                results += current.toList()
                return
            }
            val key = named[position]
            val candidates = instances[key].orEmpty() + equivalences.equivalents(key).sorted().flatMap { instances[it].orEmpty() }
            for (id in candidates) {
                if (id !in freeSet || id in claimed) continue
                current += key to id
                claimed += id
                extend(position + 1)
                claimed -= id
                current.removeAt(current.lastIndex)
            }
        }
        extend(0)
        return results
    }

    private fun unit(pairs: List<Pair<CourseKey, String>>, option: Int, index: Map<String, Int>): Unit {
        val uses = pairs.mapNotNull { pool[it.second] }
        var bits = CourseBits()
        for (pair in pairs) index[pair.second]?.let { bits = bits.union(CourseBits.of(it)) }
        return Unit(
            pairs.map { it.second },
            pairs.map { it.first },
            bits,
            option,
            uses.sumOf { maxOf(0.0, it.credits) },
            uses.all { it.isCompleted },
            uses.minOfOrNull { it.order } ?: 0,
        )
    }

    private fun preferUnit(a: Unit, b: Unit): Int {
        val completed = (if (a.isCompleted) 0 else 1) - (if (b.isCompleted) 0 else 1)
        if (completed != 0) return completed
        if (a.courses.size != b.courses.size) return a.courses.size - b.courses.size
        return a.order - b.order
    }

    private fun fills(need: Need, availableBits: CourseBits, limit: Int): List<List<Int>> {
        val usable = need.units.indices.filter { availableBits.contains(need.units[it].bits) }
        if (need.amount is Need.Amount.Alternative) return usable.take(limit).map { listOf(it) }
        val result = mutableListOf<List<Int>>()
        val chosen = mutableListOf<Int>()
        when (val amount = need.amount) {
            is Need.Amount.Count -> {
                fun pick(start: Int, taken: CourseBits, options: Set<Int>) {
                    if (chosen.size == amount.count) {
                        result += chosen.toList()
                        return
                    }
                    if (usable.size - start < amount.count - chosen.size) return
                    for (position in start until usable.size) {
                        if (result.size >= limit) return
                        val unit = need.units[usable[position]]
                        if (taken.intersects(unit.bits) || unit.option in options) continue
                        chosen += usable[position]
                        pick(position + 1, taken.union(unit.bits), options + unit.option)
                        chosen.removeAt(chosen.lastIndex)
                    }
                }
                pick(0, CourseBits(), emptySet())
            }
            is Need.Amount.Hours -> {
                val later = DoubleArray(usable.size + 1)
                for (position in usable.indices.reversed()) later[position] = later[position + 1] + need.units[usable[position]].credits
                fun pick(start: Int, taken: CourseBits, options: Set<Int>, creditsSoFar: Double) {
                    if (creditsSoFar >= amount.hours) {
                        if (chosen.all { creditsSoFar - need.units[it].credits < amount.hours }) result += chosen.toList()
                        return
                    }
                    if (creditsSoFar + later[start] < amount.hours) return
                    for (position in start until usable.size) {
                        if (result.size >= limit) return
                        val unit = need.units[usable[position]]
                        if (taken.intersects(unit.bits) || unit.option in options) continue
                        chosen += usable[position]
                        pick(position + 1, taken.union(unit.bits), options + unit.option, creditsSoFar + unit.credits)
                        chosen.removeAt(chosen.lastIndex)
                    }
                }
                pick(0, CourseBits(), emptySet(), 0.0)
            }
            Need.Amount.Alternative -> Unit
        }
        return result
    }

    private fun canStillMeet(need: Need, bits: CourseBits, credits: List<Double>): Boolean = when (val amount = need.amount) {
        Need.Amount.Alternative -> need.units.any { bits.contains(it.bits) }
        is Need.Amount.Count -> {
            val options = HashSet<Int>()
            var enough = false
            for (unit in need.units) {
                if (!bits.contains(unit.bits)) continue
                options += unit.option
                if (options.size >= amount.count) {
                    enough = true
                    break
                }
            }
            enough
        }
        is Need.Amount.Hours -> {
            var covered = CourseBits()
            for (unit in need.units) if (bits.contains(unit.bits)) covered = covered.union(unit.bits)
            credits.indices.sumOf { if (covered.has(it)) credits[it] else 0.0 } >= amount.hours
        }
    }

    private fun adopt(fills: Map<Int, List<Int>>, needs: List<Need>, free: List<String>) {
        for (need in needs) {
            val pinnedUses = used[need.ruleID].orEmpty().filter { it.bySubstitution }
            used[need.ruleID] = pinnedUses.toMutableList()
            optionsCounted[need.ruleID] = pinnedUses.size
            singles[need.ruleID] = mutableSetOf()
        }
        available.clear()
        available.addAll(free)
        for ((n, chosen) in fills) {
            val ruleID = needs[n].ruleID
            for (position in chosen) {
                val unit = needs[n].units[position]
                for ((named, id) in unit.named.zip(unit.courses)) consume(id, ruleID, satisfying = named)
                optionsCounted[ruleID] = (optionsCounted[ruleID] ?: 0) + 1
                if (unit.courses.size == 1) singles.getOrPut(ruleID) { mutableSetOf() }.add(unit.courses[0])
            }
        }
    }

    // MARK: Note checks

    private fun noteChecks(): List<DegreeAudit.NoteCheck> {
        val checks = mutableListOf<DegreeAudit.NoteCheck>()
        for (section in sections) {
            val notes = section.notes + section.groups.filter { it.rules.isEmpty() }.flatMap { it.notes }
            for (note in notes) {
                val major = majorHours(note)
                when {
                    major != null -> checks += majorHoursCheck(section.id, note, major)
                    isCoopNote(note) -> checks += coopCheck(section.id, note)
                    isNupathNote(note) -> nupathCheck(section.id, note)?.let { checks += it }
                    isUniversitywideNote(note) -> universitywideCheck(section.id, note)?.let { checks += it }
                    totalHours(note) != null -> checks += totalHoursCheck(section.id, note, totalHours(note)!!)
                }
            }
        }
        return checks
    }

    private fun majorHoursCheck(sectionID: String, note: String, required: Double): DegreeAudit.NoteCheck {
        val counted = LinkedHashMap<String, DegreeAudit.CourseUse>()
        val ranges = mutableListOf<CourseRange>()
        for (section in sections) for (group in section.groups) {
            val writing = section.title.contains("writing", true) || (group.title ?: "").contains("writing", true)
            for (rule in group.rules) {
                val kind = (effective[rule.id] as? Effective.Kind)?.kind ?: continue
                if (kind is RequirementRule.Kind.GeneralElectives) continue
                if (kind is RequirementRule.Kind.Choose) ranges += kind.ranges
                if (writing) continue
                for (use in used[rule.id].orEmpty()) counted[use.id] = use
            }
        }
        for (use in pool.values) if (use.id !in counted && courseInRange(use.key, ranges) != null) counted[use.id] = use
        val completed = counted.values.filter { it.isCompleted }.sumOf { maxOf(0.0, it.credits) }
        val planned = counted.values.filter { !it.isCompleted }.sumOf { maxOf(0.0, it.credits) }
        val (status, detail) = hoursStatus(completed, planned, required)
        return DegreeAudit.NoteCheck(sectionID, note, DegreeAudit.NoteCheck.Kind.MajorHours(required), status, detail)
    }

    private fun coopCheck(sectionID: String, note: String): DegreeAudit.NoteCheck {
        val coops = pool.values.filter { isCoop(it) }.sortedBy { it.order }
        val done = coops.firstOrNull { it.isCompleted }
        val next = coops.firstOrNull()
        var status: DegreeAudit.Status
        var detail: String
        when {
            done != null -> {
                status = DegreeAudit.Status.COMPLETE
                detail = "${done.key.description} completed"
            }
            next != null -> {
                status = DegreeAudit.Status.PLANNED
                detail = "${next.key.description} planned${next.semester?.let { " · $it" } ?: ""}"
            }
            else -> {
                status = DegreeAudit.Status.MISSING
                detail = "No co-op in the plan"
                val path = NUpathRequirement.satisfiedThroughCoop(note)
                val course = if (path == null) null else sortedPool().filter { path in (nupaths(it) ?: emptySet()) }
                    .minWithOrNull(compareBy<DegreeAudit.CourseUse> { if (it.isCompleted) 0 else 1 }.thenBy { it.order })
                if (course != null && path != null) {
                    status = if (course.isCompleted) DegreeAudit.Status.COMPLETE else DegreeAudit.Status.PLANNED
                    detail = "No co-op in the plan · ${course.key.description} (${path.short}) ${if (course.isCompleted) "completed" else "planned"}"
                }
            }
        }
        return DegreeAudit.NoteCheck(sectionID, note, DegreeAudit.NoteCheck.Kind.Coop, status, detail)
    }

    private fun nupathCheck(sectionID: String, note: String): DegreeAudit.NoteCheck? {
        if (attributes.isEmpty) return null
        val uses = sortedPool()
        val courses = mutableListOf<NUpathMatching.Course>()
        val unknown = mutableListOf<DegreeAudit.CourseUse>()
        for (use in uses) {
            val paths = nupaths(use)
            if (paths == null) unknown += use
            else if (paths.isNotEmpty()) courses += NUpathMatching.Course(use.id, paths, use.isCompleted)
        }
        val coops = uses.filter { isCoop(it) }.map { NUpathMatching.Course(it.id, emptySet(), it.isCompleted) }
        val coopCounts = HashMap<NUPath, List<NUpathMatching.Course>>()
        if (coops.isNotEmpty()) {
            for (section in requirements.sections) for (text in section.notes + section.groups.flatMap { it.notes }) {
                NUpathRequirement.satisfiedThroughCoop(text)?.let { coopCounts[it] = coops }
            }
        }
        val matching = NUpathMatching(courses, coopCounts = coopCounts)
        val isLoading = unknown.any { it.key in attributes.loading }
        val items = NUpathRequirement.all.map { requirement ->
            val chosen = matching.courses(requirement.path).mapNotNull { pool[it] }
            val status: DegreeAudit.Status?
            val detail: String
            if (chosen.size >= requirement.count) {
                status = if (chosen.all { it.isCompleted }) DegreeAudit.Status.COMPLETE else DegreeAudit.Status.PLANNED
                detail = describe(chosen)
            } else {
                status = if (unknown.isEmpty()) {
                    if (chosen.isEmpty()) DegreeAudit.Status.MISSING else DegreeAudit.Status.PARTIAL
                } else null
                val found = if (chosen.isEmpty()) "" else "${chosen.size} of ${requirement.count} · ${describe(chosen)}"
                detail = when {
                    status == null && found.isEmpty() -> if (isLoading) "Looking up the plan’s courses…" else "Not found among the courses with NUpath data"
                    status == null -> found
                    chosen.isEmpty() -> if (requirement.count == 1) "No course in the plan has it" else "Needs ${requirement.count} courses"
                    else -> found
                }
            }
            DegreeAudit.NoteItem(requirement.path.attributeCode, requirement.title, status, detail, chosen.map { it.id })
        }
        val met = items.filter { it.status?.isSatisfied == true }
        val open = items.filter { it.status?.isSatisfied != true }
        val status = when {
            open.isEmpty() -> if (met.all { it.status == DegreeAudit.Status.COMPLETE }) DegreeAudit.Status.COMPLETE else DegreeAudit.Status.PLANNED
            met.isEmpty() && items.all { it.courses.isEmpty() } -> DegreeAudit.Status.MISSING
            else -> DegreeAudit.Status.PARTIAL
        }
        var detail = "${met.size} of ${items.size} met"
        val missing = items.filter { it.status == DegreeAudit.Status.MISSING || it.status == DegreeAudit.Status.PARTIAL }
        if (missing.isNotEmpty() && missing.size <= 3) {
            detail += " · missing " + missing.joinToString(", ") { item ->
                NUPath.entries.firstOrNull { it.attributeCode == item.id }?.short ?: item.title
            }
        }
        if (unknown.isNotEmpty() && items.any { it.status == null }) {
            val names = unknown.take(3).joinToString(", ") { it.key.description }
            detail += if (isLoading) {
                " · looking up " + if (unknown.size == 1) "1 course" else "${unknown.size} courses"
            } else {
                " · no NUpath data for $names" + if (unknown.size > 3) " and ${unknown.size - 3} more" else ""
            }
        }
        return DegreeAudit.NoteCheck(
            sectionID, note, DegreeAudit.NoteCheck.Kind.Nupath, status, detail, items,
            counts = items.none { it.status == null },
        )
    }

    private fun universitywideCheck(sectionID: String, note: String): DegreeAudit.NoteCheck? {
        val minimums = requirements.universitywide ?: return null
        val completed = pool.values.filter { it.isCompleted }.sumOf { maxOf(0.0, it.credits) }
        val planned = pool.values.filter { !it.isCompleted }.sumOf { maxOf(0.0, it.credits) }
        val items = mutableListOf<DegreeAudit.NoteItem>()
        minimums.totalHours?.let { total ->
            val (status, detail) = hoursStatus(completed, planned, total)
            items += DegreeAudit.NoteItem("total-hours", "${formatHours(total)} total semester hours", status, detail)
        }
        if (minimums.requiresExperientialLearning) {
            val coops = sortedPool().filter { isCoop(it) }
            val coop = coops.firstOrNull { it.isCompleted } ?: coops.firstOrNull()
            items += DegreeAudit.NoteItem(
                "experiential",
                "Experiential learning",
                coop?.let { if (it.isCompleted) DegreeAudit.Status.COMPLETE else DegreeAudit.Status.PLANNED },
                coop?.let { describe(listOf(it)) } ?: "Not checked: only co-ops are recognized",
                coop?.let { listOf(it.id) } ?: emptyList(),
            )
        }
        minimums.gpa?.let { gpa ->
            items += DegreeAudit.NoteItem("gpa", "${formatGpa(gpa)} GPA", null, "Not checked: plans don’t include grades")
        }
        minimums.residencyHours?.let { residency ->
            items += DegreeAudit.NoteItem(
                "residency",
                "${formatHours(residency)} semester hours at Northeastern",
                null,
                "Not checked: a plan doesn’t say where credits were earned",
            )
        }
        val checked = items.filter { it.status != null }
        val status = checked.mapNotNull { it.status }.minByOrNull { it.ordinal } ?: return null
        val names = mapOf("experiential" to "experiential learning", "gpa" to "GPA", "residency" to "residency")
        val unchecked = items.filter { it.status == null }.mapNotNull { names[it.id] }
        var detail = checked.joinToString(" · ") { it.detail }
        if (unchecked.isNotEmpty()) detail += " · ${englishList(unchecked)} not checked"
        return DegreeAudit.NoteCheck(sectionID, note, DegreeAudit.NoteCheck.Kind.Universitywide, status, detail, items)
    }

    private fun totalHoursCheck(sectionID: String, note: String, required: Double): DegreeAudit.NoteCheck {
        val completed = pool.values.filter { it.isCompleted }.sumOf { maxOf(0.0, it.credits) }
        val planned = pool.values.filter { !it.isCompleted }.sumOf { maxOf(0.0, it.credits) }
        val (status, detail) = hoursStatus(completed, planned, required)
        return DegreeAudit.NoteCheck(sectionID, note, DegreeAudit.NoteCheck.Kind.TotalHours(required), status, detail)
    }

    private fun nupaths(use: DegreeAudit.CourseUse): Set<NUPath>? {
        attributes.nupaths[use.key]?.let { return it }
        if (isCoop(use)) return emptySet()
        for (other in equivalences.group(use.key)) if (other != use.key) attributes.nupaths[other]?.let { return it }
        return null
    }

    private fun sortedPool() = pool.values.sortedBy { it.order }

    private fun isCoop(use: DegreeAudit.CourseUse) =
        use.key.subject == "COOP" || Regex("""(?i)co-?op\s+(?:work\s+)?experience|cooperative\s+education""").containsMatchIn(use.title)

    companion object {
        private fun isNupathNote(note: String) =
            Regex("""(?i)complete\s+(?:the\s+)?NUpath\s+requirements""").containsMatchIn(note)

        private fun isUniversitywideNote(note: String) =
            Regex("""(?i)complete\s+(?:the\s+)?university-?\s?wide\s+requirements""").containsMatchIn(note)

        private fun isCoopNote(note: String) = DegreeAudit.isCoopNote(note)

        private fun majorHours(note: String) =
            Regex("""(?i)(\d+(?:\.\d+)?)\s+(?:semester\s+)?(?:credit\s+)?hours\s+(?:in|within)\s+the\s+major""")
                .find(note)?.groupValues?.get(1)?.toDoubleOrNull()

        private fun totalHours(note: String) =
            Regex("""(?i)(\d+(?:\.\d+)?)\s+total\s+semester\s+hours\s+required""")
                .find(note)?.groupValues?.get(1)?.toDoubleOrNull()

        private fun hoursStatus(completed: Double, planned: Double, required: Double): Pair<DegreeAudit.Status, String> {
            val status = when {
                completed >= required -> DegreeAudit.Status.COMPLETE
                completed + planned >= required -> DegreeAudit.Status.PLANNED
                completed + planned > 0 -> DegreeAudit.Status.PARTIAL
                else -> DegreeAudit.Status.MISSING
            }
            var detail = "${formatHours(completed + planned)} of ${formatHours(required)} semester hours"
            if (planned > 0) detail += " · ${formatHours(planned)} planned"
            return status to detail
        }

        private fun describe(uses: List<DegreeAudit.CourseUse>) = uses.joinToString(" · ") { use ->
            if (use.isCompleted) "${use.key.description} completed" else "${use.key.description} planned${use.semester?.let { " · $it" } ?: ""}"
        }
    }
}
