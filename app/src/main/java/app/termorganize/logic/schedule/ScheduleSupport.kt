// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.schedule

import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.CreditFormatter
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Term
import app.termorganize.core.model.TimeOfDay
import app.termorganize.core.model.CourseEquivalences
import app.termorganize.logic.degree.AppPreferences
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanReordering
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.PlanCourse
import app.termorganize.logic.degree.PrerequisiteLookup
import app.termorganize.logic.degree.ScheduleAutofillSuggestion
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.degree.UserDataStore
import app.termorganize.logic.degree.DegreePlanAutofill
import app.termorganize.logic.offering.OfferingHistory
import app.termorganize.logic.search.CatalogIndex
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.max

/** Pure helpers that edit a schedule plan. */
object PlanEditing {
    fun nextPlanName(existing: List<String>): String {
        val used = existing.map { it.trim().lowercase() }.toSet()
        for (letter in 'A'..'Z') {
            val name = "Plan $letter"
            if (name.lowercase() !in used) return name
        }
        var n = 27
        while ("plan $n" in used) n++
        return "Plan $n"
    }

    fun setSection(crn: String?, courseCRNs: Set<String>, plan: SchedulePlan): SchedulePlan {
        val pins = plan.courses.mapNotNull { it.manualCRN }
        val replacing = pins.filter { it in courseCRNs }
        // Several manual sections share this course number. Do not retarget them as a group.
        if (replacing.size > 1) return plan
        val selected = plan.selectedCRNs.filter { it !in courseCRNs || (it in pins && it !in replacing) }.toMutableList()
        if (crn != null && crn !in selected) selected += crn
        val courses = if (replacing.size == 1) {
            plan.courses.map { if (it.manualCRN == replacing[0]) it.copy(crn = crn) else it }
        } else plan.courses
        return plan.copy(courses = courses, selectedCRNs = selected)
    }

    /** Keeps every manually chosen CRN. A generated section of that course number does not replace it. */
    fun applyCombination(crns: List<String>, plan: SchedulePlan, keyOf: (String) -> CourseKey?): SchedulePlan {
        val pins = plan.courses.mapNotNull { it.manualCRN }
        val kept = crns.filter { crn ->
            val key = keyOf(crn) ?: return@filter true
            val pinnedForKey = plan.courses.filter { it.key == key }.mapNotNull { it.manualCRN }
            pinnedForKey.isEmpty() || crn in pinnedForKey
        }
        val merged = LinkedHashSet<String>()
        merged += kept
        merged += pins
        return plan.copy(selectedCRNs = merged.toList())
    }

    fun setRequired(isRequired: Boolean, key: CourseKey, plan: SchedulePlan): SchedulePlan {
        val index = plan.courses.indexOfFirst { it.key == key }
        if (index < 0) return plan
        val courses = plan.courses.toMutableList()
        courses[index] = courses[index].copy(isRequired = isRequired)
        return plan.copy(courses = courses)
    }

    fun setExcluded(excluded: Boolean, crn: String, key: CourseKey, plan: SchedulePlan): SchedulePlan {
        val index = plan.courses.indexOfFirst { it.key == key }
        if (index < 0) return plan
        val courses = plan.courses.toMutableList()
        val current = courses[index].excludedCRNs
        courses[index] = courses[index].copy(excludedCRNs = if (excluded) current + crn else current - crn)
        return plan.copy(courses = courses)
    }

    fun isFavorite(plan: SchedulePlan, sortedCRNs: List<String>) = plan.favoriteCombinations.any { it == sortedCRNs }

    fun setFavorite(favorite: Boolean, sortedCRNs: List<String>, plan: SchedulePlan): SchedulePlan {
        val kept = plan.favoriteCombinations.filter { it != sortedCRNs }
        return plan.copy(favoriteCombinations = if (favorite) kept + listOf(sortedCRNs) else kept)
    }

    fun removeStaleCRNs(crns: List<String>, plan: SchedulePlan): SchedulePlan {
        val stale = crns.toSet()
        return plan.copy(selectedCRNs = plan.selectedCRNs.filter { it !in stale })
    }

    fun pruneOrphans(index: CatalogIndex, plan: SchedulePlan): Triple<SchedulePlan, List<String>, List<List<String>>> {
        val keys = plan.courses.map { it.key }.toSet()
        fun isOrphan(crn: String): Boolean {
            val section = index.section(crn) ?: return false
            return section.courseKey !in keys
        }
        val selected = plan.selectedCRNs.filter(::isOrphan)
        val favorites = plan.favoriteCombinations.filter { combo -> combo.any(::isOrphan) }
        val updated = plan.copy(
            selectedCRNs = plan.selectedCRNs.filter { !isOrphan(it) },
            favoriteCombinations = plan.favoriteCombinations.filter { combo -> combo.none(::isOrphan) },
        )
        return Triple(updated, selected, favorites)
    }

    fun restore(removal: PlanCourseRemoval, plan: SchedulePlan): SchedulePlan {
        if (plan.courses.any { it.key == removal.course.key && it.manualCRN == removal.course.manualCRN }) return plan
        val courses = plan.courses.toMutableList()
        courses.add(removal.index.coerceIn(0, courses.size), removal.course)
        val selected = plan.selectedCRNs.toMutableList()
        for (crn in removal.selectedCRNs) if (crn !in selected) selected += crn
        val favorites = plan.favoriteCombinations.toMutableList()
        for (combination in removal.favoriteCombinations) if (combination !in favorites) favorites += combination
        return plan.copy(courses = courses, selectedCRNs = selected, favoriteCombinations = favorites)
    }

    fun apply(relaxation: ScheduleRelaxation, plan: SchedulePlan): SchedulePlan = when (relaxation) {
        is ScheduleRelaxation.MakeOptional -> setRequired(false, relaxation.key, plan)
        is ScheduleRelaxation.Preference -> {
            val preferences = relaxation.apply(plan.preferences ?: SchedulePreferences())
            plan.copy(preferences = if (preferences.isDefault) null else preferences)
        }
        else -> plan.copy(filters = relaxation.apply(plan.filters))
    }

    fun makeOptional(keys: List<CourseKey>, plan: SchedulePlan): SchedulePlan {
        var next = plan
        for (key in keys) next = setRequired(false, key, next)
        return next
    }
}

class PlanCourseRemoval(
    val course: PlanCourse,
    val index: Int,
    selectedCRNs: List<String>,
    favoriteCombinations: List<List<String>>,
) {
    val selectedCRNs = selectedCRNs.toMutableList()
    val favoriteCombinations = favoriteCombinations.toMutableList()
}

data class ScheduleConflict(
    val first: CourseSection,
    val second: CourseSection,
    val days: DaySet,
    val start: Int,
    val end: Int,
) {
    val id: String get() = "${first.crn}|${second.crn}"
    val summary: String get() = "${first.courseKey.description} overlaps ${second.courseKey.description} · ${days.shortLabel} ${TimeOfDay.rangeLabel(start, end)}"
}

object ScheduleConflicts {
    fun overlap(a: CourseSection, b: CourseSection): Triple<DaySet, Int, Int>? {
        var days = DaySet(0)
        var window: Pair<Int, Int>? = null
        for (m1 in a.weeklyMeetings) {
            for (m2 in b.weeklyMeetings) {
                if (!m1.overlaps(m2)) continue
                val s1 = m1.start ?: continue
                val e1 = m1.end ?: continue
                val s2 = m2.start ?: continue
                val e2 = m2.end ?: continue
                days = days.union(m1.days.intersection(m2.days))
                val start = maxOf(s1, s2)
                val end = minOf(e1, e2)
                if (window == null || start < window.first) window = start to end
            }
        }
        val found = window ?: return null
        return Triple(days, found.first, found.second)
    }

    fun find(among: List<CourseSection>): List<ScheduleConflict> {
        val result = ArrayList<ScheduleConflict>()
        for (i in among.indices) {
            for (j in among.indices) {
                if (j <= i) continue
                val hit = overlap(among[i], among[j]) ?: continue
                result += ScheduleConflict(among[i], among[j], hit.first, hit.second, hit.third)
            }
        }
        return result
    }
}

object ScheduleFormat {
    fun sectionTitle(section: CourseSection): String =
        if (section.sectionNumber.isEmpty()) section.classType else "${section.classType} ${section.sectionNumber}"
}

class ResolvedPlan(val plan: SchedulePlan, index: CatalogIndex?) {
    class Entry(
        val planCourse: PlanCourse,
        val course: Course?,
        val allSections: List<CourseSection>,
        val selected: List<CourseSection>,
        val catalogAvailable: Boolean,
    ) {
        val key: CourseKey get() = planCourse.key
        val isMissing: Boolean get() = catalogAvailable && course == null && allSections.isEmpty()
        val hasNoSections: Boolean get() = catalogAvailable && course != null && allSections.isEmpty()
        val courseCRNs: Set<String> get() = allSections.map { it.crn }.toSet()
        val countsTowardsCredits: Boolean get() = planCourse.isRequired || selected.isNotEmpty()
    }

    data class SectionPair(val entry: Entry, val section: CourseSection)

    val hasCatalog: Boolean = index != null
    val entries: List<Entry>
    val unlistedCRNs: List<String>
    val orphanedCRNs: List<String>
    val selectedSections: List<CourseSection>
    val conflicts: List<ScheduleConflict>
    val fullSections: List<SectionPair>
    val missingCourses: List<CourseKey>
    val credits: Pair<Double, Double>

    init {
        val bySection = HashMap<String, CourseSection>()
        if (index != null) {
            for (crn in plan.selectedCRNs) index.section(crn)?.let { bySection[crn] = it }
        }
        val built = ArrayList<Entry>()
        val claimed = HashSet<String>()
        val pinnedElsewhere = plan.courses.mapNotNull { it.manualCRN }.toSet()
        for (planCourse in plan.courses) {
            val all = index?.sections(planCourse.key).orEmpty()
            val pin = planCourse.manualCRN
            val selected = if (pin != null) {
                val section = bySection[pin]
                if (section != null) {
                    claimed += pin
                    listOf(section)
                } else emptyList()
            } else {
                plan.selectedCRNs.mapNotNull { crn ->
                    if (crn in claimed || crn in pinnedElsewhere) return@mapNotNull null
                    val section = bySection[crn] ?: return@mapNotNull null
                    if (section.courseKey != planCourse.key) return@mapNotNull null
                    claimed += crn
                    section
                }
            }
            built += Entry(planCourse, index?.course(planCourse.key), all, selected, index != null)
        }
        entries = built
        val unclaimed = if (index == null) emptyList() else plan.selectedCRNs.filter { it !in claimed }
        unlistedCRNs = unclaimed.filter { bySection[it] == null }
        orphanedCRNs = unclaimed.filter { bySection[it] != null }
        val sections = entries.flatMap { it.selected }
        selectedSections = sections
        val full = ArrayList<SectionPair>()
        for (entry in entries) {
            for (section in entry.selected) if (section.isFull) full += SectionPair(entry, section)
        }
        fullSections = full
        conflicts = ScheduleConflicts.find(sections)
        missingCourses = entries.filter { it.isMissing || it.hasNoSections }.map { it.key }
        var low = 0.0
        var high = 0.0
        for (entry in entries) {
            if (!entry.countsTowardsCredits) continue
            val course = entry.course ?: continue
            low += course.minCredits
            high += course.maxCredits
        }
        credits = low to high
    }

    val creditsLabel: String get() = CreditFormatter.label(credits.first, credits.second)
    val isOverCreditLimit: Boolean get() = credits.first > 19
    val coursesWithoutSection: List<Entry> get() = entries.filter { it.selected.isEmpty() && !it.isMissing && !it.hasNoSections }

    fun entry(key: CourseKey) = entries.firstOrNull { it.key == key }

    val warnings: List<ScheduleWarning>
        get() {
            if (!hasCatalog) return emptyList()
            val items = ArrayList<ScheduleWarning>()
            if (conflicts.isNotEmpty()) {
                items += ScheduleWarning(
                    if (conflicts.size == 1) "1 conflict" else "${conflicts.size} conflicts",
                    ScheduleWarning.Severity.ERROR,
                )
            }
            if (fullSections.isNotEmpty()) {
                items += ScheduleWarning(
                    if (fullSections.size == 1) "1 full section" else "${fullSections.size} full sections",
                    ScheduleWarning.Severity.WARNING,
                )
            }
            if (missingCourses.isNotEmpty()) {
                items += ScheduleWarning(
                    if (missingCourses.size == 1) "1 course not offered" else "${missingCourses.size} courses not offered",
                    ScheduleWarning.Severity.WARNING,
                )
            }
            if (isOverCreditLimit) items += ScheduleWarning("Over 19 credits", ScheduleWarning.Severity.WARNING)
            val missingSections = coursesWithoutSection.count { it.planCourse.isRequired }
            if (missingSections > 0 && plan.courses.isNotEmpty()) {
                items += ScheduleWarning(
                    if (missingSections == 1) "1 course needs a section" else "$missingSections courses need sections",
                    ScheduleWarning.Severity.NEUTRAL,
                )
            }
            return items
        }
}

data class ScheduleWarning(val text: String, val severity: Severity) {
    enum class Severity { ERROR, WARNING, NEUTRAL }
    val id: String get() = text
}

data class ScheduleNotice(
    val id: String,
    val severity: Severity,
    val title: String,
    val lines: List<String> = emptyList(),
    val fix: Fix? = null,
) {
    enum class Severity { ERROR, WARNING, INFO }
    data class Fix(val removeSections: List<String>)
    val fixTitle: String? get() = if (fix == null) null else "Remove"
}

object ScheduleNotices {
    fun make(resolved: ResolvedPlan, termName: String, catalogUnavailable: Boolean): List<ScheduleNotice> {
        val items = ArrayList<ScheduleNotice>()
        if (!resolved.hasCatalog && catalogUnavailable && resolved.plan.courses.isNotEmpty()) {
            items += ScheduleNotice(
                "catalog",
                ScheduleNotice.Severity.WARNING,
                "Course data for $termName isn’t on this device",
                listOf("Switch to that term and download its catalog to see sections, times and seats."),
            )
        }
        if (resolved.conflicts.isNotEmpty()) {
            val count = resolved.conflicts.size
            val lines = resolved.conflicts.take(3).map { it.summary }.toMutableList()
            if (count > 3) lines += "and ${count - 3} more"
            items += ScheduleNotice(
                "conflicts",
                ScheduleNotice.Severity.ERROR,
                if (count == 1) "1 time conflict" else "$count time conflicts",
                lines,
            )
        }
        if (resolved.fullSections.isNotEmpty()) {
            val lines = resolved.fullSections.map { pair ->
                val waitlist = if (pair.section.waitlistCapacity > 0) " · ${pair.section.waitlistRemaining} waitlist spots left" else ""
                "${pair.section.courseKey.description} ${ScheduleFormat.sectionTitle(pair.section)} is full$waitlist"
            }
            items += ScheduleNotice(
                "full",
                ScheduleNotice.Severity.WARNING,
                if (resolved.fullSections.size == 1) "1 section is full" else "${resolved.fullSections.size} sections are full",
                lines,
            )
        }
        if (resolved.missingCourses.isNotEmpty()) {
            items += ScheduleNotice(
                "missing",
                ScheduleNotice.Severity.WARNING,
                if (resolved.missingCourses.size == 1) "1 course isn’t offered in $termName" else "${resolved.missingCourses.size} courses aren’t offered in $termName",
                resolved.missingCourses.map { "${it.description} has no sections this term." },
            )
        }
        if (resolved.isOverCreditLimit) {
            items += ScheduleNotice(
                "credits",
                ScheduleNotice.Severity.WARNING,
                "${resolved.creditsLabel} is a heavy load",
                listOf("Above 19 credits you may need approval from your advisor or college."),
            )
        }
        if (resolved.unlistedCRNs.isNotEmpty()) {
            val unlisted = resolved.unlistedCRNs
            items += ScheduleNotice(
                "unlisted",
                ScheduleNotice.Severity.INFO,
                if (unlisted.size == 1) "A saved section is no longer listed" else "${unlisted.size} saved sections are no longer listed",
                listOf("CRN ${unlisted.joinToString(", ")} isn’t in the current $termName catalog."),
                ScheduleNotice.Fix(unlisted),
            )
        }
        if (resolved.orphanedCRNs.isNotEmpty()) {
            val orphaned = resolved.orphanedCRNs
            items += ScheduleNotice(
                "orphaned",
                ScheduleNotice.Severity.INFO,
                if (orphaned.size == 1) "A section from a removed course is still selected" else "${orphaned.size} sections from removed courses are still selected",
                listOf("CRN ${orphaned.joinToString(", ")} doesn’t belong to any course in this schedule."),
                ScheduleNotice.Fix(orphaned),
            )
        }
        return items
    }
}

fun ScheduleCourseInput.Companion.inputs(plan: SchedulePlan, index: CatalogIndex): List<ScheduleCourseInput> {
    val result = ArrayList<ScheduleCourseInput>()
    val claimed = HashSet<String>()
    for (planCourse in plan.courses) {
        val course = index.course(planCourse.key)
        val min = ceil(course?.minCredits ?: 0.0).toInt()
        val max = ceil(course?.maxCredits ?: 0.0).toInt()
        val manual = planCourse.manualCRN
        if (manual != null) {
            claimed += manual
            result += lockedInput(planCourse, index.section(manual), min, max)
            continue
        }
        val pins = plan.selectedCRNs.filter { it !in claimed }.mapNotNull { crn ->
            val section = index.section(crn) ?: return@mapNotNull null
            if (section.courseKey != planCourse.key) return@mapNotNull null
            if (plan.courses.any { it.manualCRN == crn }) return@mapNotNull null
            crn
        }.distinct()
        if (pins.isEmpty()) {
            result += ScheduleCourseInput(
                planCourse.key,
                planCourse.isRequired,
                index.sections(planCourse.key),
                planCourse.excludedCRNs,
                min,
                max,
            )
        } else {
            pins.forEachIndexed { offset, crn ->
                claimed += crn
                result += lockedInput(
                    planCourse,
                    index.section(crn),
                    if (offset == 0) min else 0,
                    if (offset == 0) max else 0,
                )
            }
        }
    }
    return result
}

private fun lockedInput(planCourse: PlanCourse, section: CourseSection?, min: Int, max: Int) = ScheduleCourseInput(
    planCourse.key,
    isRequired = true,
    sections = listOfNotNull(section),
    excludedCRNs = emptySet(),
    minCredits = min,
    maxCredits = max,
    locked = true,
)

fun GeneratedSchedule.Companion.favorites(plan: SchedulePlan, index: CatalogIndex, preferences: SchedulePreferences): List<GeneratedSchedule> {
    val order = plan.courses.mapIndexed { indexInPlan, course -> course.key to indexInPlan }.toMap()
    return plan.favoriteCombinations.asReversed().mapNotNull { crns ->
        val sections = crns.mapNotNull { index.section(it) }
        if (sections.size != crns.size || sections.isEmpty() || sections.any { order[it.courseKey] == null }) return@mapNotNull null
        val ordered = sections.sortedBy { order[it.courseKey] ?: Int.MAX_VALUE }
        GeneratedSchedule.measured(ordered, preferences)
    }
}

data class ScheduleBlockerOption(val courses: List<CourseKey>, val scheduleCount: Int, val isLowerBound: Boolean) {
    val id: String get() = courses.joinToString("+") { it.description }
}

data class ScheduleBlockerAnalysis(
    val requiredCount: Int = 0,
    val notOffered: List<CourseKey> = emptyList(),
    val filteredOut: List<CourseKey> = emptyList(),
    val options: List<ScheduleBlockerOption> = emptyList(),
    val conflictingPairs: List<ConflictPair> = emptyList(),
    val isIncomplete: Boolean = false,
) {
    fun clashes(key: CourseKey): List<CourseKey> = conflictingPairs.mapNotNull { pair ->
        when (key) {
            pair.first -> pair.second
            pair.second -> pair.first
            else -> null
        }
    }

    val unschedulable: List<CourseKey> get() = notOffered + filteredOut
    val blockingCourses: List<CourseKey> get() = options.firstOrNull()?.courses.orEmpty()
    val allBlockingCourses: List<CourseKey>
        get() {
            val seen = LinkedHashSet<CourseKey>()
            for (option in options) for (course in option.courses) seen += course
            return seen.toList()
        }
}

data class ScheduleNoResults(
    val diagnostics: ScheduleDiagnostics,
    val suggestions: List<ScheduleSuggestion>,
    val blockers: ScheduleBlockerAnalysis,
)

fun ScheduleGenerator.analyzeEmptyResult(
    courses: List<ScheduleCourseInput>,
    filters: ScheduleFilters,
    preferences: SchedulePreferences,
    diagnostics: ScheduleDiagnostics,
    includeBlockers: Boolean = true,
): ScheduleNoResults {
    val blockers = if (includeBlockers) analyzeBlockers(courses, filters, preferences, diagnostics) else ScheduleBlockerAnalysis()
    val suggestions = suggestRelaxations(courses, filters, preferences, includeCourseRelaxations = !includeBlockers)
    return ScheduleNoResults(diagnostics, suggestions, blockers)
}

fun ScheduleGenerator.analyzeBlockers(
    courses: List<ScheduleCourseInput>,
    filters: ScheduleFilters,
    preferences: SchedulePreferences = SchedulePreferences(),
    diagnostics: ScheduleDiagnostics? = null,
    timeBudgetMillis: Long = 1_500,
    maxProbes: Int = 60,
): ScheduleBlockerAnalysis {
    val started = System.nanoTime()
    val required = courses.indices.filter { courses[it].isRequired }
    if (required.isEmpty()) return ScheduleBlockerAnalysis()
    val base = diagnostics ?: generate(courses, filters, preferences, limits = ScheduleGenerator.Limits.probe).diagnostics
    if (base.countProblem != null || base.courses.size != courses.size) {
        return ScheduleBlockerAnalysis(requiredCount = required.size, conflictingPairs = base.conflictingPairs)
    }
    val dead = ArrayList<Int>()
    val notOffered = ArrayList<CourseKey>()
    val filteredOut = ArrayList<CourseKey>()
    for (index in required) {
        if (!base.courses[index].isBlocked) continue
        dead += index
        if (base.courses[index].totalSections == 0) notOffered += courses[index].key else filteredOut += courses[index].key
    }
    val others = required.filter { it !in dead }
    var probes = 0
    var incomplete = false
    fun overBudget() = probes >= maxProbes || (System.nanoTime() - started) / 1_000_000 > timeBudgetMillis
    fun probe(drop: List<Int>): ScheduleBlockerOption? {
        probes += 1
        val relaxed = courses.mapIndexed { index, course -> if (index in drop) course.copy(isRequired = false) else course }
        val result = generate(relaxed, filters, preferences, limits = ScheduleGenerator.Limits.blockerProbe)
        if (result.totalFound <= 0) return null
        return ScheduleBlockerOption(drop.sorted().map { courses[it].key }, result.totalFound, !result.isExhaustive)
    }
    var found = emptyList<ScheduleBlockerOption>()
    if (dead.isNotEmpty()) probe(dead)?.let { found = listOf(it) }
    if (found.isEmpty()) {
        val hits = ArrayList<ScheduleBlockerOption>()
        for (index in others) {
            if (overBudget()) {
                incomplete = true
                break
            }
            probe(dead + index)?.let { hits += it }
        }
        found = hits
    }
    if (found.isEmpty() && !incomplete && others.size >= 2) {
        val hits = ArrayList<ScheduleBlockerOption>()
        search@ for (i in others.indices) {
            for (j in others.indices) {
                if (j <= i) continue
                if (overBudget()) {
                    incomplete = true
                    break@search
                }
                probe(dead + others[i] + others[j])?.let { hits += it }
            }
        }
        found = hits
    }
    val options = found.sortedWith(compareBy<ScheduleBlockerOption> { it.courses.size }.thenByDescending { it.scheduleCount })
    return ScheduleBlockerAnalysis(required.size, notOffered, filteredOut, options, base.conflictingPairs, incomplete)
}

object ScheduleNoResultsText {
    data class Reason(val symbol: String, val title: String, val detail: String? = null)

    fun title(diagnostics: ScheduleDiagnostics): String {
        val required = diagnostics.courses.count { it.isRequired }
        if (diagnostics.countProblem != null || required <= 1) return "No schedules fit"
        return if (diagnostics.courses.size > required) "No schedule fits all $required required courses" else "No schedule fits all $required courses"
    }

    fun headline(diagnostics: ScheduleDiagnostics, blockers: ScheduleBlockerAnalysis?): String = when {
        diagnostics.hasNoCourses -> "Add some courses to this schedule first."
        blockers != null && blockers.options.isNotEmpty() -> "Here’s what would work."
        diagnostics.countProblem != null || diagnostics.blockedRequiredCourses.isNotEmpty() || diagnostics.conflictingPairs.isNotEmpty() -> "Here’s what’s getting in the way."
        else -> "These courses can’t all be taken together with the current rules."
    }

    fun list(keys: List<CourseKey>): String {
        val names = keys.map { it.description }
        return when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    fun detail(option: ScheduleBlockerOption, blockers: ScheduleBlockerAnalysis, termName: String): String {
        val parts = ArrayList<String>()
        val noun = if (option.scheduleCount == 1 && !option.isLowerBound) "schedule" else "schedules"
        parts += if (option.isLowerBound) "${option.scheduleCount}+ $noun" else "${option.scheduleCount} $noun"
        for (key in option.courses) {
            when (key) {
                in blockers.notOffered -> parts += "${key.description} isn’t offered in $termName"
                in blockers.filteredOut -> parts += "your rules rule out every ${key.description} section"
            }
        }
        if (option.courses.size == 1) {
            val clashes = blockers.clashes(option.courses.first())
            if (clashes.isNotEmpty()) parts += "overlaps ${list(clashes)}"
        }
        return parts.joinToString(" · ")
    }

    fun isUncertain(reason: String): Boolean =
        reason.contains("unknown", ignoreCase = true) || reason.contains("couldn’t", ignoreCase = true)

    fun reasons(diagnostics: ScheduleDiagnostics, termName: String): List<Reason> {
        val result = ArrayList<Reason>()
        for (report in diagnostics.courses) {
            if (!report.isBlocked || !report.isRequired) continue
            if (report.totalSections == 0) {
                result += Reason("questionmark.folder", "${report.key.description} isn’t offered in $termName", "Remove it from the schedule or make it optional.")
            } else {
                result += Reason("xmark.circle", "No ${report.key.description} sections left", rejectionText(report, diagnostics))
            }
        }
        for (pair in diagnostics.conflictingPairs) {
            result += when (pair.reason) {
                ConflictReason.OVERLAP -> Reason(
                    "rectangle.on.rectangle.slash",
                    "${pair.first.description} and ${pair.second.description} always overlap",
                    "Every remaining section of one meets while a section of the other does. Make one optional or pick different sections.",
                )
                ConflictReason.TRAVEL_TIME -> Reason(
                    "figure.walk",
                    "${pair.first.description} and ${pair.second.description} are always too close together",
                    "Every combination leaves less than ${diagnostics.preferences.minimumBreakMinutes} minutes between them in different buildings. Lower the walking time or make one optional.",
                )
            }
        }
        when (val problem = diagnostics.countProblem) {
            is CountProblem.TooManyRequired -> result += Reason("number.circle", "${problem.required} courses are required, but schedules are limited to ${problem.wanted}", "Raise the number of courses or make some courses optional.")
            is CountProblem.TooFewCourses -> result += Reason("number.circle", "Only ${problem.available} ${if (problem.available == 1) "course has" else "courses have"} sections left, but ${problem.wanted} are wanted", "Lower the number of courses or relax a filter.")
            is CountProblem.CreditsOverTarget -> result += Reason("number.circle", "The required courses are ${problem.required} credits, but your target stops at ${problem.limit}", "Raise the credit target or make a course optional.")
            is CountProblem.CreditsUnderTarget -> result += Reason("number.circle", "Your courses add up to ${problem.available} credits, but you want ${problem.wanted}", "Lower the credit target or add a course.")
            null -> Unit
        }
        for (report in diagnostics.courses) {
            if (!report.isBlocked || report.isRequired || report.totalSections <= 0) continue
            result += Reason("minus.circle", "${report.key.description} (optional) can’t be added", rejectionText(report, diagnostics))
        }
        return result
    }

    fun rejectionText(report: ScheduleDiagnostics.CourseReport, diagnostics: ScheduleDiagnostics): String {
        val total = report.totalSections
        val ordered = report.rejections.filter { it.value > 0 }.entries.sortedWith(compareByDescending<Map.Entry<ScheduleFilterReason, Int>> { it.value }.thenBy { it.key.ordinal })
        if (ordered.isEmpty()) return if (total == 1) "Its only section was filtered out." else "All $total sections were filtered out."
        fun phrase(reason: ScheduleFilterReason, plural: Boolean): String = when (reason) {
            ScheduleFilterReason.EXCLUDED_BY_USER -> if (plural) "are skipped by you" else "is skipped by you"
            ScheduleFilterReason.STARTS_TOO_EARLY -> (if (plural) "start" else "starts") + " before ${diagnostics.filters.earliestStart?.let { TimeOfDay.label(it) } ?: "your limit"}"
            ScheduleFilterReason.ENDS_TOO_LATE -> (if (plural) "end" else "ends") + " after ${diagnostics.filters.latestEnd?.let { TimeOfDay.label(it) } ?: "your limit"}"
            ScheduleFilterReason.FREE_DAY -> (if (plural) "meet" else "meets") + " on a day you want free"
            ScheduleFilterReason.HONORS -> if (plural) "are honors sections" else "is an honors section"
            ScheduleFilterReason.ONLINE -> if (plural) "are online" else "is online"
            ScheduleFilterReason.SEATS -> if (plural) "are full or low on seats" else "is full or low on seats"
            ScheduleFilterReason.CAMPUS -> if (plural) "are on another campus" else "is on another campus"
            ScheduleFilterReason.BLOCKED_TIME -> if (plural) "overlap a blocked time" else "overlaps a blocked time"
            ScheduleFilterReason.PREF_STARTS_TOO_EARLY -> (if (plural) "start" else "starts") + " before ${diagnostics.preferences.earliestStart?.let { TimeOfDay.label(it.minutes) } ?: "your earliest time"}"
            ScheduleFilterReason.PREF_ENDS_TOO_LATE -> (if (plural) "end" else "ends") + " after ${diagnostics.preferences.latestEnd?.let { TimeOfDay.label(it.minutes) } ?: "your latest time"}"
            ScheduleFilterReason.PREF_DAY_OFF -> (if (plural) "meet" else "meets") + " on a day you want off"
            ScheduleFilterReason.PREF_FORMAT -> (if (plural) "aren’t " else "isn’t ") + formatTitle(diagnostics.preferences.format).lowercase()
            ScheduleFilterReason.PREF_SEATS -> if (plural) "have no open seats" else "has no open seats"
            ScheduleFilterReason.PREF_CAMPUS -> if (plural) "are on another campus" else "is on another campus"
            ScheduleFilterReason.PREF_WORKLOAD -> if (plural) "run longer in a day than your limit" else "runs longer in a day than your limit"
        }
        if (ordered.size == 1 && ordered.first().value == total) {
            val only = ordered.first().key
            return if (total == 1) "Its only section ${phrase(only, false)}." else "All $total sections ${phrase(only, true)}."
        }
        val parts = ordered.joinToString(", ") { (reason, count) -> "$count ${phrase(reason, count != 1)}" }
        return "Of $total sections: $parts."
    }

    private fun formatTitle(format: FormatPreference) = when (format) {
        FormatPreference.NO_PREFERENCE -> "No preference"
        FormatPreference.IN_PERSON -> "In person"
        FormatPreference.HYBRID -> "Hybrid"
        FormatPreference.ONLINE -> "Online"
    }
}

data class ScheduleCarouselLayout(val hours: IntRange, val days: List<Int>) {
    companion object {
        operator fun invoke(schedules: List<GeneratedSchedule>, preferences: AppPreferences): ScheduleCarouselLayout {
            var earliest = Int.MAX_VALUE
            var latest = 0
            val weekdays = HashSet<Int>()
            for (schedule in schedules) {
                schedule.earliestStart?.let { earliest = minOf(earliest, it) }
                schedule.latestEnd?.let { latest = maxOf(latest, it) }
                for (section in schedule.sections) for (meeting in section.weeklyMeetings) weekdays += meeting.days.indices
            }
            val hours = if (earliest == Int.MAX_VALUE) {
                maxOf(0, preferences.calendarStartHour)..maxOf(preferences.calendarStartHour + 5, minOf(24, preferences.calendarEndHour))
            } else {
                val lower = maxOf(0, earliest / 60)
                val upper = minOf(24, maxOf(ceil(latest / 60.0).toInt(), lower + 5))
                lower..upper
            }
            val columns = mutableListOf(1, 2, 3, 4, 5)
            if (preferences.showWeekends || 6 in weekdays) columns += 6
            if (preferences.showWeekends || 0 in weekdays) columns.add(0, 0)
            return ScheduleCarouselLayout(hours, columns)
        }
    }
}

object ScheduleAutofillSupport {
    fun term(code: String, terms: List<Term>, index: CatalogIndex?): Term =
        terms.firstOrNull { it.code == code } ?: index?.term?.takeIf { it.code == code } ?: Term(code, "")

    fun suggestions(
        plan: SchedulePlan,
        terms: List<Term>,
        degreePlans: List<DegreePlan>,
        schedulePlans: List<SchedulePlan>,
        index: CatalogIndex?,
    ): List<ScheduleAutofillSuggestion> {
        val hasSiblings = schedulePlans.any { it.termCode == plan.termCode && it.id != plan.id && it.courses.isNotEmpty() }
        if (degreePlans.isEmpty() && !hasSiblings) return emptyList()
        return DegreePlanAutofill.scheduleSuggestions(
            term(plan.termCode, terms, index),
            degreePlans,
            schedulePlans,
            plan.id,
            plan.courses.map { it.key }.toSet(),
            index,
        )
    }

    fun sourceName(suggestion: ScheduleAutofillSuggestion): String = when (suggestion.source) {
        is ScheduleAutofillSuggestion.Source.Degree -> {
            val term = suggestion.semester
            if (term == null) "“${suggestion.planName}”" else "“${suggestion.planName}” · $term"
        }
        is ScheduleAutofillSuggestion.Source.Schedule -> "Copy “${suggestion.planName}”"
    }

    fun dismissalKey(planID: String, suggestion: ScheduleAutofillSuggestion) = "$planID|${suggestion.id}"

    fun isDismissed(key: String, raw: String) = raw.split('\n').any { it == key }

    fun adding(key: String, raw: String, limit: Int = 200): String {
        val keys = raw.split('\n').filter { it.isNotEmpty() && it != key }.toMutableList()
        keys += key
        return keys.takeLast(limit).joinToString("\n")
    }

    fun courseCountLabel(count: Int) = if (count == 1) "1 course" else "$count courses"
}

data class ScheduleDroppedCourse(val key: CourseKey, val crn: String?) {
    companion object {
        fun parse(items: List<String>): List<ScheduleDroppedCourse> {
            val seen = HashSet<ScheduleDroppedCourse>()
            val found = ArrayList<ScheduleDroppedCourse>()
            for (item in items) {
                for (line in item.split('\n')) {
                    val course = fromLine(line) ?: continue
                    if (seen.add(course)) found += course
                }
            }
            return found
        }

        private fun fromLine(line: String): ScheduleDroppedCourse? {
            val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val key = CourseKey.parse("${parts[0]} ${parts[1]}") ?: return null
            if (key.number.none { it.isDigit() }) return null
            val crn = parts.getOrNull(2)?.takeIf { it.length == 5 && it.all { ch -> ch.isDigit() } }
            return ScheduleDroppedCourse(key, crn)
        }
    }
}

object ScheduleClock {
    fun date(forMinutes: Int): LocalTime {
        val clamped = forMinutes.coerceIn(0, 24 * 60 - 1)
        return LocalTime.of(clamped / 60, clamped % 60)
    }

    fun minutes(from: LocalTime): Int = from.hour * 60 + from.minute
}

object SchedulePlanActions {
    fun removeCourse(entry: ResolvedPlan.Entry, plan: SchedulePlan, index: CatalogIndex?, userData: UserDataStore): PlanCourseRemoval {
        val protected = plan.courses.mapNotNull { it.manualCRN }.filter { it != entry.planCourse.manualCRN }.toSet()
        val crns = entry.courseCRNs.toMutableSet()
        crns += entry.selected.map { it.crn }
        if (index != null && entry.planCourse.manualCRN == null) crns += index.sections(entry.key).map { it.crn }
        crns -= protected
        val removal = PlanCourseRemoval(
            entry.planCourse,
            plan.courses.indexOfFirst { it.key == entry.key && it.manualCRN == entry.planCourse.manualCRN }.let { if (it < 0) plan.courses.size else it },
            plan.selectedCRNs.filter { it in crns },
            plan.favoriteCombinations.filter { combo -> combo.any { it in crns } },
        )
        userData.removeCourse(entry.key, plan.id, crns.toList(), entry.planCourse.crn)
        return removal
    }

    fun addAutofill(suggestion: ScheduleAutofillSuggestion, planId: String, userData: UserDataStore): List<CourseKey> {
        val added = ArrayList<CourseKey>()
        for (key in suggestion.courses) if (userData.addCourse(key, planId, required = true)) added += key
        return added
    }

    fun undoAutofill(keys: List<CourseKey>, planId: String, index: CatalogIndex?, userData: UserDataStore) {
        for (key in keys) userData.removeCourse(key, planId, index?.sections(key)?.map { it.crn }.orEmpty())
    }
}

data class ScheduleInstructorOption(val name: String, val displayName: String, val courses: List<CourseKey>) {
    val id: String get() = name
}

/** Campuses, instructors, and credits the preferences survey can offer for one schedule. */
data class SchedulePreferencesContext(
    val campusNames: List<String> = emptyList(),
    val instructors: List<ScheduleInstructorOption> = emptyList(),
    val optionalCourseCount: Int = 0,
    val requiredCredits: Int = 0,
    val totalCredits: Int = 0,
) {
    companion object {
        fun of(plan: SchedulePlan, index: CatalogIndex?): SchedulePreferencesContext {
            if (index == null) return SchedulePreferencesContext()
            val campuses = index.campusNames.filter { name ->
                !name.contains("online", ignoreCase = true) && !name.contains("no campus", ignoreCase = true)
            }
            val byName = LinkedHashMap<String, ScheduleInstructorOption>()
            var optional = 0
            var requiredCredits = 0
            var total = 0
            for (planCourse in plan.courses) {
                val course = index.course(planCourse.key)
                val least = ceil(course?.minCredits ?: 0.0).toInt()
                val most = ceil(course?.maxCredits ?: 0.0).toInt()
                if (planCourse.isRequired) requiredCredits += least else optional += 1
                total += max(least, most)
                for (section in index.sections(planCourse.key)) {
                    if (section.crn in planCourse.excludedCRNs) continue
                    for (instructor in section.instructors.orEmpty()) {
                        val existing = byName[instructor.name]?.courses.orEmpty()
                        val courses = if (planCourse.key in existing) existing else existing + planCourse.key
                        byName[instructor.name] = ScheduleInstructorOption(instructor.name, instructor.displayName, courses)
                    }
                }
            }
            return SchedulePreferencesContext(
                campuses,
                byName.values.sortedBy { it.name.lowercase() },
                optional,
                requiredCredits,
                total,
            )
        }
    }
}

/** One way to rearrange the degree plan so this semester's courses can be scheduled. */
data class ScheduleReorderOption(
    val id: String,
    val title: String,
    val reasons: List<String>,
    val addsSemester: Boolean,
    val movedAway: List<CourseKey>,
    val movedIn: List<CourseKey>,
    val apply: (DegreePlan) -> DegreePlan,
) {
    val appliedText: String
        get() = if (title.startsWith("Move ")) "Moved " + title.removePrefix("Move ") else "Updated your degree plan"

    val scheduleEffect: String
        get() {
            val parts = ArrayList<String>()
            if (movedAway.isNotEmpty()) {
                parts += "${list(movedAway)} ${if (movedAway.size == 1) "comes" else "come"} out of this schedule."
            }
            if (movedIn.isNotEmpty()) {
                val verb = if (movedIn.size == 1) "it\u2019s" else "they\u2019re"
                parts += "${list(movedIn)} will be added to it if $verb offered this term."
            }
            return parts.joinToString(" ")
        }

    companion object {
        fun list(keys: List<CourseKey>): String {
            val names = keys.map { it.description }
            val last = names.lastOrNull() ?: return ""
            return if (names.size == 1) last else names.dropLast(1).joinToString(", ") + " and " + last
        }
    }
}

data class ScheduleReorderResult(
    val options: List<ScheduleReorderOption> = emptyList(),
    val historyKnown: Boolean = true,
)

object ScheduleReorderSuggestions {
    fun semester(plan: SchedulePlan, degreePlan: DegreePlan?, terms: List<Term>, index: CatalogIndex?): Pair<DegreePlan, Term>? {
        degreePlan ?: return null
        val term = ScheduleAutofillSupport.term(plan.termCode, terms, index)
        if (degreePlan.termMatching(term) == null) return null
        return degreePlan to term
    }

    /**
     * Suggestions for moving [blocking] courses out of [term]. Returns nothing, and does no lookup,
     * when the degree plan has no matching semester or none of those courses are in it.
     */
    suspend fun load(
        blocking: List<CourseKey>,
        degreePlan: DegreePlan,
        term: Term,
        indexes: List<CatalogIndex>,
        historyOf: suspend (List<CourseKey>) -> OfferingHistory = { OfferingHistory(checkedKeys = it.toSet()) },
        prerequisites: (CourseKey) -> PrerequisiteLookup = { PrerequisiteLookup.None },
        equivalences: CourseEquivalences = CourseEquivalences.none,
    ): ScheduleReorderResult {
        val plan = degreePlan.sorted()
        val origin = plan.terms.firstOrNull { it.matches(term) } ?: return ScheduleReorderResult()
        val inSemester = origin.courses.mapNotNull { it.key }.toSet()
        val candidates = blocking.filter { it in inSemester }
        if (candidates.isEmpty()) return ScheduleReorderResult()
        val later = plan.terms.dropWhile { it.id != origin.id }.drop(1).flatMap { termRow -> termRow.courses.mapNotNull { it.key } }
        var history = historyOf(candidates)
        fun suggestions() = DegreePlanReordering.suggestions(degreePlan, origin.id, candidates, history, prerequisites, equivalences)
        var found = suggestions()
        if (found.any { it.moves.size == 1 }) found = found.filter { it.moves.size == 1 }
        else if (later.isNotEmpty()) {
            history = history.merging(historyOf(later))
            found = suggestions()
        }
        return ScheduleReorderResult(found.map { option(it, origin) }, history.checkedTerms.isNotEmpty())
    }

    fun option(suggestion: DegreePlanReordering.Suggestion, origin: DegreePlanTerm): ScheduleReorderOption =
        ScheduleReorderOption(
            id = suggestion.id,
            title = suggestion.title,
            reasons = suggestion.reasons,
            addsSemester = suggestion.addsSemester != null,
            movedAway = suggestion.moves.filter { it.from == origin.id }.map { it.course },
            movedIn = suggestion.moves.filter { it.to == origin.id }.map { it.course },
            apply = { DegreePlanReordering.apply(suggestion, it) },
        )
}
