// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.core.banner

import app.termorganize.core.model.AttributeInfo
import app.termorganize.core.model.Campus
import app.termorganize.core.model.CourseInfo
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.SectionRestriction
import app.termorganize.core.model.Subject
import app.termorganize.core.model.Term

open class FakeBannerClient(
    private val terms: List<Term> = listOf(
        Term("202710", "Fall 2026 Semester"),
        Term("202730", "Spring 2027 Semester"),
    ),
) : BannerClient() {
    override suspend fun fetchTerms(): List<Term> = terms
    override suspend fun openSession(term: String): String = "fake-session-cookie"
    override suspend fun searchSections(term: String, cookie: String, subject: String?, number: String?, offset: Int, pageSize: Int): List<BannerSection> = emptyList()
    override suspend fun refreshCourse(term: String, key: CourseKey, previous: List<CourseSection>): List<CourseSection> = previous
    override suspend fun fetchRestrictions(term: String, crns: List<String>): Map<String, List<SectionRestriction>> = emptyMap()
    override suspend fun facultyMeetings(term: String, crn: String, cookie: String): List<BannerMeetingFaculty> = emptyList()
    override suspend fun attachFaculty(term: String, sections: List<BannerSection>, onProgress: (Int, Int) -> Unit): List<BannerSection> = sections
    override suspend fun fetchSubjects(term: String): List<Subject> = emptyList()
    override suspend fun fetchCampuses(term: String): List<Campus> = emptyList()
    override suspend fun fetchAttributes(term: String): List<AttributeInfo> = emptyList()
    override suspend fun resetSearch(cookie: String) {}
    override suspend fun fetchAllSections(term: String, pageSize: Int, onProgress: (Int, Int) -> Unit): List<BannerSection> = emptyList()
    override suspend fun fetchSections(term: String, courses: List<CourseKey>): Map<CourseKey, List<BannerSection>> = emptyMap()
    override suspend fun fetchOfferedCourses(term: String, courses: List<CourseKey>): Set<CourseKey> = emptySet()
    override suspend fun fetchCourseInfo(course: CourseKey, term: String, subjects: suspend () -> BannerParsers.SubjectLookup): CourseInfo? = null
    override fun close() {}
}
