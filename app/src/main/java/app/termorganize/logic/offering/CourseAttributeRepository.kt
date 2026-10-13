// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.offering

import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.Term
import app.termorganize.logic.search.CatalogIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

interface AttributeFetching {
    suspend fun terms(): List<Term>
    suspend fun sectionAttributes(term: String, courses: List<CourseKey>): Map<CourseKey, Set<String>>
}

/**
 * NUpath and other attributes live on sections, not catalog entries. Looks at loaded catalogs,
 * then a disk cache, then Banner terms from the last three years (summers included).
 */
class CourseAttributeRepository(
    private val fetcher: AttributeFetching,
    directory: File,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val windowMonths: Int = 36,
    private val foundLifetime: Double = 30.0 * 24 * 3600,
    private val notFoundLifetime: Double = 7.0 * 24 * 3600,
) {
    private val file = File(directory, "course-attributes.json")
    private val entries = HashMap<String, Entry>()
    private var didLoad = false
    private var dirty = false

    suspend fun lookup(keys: List<CourseKey>, indexes: List<CatalogIndex> = emptyList()): Map<CourseKey, Lookup> {
        loadIfNeeded()
        val results = LinkedHashMap<CourseKey, Lookup>()
        val pending = ArrayList<CourseKey>()
        for (key in LinkedHashSet(keys)) {
            val loaded = fromLoaded(key, indexes)
            if (loaded != null) {
                results[key] = loaded
                continue
            }
            val cached = cached(key)
            if (cached != null) {
                results[key] = cached
                continue
            }
            pending += key
        }
        if (pending.isEmpty()) return results
        val terms = ArrayList<Term>()
        terms += runCatching { fetcher.terms() }.getOrDefault(emptyList()).filter { it.college == Term.College.NEU }
        for (index in indexes) {
            if (index.term.college == Term.College.NEU && terms.none { it.code == index.term.code }) terms += index.term
        }
        val window = OfferingHistoryRepository.window(terms, windowMonths)
        if (window.isEmpty()) {
            for (key in pending) results[key] = Lookup.Failed
            return results
        }
        val loadedCodes = indexes.map { it.term.code }.toSet()
        var remaining = pending.toList()
        for (term in window) {
            if (term.code in loadedCodes) continue
            var start = 0
            while (start < remaining.size) {
                val batch = remaining.subList(start, minOf(start + BATCH, remaining.size)).toList()
                start += BATCH
                try {
                    val found = fetcher.sectionAttributes(term.code, batch)
                    for ((key, codes) in found) {
                        results[key] = Lookup.Found(codes, term.code)
                        store(key, Entry(codes.sorted(), term.code, now()))
                    }
                } catch (_: Exception) {
                    for (key in remaining) if (key !in results) results[key] = Lookup.Failed
                    saveIfNeeded()
                    return results
                }
            }
            remaining = remaining.filter { it !in results }
            if (remaining.isEmpty()) break
        }
        for (key in remaining) {
            if (key in results) continue
            results[key] = Lookup.NotFound
            store(key, Entry(null, null, now()))
        }
        saveIfNeeded()
        return results
    }

    private fun cached(key: CourseKey): Lookup? {
        val entry = entries[key.description] ?: return null
        val age = now() - entry.checkedAt
        val codes = entry.codes
        val term = entry.term
        return if (codes != null && term != null) {
            if (age < foundLifetime) Lookup.Found(codes.toSet(), term) else null
        } else if (age < notFoundLifetime) Lookup.NotFound else null
    }

    private fun store(key: CourseKey, entry: Entry) {
        entries[key.description] = entry
        dirty = true
    }

    private fun loadIfNeeded() {
        if (didLoad) return
        didLoad = true
        if (!file.isFile) return
        val stored = runCatching { json.decodeFromString(Stored.serializer(), file.readText()) }.getOrNull() ?: return
        if (stored.version != VERSION) return
        entries.putAll(stored.entries)
    }

    private fun saveIfNeeded() {
        if (!dirty) return
        dirty = false
        val text = runCatching { json.encodeToString(Stored.serializer(), Stored(VERSION, entries.toMap())) }.getOrNull() ?: return
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    sealed class Lookup {
        data class Found(val codes: Set<String>, val term: String) : Lookup()
        data object NotFound : Lookup()
        data object Failed : Lookup()
    }

    @Serializable
    private data class Entry(val codes: List<String>? = null, val term: String? = null, val checkedAt: Double = 0.0)

    @Serializable
    private data class Stored(val version: Int, val entries: Map<String, Entry> = emptyMap())

    companion object {
        private const val VERSION = 1
        private const val BATCH = 10
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        fun fromLoaded(key: CourseKey, indexes: List<CatalogIndex>): Lookup? {
            for (index in indexes) {
                if (index.sections(key).isEmpty()) continue
                return Lookup.Found(index.course(key)?.attributes.orEmpty().toSet(), index.term.code)
            }
            return null
        }
    }
}
