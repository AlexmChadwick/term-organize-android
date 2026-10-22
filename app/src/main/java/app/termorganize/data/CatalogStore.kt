// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.data

import app.termorganize.core.model.AppJson
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Catalogs downloaded from Banner, plus the last term list. A bundled snapshot is used when it
 * is newer than the download, or when the download is missing or unreadable.
 *
 * Startup opens [defaultTermCode], the newest snapshot in [bundledTermCodes]. A download of
 * some other term does not change that: the app does not store the last term the picker used.
 */
class CatalogStore(
    private val directory: File,
    private val bundled: (String) -> TermCatalog? = { null },
) {
    fun load(code: String): TermCatalog? {
        val downloaded = read(File(directory, fileName(code)))
        val snapshot = runCatching { bundled(code) }.getOrNull()
        return listOfNotNull(downloaded, snapshot).maxWithOrNull(compareBy({ freshness(it).first }, { freshness(it).second }))
    }

    fun save(catalog: TermCatalog) {
        directory.mkdirs()
        val file = File(directory, fileName(catalog.term.code))
        file.writeText(AppJson.catalog.encodeToString(TermCatalog.serializer(), catalog))
    }

    fun localCodes(): List<String> {
        val downloaded = directory.listFiles().orEmpty().mapNotNull { termCode(it.name) }
        return (downloaded + presentBundledCodes()).distinct().sortedDescending()
    }

    /**
     * Term a fresh install opens: the newest bundled snapshot [bundled] can read.
     * Newer downloads of other terms stay on disk and in the picker, and do not take this slot.
     */
    fun defaultTermCode(): String? =
        bundledTermCodes.sortedDescending().firstOrNull { present(it) }

    fun saveRemoteTerms(terms: List<Term>) {
        directory.mkdirs()
        File(directory, TERMS).writeText(AppJson.catalog.encodeToString(ListSerializer(Term.serializer()), terms))
    }

    fun loadRemoteTerms(): List<Term> {
        val file = File(directory, TERMS)
        if (!file.isFile) return emptyList()
        return runCatching {
            AppJson.catalog.decodeFromString(ListSerializer(Term.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    private fun read(file: File): TermCatalog? {
        if (!file.isFile) return null
        val catalog = runCatching { AppJson.catalog.decodeFromString(TermCatalog.serializer(), file.readText()) }.getOrNull()
        if (catalog == null) file.delete()
        return catalog
    }

    private fun freshness(catalog: TermCatalog): Pair<Double, Int> =
        catalog.sectionsUpdatedAt to catalog.courses.count { it.detailsLoaded }

    private fun presentBundledCodes(): List<String> = bundledTermCodes.filter { present(it) }

    private fun present(code: String): Boolean = runCatching { bundled(code) }.getOrNull() != null

    companion object {
        private const val TERMS = "remote-terms.json"

        /** Snapshots shipped as `assets/catalog/term-<code>.json.gz`. */
        val bundledTermCodes: List<String> = listOf("202710", "202730")

        fun fileName(code: String) = "term-$code.json"

        fun termCode(fileName: String): String? {
            val match = Regex("""term-(\d{6})\.json""").matchEntire(fileName) ?: return null
            return match.groupValues[1]
        }
    }
}
