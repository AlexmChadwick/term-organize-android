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
        if (!plan.admits(course)) return plan
        val index = plan.terms.indexOfFirst { it.id == termId }
        if (index < 0) return plan
        val terms = plan.terms.toMutableList()
        terms[index] = terms[index].copy(courses = terms[index].courses + course)
        return plan.copy(terms = terms)
    }

    /**
     * Adds a second row of a course that is already in the plan. Every unpinned row of that
     * course must be given a CRN in [existingPins], and the new row's CRN must be different.
     * [termId] null appends the new row to completed courses.
     */
    fun addDuplicate(
        plan: DegreePlan,
        termId: String?,
        course: DegreePlanCourse,
        existingPins: Map<String, String>,
        titles: Map<String, String> = emptyMap(),
    ): DegreePlan {
        val key = course.key ?: return plan
        val newCrn = course.manualCRN ?: return plan
        val same = rows(plan, key)
        if (same.isEmpty()) return plan
        val unpinned = same.filter { it.manualCRN == null }
        if (unpinned.any { it.id !in existingPins }) return plan
        val pins = existingPins.filterKeys { id -> unpinned.any { it.id == id } }
        val chosen = pins.values.map { it.trim() }
        if (chosen.any { it.isEmpty() } || chosen.distinct().size != chosen.size || newCrn in chosen) return plan
        val used = (plan.completed + plan.terms.flatMap { it.courses }).mapNotNull { it.manualCRN }.toSet()
        if (newCrn in used || chosen.any { it in used }) return plan
        val pinned = pinRows(plan, pins, titles)
        val incoming = course.copy(crn = newCrn)
        if (!pinned.admits(incoming)) return plan
        return if (termId == null) pinned.copy(completed = pinned.completed + incoming)
        else add(incoming, termId, pinned)
    }

    /** Sets one row's CRN. A sibling that still has no CRN is left for [pinRows]. */
    fun setSection(plan: DegreePlan, courseId: String, crn: String, title: String? = null): DegreePlan {
        val pin = crn.trim()
        if (pin.isEmpty()) return plan
        val all = plan.completed + plan.terms.flatMap { it.courses }
        val row = all.firstOrNull { it.id == courseId } ?: return plan
        if (all.any { it.id != courseId && it.manualCRN == pin }) return plan
        val key = row.key
        if (key != null && all.any { it.key == key && it.id != courseId && it.manualCRN == null }) return plan
        return replaceCourse(plan, courseId) { current ->
            current.copy(crn = pin, title = title?.takeIf { it.isNotBlank() } ?: current.title)
        }
    }

    /** Assigns a CRN to each listed row. The CRNs have to differ, and none may already belong to another row. */
    fun pinRows(plan: DegreePlan, pins: Map<String, String>, titles: Map<String, String> = emptyMap()): DegreePlan {
        if (pins.isEmpty()) return plan
        val all = plan.completed + plan.terms.flatMap { it.courses }
        val cleaned = pins.mapValues { it.value.trim() }
        if (cleaned.values.any { it.isEmpty() } || cleaned.values.distinct().size != cleaned.size) return plan
        if (cleaned.keys.any { id -> all.none { it.id == id } }) return plan
        val usedElsewhere = all.filter { it.id !in cleaned }.mapNotNull { it.manualCRN }.toSet()
        if (cleaned.values.any { it in usedElsewhere }) return plan
        var updated = plan
        for ((id, pin) in cleaned) {
            updated = replaceCourse(updated, id) { current ->
                current.copy(crn = pin, title = titles[id]?.takeIf { it.isNotBlank() } ?: current.title)
            }
        }
        return updated
    }

    private fun rows(plan: DegreePlan, key: app.termorganize.core.model.CourseKey): List<DegreePlanCourse> =
        plan.completed.filter { it.key == key } + plan.terms.flatMap { it.courses }.filter { it.key == key }

    private fun replaceCourse(plan: DegreePlan, courseId: String, block: (DegreePlanCourse) -> DegreePlanCourse): DegreePlan =
        plan.copy(
            completed = plan.completed.map { if (it.id == courseId) block(it) else it },
            terms = plan.terms.map { term -> term.copy(courses = term.courses.map { if (it.id == courseId) block(it) else it }) },
        )

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
