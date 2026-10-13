// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.core.model

import java.text.Normalizer
import java.util.Locale

object CourseTitleMatcher {
    const val MINIMUM_SIMILARITY = 0.6

    class Title(text: String) {
        val words: Set<String>
        val parts: Set<String>
        val qualifiers: Set<String>
        val cutOffWord: String?

        init {
            val words = LinkedHashSet<String>()
            val parts = LinkedHashSet<String>()
            var last: String? = null
            for (raw in tokens(text)) {
                val part = partOf(raw)
                if (part != null) {
                    parts.add(part)
                    last = null
                    continue
                }
                val singular = singular(raw)
                val word = SYNONYMS[singular] ?: singular
                if (word in FILLER) {
                    last = null
                    continue
                }
                words.add(word)
                last = word
            }
            this.words = words
            this.parts = parts
            qualifiers = words.intersect(QUALIFIERS)
            val trimmed = text.trim()
            cutOffWord = if (trimmed.length >= 29) last?.takeIf { it.length >= 3 } else null
        }

        val isDistinctive: Boolean get() = words.size >= 2 && !FORMATS.containsAll(words)

        companion object {
            fun tokens(text: String): List<String> {
                val folded = Normalizer.normalize(text.replace("&", " and ").replace("'", "").replace("’", ""), Normalizer.Form.NFD)
                    .replace("\\p{Mn}+".toRegex(), "")
                    .lowercase(Locale.US)
                val tokens = mutableListOf<String>()
                val current = StringBuilder()
                for (ch in folded) {
                    if (ch.isLetter() || ch.isDigit()) current.append(ch)
                    else if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                if (current.isNotEmpty()) tokens.add(current.toString())
                return tokens
            }

            fun partOf(token: String): String? {
                if (token.firstOrNull()?.isDigit() == true) return token
                return ROMAN[token]?.toString()
            }

            fun singular(word: String): String {
                if (word.length <= 3) return word
                if (word.endsWith("ies") && word.length > 4) return word.dropLast(3) + "y"
                if (word.endsWith("sses")) return word.dropLast(2)
                if (word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is")) {
                    return word.dropLast(1)
                }
                return word
            }
        }
    }

    fun similarity(a: String, b: String, minimum: Double = MINIMUM_SIMILARITY): Double? =
        similarity(Title(a), Title(b), minimum)

    fun similarity(a: Title, b: Title, minimum: Double = MINIMUM_SIMILARITY): Double? {
        if (a.parts.isNotEmpty() && b.parts.isNotEmpty() && a.parts != b.parts) return null
        if (a.qualifiers != b.qualifiers) return null
        val aWords = completing(a, b.words)
        val bWords = completing(b, a.words)
        if (aWords.size < 2 || bWords.size < 2) return null
        val shared = aWords.intersect(bWords).size
        if (shared == 0) return null
        if (FORMATS.containsAll(aWords) || FORMATS.containsAll(bWords)) return null
        val score = shared.toDouble() / aWords.union(bWords).size
        if (score >= minimum) return score
        val contained = shared == aWords.size || shared == bWords.size
        return if (contained) minimum else null
    }

    private fun completing(title: Title, other: Set<String>): Set<String> {
        val cut = title.cutOffWord ?: return title.words
        if (cut in other) return title.words
        val full = other.sorted().firstOrNull { it.startsWith(cut) && it.length > cut.length } ?: return title.words
        return title.words - cut + full
    }

    private val FILLER = setOf(
        "a", "an", "and", "as", "at", "by", "for", "from", "in", "into", "of", "on", "or", "the", "to", "with",
        "principle", "introduction", "introductory", "intro", "fundamental", "topic",
        "prncple", "prncpl", "intr", "introd",
    )
    private val FORMATS = setOf(
        "special", "directed", "independent", "study", "research", "thesis", "dissertation", "seminar", "project",
        "practicum", "capstone", "continuation", "experience", "internship", "co", "op", "work", "term", "workshop",
        "lab", "reading", "tutorial", "residency", "preparation",
    )
    private val QUALIFIERS = setOf("lab", "recitation", "advanced", "intermediate", "elementary")
    private val SYNONYMS = mapOf("laboratory" to "lab")
    private val ROMAN = mapOf(
        "i" to 1, "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9, "x" to 10,
    )
}
