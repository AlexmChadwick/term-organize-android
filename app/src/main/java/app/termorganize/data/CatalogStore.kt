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
        val bundledCodes = listOf("202710").filter { runCatching { bundled(it) }.getOrNull() != null }
        return (downloaded + bundledCodes).distinct().sortedDescending()
    }

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

    companion object {
        private const val TERMS = "remote-terms.json"

        fun fileName(code: String) = "term-$code.json"

        fun termCode(fileName: String): String? {
            val match = Regex("""term-(\d{6})\.json""").matchEntire(fileName) ?: return null
            return match.groupValues[1]
        }
    }
}
