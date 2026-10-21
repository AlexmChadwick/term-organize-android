// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.degree

import app.termorganize.core.model.AppJson
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CreditFormatter
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
    val isRequired: Boolean = true,
    val excludedCRNs: Set<String> = emptySet(),
    val colorIndex: Int = 0,
    /** Manually chosen section. Identity is this CRN in the plan's term, not the course number. */
    val crn: String? = null,
) {
    val id: CourseKey get() = key
    val manualCRN: String? get() = crn?.trim()?.takeIf { it.isNotEmpty() }
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

    /**
     * A second entry with the same subject and number is allowed only when every entry for that
     * number already has a manual CRN and [crn] is a different one. The same CRN cannot be added twice.
     */
    fun admitsCourse(key: CourseKey, crn: String?): Boolean {
        val manual = crn?.trim()?.takeIf { it.isNotEmpty() }
        if (manual != null && courses.any { it.manualCRN == manual }) return false
        val same = courses.filter { it.key == key }
        if (same.isEmpty()) return true
        if (manual == null) return false
        if (same.any { it.manualCRN == null }) return false
        return true
    }
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
    /** Manually chosen section. Two entries may share a course number only when both CRNs are set and different. */
    val crn: String? = null,
) {
    val manualCRN: String? get() = crn?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        fun of(
            key: CourseKey?,
            title: String,
            credits: Double,
            label: String? = null,
            id: String = UUID.randomUUID().toString(),
            crn: String? = null,
        ) = DegreePlanCourse(id, key, label ?: key?.description ?: "", title, credits, crn)
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

    val degreePlanBannerCode: String
        get() = when (season) {
            PlanSeason.FALL -> "${year + 1}10"
            PlanSeason.WINTER -> "${year}20"
            PlanSeason.SPRING -> "${year}30"
            PlanSeason.SUMMER_A -> "${year}40"
            PlanSeason.SUMMER_FULL -> "${year}50"
            PlanSeason.SUMMER_B -> "${year}60"
        }

    fun degreePlanHasEnded(now: Instant = Instant.now()): Boolean {
        val window = TermCalendar.window(degreePlanBannerCode) ?: return false
        return window.endInclusive.isBefore(now)
    }
}

object DegreePlanText {
    fun credits(value: Double) = "${CreditFormatter.number(value)} ${if (value == 1.0) "credit" else "credits"}"
    fun courses(count: Int) = if (count == 1) "1 course" else "$count courses"
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
    private val everyCourse: List<DegreePlanCourse> get() = completed + terms.flatMap { it.courses }

    /**
     * Same subject and number twice is allowed only when every matching entry, including this one,
     * has its own manual CRN. The same CRN is never added twice. Entries with no CRN keep the
     * previous behavior: they can repeat, and the audit decides whether the repeat counts.
     */
    fun admits(course: DegreePlanCourse): Boolean {
        val crn = course.manualCRN
        if (crn != null && everyCourse.any { it.manualCRN == crn }) return false
        val key = course.key ?: return true
        val same = everyCourse.filter { it.key == key }
        if (crn != null && same.any { it.manualCRN == null }) return false
        if (crn == null && same.any { it.manualCRN != null }) return false
        return true
    }
    val completedKeys: Set<CourseKey> get() = completed.mapNotNull { it.key }.toSet()
    fun sorted(): DegreePlan = copy(terms = terms.sortedBy { it.sortKey })
    fun termMatching(term: Term): DegreePlanTerm? = terms.firstOrNull { it.matches(term) }
    val declaredEquivalences: app.termorganize.core.model.CourseEquivalences
        get() = app.termorganize.core.model.CourseEquivalences.fromPairs(courseEquivalences.map { it.course to it.countsAs })

    fun setCourseNumbers(limit: CourseNumberLimit?, substitutionId: String): DegreePlan {
        val next = substitutions.map { substitution ->
            if (substitution.id != substitutionId) substitution
            else {
                val replacement = (substitution.change as? SubstitutionChange.Replace)?.replacement ?: return@map substitution
                substitution.copy(change = SubstitutionChange.Replace(replacement.copy(courseNumbers = limit)))
            }
        }
        return copy(substitutions = next)
    }

    fun declareEquivalence(course: CourseKey, countsAs: CourseKey, note: String = "Renumbered"): DegreePlan {
        if (course == countsAs || declaredEquivalences.areEquivalent(course, countsAs)) return this
        return copy(courseEquivalences = courseEquivalences + CourseEquivalence(course = course, countsAs = countsAs, note = note))
    }

    fun removeEquivalence(id: String): DegreePlan =
        copy(courseEquivalences = courseEquivalences.filter { it.id != id })

    fun degreePlanUpcomingTerms(now: Instant = Instant.now()): List<DegreePlanTerm> =
        terms.filter { !it.degreePlanHasEnded(now) }

    fun degreePlanNextTerm(now: Instant = Instant.now()): DegreePlanTerm? =
        degreePlanUpcomingTerms(now).firstOrNull { it.courses.isNotEmpty() }

    val degreePlanCompletedLine: String
        get() = if (completed.isEmpty()) "No completed courses yet" else "${DegreePlanText.credits(completedCredits)} completed"

    fun degreePlanNextSummary(now: Instant = Instant.now()): String? {
        val next = degreePlanNextTerm(now) ?: return null
        return "${next.name} · ${DegreePlanText.credits(next.credits)} · ${DegreePlanText.courses(next.courses.size)}"
    }

    fun degreePlanNextLine(now: Instant = Instant.now()): String {
        degreePlanNextSummary(now)?.let { return "Next: $it" }
        if (terms.isEmpty()) return "No semesters planned yet"
        if (degreePlanUpcomingTerms(now).isEmpty()) return "No upcoming semesters"
        return "No courses planned yet"
    }
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
            searchHistory = obj.array("searchHistory")?.mapNotNull { element ->
                val primitive = element as? JsonPrimitive ?: return@mapNotNull null
                if (primitive.isString) primitive.content else null
            } ?: emptyList(),
            degreePlans = decodeDegreePlans(obj),
        )
    }

    private fun decodeDegreePlans(obj: JsonObject): List<DegreePlan> {
        val array = obj["degreePlans"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { runCatching { DegreePlan.decodeLenient(it) }.getOrNull() }
    }

    private fun <T> JsonObject.decodeEach(key: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { runCatching { AppJson.user.decodeFromJsonElement(serializer, it) }.getOrNull() }
    }

    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = this[key] as? JsonArray
}

fun DegreePlan.Companion.decodeLenient(element: JsonElement): DegreePlan {
    val obj = element as? JsonObject ?: return AppJson.user.decodeFromJsonElement(serializer(), element)
    val equivalents = obj["courseEquivalences"]
    val fixed = if (equivalents != null && equivalents !is JsonArray) {
        JsonObject(obj.filterKeys { it != "courseEquivalences" })
    } else obj
    return AppJson.user.decodeFromJsonElement(serializer(), fixed)
}

data class ScheduleFromDegree(val plan: SchedulePlan, val isNew: Boolean, val courseCount: Int)

/** What the primary degree plan does with a course, for the tag on a course page. */
data class CourseDegreePlanStanding(val planId: String, val kind: Kind) {
    sealed class Kind {
        data class Completed(val via: CourseKey?) : Kind()
        data class Planned(val semester: String, val via: CourseKey?) : Kind()
    }

    val text: String
        get() = when (val kind = kind) {
            is Kind.Completed -> kind.via?.let { "Completed via ${it.description}" } ?: "Completed"
            is Kind.Planned -> kind.via?.let { "Planned via ${it.description} · ${kind.semester}" } ?: "Planned · ${kind.semester}"
        }
    val symbol: String
        get() = when (kind) {
            is Kind.Completed -> "checkmark.circle.fill"
            is Kind.Planned -> "calendar"
        }
    val spokenText: String
        get() = when (val kind = kind) {
            is Kind.Completed -> kind.via?.let { "Completed via ${it.description}, in your degree plan" } ?: "Completed, in your degree plan"
            is Kind.Planned -> kind.via?.let { "Planned via ${it.description} for ${kind.semester} in your degree plan" }
                ?: "Planned for ${kind.semester} in your degree plan"
        }
}

class UserDataStore(var data: UserData = UserData()) {
    val plans: List<SchedulePlan> get() = data.plans
    val degreePlans: List<DegreePlan> get() = data.degreePlans
    val primaryDegreePlan: DegreePlan? get() = degreePlans.firstOrNull { it.isPrimary } ?: degreePlans.maxByOrNull { it.updatedAt }

    fun degreePlan(id: String) = degreePlans.firstOrNull { it.id == id }

    fun plansIn(termCode: String) = plans.filter { it.termCode == termCode }

    fun courseIsInSchedule(termCode: String, key: CourseKey, crn: String? = null): Boolean =
        plansIn(termCode).any { plan -> plan.contains(key) && (crn == null || crn in plan.selectedCRNs) }

    fun plan(id: String) = plans.firstOrNull { it.id == id }

    fun addCourse(key: CourseKey, planId: String, required: Boolean = true, crn: String? = null): Boolean {
        var added = false
        updatePlan(planId) { plan ->
            if (!plan.admitsCourse(key, crn)) plan
            else {
                added = true
                val manual = crn?.trim()?.takeIf { it.isNotEmpty() }
                plan.copy(
                    courses = plan.courses + PlanCourse(key, isRequired = required, colorIndex = plan.nextColorIndex, crn = manual),
                    selectedCRNs = if (manual == null || manual in plan.selectedCRNs) plan.selectedCRNs else plan.selectedCRNs + manual,
                )
            }
        }
        return added
    }

    fun removeCourse(key: CourseKey, planId: String, sectionsOfCourse: List<String> = emptyList(), crn: String? = null) {
        val drop = sectionsOfCourse.toSet()
        val manual = crn?.trim()?.takeIf { it.isNotEmpty() }
        updatePlan(planId) { plan ->
            val courses = if (manual == null) plan.courses.filter { it.key != key }
            else plan.courses.filter { !(it.key == key && it.manualCRN == manual) }
            val protected = courses.mapNotNull { it.manualCRN }.toSet()
            plan.copy(
                courses = courses,
                selectedCRNs = plan.selectedCRNs.filter { selected ->
                    if (selected in protected) true
                    else if (manual != null && selected == manual) false
                    else drop.isEmpty() || selected !in drop
                },
                favoriteCombinations = if (drop.isEmpty()) plan.favoriteCombinations else plan.favoriteCombinations.filter { combo -> combo.none { it in drop && it !in protected } },
            )
        }
    }

    fun courseDegreePlanStanding(key: CourseKey): CourseDegreePlanStanding? {
        val plan = primaryDegreePlan ?: return null
        val equivalences = plan.declaredEquivalences
        val taken = plan.completedKeys
        val counted = equivalences.satisfier(key, taken)
        if (counted != null) {
            return CourseDegreePlanStanding(plan.id, CourseDegreePlanStanding.Kind.Completed(if (counted == key) null else counted))
        }
        val direct = plan.terms.firstOrNull { term -> term.courses.any { it.key == key } }
        if (direct != null) return CourseDegreePlanStanding(plan.id, CourseDegreePlanStanding.Kind.Planned(direct.shortName, null))
        for (other in equivalences.equivalents(key).sorted()) {
            val term = plan.terms.firstOrNull { semester -> semester.courses.any { it.key == other } } ?: continue
            return CourseDegreePlanStanding(plan.id, CourseDegreePlanStanding.Kind.Planned(term.shortName, other))
        }
        return null
    }

    fun scheduleForDegreePlan(semester: DegreePlanTerm, termCode: String): ScheduleFromDegree? {
        val seen = LinkedHashSet<CourseKey>()
        val keys = semester.courses.mapNotNull { it.key }.filter { seen.add(it) }
        if (keys.isEmpty()) return null
        val existing = plansIn(termCode).firstOrNull { it.courses.map { course -> course.key }.toSet() == keys.toSet() }
        if (existing != null) return ScheduleFromDegree(existing, false, keys.size)
        val created = createPlan(DegreePlanEditing.scheduleName(semester, plansIn(termCode).map { it.name }), termCode)
        for (key in keys) addCourse(key, created.id)
        val stored = plans.first { it.id == created.id }
        return ScheduleFromDegree(stored, true, keys.size)
    }

    fun duplicatePlan(id: String): SchedulePlan? {
        val plan = plan(id) ?: return null
        val copy = plan.copy(
            id = UUID.randomUUID().toString(),
            name = "${plan.name} Copy",
            isPrimary = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        data = data.copy(plans = data.plans + copy)
        return copy
    }

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
