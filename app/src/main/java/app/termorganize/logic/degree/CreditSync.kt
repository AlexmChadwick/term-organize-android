// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.degree

import app.termorganize.core.model.Course
import app.termorganize.core.model.TermCatalog
import app.termorganize.core.model.VariableCredits

/**
 * Chosen credits move with the matching row. A catalog refresh clamps a saved value into the
 * new range and does not put the default back.
 */
object CreditSync {
    fun setDegreeCredits(data: UserData, planId: String, courseId: String, credits: Double): UserData {
        val degree = data.degreePlans.firstOrNull { it.id == planId } ?: return data
        var touched: DegreePlanTerm? = null
        val updated = degree.copy(
            completed = degree.completed.map { if (it.id == courseId) it.withChosen(credits) else it },
            terms = degree.terms.map { term ->
                if (term.courses.none { it.id == courseId }) term
                else term.copy(courses = term.courses.map { if (it.id == courseId) it.withChosen(credits) else it })
                    .also { touched = it }
            },
        )
        val plans = touched?.let { pushTerm(data.plans, it) } ?: data.plans
        return data.copy(
            degreePlans = data.degreePlans.map { if (it.id == planId) updated else it },
            plans = plans,
        )
    }

    fun setScheduleCredits(data: UserData, planId: String, index: Int, credits: Double): UserData {
        val schedule = data.plans.firstOrNull { it.id == planId } ?: return data
        if (index !in schedule.courses.indices) return data
        val courses = schedule.courses.toMutableList()
        courses[index] = courses[index].withChosen(credits)
        val updated = schedule.copy(courses = courses)
        return data.copy(
            plans = data.plans.map { if (it.id == planId) updated else it },
            degreePlans = data.degreePlans.map { applySchedule(it, updated) },
        )
    }

    /**
     * Fills a missing choice with the Banner default and clamps a saved choice into the catalog range.
     * Returns the same instance when nothing changes.
     */
    fun clampSaved(data: UserData, catalog: TermCatalog): UserData {
        val byKey = catalog.courses.associateBy { it.key }
        var plansChanged = false
        val plans = data.plans.map { plan ->
            if (plan.termCode != catalog.term.code) return@map plan
            var changed = false
            val courses = plan.courses.map { row ->
                val next = clampSchedule(row, byKey[row.key])
                if (next != row) changed = true
                next
            }
            if (!changed) plan else {
                plansChanged = true
                plan.copy(courses = courses)
            }
        }
        var degreesChanged = false
        val degrees = data.degreePlans.map { degree ->
            var changed = false
            val terms = degree.terms.map { term ->
                if (!term.matches(catalog.term)) return@map term
                var termChanged = false
                val courses = term.courses.map { row ->
                    val listed = row.key?.let { byKey[it] }
                    val next = clampDegree(row, listed)
                    if (next != row) termChanged = true
                    next
                }
                if (!termChanged) term else {
                    changed = true
                    term.copy(courses = courses)
                }
            }
            if (!changed) degree else {
                degreesChanged = true
                degree.copy(terms = terms)
            }
        }
        if (!plansChanged && !degreesChanged) return data
        return data.copy(plans = plans, degreePlans = degrees)
    }

    private fun pushTerm(plans: List<SchedulePlan>, term: DegreePlanTerm): List<SchedulePlan> {
        val code = term.degreePlanBannerCode
        return plans.map { plan ->
            if (plan.termCode != code) plan
            else {
                val courses = plan.courses.toMutableList()
                var changed = false
                for ((scheduleIndex, degreeIndex) in aligned(plan.courses, term.courses)) {
                    val source = term.courses[degreeIndex]
                    val next = courses[scheduleIndex].copy(
                        credits = source.credits,
                        creditLow = source.creditLow,
                        creditHigh = source.creditHigh,
                    )
                    if (next != courses[scheduleIndex]) {
                        courses[scheduleIndex] = next
                        changed = true
                    }
                }
                if (changed) plan.copy(courses = courses) else plan
            }
        }
    }

    private fun applySchedule(degree: DegreePlan, schedule: SchedulePlan): DegreePlan {
        val termIndex = degree.terms.indexOfFirst { it.degreePlanBannerCode == schedule.termCode }
        if (termIndex < 0) return degree
        val term = degree.terms[termIndex]
        val courses = term.courses.toMutableList()
        var changed = false
        for ((scheduleIndex, degreeIndex) in aligned(schedule.courses, term.courses)) {
            val source = schedule.courses[scheduleIndex]
            val chosen = source.credits ?: continue
            val next = courses[degreeIndex].copy(
                credits = chosen,
                creditLow = source.creditLow ?: courses[degreeIndex].creditLow,
                creditHigh = source.creditHigh ?: courses[degreeIndex].creditHigh,
            )
            if (next != courses[degreeIndex]) {
                courses[degreeIndex] = next
                changed = true
            }
        }
        if (!changed) return degree
        val terms = degree.terms.toMutableList()
        terms[termIndex] = term.copy(courses = courses)
        return degree.copy(terms = terms)
    }

    private fun aligned(schedule: List<PlanCourse>, degree: List<DegreePlanCourse>): List<Pair<Int, Int>> {
        val used = HashSet<Int>()
        val pairs = mutableListOf<Pair<Int, Int>>()
        for ((degreeIndex, row) in degree.withIndex()) {
            val key = row.key ?: continue
            val scheduleIndex = schedule.indices.firstOrNull { index ->
                index !in used && schedule[index].key == key && schedule[index].manualCRN == row.manualCRN
            } ?: continue
            used += scheduleIndex
            pairs += scheduleIndex to degreeIndex
        }
        return pairs
    }

    private fun DegreePlanCourse.withChosen(credits: Double): DegreePlanCourse {
        val low = creditLow
        val high = creditHigh
        val chosen = if (low != null && high != null && isVariableCredit) VariableCredits.clamp(credits, low, high) else credits
        return if (chosen == this.credits) this else copy(credits = chosen)
    }

    private fun PlanCourse.withChosen(credits: Double): PlanCourse {
        val low = creditLow
        val high = creditHigh
        val chosen = if (low != null && high != null && isVariableCredit) VariableCredits.clamp(credits, low, high) else credits
        return if (chosen == this.credits) this else copy(credits = chosen)
    }

    private fun clampSchedule(row: PlanCourse, course: Course?): PlanCourse {
        if (course == null || !course.isVariableCredit) {
            if (course != null && row.isVariableCredit) {
                return row.copy(credits = course.minCredits, creditLow = null, creditHigh = null)
            }
            return row
        }
        val chosen = row.credits?.let { VariableCredits.clamp(it, course.minCredits, course.maxCredits) }
            ?: VariableCredits.defaultChosen(course.creditHours, course.minCredits, course.maxCredits)
        val next = row.copy(credits = chosen, creditLow = course.minCredits, creditHigh = course.maxCredits)
        return if (next == row) row else next
    }

    private fun clampDegree(row: DegreePlanCourse, course: Course?): DegreePlanCourse {
        if (course == null || !course.isVariableCredit) {
            if (course != null && row.isVariableCredit) {
                return row.copy(credits = course.minCredits, creditLow = null, creditHigh = null)
            }
            return row
        }
        val chosen = VariableCredits.clamp(row.credits, course.minCredits, course.maxCredits)
        val next = row.copy(credits = chosen, creditLow = course.minCredits, creditHigh = course.maxCredits)
        return if (next == row) row else next
    }
}
