// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// The search, collector, and stratified sampler follow ScheduleGenerator.swift.

package app.termorganize.logic.schedule

import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.Meeting
import java.util.BitSet
import kotlin.math.max
import kotlin.math.min

internal object ScheduleEngine {
    fun measure(sections: List<CourseSection>, preferences: SchedulePreferences = SchedulePreferences()): GeneratedSchedule {
        val buildings = ScheduleBuildingTable()
        val intervals = ArrayList<ScheduleInterval>()
        var minSeats = Int.MAX_VALUE
        var totalSeats = 0
        var full = 0
        var points = 0.0
        for (section in sections) {
            val own = ScheduleInterval.intervals(section, buildings)
            intervals += own
            if (!preferences.isDefault) points += SchedulePreferenceRules.sectionScore(section, own, preferences)
            val seats = max(0, section.seatsRemaining)
            minSeats = min(minSeats, seats)
            totalSeats += seats
            if (seats == 0) full += 1
        }
        val raw = ScheduleMeasure.measure(intervals, ScheduleScoring.measureOptions(preferences))
        val floor = if (minSeats == Int.MAX_VALUE) 0 else minSeats
        val weights = ScheduleScoring.Weights(preferences)
        return GeneratedSchedule(
            crns = sections.map { it.crn },
            sections = sections,
            daysOnCampus = raw.campusDays,
            classDays = raw.classDays,
            earliestStart = if (raw.earliestStart == Int.MAX_VALUE) null else raw.earliestStart,
            latestEnd = if (raw.latestEnd == Int.MIN_VALUE) null else raw.latestEnd,
            totalGapMinutes = raw.gapMinutes,
            minSeats = floor,
            totalSeats = totalSeats,
            fullSections = full,
            score = weights.score(raw, full, floor) + points,
            reasons = SchedulePreferenceRules.reasons(sections, preferences),
        )
    }

    fun generate(
        courses: List<ScheduleCourseInput>,
        filters: ScheduleFilters,
        preferences: SchedulePreferences,
        sort: ScheduleSortOption,
        limits: ScheduleGenerator.Limits,
    ): ScheduleGenerationResult {
        val started = System.nanoTime()
        val result = run(courses, filters, preferences, sort, limits)
        return result.copy(elapsedNanos = System.nanoTime() - started)
    }

    fun suggestRelaxations(
        courses: List<ScheduleCourseInput>,
        filters: ScheduleFilters,
        preferences: SchedulePreferences,
        includeCourseRelaxations: Boolean = true,
    ): List<ScheduleSuggestion> {
        data class Candidate(
            val relaxation: ScheduleRelaxation,
            val filters: ScheduleFilters,
            val preferences: SchedulePreferences,
            val courses: List<ScheduleCourseInput>,
        )
        val candidates = ArrayList<Candidate>()
        fun addFilter(relaxation: ScheduleRelaxation, active: Boolean) {
            if (!active) return
            candidates += Candidate(relaxation, relaxation.apply(filters), preferences, courses)
        }
        addFilter(ScheduleRelaxation.ClearEarliestStart, filters.earliestStart != null)
        addFilter(ScheduleRelaxation.ClearLatestEnd, filters.latestEnd != null)
        addFilter(ScheduleRelaxation.ClearFreeDays, !filters.freeDays.isEmpty)
        addFilter(ScheduleRelaxation.AllowHonors, !filters.includeHonors)
        addFilter(ScheduleRelaxation.AllowOnline, !filters.includeOnline)
        addFilter(ScheduleRelaxation.AllowFullSections, filters.hideFullSections || filters.minSeats > 0)
        addFilter(ScheduleRelaxation.ClearCampus, filters.campus != null)
        addFilter(ScheduleRelaxation.ClearCourseCount, filters.courseCount != null)
        for (relaxation in PreferenceRelaxation.available(preferences)) {
            candidates += Candidate(ScheduleRelaxation.Preference(relaxation), filters, relaxation.apply(preferences), courses)
        }
        if (includeCourseRelaxations) {
            courses.forEachIndexed { index, course ->
                if (course.isRequired && courses.size > 1) {
                    val relaxed = courses.toMutableList()
                    relaxed[index] = course.copy(isRequired = false)
                    candidates += Candidate(ScheduleRelaxation.MakeOptional(course.key), filters, preferences, relaxed)
                }
            }
        }
        val suggestions = ArrayList<ScheduleSuggestion>()
        for (candidate in candidates) {
            val probe = generate(candidate.courses, candidate.filters, candidate.preferences, ScheduleSortOption.BEST, ScheduleGenerator.Limits.probe)
            if (probe.totalFound <= 0) continue
            suggestions += ScheduleSuggestion(candidate.relaxation, probe.totalFound, !probe.isExhaustive)
        }
        return suggestions.sortedByDescending { it.scheduleCount }
    }

    private fun run(
        courses: List<ScheduleCourseInput>,
        filters: ScheduleFilters,
        preferences: SchedulePreferences,
        sort: ScheduleSortOption,
        limits: ScheduleGenerator.Limits,
    ): ScheduleGenerationResult {
        val hasPreferences = !preferences.isDefault
        val kept = ArrayList<List<CourseSection>>(courses.size)
        val reports = ArrayList<ScheduleDiagnostics.CourseReport>(courses.size)
        for (course in courses) {
            val remaining = ArrayList<CourseSection>()
            val rejections = LinkedHashMap<ScheduleFilterReason, Int>()
            for (section in course.sections) {
                if (course.locked) {
                    remaining += section
                    continue
                }
                val reasons = ScheduleGenerator.rejectionReasons(section, filters, preferences, course.excludedCRNs)
                if (reasons.isEmpty()) remaining += section
                else reasons.forEach { rejections[it] = (rejections[it] ?: 0) + 1 }
            }
            kept += remaining
            reports += ScheduleDiagnostics.CourseReport(
                course.key, course.isRequired, course.sections.size, remaining.size, rejections,
            )
        }
        var diagnostics = ScheduleDiagnostics(courses = reports, filters = filters, preferences = preferences)
        if (courses.isEmpty()) return ScheduleGenerationResult(diagnostics = diagnostics)

        val required = courses.indices.filter { courses[it].isRequired }
        val optional = courses.indices.filter { !courses[it].isRequired && kept[it].isNotEmpty() }
        val target = filters.courseCount?.takeIf { it > 0 }
        val credit = preferences.creditTarget
        if (required.any { kept[it].isEmpty() }) {
            return ScheduleGenerationResult(diagnostics = diagnostics.copy(suggestions = ScheduleGenerator.suggestions(courses, kept, filters, preferences)))
        }
        if (target != null) {
            if (required.size > target) {
                return ScheduleGenerationResult(diagnostics = diagnostics.copy(
                    countProblem = CountProblem.TooManyRequired(required.size, target),
                    suggestions = listOf("More required courses (${required.size}) than the $target you asked for."),
                ))
            }
            if (required.size + optional.size < target) {
                return ScheduleGenerationResult(diagnostics = diagnostics.copy(
                    countProblem = CountProblem.TooFewCourses(required.size + optional.size, target),
                    suggestions = listOf("Only ${required.size + optional.size} courses can be scheduled, and you asked for $target."),
                ))
            }
        }
        if (credit != null) {
            val requiredCredits = required.sumOf { courses[it].minCredits }
            if (requiredCredits > credit.upper) {
                return ScheduleGenerationResult(diagnostics = diagnostics.copy(
                    countProblem = CountProblem.CreditsOverTarget(requiredCredits, credit.upper),
                    suggestions = listOf("Required courses are already $requiredCredits credits, above the ${credit.upper} credit limit."),
                ))
            }
            val available = required.sumOf { courses[it].maxCredits } + optional.sumOf { courses[it].maxCredits }
            if (available < credit.lower) {
                return ScheduleGenerationResult(diagnostics = diagnostics.copy(
                    countProblem = CountProblem.CreditsUnderTarget(available, credit.lower),
                    suggestions = listOf("These courses add up to $available credits, short of the ${credit.lower} you asked for."),
                ))
            }
        }

        fun ordered(indexes: List<Int>) = indexes.sortedWith(compareBy({ kept[it].size }, { it }))
        val searchOrder = ordered(required) + ordered(optional)
        if (searchOrder.isEmpty()) return ScheduleGenerationResult(diagnostics = diagnostics)

        val sections = ArrayList<CourseSection>()
        val masks = ArrayList<ScheduleSlotMask>()
        val weekly = ArrayList<List<Meeting>>()
        val intervalStart = ArrayList<Int>()
        val intervals = ArrayList<ScheduleInterval>()
        val sectionPoints = ArrayList<Double>()
        val dayClasses = ArrayList<IntArray>()
        val dayMinutes = ArrayList<IntArray>()
        val slots = ArrayList<SearchSlot>()
        val slotMin = ArrayList<Int>()
        val slotMax = ArrayList<Int>()
        val buildings = ScheduleBuildingTable()
        for (courseIndex in searchOrder) {
            val first = sections.size
            val candidates = kept[courseIndex].mapIndexed { offset, section ->
                val own = ScheduleInterval.intervals(section, buildings)
                val points = if (hasPreferences) SchedulePreferenceRules.sectionScore(section, own, preferences) else 0.0
                Candidate(offset, section, own, points)
            }
            val orderedCandidates = if (hasPreferences) {
                candidates.sortedWith(compareByDescending<Candidate> { it.points }.thenBy { it.offset })
            } else candidates
            for (candidate in orderedCandidates) {
                sections += candidate.section
                val meetings = candidate.section.weeklyMeetings
                weekly += meetings
                masks += ScheduleSlotMask.of(candidate.section)
                intervalStart += intervals.size
                intervals += candidate.intervals
                sectionPoints += candidate.points
                val classes = IntArray(7)
                val minutes = IntArray(7)
                for (interval in candidate.intervals) {
                    classes[interval.day] = 1
                    minutes[interval.day] += interval.end - interval.start
                }
                dayClasses += classes
                dayMinutes += minutes
            }
            slots += SearchSlot(courseIndex, courses[courseIndex].isRequired, first, sections.size - first)
            slotMin += courses[courseIndex].minCredits
            slotMax += max(courses[courseIndex].minCredits, courses[courseIndex].maxCredits)
        }
        intervalStart += intervals.size
        val travel = if (preferences.breaks == BreakPreference.AT_LEAST && preferences.minimumBreakStrength == PreferenceStrength.REQUIRED) {
            preferences.minimumBreakMinutes
        } else 0
        val rules = SearchRules(
            maxClassesPerDay = preferences.maxClassesPerDay ?: Int.MAX_VALUE,
            maxMinutesPerDay = preferences.maxMinutesPerDay ?: Int.MAX_VALUE,
            dayClasses = dayClasses,
            dayMinutes = dayMinutes,
            travelMinutes = travel,
            credit = credit,
            slotMin = slotMin,
            slotMax = slotMax,
        )
        val seats = IntArray(sections.size) { max(0, sections[it].seatsRemaining) }
        val collector = ScheduleCollector(
            sort, max(1, limits.maxResults), slots.map { it.start }, intervalStart, intervals, seats, sectionPoints,
            ScheduleScoring.Weights(preferences), ScheduleScoring.measureOptions(preferences),
        )
        val search = ScheduleSearch(slots, masks, weekly, sections, target, rules, limits, collector, seed(sections))
        search.runExhaustive()
        var totalFound = search.leafCount
        var exhaustive = !search.hitLimit
        if (search.hitLimit && limits.allowSampling && limits.sampleLeaves > 0) {
            exhaustive = false
            search.runSampled()
            totalFound = max(totalFound, search.leafCount)
        } else if (search.hitLimit) {
            exhaustive = false
        }
        if (totalFound == 0) {
            val pairs = if (exhaustive) conflictingPairs(search, slots, courses) else emptyList()
            diagnostics = diagnostics.copy(
                conflictingPairs = pairs,
                suggestions = ScheduleGenerator.suggestions(courses, kept, filters, preferences),
            )
            return ScheduleGenerationResult(totalFound = 0, isExhaustive = exhaustive, diagnostics = diagnostics)
        }
        val schedules = ArrayList<GeneratedSchedule>(collector.count)
        for (ranked in collector.ranked()) {
            val picked = ArrayList<Pair<Int, CourseSection>>()
            for (s in slots.indices) {
                val pick = ranked.picks[s]
                if (pick < 0) continue
                picked += slots[s].courseIndex to sections[slots[s].start + pick]
            }
            picked.sortBy { it.first }
            val chosen = picked.map { it.second }
            val m = ranked.metrics
            schedules += GeneratedSchedule(
                crns = chosen.map { it.crn },
                sections = chosen,
                daysOnCampus = m.campusDays,
                classDays = m.classDays,
                earliestStart = if (m.earliest == Int.MAX_VALUE) null else m.earliest,
                latestEnd = if (m.classDays == 0) null else m.latest,
                totalGapMinutes = m.gap,
                minSeats = m.minSeats,
                totalSeats = m.totalSeats,
                fullSections = m.full,
                score = m.score,
                reasons = if (hasPreferences) SchedulePreferenceRules.reasons(chosen, preferences) else emptyList(),
            )
        }
        return ScheduleGenerationResult(schedules, totalFound, exhaustive, diagnostics)
    }

    private fun conflictingPairs(search: ScheduleSearch, slots: List<SearchSlot>, courses: List<ScheduleCourseInput>): List<ConflictPair> {
        val pairs = ArrayList<ConflictPair>()
        for (a in slots.indices) {
            for (b in slots.indices) {
                if (b <= a || !slots[a].required || !slots[b].required) continue
                if (search.hasCompatiblePair(a, b)) continue
                val reason = if (search.hasOverlapFreePair(a, b)) ConflictReason.TRAVEL_TIME else ConflictReason.OVERLAP
                pairs += ConflictPair(courses[slots[a].courseIndex].key, courses[slots[b].courseIndex].key, reason)
            }
        }
        return pairs
    }

    private fun seed(sections: List<CourseSection>): Long {
        var hash = 0xcbf29ce484222325u.toLong()
        for (section in sections) {
            for (byte in section.crn.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and 0xff)
                hash *= 0x100000001b3u.toLong()
            }
        }
        return hash
    }

    private data class Candidate(val offset: Int, val section: CourseSection, val intervals: List<ScheduleInterval>, val points: Double)
}

private data class SearchSlot(val courseIndex: Int, val required: Boolean, val start: Int, val size: Int)

private class LeafMetrics {
    var classDays: Int = 0
    var campusDays: Int = 0
    var earliest: Int = Int.MAX_VALUE
    var latest: Int = 0
    var gap: Int = 0
    var minSeats: Int = 0
    var totalSeats: Int = 0
    var full: Int = 0
    var score: Double = 0.0
}

private fun ScheduleSortOption.precedes(a: LeafMetrics, b: LeafMetrics, tieBreak: Boolean): Boolean {
    when (this) {
        ScheduleSortOption.BEST -> Unit
        ScheduleSortOption.FEWEST_DAYS -> {
            if (a.campusDays != b.campusDays) return a.campusDays < b.campusDays
            if (a.classDays != b.classDays) return a.classDays < b.classDays
            if (a.gap != b.gap) return a.gap < b.gap
        }
        ScheduleSortOption.LATEST_START -> {
            if (a.earliest != b.earliest) return a.earliest > b.earliest
            if (a.campusDays != b.campusDays) return a.campusDays < b.campusDays
        }
        ScheduleSortOption.EARLIEST_FINISH -> {
            if (a.latest != b.latest) return a.latest < b.latest
            if (a.campusDays != b.campusDays) return a.campusDays < b.campusDays
        }
        ScheduleSortOption.FEWEST_GAPS -> {
            if (a.gap != b.gap) return a.gap < b.gap
            if (a.campusDays != b.campusDays) return a.campusDays < b.campusDays
        }
        ScheduleSortOption.MOST_SEATS -> {
            if (a.minSeats != b.minSeats) return a.minSeats > b.minSeats
            if (a.totalSeats != b.totalSeats) return a.totalSeats > b.totalSeats
        }
    }
    if (a.score != b.score) return a.score > b.score
    return tieBreak
}

private class ScheduleCollector(
    private val sort: ScheduleSortOption,
    private val capacity: Int,
    private val slotStart: List<Int>,
    private val intervalStart: List<Int>,
    private val intervals: List<ScheduleInterval>,
    private val seatsRemaining: IntArray,
    private val sectionPoints: List<Double>,
    private val weights: ScheduleScoring.Weights,
    private val options: ScheduleMeasureOptions,
) {
    class Entry(val metrics: LeafMetrics, val seq: Int, val hash: Long, val picks: IntArray)

    private val slotCount = slotStart.size
    private val entries = ArrayList<Entry>()
    private val members = HashSet<Long>()
    private var nextSeq = 0
    private val scratch = ArrayList<ScheduleInterval>()

    val count: Int get() = entries.size

    fun offer(picks: IntArray) {
        scratch.clear()
        var minSeats = Int.MAX_VALUE
        var totalSeats = 0
        var full = 0
        var points = 0.0
        for (s in 0 until slotCount) {
            val pick = picks[s]
            if (pick < 0) continue
            val g = slotStart[s] + pick
            for (k in intervalStart[g] until intervalStart[g + 1]) scratch += intervals[k]
            val seats = seatsRemaining[g]
            minSeats = min(minSeats, seats)
            totalSeats += seats
            if (seats == 0) full += 1
            points += sectionPoints[g]
        }
        val raw = ScheduleMeasure.measure(scratch, options)
        val floor = if (minSeats == Int.MAX_VALUE) 0 else minSeats
        val metrics = LeafMetrics().also {
            it.classDays = raw.classDays
            it.campusDays = raw.campusDays
            it.earliest = raw.earliestStart
            it.latest = if (raw.latestEnd == Int.MIN_VALUE) 0 else raw.latestEnd
            it.gap = raw.gapMinutes
            it.minSeats = floor
            it.totalSeats = totalSeats
            it.full = full
            it.score = weights.score(raw, full, floor) + points
        }
        val seq = nextSeq
        nextSeq += 1
        if (entries.size == capacity && !sort.precedes(metrics, entries[0].metrics, false)) return
        var hash = 0xcbf29ce484222325u.toLong()
        for (s in 0 until slotCount) {
            hash = hash xor (picks[s] + 1).toLong()
            hash *= 0x100000001b3u.toLong()
        }
        if (!members.add(hash)) return
        val stored = picks.copyOf()
        if (entries.size < capacity) {
            entries += Entry(metrics, seq, hash, stored)
            siftUp(entries.lastIndex)
        } else {
            members.remove(entries[0].hash)
            entries[0] = Entry(metrics, seq, hash, stored)
            siftDown(0)
        }
    }

    fun ranked(): List<Entry> = entries.sortedWith { a, b ->
        when {
            sort.precedes(a.metrics, b.metrics, a.seq < b.seq) -> -1
            sort.precedes(b.metrics, a.metrics, b.seq < a.seq) -> 1
            else -> 0
        }
    }

    private fun isWorse(a: Entry, b: Entry) = sort.precedes(b.metrics, a.metrics, b.seq < a.seq)

    private fun siftUp(index: Int) {
        var child = index
        while (child > 0) {
            val parent = (child - 1) / 2
            if (!isWorse(entries[child], entries[parent])) return
            entries.swap(child, parent)
            child = parent
        }
    }

    private fun siftDown(index: Int) {
        var parent = index
        val n = entries.size
        while (true) {
            val left = 2 * parent + 1
            val right = left + 1
            var worst = parent
            if (left < n && isWorse(entries[left], entries[worst])) worst = left
            if (right < n && isWorse(entries[right], entries[worst])) worst = right
            if (worst == parent) return
            entries.swap(parent, worst)
            parent = worst
        }
    }
}

private fun <T> MutableList<T>.swap(i: Int, j: Int) {
    val tmp = this[i]
    this[i] = this[j]
    this[j] = tmp
}

private class SearchRules(
    val maxClassesPerDay: Int,
    val maxMinutesPerDay: Int,
    val dayClasses: List<IntArray>,
    val dayMinutes: List<IntArray>,
    val travelMinutes: Int,
    val credit: CreditTarget?,
    val slotMin: List<Int>,
    val slotMax: List<Int>,
) {
    val limitsDays: Boolean = maxClassesPerDay != Int.MAX_VALUE || maxMinutesPerDay != Int.MAX_VALUE
}

private class ScheduleSearch(
    private val slots: List<SearchSlot>,
    private val masks: List<ScheduleSlotMask>,
    private val weeklyMeetings: List<List<Meeting>>,
    private val sections: List<CourseSection>,
    private val target: Int?,
    private val rules: SearchRules,
    private val limits: ScheduleGenerator.Limits,
    private val collector: ScheduleCollector,
    seed: Long,
) {
    private val slotCount = slots.size
    private val compat: Array<BitSet> = Array(sections.size) { BitSet(sections.size) }
    private val picks = IntArray(slotCount) { SKIP_SECTION }
    private val loadClasses = IntArray(7)
    private val loadMinutes = IntArray(7)
    private var creditsMin = 0
    private var creditsMax = 0
    private var chosen = 0
    private var nodes = 0
    private var maxLeaves = 1
    private var deadline = 0L
    private val rng = ScheduleSplitMix64(seed)
    var leafCount = 0
        private set
    var hitLimit = false
        private set
    private var aborted = false

    init {
        val slotOf = IntArray(sections.size)
        for ((s, slot) in slots.withIndex()) {
            for (g in slot.start until slot.start + slot.size) slotOf[g] = s
        }
        for (g in sections.indices) {
            for (h in g + 1 until sections.size) {
                if (slotOf[g] == slotOf[h]) continue
                if (masks[g].conflicts(masks[h]) && meetingsConflict(weeklyMeetings[g], weeklyMeetings[h])) continue
                if (rules.travelMinutes > 0 && SchedulePreferenceRules.tooClose(sections[g], weeklyMeetings[g], sections[h], weeklyMeetings[h], rules.travelMinutes)) continue
                compat[g].set(h)
                compat[h].set(g)
            }
        }
    }

    fun hasCompatiblePair(slotA: Int, slotB: Int): Boolean {
        val a = slots[slotA]
        val b = slots[slotB]
        for (i in 0 until a.size) {
            val row = compat[a.start + i]
            for (j in 0 until b.size) if (row.get(b.start + j)) return true
        }
        return false
    }

    fun hasOverlapFreePair(slotA: Int, slotB: Int): Boolean {
        if (rules.travelMinutes <= 0) return hasCompatiblePair(slotA, slotB)
        val a = slots[slotA]
        val b = slots[slotB]
        for (i in 0 until a.size) {
            for (j in 0 until b.size) {
                val g = a.start + i
                val h = b.start + j
                if (!(masks[g].conflicts(masks[h]) && meetingsConflict(weeklyMeetings[g], weeklyMeetings[h]))) return true
            }
        }
        return false
    }

    fun runExhaustive() {
        reset(limits.maxExhaustiveLeaves, limits.timeBudgetNanos)
        descendExhaustive(0)
    }

    fun runSampled() {
        reset(limits.sampleLeaves, limits.sampleTimeBudgetNanos)
        descendSampled(0, maxLeaves)
    }

    private fun reset(leaves: Int, budgetNanos: Long) {
        maxLeaves = max(1, leaves)
        leafCount = 0
        hitLimit = false
        aborted = false
        nodes = 0
        chosen = 0
        creditsMin = 0
        creditsMax = 0
        loadClasses.fill(0)
        loadMinutes.fill(0)
        picks.fill(SKIP_SECTION)
        deadline = System.nanoTime() + max(50_000_000L, budgetNanos)
    }

    private fun checkBudget() {
        if (nodes >= limits.maxNodes || System.nanoTime() >= deadline) {
            hitLimit = true
            aborted = true
        }
    }

    private fun meetingsConflict(a: List<Meeting>, b: List<Meeting>) = a.any { x -> b.any { x.overlaps(it) } }

    private fun compatibleWithChosen(global: Int): Boolean {
        for (s in 0 until slotCount) {
            val pick = picks[s]
            if (pick < 0) continue
            if (!compat[global].get(slots[s].start + pick)) return false
        }
        return true
    }

    private fun canAdd(global: Int, slot: Int): Boolean {
        if (rules.limitsDays) {
            val classes = rules.dayClasses[global]
            val minutes = rules.dayMinutes[global]
            for (day in 0..6) {
                if (loadClasses[day] + classes[day] > rules.maxClassesPerDay) return false
                if (loadMinutes[day] + minutes[day] > rules.maxMinutesPerDay) return false
            }
        }
        val window = rules.credit
        if (window != null && creditsMin + rules.slotMin[slot] > window.upper) return false
        return true
    }

    private fun take(global: Int, slot: Int) {
        if (rules.limitsDays) {
            val classes = rules.dayClasses[global]
            val minutes = rules.dayMinutes[global]
            for (day in 0..6) {
                loadClasses[day] += classes[day]
                loadMinutes[day] += minutes[day]
            }
        }
        creditsMin += rules.slotMin[slot]
        creditsMax += rules.slotMax[slot]
    }

    private fun release(global: Int, slot: Int) {
        if (rules.limitsDays) {
            val classes = rules.dayClasses[global]
            val minutes = rules.dayMinutes[global]
            for (day in 0..6) {
                loadClasses[day] -= classes[day]
                loadMinutes[day] -= minutes[day]
            }
        }
        creditsMin -= rules.slotMin[slot]
        creditsMax -= rules.slotMax[slot]
    }

    private fun canStillAdd(slot: Int): Boolean {
        val current = slots[slot]
        for (local in 0 until current.size) {
            val global = current.start + local
            if (compatibleWithChosen(global) && canAdd(global, slot)) return true
        }
        return false
    }

    private fun feasible(from: Int): Boolean {
        if (target == null && rules.credit == null) return true
        var reachable = chosen
        var most = creditsMax
        var least = creditsMin
        for (s in from until slotCount) {
            val required = slots[s].required
            if (required || canStillAdd(s)) {
                reachable += 1
                most += rules.slotMax[s]
            }
            if (required) least += rules.slotMin[s]
        }
        if (target != null && reachable < target) return false
        val window = rules.credit
        if (window != null && (most < window.lower || least > window.upper)) return false
        return true
    }

    private fun recordLeaf(): Boolean {
        if (chosen == 0) return false
        if (target != null && chosen != target) return false
        val window = rules.credit
        if (window != null && (creditsMin > window.upper || creditsMax < window.lower)) return false
        if (target == null && window == null) {
            for (s in 0 until slotCount) {
                if (slots[s].required || picks[s] != SKIP_SECTION) continue
                if (canStillAdd(s)) return false
            }
        }
        collector.offer(picks)
        leafCount += 1
        return true
    }

    private fun stopIfLeafBudget() {
        if (leafCount >= maxLeaves) {
            hitLimit = true
            aborted = true
        }
    }

    private fun descendExhaustive(depth: Int) {
        if (aborted) return
        nodes += 1
        if (nodes and 0x3FF == 0) {
            checkBudget()
            if (aborted) return
        }
        if (depth == slotCount) {
            if (recordLeaf()) stopIfLeafBudget()
            return
        }
        if (target != null && chosen == target) {
            for (s in depth until slotCount) picks[s] = SKIP_SECTION
            if (recordLeaf()) stopIfLeafBudget()
            return
        }
        if (!feasible(depth)) return
        val slot = slots[depth]
        if (!slot.required && feasible(depth + 1)) {
            picks[depth] = SKIP_SECTION
            descendExhaustive(depth + 1)
            if (aborted) return
        }
        for (local in 0 until slot.size) {
            val global = slot.start + local
            if (!compatibleWithChosen(global) || !canAdd(global, depth)) continue
            take(global, depth)
            picks[depth] = local
            chosen += 1
            descendExhaustive(depth + 1)
            chosen -= 1
            picks[depth] = SKIP_SECTION
            release(global, depth)
            if (aborted) return
        }
    }

    private fun descendSampled(depth: Int, budget: Int): Int {
        if (aborted) return 0
        nodes += 1
        if (nodes and 0x3FF == 0) {
            checkBudget()
            if (aborted) return 0
        }
        if (depth == slotCount) return if (recordLeaf()) 1 else 0
        if (target != null && chosen == target) {
            for (s in depth until slotCount) picks[s] = SKIP_SECTION
            return if (recordLeaf()) 1 else 0
        }
        if (!feasible(depth)) return 0
        val slot = slots[depth]
        val options = ArrayList<Int>()
        if (!slot.required && feasible(depth + 1)) options += SKIP_SECTION
        for (local in 0 until slot.size) {
            val global = slot.start + local
            if (compatibleWithChosen(global) && canAdd(global, depth)) options += local
        }
        if (options.isEmpty()) return 0
        options.shuffle(rng)
        var remaining = budget
        var left = options.size
        var emitted = 0
        for (option in options) {
            if (aborted || remaining <= 0) break
            val share = max(1, (remaining + left - 1) / left)
            left -= 1
            var got = 0
            if (option == SKIP_SECTION) {
                picks[depth] = SKIP_SECTION
                got = descendSampled(depth + 1, share)
            } else {
                val global = slot.start + option
                take(global, depth)
                picks[depth] = option
                chosen += 1
                got = descendSampled(depth + 1, share)
                chosen -= 1
                picks[depth] = SKIP_SECTION
                release(global, depth)
            }
            emitted += got
            remaining -= got
        }
        return emitted
    }
}

private const val SKIP_SECTION = -1

internal class ScheduleSplitMix64(var state: Long) {
    fun next(): Long {
        state += 0x9E3779B97F4A7C15u.toLong()
        var z = state
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9u.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBu.toLong()
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int {
        if (bound <= 1) return 0
        return (next().ushr(1) % bound.toLong()).toInt()
    }
}

private fun <T> MutableList<T>.shuffle(rng: ScheduleSplitMix64) {
    var amount = size
    var current = 0
    while (amount > 1) {
        val random = rng.nextInt(amount)
        amount -= 1
        val swapIndex = current + random
        val tmp = this[current]
        this[current] = this[swapIndex]
        this[swapIndex] = tmp
        current += 1
    }
}
