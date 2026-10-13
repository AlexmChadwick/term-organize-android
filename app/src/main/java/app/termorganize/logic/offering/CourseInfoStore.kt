// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.offering

import app.termorganize.core.model.CourseEquivalences
import app.termorganize.core.model.CourseInfo
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.EquivalenceSuggester
import app.termorganize.core.model.NUPath
import app.termorganize.core.model.PrerequisiteListing
import app.termorganize.core.model.Requisite
import app.termorganize.logic.degree.DegreeCourseAttributes
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.RequisiteAnswer
import app.termorganize.logic.search.CatalogIndex

/**
 * Catalog information for courses the open term does not list, plus equivalences derived from
 * Banner and from cross-listed sections.
 */
class CourseInfoStore(
    private val repository: CourseInfoRepository,
    private val offeringRepository: OfferingHistoryRepository,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val retryDelayMillis: Long = 60_000,
    private val attributeRepository: CourseAttributeRepository? = null,
) {
    sealed class LookupState {
        data object Unknown : LookupState()
        data object Loading : LookupState()
        data class Found(val info: CourseInfo) : LookupState()
        data object NotFound : LookupState()
        data object Failed : LookupState()
    }

    var version: Int = 0
        private set

    private val infos = HashMap<CourseKey, CourseInfo>()
    private val notFound = HashSet<CourseKey>()
    private val failedAt = HashMap<CourseKey, Long>()
    private val loading = HashSet<CourseKey>()
    private var derived = CourseEquivalences.none
    private val attributeLookups = HashMap<CourseKey, CourseAttributeRepository.Lookup>()
    private val attributesFailedAt = HashMap<CourseKey, Long>()
    private val attributesLoading = HashSet<CourseKey>()
    private var indexes: List<CatalogIndex> = emptyList()
    private var indexStamp = HashMap<String, Double>()
    private var prerequisiteCache: Triple<String, Double, List<PrerequisiteListing>>? = null

    fun info(key: CourseKey): CourseInfo? = infos[key]

    fun infosSnapshot(): Map<CourseKey, CourseInfo> = infos.toMap()

    fun state(key: CourseKey): LookupState = when {
        key in infos -> LookupState.Found(infos.getValue(key))
        key in loading -> LookupState.Loading
        key in notFound -> LookupState.NotFound
        key in failedAt -> LookupState.Failed
        else -> LookupState.Unknown
    }

    fun requisiteLookup(): (CourseKey) -> RequisiteAnswer {
        val table = infos.mapValues { it.value.prerequisites }
        return { key -> if (key in table) RequisiteAnswer.Known(table[key]) else RequisiteAnswer.Unknown }
    }

    fun equivalences(plan: DegreePlan?): CourseEquivalences =
        if (plan == null) derived else derived.merging(plan.declaredEquivalences)

    /** NUpath attributes for a plan: loaded catalogs first, then what [loadAttributes] found. */
    fun degreeAttributes(plan: DegreePlan): DegreeCourseAttributes {
        val equivalences = equivalences(plan)
        val own = planKeys(plan)
        val keys = LinkedHashSet(own)
        for (key in own) keys += equivalences.group(key)
        val nupaths = LinkedHashMap<CourseKey, Set<NUPath>>()
        val loading = HashSet<CourseKey>()
        for (key in keys) {
            when (val lookup = attributeLookups[key] ?: CourseAttributeRepository.fromLoaded(key, indexes)) {
                is CourseAttributeRepository.Lookup.Found -> nupaths[key] = NUPath.from(lookup.codes.toList()).toSet()
                CourseAttributeRepository.Lookup.NotFound, CourseAttributeRepository.Lookup.Failed -> Unit
                null -> if (key in own) loading += key
            }
            if (key in attributesLoading) loading += key
        }
        return DegreeCourseAttributes(nupaths, loading)
    }

    suspend fun loadAttributes(keys: List<CourseKey>, indexes: List<CatalogIndex>) {
        val repository = attributeRepository ?: return
        this.indexes = indexes
        val now = nowMillis()
        val wanted = LinkedHashSet(keys).filter { key ->
            if (key in attributesLoading || CourseAttributeRepository.fromLoaded(key, indexes) != null) return@filter false
            when (val known = attributeLookups[key]) {
                null -> true
                CourseAttributeRepository.Lookup.Failed -> attributesFailedAt[key]?.let { now - it > retryDelayMillis } != false
                is CourseAttributeRepository.Lookup.Found, CourseAttributeRepository.Lookup.NotFound -> false
            }
        }
        if (wanted.isEmpty()) return
        attributesLoading += wanted
        version += 1
        val results = repository.lookup(wanted, indexes)
        for (key in wanted) {
            attributesLoading -= key
            when (val result = results[key] ?: CourseAttributeRepository.Lookup.Failed) {
                CourseAttributeRepository.Lookup.Failed -> {
                    attributeLookups[key] = CourseAttributeRepository.Lookup.Failed
                    attributesFailedAt[key] = nowMillis()
                }
                else -> {
                    attributeLookups[key] = result
                    attributesFailedAt -= key
                }
            }
        }
        version += 1
    }

    private fun planKeys(plan: DegreePlan): List<CourseKey> =
        (plan.completed + plan.terms.flatMap { it.courses }).mapNotNull { it.key }

    suspend fun offeringHistory(keys: List<CourseKey>, indexes: List<CatalogIndex>? = null): OfferingHistory =
        offeringRepository.history(keys, indexes ?: this.indexes)

    suspend fun load(keys: List<CourseKey>, indexes: List<CatalogIndex>) {
        this.indexes = indexes
        val wanted = LinkedHashSet(keys).toList()
        var changed = false
        for (key in wanted) {
            var info = CourseInfoRepository.fromLoaded(key, indexes) ?: continue
            val previous = infos[key]
            if (previous != null) {
                if (info.sourceTerm < previous.sourceTerm) continue
                info = info.copy(equivalents = previous.equivalents)
                if (info == previous) continue
            }
            infos[key] = info
            notFound -= key
            changed = true
        }
        val stamp = HashMap<String, Double>()
        for (index in indexes) stamp.putIfAbsent(index.term.code, index.catalog.sectionsUpdatedAt)
        val indexesChanged = stamp != indexStamp
        indexStamp = stamp
        if ((changed || indexesChanged) && rebuildEquivalences()) changed = true
        if (changed) version += 1
        val now = nowMillis()
        val pending = wanted.filter { key ->
            key !in infos && key !in notFound && key !in loading &&
                failedAt[key]?.let { now - it > retryDelayMillis } != false
        }
        if (pending.isEmpty()) return
        loading += pending
        version += 1
        val results = repository.lookup(pending, indexes)
        for (key in pending) if (key in loading) receive(results[key] ?: CourseInfoLookup.Failed, key)
    }

    fun equivalenceSuggestions(
        key: CourseKey,
        plan: DegreePlan? = null,
        limit: Int = 3,
        catalogTitles: Map<CourseKey, String> = emptyMap(),
        alsoComparing: List<CourseKey> = emptyList(),
    ): List<app.termorganize.core.model.EquivalenceSuggestion> {
        val newest = indexes.firstOrNull() ?: return emptyList()
        if (newest.course(key) != null) return emptyList()
        when (val state = state(key)) {
            is LookupState.Found -> if (state.info.isRetired == false) return emptyList()
            LookupState.NotFound -> Unit
            else -> return emptyList()
        }
        val planKeys = plan?.let { it.completed.mapNotNull { course -> course.key } + it.terms.flatMap { term -> term.courses.mapNotNull { course -> course.key } } }.orEmpty()
        return EquivalenceSuggester.suggestions(
            retired = key,
            prerequisites = prerequisiteList(newest),
            isCurrent = { newest.course(it) != null },
            excluding = equivalences(plan),
            limit = limit,
            title = { titleOf(it, catalogTitles, plan) },
            titleCandidates = planKeys + alsoComparing,
        )
    }

    private fun receive(result: CourseInfoLookup, key: CourseKey) {
        loading -= key
        when (result) {
            is CourseInfoLookup.Found -> {
                infos[key] = result.info
                notFound -= key
                failedAt -= key
                rebuildEquivalences()
            }
            CourseInfoLookup.NotFound -> {
                notFound += key
                failedAt -= key
            }
            CourseInfoLookup.Failed -> failedAt[key] = nowMillis()
        }
        version += 1
    }

    private fun titleOf(key: CourseKey, catalogTitles: Map<CourseKey, String>, plan: DegreePlan?): String? {
        catalogTitles[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        infos[key]?.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        for (index in indexes) index.course(key)?.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val stored = plan ?: return null
        for (course in stored.completed + stored.terms.flatMap { it.courses }) {
            if (course.key == key) course.title.trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun prerequisiteList(index: CatalogIndex): List<PrerequisiteListing> {
        val stamp = index.catalog.sectionsUpdatedAt
        val cached = prerequisiteCache
        if (cached != null && cached.first == index.term.code && cached.second == stamp) return cached.third
        val list = index.courses.mapNotNull { course -> course.prereqs?.let { PrerequisiteListing(course.key, it) } }
        prerequisiteCache = Triple(index.term.code, stamp, list)
        return list
    }

    private fun rebuildEquivalences(): Boolean {
        var result = CourseEquivalences.fromGroups(
            infos.values.filter { it.equivalents.isNotEmpty() }.map { listOf(it.key) + it.equivalents },
        )
        for (index in indexes) result = result.merging(CourseEquivalences.fromCrossListed(index))
        if (result.groups == derived.groups) return false
        derived = result
        return true
    }
}
