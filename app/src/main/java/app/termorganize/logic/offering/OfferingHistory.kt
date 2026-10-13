// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.offering

import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.Term
import app.termorganize.logic.degree.PlanSeason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.round

/**
 * In which recent Banner terms courses had sections, so a plan can avoid a semester a course
 * has never been taught in. A course is "likely offered" in a season when it had sections in
 * at least one checked term of that season. Summer 1, Summer 2, and the full summer all count
 * as summer.
 */
data class OfferingHistory(
    val checkedTerms: List<String> = emptyList(),
    val offered: Map<CourseKey, Set<String>> = emptyMap(),
    val checkedKeys: Set<CourseKey>? = null,
) {
    fun isChecked(key: CourseKey): Boolean = checkedTerms.isNotEmpty() && (checkedKeys?.contains(key) ?: true)

    /** Checked terms in which the course had sections, newest first. */
    fun offeredTerms(key: CourseKey): List<String> {
        val terms = offered[key] ?: return emptyList()
        return checkedTerms.filter { it in terms }
    }

    fun seasons(key: CourseKey): Set<PlanSeason> =
        offeredTerms(key).map { PlanSeason.fromTerm(termSeason(it)) }.toSet()

    /**
     * True when the course had sections in a checked term of [season], false when it had none,
     * and null when that can't be said.
     */
    fun isLikelyOffered(key: CourseKey, season: PlanSeason): Boolean? {
        if (!isChecked(key)) return null
        val relevant = checkedTerms.filter { season.matches(termSeason(it)) }
        if (relevant.isEmpty()) return null
        val terms = offered[key].orEmpty()
        return relevant.any { it in terms }
    }

    fun summary(key: CourseKey): String {
        if (!isChecked(key)) return "Offering history unknown"
        val terms = offeredTerms(key)
        val latest = terms.firstOrNull()
        if (latest == null) {
            val years = checkedYears
            return if (years == 1) "Not offered in the last year" else "Not offered in the last $years years"
        }
        val regular = Family.entries.filter { isRegular(it, key) }
        if (regular.isEmpty()) return "Last offered ${label(latest)}"
        val others = Family.entries.filter { family ->
            family !in regular && terms.any { familyOf(termSeason(it)) == family }
        }
        val regularly = "Offered every " + list(regular.map { it.wire })
        return if (others.isEmpty()) regularly else "$regularly, and sometimes in the ${list(others.map { it.wire })}"
    }

    fun summary(key: CourseKey, season: PlanSeason): String {
        if (isLikelyOffered(key, season) == null) return "Offering history unknown"
        val family = familyOf(season)
        val relevant = checkedTerms.filter { season.matches(termSeason(it)) }
        val terms = offered[key].orEmpty()
        val latest = relevant.firstOrNull { it in terms }
        if (latest == null) {
            val years = checkedYears
            val span = if (years == 1) "the last year" else "the last $years years"
            val name = family?.wire ?: season.shortName.lowercase()
            return "Not offered in a $name term in $span"
        }
        if (family != null && isRegular(family, key)) return "Offered every ${family.wire}"
        return "Offered in ${label(latest)}"
    }

    fun merging(other: OfferingHistory): OfferingHistory {
        val terms = (checkedTerms + other.checkedTerms).distinct().sortedDescending()
        val combined = HashMap<CourseKey, MutableSet<String>>()
        for ((key, codes) in offered) combined.getOrPut(key) { mutableSetOf() }.addAll(codes)
        for ((key, codes) in other.offered) combined.getOrPut(key) { mutableSetOf() }.addAll(codes)
        val keys = if (checkedKeys != null && other.checkedKeys != null) checkedKeys + other.checkedKeys else null
        return OfferingHistory(terms, combined.mapValues { it.value.toSet() }, keys)
    }

    fun toJson(): String {
        val root = buildJsonObject {
            put("checkedTerms", JsonArray(checkedTerms.map { JsonPrimitive(it) }))
            put("offered", buildJsonObject {
                for ((key, codes) in offered) put(key.description, JsonArray(codes.sortedDescending().map { JsonPrimitive(it) }))
            })
            if (checkedKeys != null) {
                put("checkedKeys", JsonArray(checkedKeys.map { it.description }.sorted().map { JsonPrimitive(it) }))
            }
        }
        return root.toString()
    }

    private fun isRegular(family: Family, key: CourseKey): Boolean {
        val offeredTerms = offered[key].orEmpty()
        val periods = HashMap<Int, Boolean>()
        for (code in checkedTerms) {
            if (familyOf(termSeason(code)) != family) continue
            val year = Term(code, "").calendarYear
            periods[year] = (periods[year] ?: false) || code in offeredTerms
        }
        return periods.size >= 2 && periods.values.none { !it }
    }

    private val checkedYears: Int
        get() {
            val newest = checkedTerms.firstOrNull() ?: return 1
            val oldest = checkedTerms.lastOrNull() ?: return 1
            val months = monthIndex(newest) - monthIndex(oldest)
            return max(1, round(months / 12.0).toInt())
        }

    private enum class Family(val wire: String) {
        FALL("fall"),
        SPRING("spring"),
        SUMMER("summer"),
    }

    companion object {
        val empty = OfferingHistory()

        fun monthIndex(code: String): Int {
            val term = Term(code, "")
            return term.calendarYear * 12 + PlanSeason.fromTerm(term.season).startMonth - 1
        }

        fun label(code: String): String {
            val term = Term(code, "")
            return "${term.season.displayName} ${term.calendarYear}"
        }

        fun fromJson(text: String): OfferingHistory {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            val terms = root["checkedTerms"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val offered = HashMap<CourseKey, Set<String>>()
            val raw = root["offered"]?.jsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
            for ((code, value) in raw) {
                val key = CourseKey.parse(code) ?: continue
                offered[key] = value.jsonArray.map { it.jsonPrimitive.content }.toSet()
            }
            val checked = root["checkedKeys"]?.jsonArray?.mapNotNull { CourseKey.parse(it.jsonPrimitive.content) }?.toSet()
            return OfferingHistory(terms, offered, if ("checkedKeys" in root) checked else null)
        }

        private fun termSeason(code: String) = Term(code, "").season

        private fun familyOf(season: Term.Season): Family? = when (season) {
            Term.Season.FALL -> Family.FALL
            Term.Season.SPRING -> Family.SPRING
            Term.Season.SUMMER1, Term.Season.SUMMER_FULL, Term.Season.SUMMER2 -> Family.SUMMER
            Term.Season.WINTER -> null
        }

        private fun familyOf(season: PlanSeason): Family? = when (season) {
            PlanSeason.FALL -> Family.FALL
            PlanSeason.SPRING -> Family.SPRING
            PlanSeason.SUMMER_A, PlanSeason.SUMMER_FULL, PlanSeason.SUMMER_B -> Family.SUMMER
            PlanSeason.WINTER -> null
        }

        private fun list(items: List<String>): String = when (items.size) {
            0, 1 -> items.firstOrNull() ?: ""
            2 -> "${items[0]} and ${items[1]}"
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }
    }
}
