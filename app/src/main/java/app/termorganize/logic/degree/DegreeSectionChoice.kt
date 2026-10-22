// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.degree

import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.logic.search.CatalogIndex

/** Sections a degree-plan term can offer when the open catalog is that term. */
object DegreeSectionChoice {
    fun message(termName: String) =
        "Pick a section to add this course twice. No sections are listed for $termName yet."

    fun offered(term: DegreePlanTerm, key: CourseKey, index: CatalogIndex?): List<CourseSection> {
        if (index == null || !term.matches(index.term)) return emptyList()
        return index.sections(key)
    }
}
