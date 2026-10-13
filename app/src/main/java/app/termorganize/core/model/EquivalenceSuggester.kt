// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.core.model

/** A guess that a retired course counts as [candidate]. The student confirms it. */
data class EquivalenceSuggestion(
    val course: CourseKey,
    val candidate: CourseKey,
    val together: Int,
    val total: Int,
    val similarTitle: String? = null,
    val titleScore: Double? = null,
) {
    val id: String get() = "${course.description}|${candidate.description}"
    val confidence: Double get() = if (total > 0) together.toDouble() / total else 0.0
    val isListedTogether: Boolean get() = together > 0
    val evidence: String
        get() {
            val listing = if (together > 0) {
                if (total == 1) "Listed together in the prerequisites of the 1 course that names ${course.description}"
                else "Listed together in $together of the $total prerequisites that name ${course.description}"
            } else null
            return when {
                listing != null && similarTitle != null -> "$listing · similar title"
                listing != null -> listing
                similarTitle != null -> "Similar title: $similarTitle"
                else -> ""
            }
        }
}

data class PrerequisiteListing(val course: CourseKey, val requisite: Requisite)

object EquivalenceSuggester {
    fun suggestions(
        retired: CourseKey,
        prerequisites: List<PrerequisiteListing>,
        isCurrent: (CourseKey) -> Boolean,
        excluding: CourseEquivalences = CourseEquivalences.none,
        minimumTogether: Int = 2,
        minimumShare: Double = 0.5,
        limit: Int = 3,
        title: (CourseKey) -> String? = { null },
        titleCandidates: List<CourseKey> = emptyList(),
        minimumTitleSimilarity: Double = CourseTitleMatcher.MINIMUM_SIMILARITY,
    ): List<EquivalenceSuggestion> {
        if (limit <= 0) return emptyList()
        val (listed, dependents) = listedTogether(retired, prerequisites, isCurrent, excluding, minimumTogether, minimumShare)
        val similar = similarTitles(retired, titleCandidates, excluding, title, minimumTitleSimilarity)
        val byPrerequisites = listed.map { suggestion ->
            val match = similar[suggestion.candidate]
            if (match == null) suggestion
            else suggestion.copy(similarTitle = match.first, titleScore = match.second)
        }.sortedWith(compareByDescending<EquivalenceSuggestion> { it.together }.thenBy { it.candidate })
        val listedKeys = listed.map { it.candidate }.toSet()
        val byTitle = similar
            .filterKeys { it !in listedKeys }
            .map { (candidate, match) ->
                EquivalenceSuggestion(retired, candidate, 0, dependents, match.first, match.second)
            }
            .sortedWith(compareByDescending<EquivalenceSuggestion> { it.titleScore ?: 0.0 }.thenBy { it.candidate })
        val free = maxOf(0, limit - byPrerequisites.size)
        val reserved = if (limit >= 2 && byPrerequisites.isNotEmpty()) 1 else 0
        val titleSlots = minOf(byTitle.size, maxOf(free, reserved))
        return byPrerequisites.take(limit - titleSlots) + byTitle.take(titleSlots)
    }

    private fun listedTogether(
        retired: CourseKey,
        prerequisites: List<PrerequisiteListing>,
        isCurrent: (CourseKey) -> Boolean,
        known: CourseEquivalences,
        minimumTogether: Int,
        minimumShare: Double,
    ): Pair<List<EquivalenceSuggestion>, Int> {
        val dependents = LinkedHashSet<CourseKey>()
        val together = HashMap<CourseKey, MutableSet<CourseKey>>()
        for ((course, requisite) in prerequisites) {
            if (course == retired) continue
            var mentions = false
            fun visit(node: Requisite) {
                when (node) {
                    is Requisite.CourseReq -> if (node.key == retired) mentions = true
                    is Requisite.TestReq -> Unit
                    is Requisite.And -> node.items.forEach(::visit)
                    is Requisite.Or -> {
                        val alternatives = node.items.mapNotNull { (it as? Requisite.CourseReq)?.key }
                        if (retired in alternatives) {
                            for (other in alternatives) {
                                if (other != retired) together.getOrPut(other) { mutableSetOf() }.add(course)
                            }
                        }
                        node.items.forEach(::visit)
                    }
                }
            }
            requisite.simplified()?.let(::visit)
            if (mentions) dependents += course
        }
        val total = dependents.size
        if (total == 0) return emptyList<EquivalenceSuggestion>() to 0
        val found = together.mapNotNull { (candidate, courses) ->
            if (!isCurrent(candidate) || known.areEquivalent(retired, candidate)) return@mapNotNull null
            if (courses.size < minimumTogether || courses.size.toDouble() / total < minimumShare) return@mapNotNull null
            EquivalenceSuggestion(retired, candidate, courses.size, total)
        }
        return found to total
    }

    private fun similarTitles(
        retired: CourseKey,
        candidates: List<CourseKey>,
        known: CourseEquivalences,
        title: (CourseKey) -> String?,
        minimum: Double,
    ): Map<CourseKey, Pair<String, Double>> {
        if (candidates.isEmpty()) return emptyMap()
        val retiredText = title(retired) ?: return emptyMap()
        val retiredTitle = CourseTitleMatcher.Title(retiredText)
        if (!retiredTitle.isDistinctive) return emptyMap()
        val found = LinkedHashMap<CourseKey, Pair<String, Double>>()
        for (candidate in candidates) {
            if (candidate == retired || candidate in found || known.areEquivalent(retired, candidate)) continue
            val text = title(candidate)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val score = CourseTitleMatcher.similarity(retiredTitle, CourseTitleMatcher.Title(text), minimum) ?: continue
            found[candidate] = text to score
        }
        return found
    }
}
