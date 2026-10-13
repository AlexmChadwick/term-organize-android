// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.degree

import app.termorganize.core.model.CourseEquivalences
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CreditFormatter
import app.termorganize.core.model.Requisite
import app.termorganize.logic.offering.OfferingHistory

/**
 * What a course needs before it. Unknown is distinct from "this course has no prerequisites".
 */
sealed class PrerequisiteLookup {
    data object Unknown : PrerequisiteLookup()
    data object None : PrerequisiteLookup()
    data class Needs(val requisite: Requisite) : PrerequisiteLookup()
}

/**
 * Ways to reorder a degree plan when a semester can't be scheduled because some of its courses
 * aren't taught then: move a course later, swap it with a later course that is taught now, or
 * add a semester. Pure function of the plan.
 */
object DegreePlanReordering {
    data class Move(val course: CourseKey, val from: String, val to: String)

    data class Suggestion(
        val id: String,
        val moves: List<Move>,
        val title: String,
        val reasons: List<String>,
        val addsSemester: DegreePlanTerm? = null,
    )

    const val MAX_SUGGESTIONS = 5

    fun suggestions(
        plan: DegreePlan,
        termId: String,
        blocking: List<CourseKey>,
        history: OfferingHistory,
        prerequisites: (CourseKey) -> PrerequisiteLookup,
        equivalences: CourseEquivalences = CourseEquivalences.none,
        maxCreditsPerTerm: Double = 20.0,
    ): List<Suggestion> {
        val sorted = plan.sorted()
        val origin = sorted.terms.indexOfFirst { it.id == termId }
        if (origin < 0) return emptyList()
        val requirements = HashMap<CourseKey, Requirement>()
        for (key in sorted.terms.flatMap { term -> term.courses.mapNotNull { it.key } }) {
            if (key in requirements) continue
            requirements[key] = when (val answer = prerequisites(key)) {
                PrerequisiteLookup.Unknown -> Requirement.Unknown
                PrerequisiteLookup.None -> Requirement.Nothing
                is PrerequisiteLookup.Needs -> Requirement.Needs(answer.requisite)
            }
        }
        val engine = Engine(
            sorted,
            origin,
            history,
            requirements,
            equivalences.merging(sorted.declaredEquivalences),
            maxCreditsPerTerm,
        )
        val candidates = ArrayList<Candidate>()
        val seen = HashSet<CourseKey>()
        for ((order, key) in blocking.withIndex()) {
            if (!seen.add(key)) continue
            val course = sorted.terms[origin].courses.firstOrNull { it.key == key } ?: continue
            candidates += engine.candidates(course, order, blocking.toSet())
        }
        candidates.sortBy { it.rank }
        return candidates.take(MAX_SUGGESTIONS).map { it.suggestion }
    }

    /** Adds the semester a suggestion needs, then moves each course. A stale suggestion changes nothing. */
    fun apply(suggestion: Suggestion, plan: DegreePlan): DegreePlan {
        var updated = plan
        val semesterId = HashMap<String, String>()
        val semester = suggestion.addsSemester
        if (semester != null) {
            val existing = updated.terms.firstOrNull { it.season == semester.season && it.year == semester.year }
            if (existing != null) semesterId[semester.id] = existing.id
            else updated = updated.copy(terms = updated.terms + semester).sorted()
        }
        for (move in suggestion.moves) {
            updated = performMove(move.course, move.from, semesterId[move.to] ?: move.to, updated) ?: return plan
        }
        return updated
    }

    private fun performMove(course: CourseKey, from: String, to: String, plan: DegreePlan): DegreePlan? {
        val terms = plan.terms.toMutableList()
        val source = terms.indexOfFirst { it.id == from }
        val target = terms.indexOfFirst { it.id == to }
        if (source < 0 || target < 0) return null
        val sourceCourses = terms[source].courses.toMutableList()
        val position = sourceCourses.indexOfFirst { it.key == course }
        if (position < 0) return null
        val moved = sourceCourses.removeAt(position)
        terms[source] = terms[source].copy(courses = sourceCourses)
        val targetCourses = terms[target].courses.toMutableList()
        targetCourses += moved
        terms[target] = terms[target].copy(courses = targetCourses)
        return plan.copy(terms = terms)
    }

    /** A UUID-shaped id that depends only on [text], so the same added semester has the same id. */
    fun stableId(text: String): String {
        var first = 0xcbf29ce484222325UL
        var second = 0x84222325cbf29ce4UL
        val prime = 0x100000001b3UL
        for (byte in text.encodeToByteArray()) {
            val value = byte.toUByte().toULong()
            first = (first xor value) * prime
            second = ((second + value) * prime) xor first
        }
        val hex = first.toString(16).padStart(16, '0') + second.toString(16).padStart(16, '0')
        val parts = listOf(
            hex.substring(0, 8),
            hex.substring(8, 12),
            hex.substring(12, 16),
            hex.substring(16, 20),
            hex.substring(20, 32),
        )
        return parts.joinToString("-").uppercase()
    }

    private sealed class Requirement {
        data object Unknown : Requirement()
        data object Nothing : Requirement()
        data class Needs(val requisite: Requisite) : Requirement()
    }

    private enum class Check { MET, MISSING, UNKNOWN }

    private data class Rank(
        val moves: Int,
        val target: Int,
        val uncertainty: Int,
        val order: Int,
        val detail: Int,
    ) : Comparable<Rank> {
        override fun compareTo(other: Rank): Int {
            moves.compareTo(other.moves).takeIf { it != 0 }?.let { return it }
            target.compareTo(other.target).takeIf { it != 0 }?.let { return it }
            uncertainty.compareTo(other.uncertainty).takeIf { it != 0 }?.let { return it }
            order.compareTo(other.order).takeIf { it != 0 }?.let { return it }
            return detail.compareTo(other.detail)
        }
    }

    private class Candidate(val suggestion: Suggestion, val rank: Rank)

    private class Engine(
        val plan: DegreePlan,
        val origin: Int,
        val history: OfferingHistory,
        val requirements: Map<CourseKey, Requirement>,
        val equivalences: CourseEquivalences,
        val maxCredits: Double,
    ) {
        val before: Map<String, Check> = evaluate(plan)

        private fun check(key: CourseKey, taken: Set<CourseKey>): Check = when (requirements[key] ?: Requirement.Unknown) {
            Requirement.Unknown -> Check.UNKNOWN
            Requirement.Nothing -> Check.MET
            is Requirement.Needs -> {
                val requisite = (requirements[key] as Requirement.Needs).requisite
                if (DegreePlanAnalysis.status(requisite, taken, equivalences) == DegreePlanAnalysis.PrerequisiteStatus.Met) Check.MET
                else Check.MISSING
            }
        }

        private fun evaluate(plan: DegreePlan): Map<String, Check> {
            val taken = plan.completedKeys.toMutableSet()
            val checks = HashMap<String, Check>()
            for (term in plan.terms) {
                for (course in term.courses) {
                    val key = course.key ?: continue
                    checks[course.id] = check(key, taken)
                }
                taken += term.courses.mapNotNull { it.key }
            }
            return checks
        }

        private fun checksAfter(changed: DegreePlan, moved: Set<String>): Map<String, Check>? {
            val after = evaluate(changed)
            for ((id, check) in after) {
                if (id in moved) {
                    if (check == Check.MISSING) return null
                } else if (before[id] == Check.MET && check == Check.MISSING) {
                    return null
                }
            }
            return after
        }

        private fun creditsLabel(credits: Double): String =
            "${CreditFormatter.number(credits)} ${if (credits == 1.0) "credit" else "credits"}"

        /** 1 when the history can't say; null when it says the course isn't taught then. */
        private fun uncertainty(key: CourseKey, season: PlanSeason): Int? = when (history.isLikelyOffered(key, season)) {
            false -> null
            true -> 0
            null -> 1
        }

        private fun offeringReason(key: CourseKey, season: PlanSeason, named: Boolean): String {
            val text = history.summary(key, season)
            return if (named) "${key.description}: ${text.replaceFirstChar { it.lowercase() }}" else text
        }

        private fun prerequisiteReason(key: CourseKey, check: Check, wasMet: Boolean, named: Boolean): String {
            val requirement = requirements[key] ?: Requirement.Unknown
            val text = when {
                check == Check.UNKNOWN -> "Prerequisites couldn’t be checked"
                requirement == Requirement.Nothing -> "No prerequisites"
                check == Check.MET -> if (wasMet) "Prerequisites still met" else "Prerequisites met by then"
                else -> "Prerequisites not met"
            }
            return if (named) "${key.description}: ${text.replaceFirstChar { it.lowercase() }}" else text
        }

        fun candidates(course: DegreePlanCourse, order: Int, blocking: Set<CourseKey>): List<Candidate> {
            val key = course.key ?: return emptyList()
            val found = ArrayList<Candidate>()
            for (target in (origin + 1) until maxOf(origin + 1, plan.terms.size)) {
                singleMove(course, key, target, order)?.let { found += it }
                for ((position, partner) in plan.terms[target].courses.withIndex()) {
                    val partnerKey = partner.key ?: continue
                    if (partnerKey in blocking || partnerKey == key) continue
                    swap(course, key, partner, partnerKey, target, order, position)?.let { found += it }
                }
            }
            if (found.isEmpty()) appendedSemester(course, key, order)?.let { found += it }
            return found
        }

        private fun singleMove(course: DegreePlanCourse, key: CourseKey, target: Int, order: Int): Candidate? {
            val semester = plan.terms[target]
            val offering = uncertainty(key, semester.season) ?: return null
            val total = semester.credits + course.credits
            if (total > maxCredits) return null
            val move = Move(key, plan.terms[origin].id, semester.id)
            val changed = performMove(key, move.from, move.to, plan) ?: return null
            val after = checksAfter(changed, setOf(course.id)) ?: return null
            val check = after[course.id] ?: Check.UNKNOWN
            val reasons = listOf(
                offeringReason(key, semester.season, named = false),
                prerequisiteReason(key, check, before[course.id] == Check.MET, named = false),
                "${semester.shortName} would have ${creditsLabel(total)}",
            )
            val suggestion = Suggestion(
                "move-${key.description}-${move.to}",
                listOf(move),
                "Move ${key.description} to ${semester.shortName}",
                reasons,
            )
            return Candidate(suggestion, Rank(1, target, offering + if (check == Check.UNKNOWN) 1 else 0, order, 0))
        }

        private fun swap(
            course: DegreePlanCourse,
            key: CourseKey,
            partner: DegreePlanCourse,
            partnerKey: CourseKey,
            target: Int,
            order: Int,
            detail: Int,
        ): Candidate? {
            val semester = plan.terms[target]
            val home = plan.terms[origin]
            val courseOffering = uncertainty(key, semester.season) ?: return null
            val partnerOffering = uncertainty(partnerKey, home.season) ?: return null
            val newHome = home.credits - course.credits + partner.credits
            val newTarget = semester.credits - partner.credits + course.credits
            if (newHome > maxOf(maxCredits, home.credits) || newTarget > maxOf(maxCredits, semester.credits)) return null
            val moves = listOf(
                Move(key, home.id, semester.id),
                Move(partnerKey, semester.id, home.id),
            )
            var changed = plan
            for (move in moves) changed = performMove(move.course, move.from, move.to, changed) ?: return null
            val after = checksAfter(changed, setOf(course.id, partner.id)) ?: return null
            val courseCheck = after[course.id] ?: Check.UNKNOWN
            val partnerCheck = after[partner.id] ?: Check.UNKNOWN
            val reasons = listOf(
                offeringReason(key, semester.season, named = true),
                offeringReason(partnerKey, home.season, named = true),
                prerequisiteReason(key, courseCheck, before[course.id] == Check.MET, named = true),
                prerequisiteReason(partnerKey, partnerCheck, before[partner.id] == Check.MET, named = true),
                "${home.shortName} would have ${creditsLabel(newHome)}",
                "${semester.shortName} would have ${creditsLabel(newTarget)}",
            )
            val suggestion = Suggestion(
                "swap-${key.description}-${semester.id}-${partnerKey.description}",
                moves,
                "Move ${key.description} to ${semester.shortName} and ${partnerKey.description} to ${home.shortName}",
                reasons,
            )
            val unsure = courseOffering + partnerOffering +
                (if (courseCheck == Check.UNKNOWN) 1 else 0) +
                (if (partnerCheck == Check.UNKNOWN) 1 else 0)
            return Candidate(suggestion, Rank(2, target, unsure, order, detail))
        }

        private fun appendedSemester(course: DegreePlanCourse, key: CourseKey, order: Int): Candidate? {
            if (course.credits > maxCredits) return null
            val home = plan.terms[origin]
            for (upcoming in DegreePlanAutofill.nextSemesters(plan, 8)) {
                if (upcoming.sortKey <= home.sortKey) continue
                val offering = uncertainty(key, upcoming.season) ?: continue
                val semester = DegreePlanTerm(
                    id = stableId("degree-plan-term-${swiftName(upcoming.season)}-${upcoming.year}"),
                    season = upcoming.season,
                    year = upcoming.year,
                )
                var changed = plan.copy(terms = plan.terms + semester).sorted()
                val move = Move(key, home.id, semester.id)
                changed = performMove(key, move.from, move.to, changed) ?: continue
                val after = checksAfter(changed, setOf(course.id)) ?: continue
                val check = after[course.id] ?: Check.UNKNOWN
                val reasons = listOf(
                    offeringReason(key, semester.season, named = false),
                    prerequisiteReason(key, check, before[course.id] == Check.MET, named = false),
                    "Adds ${semester.shortName} to your plan, with ${creditsLabel(course.credits)}",
                )
                val suggestion = Suggestion(
                    "move-${key.description}-${semester.id}",
                    listOf(move),
                    "Move ${key.description} to a new ${semester.shortName} semester",
                    reasons,
                    addsSemester = semester,
                )
                return Candidate(suggestion, Rank(1, plan.terms.size, offering + if (check == Check.UNKNOWN) 1 else 0, order, 0))
            }
            return null
        }
    }

    private fun swiftName(season: PlanSeason): String = when (season) {
        PlanSeason.WINTER -> "winter"
        PlanSeason.SPRING -> "spring"
        PlanSeason.SUMMER_A -> "summerA"
        PlanSeason.SUMMER_FULL -> "summerFull"
        PlanSeason.SUMMER_B -> "summerB"
        PlanSeason.FALL -> "fall"
    }
}
