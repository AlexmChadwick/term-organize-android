// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.professors

import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.logic.search.CatalogIndex
import app.termorganize.logic.search.InstructorSummary
import java.net.URLEncoder

object CourseColors {
    val palette = listOf(
        0xFF1D4ED8.toInt(), 0xFFEA580C.toInt(), 0xFF15803D.toInt(), 0xFF7E22CE.toInt(), 0xFFDB2777.toInt(),
        0xFF0F766E.toInt(), 0xFF4338CA.toInt(), 0xFF92400E.toInt(), 0xFF0D9488.toInt(), 0xFFDC2626.toInt(),
    )

    fun at(index: Int): Int = palette[((index % palette.size) + palette.size) % palette.size]

    fun forCode(code: String): Int {
        var hash = 5381L
        for (byte in code.toByteArray()) hash = (hash * 33 + (byte.toInt() and 0xFF)) and 0xFFFFFFFFL
        return at((hash % palette.size).toInt())
    }
}

object ProfessorPage {
    fun sections(summary: InstructorSummary, index: CatalogIndex): List<CourseSection> =
        summary.crns.mapNotNull { index.section(it) }.sortedWith(compareBy<CourseSection> { it.courseKey }.thenBy { it.sectionNumber })

    fun courseColors(keys: List<CourseKey>): Map<CourseKey, Int> {
        val used = HashSet<Int>()
        val colors = LinkedHashMap<CourseKey, Int>()
        for (key in keys) {
            var slot = CourseColors.palette.indexOf(CourseColors.forCode(key.description)).let { if (it < 0) 0 else it }
            if (used.size < CourseColors.palette.size) while (slot in used) slot = (slot + 1) % CourseColors.palette.size
            used += slot
            colors[key] = CourseColors.palette[slot]
        }
        return colors
    }

    fun rateMyProfessorsUrl(displayName: String): String {
        val query = URLEncoder.encode(displayName, "UTF-8").replace("+", "%20")
        return "https://www.ratemyprofessors.com/search/professors/696?q=$query"
    }

    fun summaryText(sections: List<CourseSection>): String {
        val courses = sections.map { it.courseKey }.toSet().size
        return "Teaches ${count(sections.size, "section")} across ${count(courses, "course")}"
    }

    private fun count(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}
