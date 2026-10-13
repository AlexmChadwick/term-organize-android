// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.degree

import java.util.Locale
import java.util.UUID

/** Edits a degree plan and returns the updated copy. */
object DegreePlanEditing {
    fun removePlannedCourse(courseId: String, plan: DegreePlan): DegreePlan =
        plan.copy(terms = plan.terms.map { term -> term.copy(courses = term.courses.filter { it.id != courseId }) })

    fun add(course: DegreePlanCourse, termId: String, plan: DegreePlan): DegreePlan {
        val index = plan.terms.indexOfFirst { it.id == termId }
        if (index < 0) return plan
        val terms = plan.terms.toMutableList()
        terms[index] = terms[index].copy(courses = terms[index].courses + course)
        return plan.copy(terms = terms)
    }

    fun replacePlannedCourse(courseId: String, replacement: DegreePlanCourse, plan: DegreePlan): DegreePlan {
        val terms = plan.terms.map { term ->
            val position = term.courses.indexOfFirst { it.id == courseId }
            if (position < 0) term
            else term.copy(courses = term.courses.toMutableList().also { it[position] = replacement })
        }
        return plan.copy(terms = terms)
    }

    /** Moves a planned course to [position] in a semester (0 is the top). */
    fun move(courseId: String, termId: String, position: Int, plan: DegreePlan): DegreePlan {
        val destination = plan.terms.indexOfFirst { it.id == termId }
        if (destination < 0) return plan
        var index = position
        val current = plan.terms[destination].courses.indexOfFirst { it.id == courseId }
        if (current >= 0 && current < position) index -= 1
        val taken = takePlanned(courseId, plan) ?: return plan
        val terms = taken.plan.terms.toMutableList()
        val courses = terms[destination].courses.toMutableList()
        courses.add(index.coerceIn(0, courses.size), taken.course)
        terms[destination] = terms[destination].copy(courses = courses)
        return taken.plan.copy(terms = terms)
    }

    fun uniqueName(base: String, existing: List<String>): String {
        val taken = existing.map { it.trim().lowercase(Locale.US) }.toSet()
        val wanted = base.trim()
        if (wanted.lowercase(Locale.US) !in taken) return wanted
        var number = 2
        while ("$wanted $number".lowercase(Locale.US) in taken) number++
        return "$wanted $number"
    }

    fun scheduleName(semester: DegreePlanTerm, existing: List<String>): String =
        uniqueName("${semester.name} plan", existing)

    /** A copy with fresh identifiers, named "<name> copy" (numbered when that name is taken). */
    fun duplicate(plan: DegreePlan, existingNames: List<String>): DegreePlan {
        val now = System.currentTimeMillis()
        return plan.copy(
            id = UUID.randomUUID().toString(),
            name = uniqueName("${plan.name} copy", existingNames),
            isPrimary = false,
            importedFrom = null,
            printedAt = null,
            createdAt = now,
            updatedAt = now,
            completed = plan.completed.map { it.copy(id = UUID.randomUUID().toString()) },
            terms = plan.terms.map { term ->
                term.copy(id = UUID.randomUUID().toString(), courses = term.courses.map { it.copy(id = UUID.randomUUID().toString()) })
            },
        )
    }

    private fun takePlanned(courseId: String, plan: DegreePlan): Taken? {
        for (index in plan.terms.indices) {
            val position = plan.terms[index].courses.indexOfFirst { it.id == courseId }
            if (position < 0) continue
            val course = plan.terms[index].courses[position]
            val terms = plan.terms.toMutableList()
            terms[index] = terms[index].copy(courses = terms[index].courses.filterIndexed { i, _ -> i != position })
            return Taken(course, plan.copy(terms = terms))
        }
        return null
    }

    private data class Taken(val course: DegreePlanCourse, val plan: DegreePlan)
}
