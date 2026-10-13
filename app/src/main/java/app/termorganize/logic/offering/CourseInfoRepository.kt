// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.offering

import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseInfo
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.Term
import app.termorganize.logic.search.CatalogIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

interface CourseInfoFetching {
    suspend fun terms(): List<Term>
    suspend fun catalogEntry(key: CourseKey, term: String): CourseInfo?
}

sealed class CourseInfoLookup {
    data class Found(val info: CourseInfo) : CourseInfoLookup()
    data object NotFound : CourseInfoLookup()
    data object Failed : CourseInfoLookup()
}

/**
 * Prerequisites and equivalents for a course, including ones the open term does not list.
 * Order: loaded catalogs, the disk cache, then Banner (newest term, then earlier falls and springs).
 */
class CourseInfoRepository(
    private val fetcher: CourseInfoFetching,
    directory: File,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val maxTermsBack: Int = 8,
    private val notFoundLifetime: Double = 7.0 * 24 * 3600,
    private val foundLifetime: Double = 180.0 * 24 * 3600,
    concurrency: Int = 2,
) {
    private val file = File(directory, "course-info.json")
    private val concurrency = maxOf(1, concurrency)
    private val found = HashMap<CourseKey, CourseInfo>()
    private val missing = HashMap<CourseKey, Double>()
    private var didLoad = false
    private var dirty = false
    private var termCache: Pair<List<String>, Double>? = null

    fun cachedLookup(key: CourseKey): CourseInfoLookup? {
        loadIfNeeded()
        val info = found[key]
        if (info != null && isFresh(info)) return CourseInfoLookup.Found(info)
        val checkedAt = missing[key]
        if (checkedAt != null && now() - checkedAt < notFoundLifetime) return CourseInfoLookup.NotFound
        return null
    }

    suspend fun lookup(key: CourseKey, indexes: List<CatalogIndex> = emptyList()): CourseInfoLookup =
        lookup(listOf(key), indexes)[key] ?: CourseInfoLookup.Failed

    suspend fun lookup(keys: List<CourseKey>, indexes: List<CatalogIndex> = emptyList()): Map<CourseKey, CourseInfoLookup> {
        loadIfNeeded()
        val results = HashMap<CourseKey, CourseInfoLookup>()
        val pending = ArrayList<CourseKey>()
        for (key in unique(keys)) {
            val known = fromLoaded(key, indexes)?.let { CourseInfoLookup.Found(it) } ?: cachedLookup(key)
            if (known != null) results[key] = known else pending += key
        }
        if (pending.isEmpty()) return results
        val terms = try {
            catalogTerms()
        } catch (_: Exception) {
            for (key in pending) results[key] = staleOrFailed(key)
            return results
        }
        val limit = minOf(concurrency, pending.size)
        val semaphore = Semaphore(limit)
        val resolved = coroutineScope {
            pending.map { key ->
                async {
                    semaphore.withPermit { key to resolve(key, terms) }
                }
            }.awaitAll()
        }
        for ((key, result) in resolved) {
            results[key] = if (result == CourseInfoLookup.Failed) staleOrFailed(key) else result
        }
        saveIfNeeded()
        return results
    }

    fun clear() {
        found.clear()
        missing.clear()
        termCache = null
        didLoad = true
        dirty = false
        file.delete()
    }

    private suspend fun catalogTerms(): List<String> {
        val cached = termCache
        if (cached != null && now() - cached.second < 3600) return cached.first
        val codes = catalogTerms(fetcher.terms(), maxTermsBack)
        if (codes.isEmpty()) error("Banner listed no Northeastern terms")
        termCache = codes to now()
        return codes
    }

    private suspend fun resolve(key: CourseKey, terms: List<String>): CourseInfoLookup {
        val result = search(key, terms)
        when (result) {
            is CourseInfoLookup.Found -> {
                found[key] = result.info
                missing.remove(key)
                dirty = true
            }
            CourseInfoLookup.NotFound -> {
                missing[key] = now()
                dirty = true
            }
            CourseInfoLookup.Failed -> Unit
        }
        return result
    }

    private suspend fun search(key: CourseKey, terms: List<String>): CourseInfoLookup {
        for ((position, term) in terms.withIndex()) {
            try {
                val info = fetcher.catalogEntry(key, term)
                if (info != null) return CourseInfoLookup.Found(info.copy(isRetired = position > 0))
            } catch (_: Exception) {
                return CourseInfoLookup.Failed
            }
        }
        return CourseInfoLookup.NotFound
    }

    private fun staleOrFailed(key: CourseKey): CourseInfoLookup =
        found[key]?.let { CourseInfoLookup.Found(it) } ?: CourseInfoLookup.Failed

    private fun isFresh(info: CourseInfo): Boolean = now() - info.fetchedAt < foundLifetime

    private fun loadIfNeeded() {
        if (didLoad) return
        didLoad = true
        if (!file.isFile) return
        val stored = runCatching { json.decodeFromString(Stored.serializer(), file.readText()) }.getOrNull() ?: return
        if (stored.version != VERSION) return
        for (info in stored.found) found[info.key] = info
        for (entry in stored.missing) missing[entry.key] = entry.checkedAt
    }

    private fun saveIfNeeded() {
        if (!dirty) return
        dirty = false
        val current = now()
        val stored = Stored(
            VERSION,
            found.values.filter(::isFresh).sortedBy { it.key },
            missing.filter { current - it.value < notFoundLifetime }
                .map { Missing(it.key, it.value) }
                .sortedBy { it.key },
        )
        val text = runCatching { json.encodeToString(Stored.serializer(), stored) }.getOrNull() ?: return
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Serializable
    private data class Missing(val key: CourseKey, val checkedAt: Double)

    @Serializable
    private data class Stored(val version: Int, val found: List<CourseInfo>, val missing: List<Missing>)

    companion object {
        private const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        fun catalogTerms(terms: List<Term>, maxBack: Int): List<String> {
            val neu = terms.filter { it.college == Term.College.NEU }.sortedByDescending { it.code }
            val newest = neu.firstOrNull() ?: return emptyList()
            val codes = mutableListOf(newest.code)
            for (term in neu.drop(1)) {
                if (term.season != Term.Season.FALL && term.season != Term.Season.SPRING) continue
                if (codes.size > maxBack) break
                codes += term.code
            }
            return codes
        }

        fun fromLoaded(key: CourseKey, indexes: List<CatalogIndex>): CourseInfo? {
            for ((position, index) in indexes.withIndex()) {
                val course = index.course(key) ?: continue
                fromCourse(course, index, position == 0)?.let { return it }
            }
            return null
        }

        fun fromCourse(course: Course, index: CatalogIndex, isNewest: Boolean): CourseInfo? {
            if (!course.detailsLoaded) return null
            return CourseInfo(
                key = course.key,
                title = course.title,
                minCredits = course.minCredits,
                maxCredits = course.maxCredits,
                prerequisites = course.prereqs,
                corequisites = course.coreqs,
                sourceTerm = index.term.code,
                isRetired = if (isNewest) false else null,
                fetchedAt = index.catalog.generatedAt,
            )
        }

        private fun unique(keys: List<CourseKey>): List<CourseKey> {
            val seen = LinkedHashSet<CourseKey>()
            return keys.filter { seen.add(it) }
        }
    }
}
