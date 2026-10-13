// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Banner registration client. Endpoint shape follows SearchNEU (AGPL-3.0). See NOTICE.md.

package app.termorganize.core.banner

import app.termorganize.core.model.AppJson
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.Subject
import app.termorganize.core.model.Term
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BannerClient(
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
    },
) {
    private val base = "https://nubanner.neu.edu/StudentRegistrationSsb/ssb/"

    suspend fun fetchTerms(max: Int = 40): List<Term> {
        val text = client.get(base + "classSearch/getTerms?offset=1&max=$max&searchTerm=").bodyAsText()
        return AppJson.catalog.decodeFromString<List<BannerTerm>>(text).map {
            Term(it.code, HTMLText.decodeEntities(it.description))
        }
    }

    suspend fun openSession(term: String): String {
        val response: HttpResponse = client.post(base + "term/search?mode=search") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("term=$term&studyPath=&studyPathText=&startDatepicker=&endDatepicker=")
        }
        val cookies = response.headers.getAll("Set-Cookie").orEmpty()
            .map { it.substringBefore(';') }
            .filter { it.contains('=') }
        if (cookies.isEmpty()) error("Banner did not start a search session")
        return cookies.joinToString("; ")
    }

    suspend fun searchSections(term: String, cookie: String, subject: String? = null, number: String? = null, offset: Int = 0, pageSize: Int = 500): List<BannerSection> {
        val query = buildString {
            append("txt_term=$term&pageOffset=$offset&pageMaxSize=$pageSize&sortColumn=subjectDescription&sortDirection=asc")
            if (subject != null) append("&txt_subject=$subject")
            if (number != null) append("&txt_courseNumber=$number")
        }
        val text = client.get(base + "searchResults/searchResults?$query") {
            header(HttpHeaders.Cookie, cookie)
        }.bodyAsText()
        return AppJson.catalog.decodeFromString<BannerSectionPage>(text).data.orEmpty()
    }

    suspend fun refreshCourse(term: String, key: CourseKey, previous: List<CourseSection>): List<CourseSection> {
        val cookie = openSession(term)
        val banner = searchSections(term, cookie, key.subject, key.number)
        val exact = banner.filter { it.subject == key.subject && it.courseNumber == key.number }
        return CatalogBuilder.makeSections(exact, previous.associateBy { it.crn })
    }

    fun close() = client.close()

    companion object {
        private val mutex = Mutex()
        suspend fun <T> shared(block: suspend (BannerClient) -> T): T = mutex.withLock {
            val client = BannerClient()
            try { block(client) } finally { client.close() }
        }
    }
}

suspend fun BannerClient.fetchSubjects(term: String): List<Subject> = emptyList()
