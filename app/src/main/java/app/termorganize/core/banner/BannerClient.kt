// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Banner registration client. Endpoint shape follows SearchNEU (AGPL-3.0). See NOTICE.md.

package app.termorganize.core.banner

import app.termorganize.core.model.AppJson
import app.termorganize.core.model.AttributeInfo
import app.termorganize.core.model.Campus
import app.termorganize.core.model.CourseInfo
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
import java.util.concurrent.TimeUnit

class BannerClient(
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                connectTimeout(8, TimeUnit.SECONDS)
                readTimeout(20, TimeUnit.SECONDS)
                callTimeout(25, TimeUnit.SECONDS)
            }
        }
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

    suspend fun fetchSubjects(term: String): List<Subject> =
        fetchCodes("classSearch/get_subject", term, 900).map { Subject(it.code, HTMLText.decodeEntities(it.description).trim()) }

    suspend fun fetchCampuses(term: String): List<Campus> =
        fetchCodes("classSearch/get_campus", term, 200).map { Campus(it.code, HTMLText.decodeEntities(it.description).trim()) }

    suspend fun fetchAttributes(term: String): List<AttributeInfo> =
        fetchCodes("classSearch/get_attribute", term, 500).map { AttributeInfo(it.code, HTMLText.decodeEntities(it.description).trim()) }

    suspend fun resetSearch(cookie: String) {
        client.post(base + "classSearch/resetDataForm") {
            header(HttpHeaders.Cookie, cookie)
        }
    }

    /**
     * Every section in a term. Pages until Banner returns a short page or the reported total.
     * A safety cap keeps a bad total from paging forever.
     */
    suspend fun fetchAllSections(
        term: String,
        pageSize: Int = 500,
        onProgress: (fetched: Int, total: Int) -> Unit = { _, _ -> },
    ): List<BannerSection> {
        val cookie = openSession(term)
        val probe = searchPage(term, cookie, offset = 0, pageSize = 1)
        val total = probe.totalCount
        if (total <= 0) return emptyList()
        onProgress(0, total)
        val all = ArrayList<BannerSection>(total)
        val seen = HashSet<String>()
        var page = 0
        while (all.size < total && page < 80) {
            val data = searchSections(term, cookie, offset = page * pageSize, pageSize = pageSize)
            if (data.isEmpty()) break
            for (section in data) if (seen.add(section.courseReferenceNumber)) all += section
            onProgress(minOf(all.size, total), total)
            page += 1
            if (data.size < pageSize) break
        }
        return all
    }

    /**
     * Live sections for specific courses. One session, reset between courses.
     * Banner treats the course number as a prefix, so only exact matches are kept.
     */
    suspend fun fetchSections(term: String, courses: List<CourseKey>): Map<CourseKey, List<BannerSection>> {
        if (courses.isEmpty()) return emptyMap()
        val cookie = openSession(term)
        val out = LinkedHashMap<CourseKey, List<BannerSection>>()
        courses.forEachIndexed { index, key ->
            if (index > 0) resetSearch(cookie)
            val page = searchSections(term, cookie, key.subject, key.number, pageSize = 500)
            out[key] = page.filter { it.subject == key.subject && it.courseNumber == key.number }
        }
        return out
    }

    /** Which of [courses] have at least one exact section in [term]. One session, reset between courses. */
    suspend fun fetchOfferedCourses(term: String, courses: List<CourseKey>): Set<CourseKey> {
        if (courses.isEmpty()) return emptySet()
        val cookie = openSession(term)
        val offered = mutableSetOf<CourseKey>()
        courses.forEachIndexed { index, key ->
            if (index > 0) resetSearch(cookie)
            val page = searchSections(term, cookie, key.subject, key.number, pageSize = 20)
            if (page.any { it.subject == key.subject && it.courseNumber == key.number }) offered += key
        }
        return offered
    }

    /**
     * Catalog entry for one course in one term: title, credits, requisites, and mutual exclusions.
     * Null when that term's catalog does not list the course.
     */
    suspend fun fetchCourseInfo(
        course: CourseKey,
        term: String,
        subjects: suspend () -> BannerParsers.SubjectLookup,
    ): CourseInfo? {
        if (!catalogLists(course, term)) return null
        val detailsHtml = postCourseForm("courseSearchResults/getCourseCatalogDetails", term, course)
        val prerequisitesHtml = postCourseForm("courseSearchResults/getPrerequisites", term, course)
        val corequisitesHtml = postCourseForm("courseSearchResults/getCorequisites", term, course)
        val exclusionsHtml = postCourseForm("courseSearchResults/getCourseMutuallyExclusions", term, course)
        val lookup = subjects()
        val details = BannerParsers.catalogDetails(detailsHtml)
        return CourseInfo(
            key = course,
            title = details.title.orEmpty(),
            minCredits = details.minCredits,
            maxCredits = details.maxCredits,
            prerequisites = BannerParsers.prerequisites(prerequisitesHtml, lookup),
            corequisites = BannerParsers.corequisites(corequisitesHtml, lookup),
            equivalents = BannerParsers.mutualExclusions(exclusionsHtml, lookup),
            sourceTerm = term,
            fetchedAt = System.currentTimeMillis() / 1000.0,
        )
    }

    private suspend fun catalogLists(course: CourseKey, term: String): Boolean {
        val query = "searchTerm=${course.subject}%20${course.number}&term=$term&offset=1&max=10"
        val text = client.get(base + "courseSearch/get_subjectcoursecombo?$query").bodyAsText()
        val items = runCatching { AppJson.catalog.decodeFromString<List<BannerCodeDescription>>(text) }.getOrElse { return false }
        return BannerParsers.catalogCodes(items, course)
    }

    private suspend fun postCourseForm(path: String, term: String, course: CourseKey): String {
        val response = client.post(base + path) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("courseNumber=${course.number}&subjectCode=${course.subject}&term=$term")
        }
        return response.bodyAsText()
    }

    private suspend fun searchPage(term: String, cookie: String, offset: Int, pageSize: Int): BannerSectionPage {
        val query = "txt_term=$term&pageOffset=$offset&pageMaxSize=$pageSize&sortColumn=subjectDescription&sortDirection=asc"
        val text = client.get(base + "searchResults/searchResults?$query") {
            header(HttpHeaders.Cookie, cookie)
        }.bodyAsText()
        return AppJson.catalog.decodeFromString(BannerSectionPage.serializer(), text)
    }

    private suspend fun fetchCodes(path: String, term: String, max: Int): List<BannerCodeDescription> {
        val text = client.get(base + "$path?searchTerm=&term=$term&offset=1&max=$max").bodyAsText()
        return AppJson.catalog.decodeFromString(text)
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
