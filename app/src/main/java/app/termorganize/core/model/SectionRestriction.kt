// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.core.model

import kotlinx.serialization.Serializable

/** One Banner enrollment restriction list. Must and cannot stay distinct so later features can reuse them. */
@Serializable
enum class RestrictionKind { MUST, CANNOT }

@Serializable
data class SectionRestriction(
    val kind: RestrictionKind,
    val category: String,
    val values: List<String>,
)

object CpsRestriction {
    const val SECTION_NOTE = "Restricted to CPS (College of Professional Studies) students"
    const val SOME_NOTE = "Some sections are CPS only"
    private const val COLLEGE = "College of Professional Studies"

    /**
     * True when a loaded Must Levels list is only CPS levels, or a loaded Must Colleges list is
     * only the College of Professional Studies. Cannot lists do not count. Null means not loaded.
     */
    fun isCpsOnly(restrictions: List<SectionRestriction>?): Boolean? {
        if (restrictions == null) return null
        val must = restrictions.filter { it.kind == RestrictionKind.MUST }
        val levels = must.filter { it.category.equals("Levels", ignoreCase = true) }.flatMap { it.values }
        if (levels.isNotEmpty() && levels.all { it.trim().startsWith("CPS -") }) return true
        val colleges = must.filter { it.category.equals("Colleges", ignoreCase = true) }.flatMap { it.values }
        if (colleges.isNotEmpty() && colleges.all { isProfessionalStudies(it) }) return true
        return false
    }

    /**
     * Course caption from the sections whose restrictions are loaded.
     * Unknown sections add nothing. All known CPS-only sections use [SECTION_NOTE]. A mix uses [SOME_NOTE].
     */
    fun courseNote(sections: List<CourseSection>): String? {
        val known = sections.mapNotNull { it.cpsOnly }
        if (known.isEmpty() || known.none { it }) return null
        return if (known.all { it }) SECTION_NOTE else SOME_NOTE
    }

    fun apply(sections: List<CourseSection>, found: Map<String, List<SectionRestriction>>): List<CourseSection> =
        sections.map { section ->
            val parsed = found[section.crn] ?: return@map section
            section.copy(restrictions = parsed)
        }

    private fun isProfessionalStudies(value: String): Boolean {
        val text = value.trim()
        return text.equals(COLLEGE, ignoreCase = true) || text.startsWith(COLLEGE, ignoreCase = true)
    }
}
