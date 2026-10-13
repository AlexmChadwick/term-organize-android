// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.offering

import app.termorganize.core.model.CourseEquivalences
import app.termorganize.core.model.CourseKey
import app.termorganize.logic.search.CatalogIndex

fun CourseEquivalences.Companion.fromCrossListed(index: CatalogIndex): CourseEquivalences {
    val pairs = LinkedHashSet<Pair<CourseKey, CourseKey>>()
    for (section in index.catalog.sections) {
        if (section.crossListCRNs.isEmpty()) continue
        val key = section.courseKey
        if (index.course(key)?.specialTopics == true) continue
        for (crn in section.crossListCRNs) {
            val other = index.section(crn) ?: continue
            val otherKey = other.courseKey
            if (otherKey.number != key.number || otherKey.subject == key.subject) continue
            if (index.course(otherKey)?.specialTopics == true) continue
            val title = normalizedTitle(index.course(key)?.title ?: section.title)
            val otherTitle = normalizedTitle(index.course(otherKey)?.title ?: other.title)
            if (title.isEmpty() || title != otherTitle) continue
            val ordered = if (key <= otherKey) key to otherKey else otherKey to key
            pairs += ordered
        }
    }
    return fromPairs(pairs.toList())
}

private fun normalizedTitle(title: String) =
    title.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()
