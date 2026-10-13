// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.degree

import app.termorganize.core.model.AppJson
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.Term
import app.termorganize.logic.schedule.ScheduleFilters
import app.termorganize.logic.schedule.SchedulePreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

const val COURSE_COLOR_COUNT = 10

@Serializable
data class PlanCourse(
    val key: CourseKey,
    val excludedCRNs: Set<String> = emptySet(),
    val colorIndex: Int = 0,
) {
    val id: CourseKey get() = key
}

@Serializable
data class SchedulePlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val termCode: String,
    val courses: List<PlanCourse> = emptyList(),
    val selectedCRNs: List<String> = emptyList(),
    val filters: ScheduleFilters = ScheduleFilters(),
    val preferences: SchedulePreferences? = null,
    val favoriteCombinations: List<List<String>> = emptyList(),
    val isPrimary: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun contains(key: CourseKey) = courses.any { it.key == key }
    val nextColorIndex: Int
        get() {
            val used = courses.map { it.colorIndex }.toSet()
            return (0 until COURSE_COLOR_COUNT).firstOrNull { it !in used } ?: courses.size % COURSE_COLOR_COUNT
        }
}

@Serializable
data class WatchItem(
    val termCode: String,
    val crn: String,
    val courseKey: CourseKey,
    val courseTitle: String,
    val sectionLabel: String,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeatsRemaining: Int,
    val lastWaitlistRemaining: Int,
    val seatCapacity: Int,
    val lastCheckedAt: Long? = null,
    val notifyOnWaitlist: Boolean = false,
    val lastNotifiedAt: Long? = null,
) {
    val id: String get() = "$termCode|$crn"
}

@Serializable
data class SavedCourse(val key: CourseKey, val termCode: String, val savedAt: Long = System.currentTimeMillis())

@Serializable
data class RecentCourse(val key: CourseKey, val termCode: String, val openedAt: Long = System.currentTimeMillis())

@Serializable
data class AppPreferences(
    val selectedTermCode: String? = null,
    val preferredCampus: String? = null,
    val hasCompletedOnboarding: Boolean = false,
    val showWeekends: Boolean = false,
    val autoRefreshSeats: Boolean = true,
    val backgroundRefreshEnabled: Boolean = true,
    val calendarStartHour: Int = 8,
    val calendarEndHour: Int = 21,
    val dynamicColor: Boolean = true,
    val darkTheme: String = "system",
)

enum class PlanSeason {
    WINTER, SPRING, SUMMER_A, SUMMER_FULL, SUMMER_B, FALL,
    ;

    val exportName: String
        get() = when (this) {
            WINTER -> "Winter"
            SPRING -> "Spring"
            SUMMER_A -> "Summer Session A"
            SUMMER_FULL -> "Summer Full"
            SUMMER_B -> "Summer Session B"
            FALL -> "Fall"
        }
    val shortName: String
        get() = when (this) {
            SUMMER_A -> "Summer A"
            SUMMER_B -> "Summer B"
            SUMMER_FULL -> "Summer"
            else -> exportName
        }
    val startMonth: Int
        get() = when (this) {
            WINTER, SPRING -> 1
            SUMMER_A, SUMMER_FULL -> 5
            SUMMER_B -> 7
            FALL -> 9
        }
    val orderWithinMonth: Int
        get() = when (this) {
            WINTER -> 0
            SPRING -> 1
            SUMMER_A -> 0
            SUMMER_FULL -> 1
            else -> 0
        }

    fun matches(season: Term.Season): Boolean = when (this to season) {
        FALL to Term.Season.FALL, SPRING to Term.Season.SPRING, WINTER to Term.Season.WINTER -> true
        SUMMER_A to Term.Season.SUMMER1, SUMMER_A to Term.Season.SUMMER_FULL -> true
        SUMMER_B to Term.Season.SUMMER2, SUMMER_B to Term.Season.SUMMER_FULL -> true
        SUMMER_FULL to Term.Season.SUMMER_FULL, SUMMER_FULL to Term.Season.SUMMER1, SUMMER_FULL to Term.Season.SUMMER2 -> true
        else -> false
    }

    companion object {
        fun fromExportName(raw: String): PlanSeason? {
            val s = raw.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            return when (s) {
                "fall", "autumn" -> FALL
                "spring" -> SPRING
                "winter" -> WINTER
                "summer session a", "summer a", "summer 1", "summer i", "summer session 1" -> SUMMER_A
                "summer session b", "summer b", "summer 2", "summer ii", "summer session 2" -> SUMMER_B
                "summer full", "summer", "full summer", "summer session full" -> SUMMER_FULL
                else -> null
            }
        }

        fun fromTerm(season: Term.Season): PlanSeason = when (season) {
            Term.Season.FALL -> FALL
            Term.Season.WINTER -> WINTER
            Term.Season.SPRING -> SPRING
            Term.Season.SUMMER1 -> SUMMER_A
            Term.Season.SUMMER_FULL -> SUMMER_FULL
            Term.Season.SUMMER2 -> SUMMER_B
        }
    }
}

@Serializable
data class DegreePlanCourse(
    val id: String = UUID.randomUUID().toString(),
    val key: CourseKey? = null,
    val label: String = "",
    val title: String = "",
    val credits: Double = 0.0,
) {
    companion object {
        fun of(key: CourseKey?, title: String, credits: Double, label: String? = null, id: String = UUID.randomUUID().toString()) =
            DegreePlanCourse(id, key, label ?: key?.description ?: "", title, credits)
    }
}

@Serializable
data class DegreePlanTerm(
    val id: String = UUID.randomUUID().toString(),
    val season: PlanSeason,
    val year: Int,
    val courses: List<DegreePlanCourse> = emptyList(),
) {
    val name: String get() = "${season.exportName} $year"
    val shortName: String get() = "${season.shortName} $year"
    val credits: Double get() = courses.sumOf { it.credits }
    val monthIndex: Int get() = year * 12 + season.startMonth - 1
    val sortKey: Int get() = monthIndex * 4 + season.orderWithinMonth
    fun matches(term: Term) = term.calendarYear == year && season.matches(term.season)
}

@Serializable
data class CourseEquivalence(
    val id: String = UUID.randomUUID().toString(),
    val course: CourseKey,
    val countsAs: CourseKey,
    val note: String = "",
)

@Serializable
data class RequirementReplacement(
    val count: Int? = null,
    val hours: Double? = null,
    val likeRuleID: String? = null,
    val likeRuleTitle: String? = null,
    val courses: List<CourseKey> = emptyList(),
    val courseNumbers: CourseNumberLimit? = null,
) {
    val summary: String
        get() {
            val amount = when {
                count != null -> if (count == 1) "1 course" else "$count courses"
                hours != null -> "${trimNumber(hours)} semester hours"
                else -> "Courses"
            }
            val sources = mutableListOf<String>()
            likeRuleTitle?.let { sources += "like $it" }
            if (courses.isNotEmpty()) sources += "from ${courses.joinToString(", ") { it.description }}"
            var text = (listOf(amount) + sources).joinToString(" ")
            if (likeRuleTitle != null) courseNumbers?.summary?.let { text += ", $it" }
            return text
        }
}

@Serializable
sealed class CourseNumberLimit {
    @Serializable
    data object SameAsRequirement : CourseNumberLimit()
    @Serializable
    data object AnyNumber : CourseNumberLimit()
    @Serializable
    data class AtLeast(val minimum: Int) : CourseNumberLimit()

    val summary: String?
        get() = when (this) {
            SameAsRequirement -> null
            AnyNumber -> "any course number"
            is AtLeast -> "numbered $minimum or higher"
        }
}

@Serializable
sealed class SubstitutionChange {
    @Serializable
    data class CountCourse(val key: CourseKey) : SubstitutionChange()
    @Serializable
    data class Replace(val replacement: RequirementReplacement) : SubstitutionChange()
    @Serializable
    data object Waive : SubstitutionChange()
}

@Serializable
data class RequirementSubstitution(
    val id: String = UUID.randomUUID().toString(),
    val ruleID: String,
    val ruleTitle: String,
    val change: SubstitutionChange,
    val note: String = "",
    val approvedOn: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class DegreePlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val program: String? = null,
    val studentName: String? = null,
    val studentID: String? = null,
    val completed: List<DegreePlanCourse> = emptyList(),
    val terms: List<DegreePlanTerm> = emptyList(),
    val isPrimary: Boolean = false,
    val importedFrom: String? = null,
    val printedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val catalogYear: Int? = null,
    val catalogProgramPath: String? = null,
    val catalogProgramTitle: String? = null,
    val concentrationID: String? = null,
    val substitutions: List<RequirementSubstitution> = emptyList(),
    val courseEquivalences: List<CourseEquivalence> = emptyList(),
) {
    val completedCredits: Double get() = completed.sumOf { it.credits }
    val plannedCredits: Double get() = terms.sumOf { it.credits }
    val completedKeys: Set<CourseKey> get() = completed.mapNotNull { it.key }.toSet()
    fun sorted(): DegreePlan = copy(terms = terms.sortedBy { it.sortKey })
    fun termMatching(term: Term): DegreePlanTerm? = terms.firstOrNull { it.matches(term) }
    val declaredEquivalences: app.termorganize.core.model.CourseEquivalences
        get() = app.termorganize.core.model.CourseEquivalences.fromPairs(courseEquivalences.map { it.course to it.countsAs })
}

@Serializable
data class UserData(
    val version: Int = 1,
    val preferences: AppPreferences = AppPreferences(),
    val saved: List<SavedCourse> = emptyList(),
    val recents: List<RecentCourse> = emptyList(),
    val plans: List<SchedulePlan> = emptyList(),
    val watchlist: List<WatchItem> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val degreePlans: List<DegreePlan> = emptyList(),
)

object UserJson {
    fun encode(data: UserData): String = AppJson.user.encodeToString(UserData.serializer(), data)
    fun decode(text: String): UserData = decodeElement(AppJson.user.parseToJsonElement(text))

    fun decodeElement(element: JsonElement): UserData {
        val obj = element.jsonObject
        return UserData(
            version = obj.int("version") ?: 1,
            preferences = obj.obj("preferences")?.let { runCatching { AppJson.user.decodeFromJsonElement(AppPreferences.serializer(), it) }.getOrNull() } ?: AppPreferences(),
            saved = obj.decodeEach("saved", SavedCourse.serializer()),
            recents = obj.decodeEach("recents", RecentCourse.serializer()),
            plans = obj.decodeEach("plans", SchedulePlan.serializer()),
            watchlist = obj.decodeEach("watchlist", WatchItem.serializer()),
            searchHistory = obj.array("searchHistory")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            degreePlans = obj.decodeEach("degreePlans", DegreePlan.serializer()),
        )
    }

    private fun <T> JsonObject.decodeEach(key: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { runCatching { AppJson.user.decodeFromJsonElement(serializer, it) }.getOrNull() }
    }

    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = this[key] as? JsonArray
}

class UserDataStore(var data: UserData = UserData()) {
    val plans: List<SchedulePlan> get() = data.plans
    val degreePlans: List<DegreePlan> get() = data.degreePlans
    val primaryDegreePlan: DegreePlan? get() = degreePlans.firstOrNull { it.isPrimary } ?: degreePlans.maxByOrNull { it.updatedAt }

    fun degreePlan(id: String) = degreePlans.firstOrNull { it.id == id }

    fun createPlan(name: String, termCode: String): SchedulePlan {
        val plan = SchedulePlan(name = name, termCode = termCode, isPrimary = data.plans.none { it.termCode == termCode })
        data = data.copy(plans = data.plans + plan)
        return plan
    }

    fun updatePlan(id: String, block: (SchedulePlan) -> SchedulePlan) {
        data = data.copy(plans = data.plans.map { if (it.id == id) block(it).copy(updatedAt = System.currentTimeMillis()) else it })
    }

    fun deletePlan(id: String) {
        val plan = data.plans.firstOrNull { it.id == id } ?: return
        var next = data.plans.filter { it.id != id }
        if (plan.isPrimary) {
            val i = next.indexOfFirst { it.termCode == plan.termCode }
            if (i >= 0) next = next.toMutableList().also { it[i] = it[i].copy(isPrimary = true) }
        }
        data = data.copy(plans = next)
    }

    fun setPrimaryPlan(id: String) {
        val term = data.plans.firstOrNull { it.id == id }?.termCode ?: return
        data = data.copy(plans = data.plans.map { if (it.termCode == term) it.copy(isPrimary = it.id == id) else it })
    }

    fun addDegreePlan(plan: DegreePlan): DegreePlan {
        var stored = plan
        if (data.degreePlans.isEmpty()) stored = stored.copy(isPrimary = true)
        if (stored.isPrimary) {
            data = data.copy(degreePlans = data.degreePlans.map { it.copy(isPrimary = false) })
        }
        data = data.copy(degreePlans = data.degreePlans + stored)
        return stored
    }

    fun setPrimaryDegreePlan(id: String) {
        data = data.copy(degreePlans = data.degreePlans.map { it.copy(isPrimary = it.id == id) })
    }

    fun deleteDegreePlan(id: String) {
        val wasPrimary = degreePlan(id)?.isPrimary == true
        var next = data.degreePlans.filter { it.id != id }
        if (wasPrimary && next.isNotEmpty() && next.none { it.isPrimary }) {
            next = next.toMutableList().also { it[0] = it[0].copy(isPrimary = true) }
        }
        data = data.copy(degreePlans = next)
    }

    fun activePrimaryPlan(now: Instant = Instant.now()): SchedulePlan? {
        val candidates = data.plans.mapNotNull { plan ->
            if (!plan.isPrimary || plan.selectedCRNs.isEmpty()) return@mapNotNull null
            val window = TermCalendar.window(plan.termCode) ?: return@mapNotNull null
            plan to window
        }
        candidates.filter { now in it.second }.maxByOrNull { it.first.selectedCRNs.size }?.let { return it.first }
        candidates.filter { it.second.start.isAfter(now) }.minByOrNull { it.second.start }?.let { return it.first }
        val cutoff = now.minusSeconds(14L * 24 * 3600)
        return candidates.filter { !it.second.endInclusive.isBefore(cutoff) }.maxByOrNull { it.second.endInclusive }?.first
    }
}

data class TimeWindow(val start: Instant, val endInclusive: Instant) {
    operator fun contains(instant: Instant) = !instant.isBefore(start) && !instant.isAfter(endInclusive)
}

object TermCalendar {
    val newYork: java.time.ZoneId = java.time.ZoneId.of("America/New_York")

    fun window(termCode: String): TimeWindow? {
        val term = Term(termCode, "")
        val year = term.calendarYear
        val (start, end) = when (term.season) {
            Term.Season.FALL -> (9 to 1) to (12 to 23)
            Term.Season.WINTER -> (1 to 1) to (3 to 31)
            Term.Season.SPRING -> (1 to 3) to (4 to 30)
            Term.Season.SUMMER1 -> (5 to 1) to (6 to 30)
            Term.Season.SUMMER_FULL -> (5 to 1) to (8 to 20)
            Term.Season.SUMMER2 -> (6 to 25) to (8 to 20)
        }
        val first = java.time.LocalDate.of(year, start.first, start.second).atStartOfDay(newYork).toInstant()
        val lastDay = java.time.LocalDate.of(year, end.first, end.second)
        val last = lastDay.plusDays(1).atStartOfDay(newYork).toInstant().minusMillis(1000)
        return TimeWindow(first, last)
    }
}

private fun trimNumber(value: Double): String =
    if (kotlin.math.abs(value % 1.0) < 1e-9) value.toInt().toString() else value.toString()

fun JsonObject.putIf(key: String, value: String?) = buildJsonObject {
    for ((k, v) in this@putIf) put(k, v)
    if (value != null) put(key, value)
}
