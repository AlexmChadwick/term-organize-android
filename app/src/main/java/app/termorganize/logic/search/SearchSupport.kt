// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.search

import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.NUPath
import app.termorganize.core.model.Subject

enum class SearchDayPreset(val menuTitle: String, val chipTitle: String, val days: DaySet?) {
    ANY("Any days", "Days", null),
    NO_FRIDAYS("No Friday classes", "No Fridays", DaySet(listOf(1, 2, 3, 4))),
    MWR("Mon, Wed, Thu only", "MWR only", DaySet(listOf(1, 3, 4))),
    TF("Tue, Fri only", "TF only", DaySet(listOf(2, 5))),
    MR("Mon, Thu only", "MR only", DaySet(listOf(1, 4))),
    WF("Wed, Fri only", "WF only", DaySet(listOf(3, 5))),
    CUSTOM("Custom…", "Custom days", null),
    ;

    companion object {
        fun matching(days: DaySet?): SearchDayPreset? =
            entries.firstOrNull { it != CUSTOM && it.days == days }

        fun selection(days: DaySet?): SearchDayPreset = matching(days) ?: CUSTOM
    }
}

object SearchFilterTitles {
    fun days(days: DaySet?): String {
        if (days == null) return "Days"
        SearchDayPreset.matching(days)?.let { return it.chipTitle }
        return if (days.isEmpty) "No set days" else "${days.compactLabel} only"
    }

    fun subjects(subjects: Set<String>): String = when (subjects.size) {
        0 -> "Subject"
        1 -> subjects.first()
        else -> "${subjects.size} subjects"
    }

    fun nupaths(nupaths: Set<NUPath>): String = when (nupaths.size) {
        0 -> "NUpath"
        1, 2 -> NUPath.entries.filter { it in nupaths }.joinToString(", ") { it.short }
        else -> "NUpath · ${nupaths.size}"
    }

    fun level(level: Int): String = if (level == 0) "Under 1000" else "${level}000"
}

object SearchTimeOfDay {
    fun minutes(fromMinutes: Int): Int = fromMinutes.coerceIn(0, 24 * 60 - 1)
}

enum class HomeQuickSearch(val title: String, val symbol: String, val filters: SearchFilters) {
    OPEN_SEATS("Open seats", "chair", SearchFilters(openSeatsOnly = true)),
    HONORS("Honors", "star", SearchFilters(honorsOnly = true)),
    ONLINE("Online", "laptopcomputer", SearchFilters(instruction = InstructionFilter.ONLINE)),
    NO_FRIDAYS("No Friday classes", "calendar.badge.minus", SearchFilters(allowedDays = DaySet(listOf(1, 2, 3, 4)))),
    FIRST_YEAR("1000-level", "1.circle", SearchFilters(levels = setOf(1))),
}

fun browseSummaryText(shown: Int, total: Int, open: Int, openOnly: Boolean = false, noun: String = "course"): String {
    fun plural(count: Int) = if (count == 1) noun else "${noun}s"
    var text = if (shown == total) "$total ${plural(total)}" else "$shown of $total ${plural(total)}"
    if (open > 0 && !openOnly) text += " · $open with open seats"
    return text
}

fun browseLevelTitle(level: Int) = if (level == 0) "Under 1000" else "${level}000-level"

fun browseLevelNoun(level: Int) = if (level == 0) "courses under 1000" else "${level}000-level courses"

data class SearchExample(val title: String, val caption: String, val symbol: String)

object SearchExampleCache {
    private var cachedKey: String? = null
    private var cached: List<SearchExample> = emptyList()

    fun examples(index: CatalogIndex): List<SearchExample> {
        val key = "${index.term.code}|${index.catalog.sectionsUpdatedAt}"
        if (key == cachedKey) return cached
        val built = build(index)
        cachedKey = key
        cached = built
        return built
    }

    private fun build(index: CatalogIndex): List<SearchExample> {
        val result = mutableListOf<SearchExample>()
        val preferred = listOf("CS 3000", "CS 2500", "MATH 1341", "ENGW 1111", "PHYS 1151")
        val exampleKey = preferred.mapNotNull { CourseKey.parse(it) }.firstOrNull { index.course(it) != null }
            ?: index.courses.firstOrNull()?.key
        if (exampleKey != null) result += SearchExample(exampleKey.description, "Course code", "book.closed")
        val topics = listOf("machine learning", "data structures", "calculus", "psychology", "writing")
        val topic = topics.firstOrNull { name -> index.searchDocuments.any { name in it.titleNormalized } }
        if (topic != null) result += SearchExample(topic, "Topic or title", "lightbulb")
        val crn = exampleKey?.let { index.sections(it).firstOrNull()?.crn } ?: index.catalog.sections.firstOrNull()?.crn
        if (crn != null) result += SearchExample(crn, "CRN", "number")
        val teacher = exampleKey?.let { key ->
            index.sections(key).firstNotNullOfOrNull { section ->
                section.instructors.orEmpty().firstOrNull { it.primary } ?: section.instructors.orEmpty().firstOrNull()
            }?.let { index.instructor(it.name) }
        }
        val professor = teacher ?: index.instructors.maxByOrNull { it.courseKeys.size }
        if (professor != null) result += SearchExample(professor.displayName, "Professor", "person.crop.circle")
        return result
    }
}

fun CatalogIndex.popularSubjects(limit: Int = 12): List<Subject> {
    val counts = courseCountBySubject
    return activeSubjects.sortedWith(compareByDescending<Subject> { counts[it.code] ?: 0 }.thenBy { it.code }).take(limit)
}
