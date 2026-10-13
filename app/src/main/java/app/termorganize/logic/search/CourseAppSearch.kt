// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.search

import android.content.Context
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import androidx.appsearch.platformstorage.PlatformStorage
import app.termorganize.core.model.Course
import java.util.concurrent.TimeUnit

/**
 * On-device course index. In-app search still uses [SearchEngine]. This index is consulted only
 * when that search returns nothing, and it is also published to the system AppSearch service
 * when the device has one.
 */
object CourseAppSearch {
    data class Hit(val subject: String, val number: String, val title: String) {
        val code: String get() = "$subject $number"
    }

    private const val DATABASE = "termorganize-courses"
    private const val SCHEMA = "Course"
    private val lock = Any()

    @Volatile
    private var namespace: String = "catalog"

    fun index(context: Context, termCode: String, courses: List<Course>): Boolean = synchronized(lock) {
        if (courses.isEmpty()) return false
        namespace = termCode
        val documents = courses.map { course ->
            GenericDocument.Builder<GenericDocument.Builder<*>>(termCode, "${course.subject}_${course.number}", SCHEMA)
                .setPropertyString("code", course.code)
                .setPropertyString("title", course.title)
                .setPropertyString("subject", course.subject)
                .setPropertyString("number", course.number)
                .setPropertyString("description", course.description)
                .build()
        }
        val local = runCatching { put(localSession(context), documents) }.isSuccess
        runCatching {
            val session = platformSession(context) ?: return@runCatching
            put(session, documents)
        }
        local
    }

    fun search(context: Context, query: String): List<Hit> = synchronized(lock) {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        runCatching { query(localSession(context), text) }.getOrDefault(emptyList())
    }

    private fun put(session: androidx.appsearch.app.AppSearchSession, documents: List<GenericDocument>) {
        session.setSchemaAsync(
            SetSchemaRequest.Builder().addSchemas(schema()).setForceOverride(true).build(),
        ).get(30, TimeUnit.SECONDS)
        var start = 0
        while (start < documents.size) {
            val end = minOf(start + 200, documents.size)
            val request = PutDocumentsRequest.Builder().addGenericDocuments(documents.subList(start, end)).build()
            session.putAsync(request).get(60, TimeUnit.SECONDS)
            start = end
        }
    }

    private fun query(session: androidx.appsearch.app.AppSearchSession, text: String): List<Hit> {
        val spec = SearchSpec.Builder()
            .addFilterSchemas(SCHEMA)
            .addFilterNamespaces(namespace)
            .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
            .setResultCountPerPage(20)
            .build()
        val results = session.search(text, spec)
        val page = results.nextPageAsync.get(15, TimeUnit.SECONDS)
        results.close()
        return page.mapNotNull { result ->
            val document = result.genericDocument
            val subject = document.getPropertyString("subject")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val number = document.getPropertyString("number").orEmpty()
            Hit(subject, number, document.getPropertyString("title").orEmpty())
        }
    }

    private fun schema(): AppSearchSchema = AppSearchSchema.Builder(SCHEMA)
        .addProperty(
            AppSearchSchema.StringPropertyConfig.Builder("code")
                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                .build(),
        )
        .addProperty(
            AppSearchSchema.StringPropertyConfig.Builder("title")
                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                .build(),
        )
        .addProperty(
            AppSearchSchema.StringPropertyConfig.Builder("subject")
                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                .build(),
        )
        .addProperty(
            AppSearchSchema.StringPropertyConfig.Builder("number")
                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                .build(),
        )
        .addProperty(
            AppSearchSchema.StringPropertyConfig.Builder("description")
                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                .build(),
        )
        .build()

    private var local: androidx.appsearch.app.AppSearchSession? = null
    private var platform: androidx.appsearch.app.AppSearchSession? = null

    private fun localSession(context: Context): androidx.appsearch.app.AppSearchSession {
        local?.let { return it }
        val created = LocalStorage.createSearchSessionAsync(
            LocalStorage.SearchContext.Builder(context, DATABASE).build(),
        ).get(30, TimeUnit.SECONDS)
        local = created
        return created
    }

    private fun platformSession(context: Context): androidx.appsearch.app.AppSearchSession? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        platform?.let { return it }
        val created = PlatformStorage.createSearchSessionAsync(
            PlatformStorage.SearchContext.Builder(context, DATABASE).build(),
        ).get(30, TimeUnit.SECONDS)
        platform = created
        return created
    }
}
