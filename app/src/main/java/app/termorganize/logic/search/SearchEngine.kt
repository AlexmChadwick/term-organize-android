// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.search

import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.NUPath
import java.text.Normalizer
import java.util.Locale

enum class SearchSort(val label: String) { RELEVANCE("Best Match"), COURSE_NUMBER("Course Number"), TITLE("Title"), OPEN_SEATS("Most Open Seats") }

enum class InstructionFilter(val label: String) { ANY("Any"), IN_PERSON("In Person"), ONLINE("Online") }

data class SearchFilters(
    val subjects: Set<String> = emptySet(),
    val nupaths: Set<NUPath> = emptySet(),
    val levels: Set<Int> = emptySet(),
    val minCredits: Double? = null,
    val maxCredits: Double? = null,
    val campuses: Set<String> = emptySet(),
    val classTypes: Set<String> = emptySet(),
    val honorsOnly: Boolean = false,
    val openSeatsOnly: Boolean = false,
    val instruction: InstructionFilter = InstructionFilter.ANY,
    val allowedDays: DaySet? = null,
    val startsAfter: Int? = null,
    val endsBefore: Int? = null,
) {
    val activeCount: Int
        get() {
            var n = 0
            if (subjects.isNotEmpty()) n++
            if (nupaths.isNotEmpty()) n++
            if (levels.isNotEmpty()) n++
            if (minCredits != null || maxCredits != null) n++
            if (campuses.isNotEmpty()) n++
            if (classTypes.isNotEmpty()) n++
            if (honorsOnly) n++
            if (openSeatsOnly) n++
            if (instruction != InstructionFilter.ANY) n++
            if (allowedDays != null) n++
            if (startsAfter != null || endsBefore != null) n++
            return n
        }
    val isEmpty: Boolean get() = activeCount == 0
    val hasSectionFilters: Boolean
        get() = campuses.isNotEmpty() || classTypes.isNotEmpty() || honorsOnly || openSeatsOnly ||
            instruction != InstructionFilter.ANY || allowedDays != null || startsAfter != null || endsBefore != null

    fun matches(course: Course): Boolean {
        if (subjects.isNotEmpty() && course.subject !in subjects) return false
        if (nupaths.isNotEmpty()) {
            val attrs = course.attributes.toSet()
            if (!nupaths.all { it.attributeCode in attrs }) return false
        }
        if (levels.isNotEmpty()) {
            val level = course.key.level ?: return false
            if (level !in levels) return false
        }
        if (minCredits != null && course.maxCredits < minCredits) return false
        if (maxCredits != null && course.minCredits > maxCredits) return false
        return true
    }

    fun matches(section: CourseSection): Boolean {
        if (campuses.isNotEmpty() && section.campus !in campuses) return false
        if (classTypes.isNotEmpty() && section.classType !in classTypes) return false
        if (honorsOnly && !section.honors) return false
        if (openSeatsOnly && !section.isOpen) return false
        when (instruction) {
            InstructionFilter.ANY -> Unit
            InstructionFilter.ONLINE -> if (!section.isOnline) return false
            InstructionFilter.IN_PERSON -> if (section.isOnline) return false
        }
        val weekly = section.weeklyMeetings
        val days = allowedDays
        if (days != null) {
            for (m in weekly) if (!m.days.isSubsetOf(days)) return false
        }
        val after = startsAfter
        if (after != null) for (m in weekly) if ((m.start ?: 0) < after) return false
        val before = endsBefore
        if (before != null) for (m in weekly) if ((m.end ?: 0) > before) return false
        return true
    }
}

data class SearchQuery(val text: String = "", val filters: SearchFilters = SearchFilters(), val sort: SearchSort = SearchSort.RELEVANCE)

data class SearchResult(
    val course: Course,
    val stats: CourseStats,
    val score: Double,
    val matchNote: String? = null,
) {
    val id: CourseKey get() = course.key
}

data class SearchResponse(
    val results: List<SearchResult> = emptyList(),
    val crnMatch: CourseSection? = null,
    val instructors: List<InstructorSummary> = emptyList(),
    val subjects: List<app.termorganize.core.model.Subject> = emptyList(),
) {
    val totalCount: Int get() = results.size
}

data class SearchDocument(
    val key: CourseKey,
    val subjectLower: String,
    val numberLower: String,
    val titleNormalized: String,
    val titleWords: List<String>,
    val subjectWords: List<String>,
    val instructorWords: List<String>,
    val instructorNames: List<String>,
    val descriptionWords: Set<String>,
    val topicWords: List<String>,
    val nupathShorts: Set<String>,
) {
    constructor(course: Course, subjectName: String, instructorNames: List<String>, sectionTitles: List<String>) : this(
        key = course.key,
        subjectLower = course.subject.lowercase(Locale.US),
        numberLower = course.number.lowercase(Locale.US),
        titleNormalized = SearchText.normalize(course.title),
        titleWords = SearchText.words(course.title),
        subjectWords = SearchText.words(subjectName),
        instructorNames = instructorNames,
        instructorWords = instructorNames.flatMap { SearchText.words(it) },
        descriptionWords = SearchText.words(course.description).filter { it.length >= 3 }.toSet(),
        topicWords = sectionTitles.flatMap { SearchText.words(it) },
        nupathShorts = course.nupaths.map { it.short.lowercase(Locale.US) }.toSet(),
    )
}

object SearchText {
    val stopWords = setOf("and", "of", "the", "to", "in", "for", "a", "an", "with", "on", "at", "intro", "introduction")
    val aliases = mapOf(
        "orgo" to "organic chemistry", "ochem" to "organic chemistry", "linalg" to "linear algebra",
        "diffeq" to "differential equations", "diffeqs" to "differential equations", "stats" to "statistics",
        "stat" to "statistics", "econ" to "economics", "psych" to "psychology", "chem" to "chemistry",
        "ml" to "machine learning", "ai" to "artificial intelligence", "hci" to "human computer interaction",
        "ood" to "object oriented", "algo" to "algorithms", "algos" to "algorithms", "swe" to "software engineering",
        "nlp" to "natural language processing", "db" to "databases", "dbs" to "databases", "fundies" to "program design",
        "compsci" to "computer science", "cs1" to "program design implementation 1", "discrete" to "discrete",
        "theory" to "theory", "calc" to "calculus", "bio" to "biology", "phys" to "physics", "intl" to "international",
        "mgmt" to "management", "acct" to "accounting", "mktg" to "marketing", "philo" to "philosophy", "poli" to "political",
        "polisci" to "political science", "comm" to "communication", "comms" to "communication", "lab" to "lab",
    )

    fun normalize(s: String): String {
        val folded = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.US)
        val out = StringBuilder()
        var lastWasSpace = true
        for (ch in folded) {
            if (ch.isLetter() || ch.isDigit()) {
                out.append(ch)
                lastWasSpace = false
            } else if (ch == '&') {
                if (!lastWasSpace) out.append(' ')
                out.append("and ")
                lastWasSpace = true
            } else if (!lastWasSpace) {
                out.append(' ')
                lastWasSpace = true
            }
        }
        return out.toString().trim()
    }

    fun expandAliases(normalized: String, subjectCodes: Set<String>): String =
        normalized.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            if (word.uppercase(Locale.US) in subjectCodes) word else aliases[word] ?: word
        }

    fun words(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    fun editDistance(a: String, b: String, limit: Int): Int {
        if (a.isEmpty() || b.isEmpty()) return maxOf(a.length, b.length)
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, curr[j])
            }
            if (rowMin > limit) return limit + 1
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}

object SearchEngine {
    fun search(query: SearchQuery, index: CatalogIndex): SearchResponse {
        val text = query.text.trim()
        val filters = query.filters
        fun passes(course: Course) = filters.matches(course) &&
            (!filters.hasSectionFilters || index.sections(course.key).any { filters.matches(it) })
        fun stats(course: Course): CourseStats =
            if (!filters.hasSectionFilters) index.stats(course.key)
            else CatalogIndex.makeStats(index.sections(course.key).filter { filters.matches(it) })

        var crn: CourseSection? = null
        if (text.length == 5 && text.all { it.isDigit() }) crn = index.section(text)

        if (text.isEmpty()) {
            val scored = index.courses.mapNotNull { course ->
                if (!passes(course)) null else SearchResult(course, stats(course), 0.0)
            }
            val sort = if (query.sort == SearchSort.RELEVANCE) SearchSort.COURSE_NUMBER else query.sort
            return SearchResponse(sort(scored, sort), crn)
        }

        val rawNormalized = SearchText.normalize(text)
        val normalized = SearchText.expandAliases(rawNormalized, index.subjectsByCode.keys)
        val tokens = normalized.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return SearchResponse(crnMatch = crn)

        val subjects = index.activeSubjects.filter { subject ->
            val name = SearchText.normalize(subject.name)
            subject.code.lowercase(Locale.US) == rawNormalized || name == rawNormalized || name == normalized ||
                (rawNormalized.length >= 4 && name.startsWith(rawNormalized))
        }
        val rawTokens = rawNormalized.split(' ').filter { it.isNotEmpty() }
        val instructors = if (rawTokens.all { it.length >= 2 } && !rawTokens.all { t -> t.all { it.isDigit() } }) {
            index.instructors.filter { instructor ->
                val words = SearchText.words(instructor.displayName)
                rawTokens.all { t -> words.any { it.startsWith(t) } }
            }.take(8)
        } else emptyList()

        val code = parseCourseCode(rawNormalized, index.subjectsByCode)
        val scored = mutableListOf<SearchResult>()
        for (doc in index.searchDocuments) {
            val course = index.course(doc.key) ?: continue
            val scoredMatch: Pair<Double, String?> = if (code != null) {
                if (doc.subjectLower != code.first) continue
                val score = when {
                    code.second.isEmpty() -> 600.0 - doc.key.numericValue / 100.0
                    doc.numberLower == code.second -> 2000.0
                    doc.numberLower.startsWith(code.second) -> 1200.0 - (doc.numberLower.length - code.second.length)
                    else -> continue
                }
                score to null
            } else {
                scoreTokens(tokens, normalized, doc) ?: continue
            }
            if (!passes(course)) continue
            scored += SearchResult(course, stats(course), scoredMatch.first, scoredMatch.second)
        }
        return SearchResponse(sort(scored, query.sort), crn, instructors, subjects)
    }

    private fun parseCourseCode(normalized: String, subjects: Map<String, app.termorganize.core.model.Subject>): Pair<String, String>? {
        val compact = normalized.replace(" ", "")
        val letters = compact.takeWhile { it.isLetter() }
        val rest = compact.drop(letters.length)
        if (letters.length !in 2..5 || subjects[letters.uppercase(Locale.US)] == null) return null
        if (rest.any { !it.isDigit() && !it.isLetter() } || rest.length > 4) return null
        if (rest.isEmpty()) return if (normalized == letters) letters to "" else null
        if (rest.first().isDigit().not()) return null
        return letters to rest
    }

    private fun scoreTokens(tokens: List<String>, phrase: String, doc: SearchDocument): Pair<Double, String?>? {
        var total = 0.0
        var titleHits = 0
        var note: String? = null
        val meaningful = tokens.filter { it !in SearchText.stopWords }
        val wantsIntro = tokens.any { it == "intro" || it == "introduction" || it == "introductory" }
        for (token in if (meaningful.isEmpty()) tokens else meaningful) {
            var best = 0.0
            var bestNote: String? = null
            if (token == doc.subjectLower) best = maxOf(best, 50.0)
            if (token == doc.numberLower) best = maxOf(best, 60.0)
            else if (token.all { it.isDigit() } && doc.numberLower.startsWith(token)) best = maxOf(best, 30.0)
            if (token in doc.titleWords) {
                best = maxOf(best, 40.0); titleHits++
            } else if (token.length >= 2 && doc.titleWords.any { it.startsWith(token) }) {
                best = maxOf(best, 28.0); titleHits++
            }
            if (doc.subjectWords.any { it.startsWith(token) }) best = maxOf(best, if (token.length >= 3) 20.0 else 8.0)
            if (token in doc.nupathShorts) best = maxOf(best, 15.0)
            if (best == 0.0 && token.length >= 2 && doc.topicWords.any { it.startsWith(token) }) {
                best = 24.0; bestNote = "Topic match"
            }
            if (best == 0.0 && token.length >= 2 && doc.instructorWords.any { it.startsWith(token) }) {
                best = 22.0
                val name = doc.instructorNames.firstOrNull { SearchText.words(it).any { w -> w.startsWith(token) } }
                if (name != null) bestNote = "Taught by $name"
            }
            if (best == 0.0 && token.length >= 3 && (token in doc.descriptionWords || (token.length >= 5 && doc.descriptionWords.any { it.startsWith(token) }))) {
                best = 6.0; bestNote = "Description mentions \u201c$token\u201d"
            }
            if (best == 0.0 && token.length >= 4) {
                val limit = if (token.length >= 7) 2 else 1
                if (doc.titleWords.any {
                        SearchText.editDistance(it, token, limit) <= limit ||
                            (it.length > token.length && SearchText.editDistance(it.take(token.length), token, 1) <= 1)
                    }
                ) {
                    best = 12.0; titleHits++
                }
            }
            if (best == 0.0) return null
            total += best
            if (note == null) note = bestNote
        }
        if (doc.titleNormalized == phrase) total += 150
        else if (doc.titleNormalized.startsWith(phrase)) total += 80
        else if (phrase.length >= 4 && phrase in doc.titleNormalized) total += 50
        if (titleHits >= maxOf(1, meaningful.size)) {
            total += 25; note = null
        }
        if (wantsIntro) {
            val level = doc.key.level
            if (level == 1) total += 15 else if (level == 2) total += 6
        }
        total -= doc.key.numericValue / 10_000.0
        return total to note
    }

    fun sort(results: List<SearchResult>, sort: SearchSort): List<SearchResult> = when (sort) {
        SearchSort.RELEVANCE -> results.sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.course.key })
        SearchSort.COURSE_NUMBER -> results.sortedBy { it.course.key }
        SearchSort.TITLE -> results.sortedBy { it.course.title.lowercase(Locale.US) }
        SearchSort.OPEN_SEATS -> results.sortedWith(compareByDescending<SearchResult> { it.stats.seatsRemaining }.thenBy { it.course.key })
    }
}
