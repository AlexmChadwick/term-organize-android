// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.offering

import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.Term
import app.termorganize.logic.degree.TermCalendar
import app.termorganize.logic.search.CatalogIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Banner (or a test double) as the source of which courses had sections. */
interface OfferingFetching {
    suspend fun terms(): List<Term>
    suspend fun offeredCourses(term: String, courses: List<CourseKey>): Set<CourseKey>
}

/**
 * Offering history for the last two years of Northeastern terms, summers included.
 * A loaded catalog answers a term with no request. Otherwise the disk cache, then Banner.
 * Past terms stay cached; the newest term is read again after a week. A term that could
 * not be reached is left out of [OfferingHistory.checkedTerms].
 */
class OfferingHistoryRepository(
    private val fetcher: OfferingFetching,
    directory: File,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val windowMonths: Int = 24,
    private val currentTermLifetime: Double = 7.0 * 24 * 3600,
) {
    private val file = File(directory, "offering-history.json")
    private val cache: HashMap<String, MutableMap<String, OfferingCacheEntry>> = HashMap()
    private var didLoad = false
    private var dirty = false

    suspend fun history(keys: List<CourseKey>, indexes: List<CatalogIndex> = emptyList()): OfferingHistory {
        loadIfNeeded()
        val unique = LinkedHashSet(keys).toList()
        if (unique.isEmpty()) return OfferingHistory.empty
        val terms = ArrayList<Term>()
        val remote = runCatching { fetcher.terms() }.getOrDefault(emptyList())
        terms += remote.filter { it.college == Term.College.NEU }
        for (index in indexes) {
            if (index.term.college == Term.College.NEU && terms.none { it.code == index.term.code }) terms += index.term
        }
        val window = window(terms, windowMonths)
        val newest = window.firstOrNull()?.code
            ?: return OfferingHistory(checkedKeys = unique.toSet())
        val indexByTerm = HashMap<String, CatalogIndex>()
        for (index in indexes) indexByTerm.putIfAbsent(index.term.code, index)
        val answers = HashMap<String, Set<CourseKey>>()
        for (term in window) {
            val offered = offered(unique, term.code, term.code == newest, indexByTerm[term.code]) ?: continue
            answers[term.code] = offered
        }
        saveIfNeeded()
        val checked = window.map { it.code }.filter { answers[it] != null }
        val offered = HashMap<CourseKey, MutableSet<String>>()
        for (code in checked) {
            for (key in answers[code].orEmpty()) offered.getOrPut(key) { mutableSetOf() }.add(code)
        }
        return OfferingHistory(checked, offered.mapValues { it.value.toSet() }, unique.toSet())
    }

    fun clear() {
        cache.clear()
        didLoad = true
        dirty = false
        file.delete()
    }

    private suspend fun offered(keys: List<CourseKey>, term: String, isNewest: Boolean, index: CatalogIndex?): Set<CourseKey>? {
        if (index != null) return keys.filter { index.sections(it).isNotEmpty() }.toSet()
        val missing = keys.filter { !isFresh(term, it, isNewest) }
        var start = 0
        while (start < missing.size) {
            val end = minOf(start + BATCH, missing.size)
            val batch = missing.subList(start, end).toList()
            start += BATCH
            try {
                val found = fetcher.offeredCourses(term, batch)
                val stamp = now()
                val row = cache.getOrPut(term) { HashMap() }
                for (key in batch) row[key.description] = OfferingCacheEntry(stamp, key in found)
                dirty = true
            } catch (_: Exception) {
                return null
            }
        }
        return keys.filter { cache[term]?.get(it.description)?.offered == true }.toSet()
    }

    private fun isFresh(term: String, key: CourseKey, isNewest: Boolean): Boolean {
        val entry = cache[term]?.get(key.description) ?: return false
        if (isNewest) return now() - entry.fetchedAt < currentTermLifetime
        val window = TermCalendar.window(term) ?: return true
        return entry.fetchedAt >= window.start.epochSecond.toDouble()
    }

    private fun loadIfNeeded() {
        if (didLoad) return
        didLoad = true
        if (!file.isFile) return
        val stored = runCatching { json.decodeFromString(OfferingCacheFile.serializer(), file.readText()) }.getOrNull() ?: return
        if (stored.version != VERSION) return
        cache.clear()
        for ((term, rows) in stored.terms) cache[term] = rows.toMutableMap()
    }

    private fun saveIfNeeded() {
        if (!dirty) return
        dirty = false
        val stored = OfferingCacheFile(VERSION, cache.mapValues { it.value.toMap() })
        val text = runCatching { json.encodeToString(OfferingCacheFile.serializer(), stored) }.getOrNull() ?: return
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    companion object {
        private const val VERSION = 2
        private const val BATCH = 10
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Northeastern terms within [months] of the newest one, newest first. */
        fun window(terms: List<Term>, months: Int): List<Term> {
            val sorted = terms.sortedByDescending { it.code }
            val newest = sorted.firstOrNull() ?: return emptyList()
            val oldest = OfferingHistory.monthIndex(newest.code) - months
            return sorted.filter { OfferingHistory.monthIndex(it.code) >= oldest }
        }
    }
}

@Serializable
private data class OfferingCacheEntry(val fetchedAt: Double, val offered: Boolean)

@Serializable
private data class OfferingCacheFile(val version: Int, val terms: Map<String, Map<String, OfferingCacheEntry>>)
