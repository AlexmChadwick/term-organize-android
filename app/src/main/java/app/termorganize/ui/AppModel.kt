// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.termorganize.core.banner.BannerClient
import app.termorganize.core.banner.BannerParsers
import app.termorganize.core.banner.CatalogBuilder
import app.termorganize.core.model.AppJson
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseEquivalences
import app.termorganize.core.model.CourseInfo
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.HONORS_ATTRIBUTE_CODE
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Requisite
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.core.model.TermMenu
import app.termorganize.data.AppDatabase
import app.termorganize.data.CatalogStore
import app.termorganize.data.UserRow
import app.termorganize.data.WidgetFiles
import app.termorganize.logic.degree.CatalogProgram
import app.termorganize.logic.degree.CatalogYear
import app.termorganize.logic.degree.CourseEquivalence
import app.termorganize.logic.degree.DegreeCourseAttributes
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanAnalysis
import app.termorganize.logic.degree.DegreePlanAutofill
import app.termorganize.logic.degree.DegreePlanEditing
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanFormat
import app.termorganize.logic.degree.DegreePlanImportException
import app.termorganize.logic.degree.DegreePlanPdf
import app.termorganize.logic.degree.DegreePlanReordering
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.PlanCourse
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.degree.PrerequisiteLookup
import app.termorganize.logic.degree.RequisiteAnswer
import app.termorganize.logic.degree.ProgramCatalogParser
import app.termorganize.logic.degree.ProgramCatalogWalker
import app.termorganize.logic.degree.ProgramRequirements
import app.termorganize.logic.degree.RequirementSubstitution
import app.termorganize.logic.degree.ScheduleAutofillSuggestion
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.degree.UserData
import app.termorganize.logic.degree.UserJson
import app.termorganize.logic.degree.WatchItem
import app.termorganize.logic.nl.PreferencesParser
import app.termorganize.logic.offering.AttributeFetching
import app.termorganize.logic.offering.CourseAttributeRepository
import app.termorganize.logic.offering.CourseInfoFetching
import app.termorganize.logic.offering.CourseInfoRepository
import app.termorganize.logic.offering.CourseInfoStore
import app.termorganize.logic.offering.OfferingFetching
import app.termorganize.logic.offering.OfferingHistory
import app.termorganize.logic.offering.OfferingHistoryRepository
import app.termorganize.logic.schedule.PlanEditing
import app.termorganize.logic.schedule.ScheduleCalendarOutcome
import app.termorganize.logic.schedule.analyzeEmptyResult
import app.termorganize.logic.schedule.ScheduleCourseInput
import app.termorganize.logic.schedule.ScheduleReorderOption
import app.termorganize.logic.schedule.ScheduleReorderSuggestions
import app.termorganize.logic.schedule.inputs
import app.termorganize.logic.schedule.ScheduleExporter
import app.termorganize.logic.schedule.ScheduleFilters
import app.termorganize.logic.schedule.ScheduleGenerationResult
import app.termorganize.logic.schedule.ScheduleGenerator
import app.termorganize.logic.schedule.SchedulePreferences
import app.termorganize.logic.schedule.SchedulePreset
import app.termorganize.logic.schedule.calendarText
import app.termorganize.logic.schedule.events
import app.termorganize.logic.schedule.insertAll
import app.termorganize.logic.search.CatalogIndex
import app.termorganize.logic.search.CourseAppSearch
import app.termorganize.logic.search.SearchEngine
import app.termorganize.logic.search.SearchFilters
import app.termorganize.logic.search.SearchQuery
import app.termorganize.logic.widget.WidgetMeeting
import app.termorganize.logic.widget.WidgetScheduleData
import app.termorganize.widget.AgendaWidget
import app.termorganize.widget.UpNextWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

class AppModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.create(app).userDao()
    private val catalogs = CatalogStore(File(app.filesDir, "catalogs")) { code ->
        if (code == "202710") loadBundled() else null
    }
    private val offeringDirectory = File(app.filesDir, "course-info")
    private val _index = MutableStateFlow<CatalogIndex?>(null)
    private val _user = MutableStateFlow(UserData())
    private val _status = MutableStateFlow("Loading the catalog…")
    private val _error = MutableStateFlow<String?>(null)
    private val _program = MutableStateFlow<ProgramRequirements?>(null)
    private val _programs = MutableStateFlow<List<CatalogProgram>>(emptyList())
    private val _otherTerms = MutableStateFlow<List<Term>>(emptyList())
    private val _olderNote = MutableStateFlow<String?>(null)
    private val _availableTerms = MutableStateFlow<List<Term>>(emptyList())
    private val courseInfoLock = Mutex()
    private val courseInfos by lazy {
        CourseInfoStore(
            CourseInfoRepository(BannerCourseInfoFetcher(), offeringDirectory),
            OfferingHistoryRepository(BannerOfferingFetcher(), offeringDirectory),
            attributeRepository = CourseAttributeRepository(BannerAttributeFetcher(), offeringDirectory),
        )
    }
    private val _courseInfo = MutableStateFlow(CourseInfoSnapshot())
    private val _offering = MutableStateFlow(OfferingHistory.empty)
    private val _syncLabel = MutableStateFlow("")
    val index: StateFlow<CatalogIndex?> = _index
    val user: StateFlow<UserData> = _user
    val status: StateFlow<String> = _status
    val error: StateFlow<String?> = _error
    val program: StateFlow<ProgramRequirements?> = _program
    val programs: StateFlow<List<CatalogProgram>> = _programs
    val otherTerms: StateFlow<List<Term>> = _otherTerms
    val olderNote: StateFlow<String?> = _olderNote
    val availableTerms: StateFlow<List<Term>> = _availableTerms
    val offering: StateFlow<OfferingHistory> = _offering
    val courseInfo: StateFlow<CourseInfoSnapshot> = _courseInfo
    val syncLabel: StateFlow<String> = _syncLabel

    init {
        viewModelScope.launch {
            try {
                val stored = withContext(Dispatchers.IO) { dao.get()?.let { UserJson.decode(it.json) } }
                if (stored != null) _user.value = stored
                val catalog = withContext(Dispatchers.IO) { catalogs.load("202710") ?: loadBundled() }
                adopt(catalog)
                refreshTermList()
            } catch (error: Throwable) {
                _status.value = "Couldn’t load the catalog. ${error::class.java.simpleName}: ${error.message}"
                _error.value = error.message ?: error.javaClass.simpleName
            }
        }
    }

    private fun adopt(catalog: TermCatalog) {
        _index.value = CatalogIndex.build(catalog)
        _status.value = ""
        _syncLabel.value = ""
        if (_availableTerms.value.none { it.code == catalog.term.code }) {
            _availableTerms.value = (listOf(catalog.term) + _availableTerms.value).distinctBy { it.code }
        }
        publishWidget()
        val courses = catalog.courses
        val code = catalog.term.code
        viewModelScope.launch(Dispatchers.IO) {
            CourseAppSearch.index(getApplication(), code, courses)
        }
    }

    fun termLabel(code: String): String {
        val known = (_availableTerms.value + _otherTerms.value + listOfNotNull(_index.value?.term))
            .firstOrNull { it.code == code }
        return known?.name ?: code
    }

    fun openTermCode(code: String) {
        val known = (_availableTerms.value + _otherTerms.value + listOfNotNull(_index.value?.term))
            .firstOrNull { it.code == code }
        openTerm(known ?: Term(code, "Term $code"))
    }

    fun openTerm(term: Term, refresh: Boolean = false) {
        viewModelScope.launch {
            if (!refresh && _index.value?.term?.code == term.code && _index.value != null) return@launch
            val cached = if (refresh) null else withContext(Dispatchers.IO) { catalogs.load(term.code) }
            if (cached != null) {
                adopt(cached)
                return@launch
            }
            val downloaded = runCatching { withContext(Dispatchers.IO) { downloadTerm(term) } }
            downloaded.onSuccess { adopt(it) }.onFailure {
                _syncLabel.value = ""
                _status.value = ""
                _error.value = it.message ?: "Couldn’t download ${term.name}."
            }
        }
    }

    fun refreshOpenTerm() {
        val term = _index.value?.term ?: return
        openTerm(term, refresh = true)
    }

    private suspend fun downloadTerm(term: Term): TermCatalog {
        _syncLabel.value = "Connecting to Banner…"
        return BannerClient.shared { client ->
            val downloaded = client.fetchAllSections(term.code) { fetched, total ->
                _syncLabel.value = "Downloading sections $fetched of $total"
            }
            val sections = runCatching {
                client.attachFaculty(term.code, downloaded) { done, total ->
                    _syncLabel.value = "Loading instructors $done of $total"
                }
            }.getOrDefault(downloaded)
            val subjects = runCatching { client.fetchSubjects(term.code) }.getOrNull()
            val campuses = runCatching { client.fetchCampuses(term.code) }.getOrNull()
            val attributes = runCatching { client.fetchAttributes(term.code) }.getOrNull()
            _syncLabel.value = "Saving catalog…"
            val existing = catalogs.load(term.code)
            val knowledge = knowledgeExcept(term.code)
            val merged = CatalogBuilder.merge(existing, sections, term, subjects, campuses, attributes, knowledge)
            catalogs.save(merged)
            merged
        }
    }

    private fun knowledgeExcept(termCode: String): Map<CourseKey, Course> {
        val result = LinkedHashMap<CourseKey, Course>()
        for (code in catalogs.localCodes()) {
            if (code == termCode) continue
            val other = catalogs.load(code) ?: continue
            for (course in other.courses) {
                if (course.detailsLoaded && result[course.key] == null) result[course.key] = course
            }
        }
        return result
    }

    private fun refreshTermList() {
        viewModelScope.launch(Dispatchers.IO) {
            val remote = runCatching { BannerClient.shared { it.fetchTerms() } }
            val terms = remote.getOrElse { catalogs.loadRemoteTerms() }
            if (remote.isSuccess) catalogs.saveRemoteTerms(terms)
            val open = _index.value?.term
            val shown = TermMenu.listed(terms, catalogs.localCodes())
            val withOpen = if (open != null && shown.none { it.code == open.code }) listOf(open) + shown else shown
            _availableTerms.value = withOpen.ifEmpty { listOfNotNull(open) }
            _otherTerms.value = terms.sortedByDescending { it.code }
        }
    }

    fun refreshOffering(plan: DegreePlan) {
        val keys = (plan.completed + plan.terms.flatMap { it.courses }).mapNotNull { it.key }.distinct()
        if (keys.isEmpty()) {
            _offering.value = OfferingHistory.empty
            return
        }
        viewModelScope.launch {
            val loaded = listOfNotNull(_index.value)
            val history = runCatching {
                withContext(Dispatchers.IO) {
                    OfferingHistoryRepository(BannerOfferingFetcher(), offeringDirectory).history(keys, loaded)
                }
            }.getOrElse { OfferingHistory(checkedKeys = keys.toSet()) }
            _offering.value = history
        }
    }

    fun refreshCourseInfo(plan: DegreePlan) {
        val keys = (plan.completed + plan.terms.flatMap { it.courses }).mapNotNull { it.key }.distinct()
        if (keys.isEmpty()) {
            _courseInfo.value = CourseInfoSnapshot()
            return
        }
        val loaded = listOfNotNull(_index.value)
        viewModelScope.launch {
            val snapshot = runCatching {
                withContext(Dispatchers.IO) {
                    courseInfoLock.withLock {
                        courseInfos.load(keys, loaded)
                        courseInfos.loadAttributes(keys, loaded)
                        CourseInfoSnapshot(
                            courseInfos.infosSnapshot(),
                            courseInfos.equivalences(plan),
                            courseInfos.degreeAttributes(plan),
                        )
                    }
                }
            }.getOrElse { CourseInfoSnapshot() }
            _courseInfo.value = snapshot
        }
    }

    fun coursePrerequisite(key: CourseKey, plan: DegreePlan, completed: Boolean, termIndex: Int): DegreePlanAnalysis.PrerequisiteStatus {
        val catalog = _index.value
        val snapshot = _courseInfo.value
        val lookup = snapshot.lookup()
        val term = if (completed) -1 else termIndex
        return DegreePlanAnalysis.prerequisiteStatus(key, term, plan, catalog, lookup, snapshot.equivalences)
    }

    fun offeringLine(key: CourseKey?, season: PlanSeason?): String? {
        if (key == null) return null
        val history = _offering.value
        if (!history.isChecked(key)) return null
        return if (season == null) history.summary(key) else history.summary(key, season)
    }

    fun moveSuggestions(plan: DegreePlan, term: DegreePlanTerm): List<DegreePlanReordering.Suggestion> {
        val history = _offering.value
        if (history.checkedTerms.isEmpty()) return emptyList()
        val blocking = term.courses.mapNotNull { it.key }.filter { history.isLikelyOffered(it, term.season) == false }
        if (blocking.isEmpty()) return emptyList()
        return DegreePlanReordering.suggestions(
            plan, term.id, blocking, history, ::prerequisitesOf, plan.declaredEquivalences,
        )
    }

    fun applyMove(planId: String, suggestion: DegreePlanReordering.Suggestion) {
        updateDegree(planId) { DegreePlanReordering.apply(suggestion, it) }
    }

    private fun prerequisitesOf(key: CourseKey): PrerequisiteLookup {
        val course = _index.value?.course(key)
        if (course != null && course.detailsLoaded) {
            val prereqs: Requisite? = course.prereqs
            return if (prereqs == null) PrerequisiteLookup.None else PrerequisiteLookup.Needs(prereqs)
        }
        val known = _courseInfo.value.lookup()(key)
        return when (known) {
            is RequisiteAnswer.Known -> if (known.requisite == null) PrerequisiteLookup.None else PrerequisiteLookup.Needs(known.requisite)
            RequisiteAnswer.Unknown -> PrerequisiteLookup.Unknown
        }
    }

    fun courseIndexHits(query: String): List<CourseAppSearch.Hit> = CourseAppSearch.search(getApplication(), query)

    private fun loadBundled(): TermCatalog {
        val assets = getApplication<Application>().assets
        val bytes = listOf("catalog/term-202710.json.gz", "catalog/term-202710.json").firstNotNullOf { name ->
            runCatching { assets.open(name).use { it.readBytes() } }.getOrNull()
        }
        val text = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.decodeToString()
        } else bytes.decodeToString()
        return AppJson.catalog.decodeFromString(TermCatalog.serializer(), text)
    }

    fun update(block: (UserData) -> UserData) {
        val next = block(_user.value)
        _user.value = next
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsert(UserRow(json = UserJson.encode(next)))
        }
    }

    fun search(text: String, filters: SearchFilters = SearchFilters()) =
        _index.value?.let { SearchEngine.search(SearchQuery(text, filters), it) }

    fun course(key: CourseKey): Course? = _index.value?.course(key)
    fun sections(key: CourseKey): List<CourseSection> = _index.value?.sections(key).orEmpty()

    fun ensurePlan(): SchedulePlan {
        val term = _index.value?.term?.code ?: "202710"
        val existing = _user.value.plans.firstOrNull { it.termCode == term && it.isPrimary }
            ?: _user.value.plans.firstOrNull { it.termCode == term }
        if (existing != null) return existing
        val plan = SchedulePlan(name = "My Schedule", termCode = term, isPrimary = true)
        update { it.copy(plans = it.plans + plan) }
        return plan
    }

    fun addToPlan(key: CourseKey, crn: String? = null) {
        val plan = ensurePlan()
        if (!plan.admitsCourse(key, crn)) return
        val manual = crn?.trim()?.takeIf { it.isNotEmpty() }
        val course = PlanCourse(key, colorIndex = plan.nextColorIndex, crn = manual)
        update { data ->
            data.copy(plans = data.plans.map { current ->
                if (current.id != plan.id) current
                else current.copy(
                    courses = current.courses + course,
                    selectedCRNs = if (manual == null || manual in current.selectedCRNs) current.selectedCRNs else current.selectedCRNs + manual,
                )
            })
        }
    }

    fun toggleCrn(planId: String, crn: String) {
        val key = _index.value?.section(crn)?.courseKey
        update { data ->
            data.copy(plans = data.plans.map { plan ->
                if (plan.id != planId) plan else toggleSection(plan, crn, key)
            })
        }
        publishWidget()
    }

    private fun toggleSection(plan: SchedulePlan, crn: String, key: CourseKey?): SchedulePlan {
        val selected = crn in plan.selectedCRNs || plan.courses.any { it.manualCRN == crn }
        if (selected) {
            val cleared = plan.courses.map { if (it.manualCRN == crn) it.copy(crn = null) else it }
            return plan.copy(courses = collapseUnpinned(cleared), selectedCRNs = plan.selectedCRNs.filter { it != crn })
        }
        if (key == null) return plan.copy(selectedCRNs = if (crn in plan.selectedCRNs) plan.selectedCRNs else plan.selectedCRNs + crn)
        val open = plan.courses.indexOfFirst { it.key == key && it.manualCRN == null }
        if (open >= 0) {
            val courses = plan.courses.toMutableList()
            courses[open] = courses[open].copy(crn = crn)
            return plan.copy(courses = courses, selectedCRNs = plan.selectedCRNs + crn)
        }
        if (plan.courses.any { it.key == key }) {
            if (!plan.admitsCourse(key, crn)) return plan
            return plan.copy(
                courses = plan.courses + PlanCourse(key, colorIndex = plan.nextColorIndex, crn = crn),
                selectedCRNs = plan.selectedCRNs + crn,
            )
        }
        return plan.copy(selectedCRNs = plan.selectedCRNs + crn)
    }

    private fun collapseUnpinned(courses: List<PlanCourse>): List<PlanCourse> =
        courses.filter { course ->
            if (course.manualCRN != null) true
            else courses.none { it.key == course.key && it.manualCRN != null } &&
                courses.indexOfFirst { it.key == course.key && it.manualCRN == null } == courses.indexOf(course)
        }

    fun generate(plan: SchedulePlan): ScheduleGenerationResult {
        val index = _index.value ?: return ScheduleGenerationResult()
        val inputs = ScheduleCourseInput.inputs(plan, index)
        val preferences = plan.preferences ?: SchedulePreferences()
        val result = ScheduleGenerator.generate(inputs, plan.filters, preferences)
        if (result.totalFound > 0) return result
        return result.copy(noResults = ScheduleGenerator.analyzeEmptyResult(inputs, plan.filters, preferences, result.diagnostics))
    }

    fun scheduleMoves(blocking: List<CourseKey>): List<ScheduleReorderOption> {
        if (blocking.isEmpty()) return emptyList()
        val degree = _user.value.degreePlans.firstOrNull { it.isPrimary } ?: return emptyList()
        val term = _index.value?.term ?: return emptyList()
        val origin = degree.sorted().termMatching(term) ?: return emptyList()
        if (blocking.none { key -> origin.courses.any { it.key == key } }) return emptyList()
        val suggestions = DegreePlanReordering.suggestions(
            degree, origin.id, blocking, _offering.value, ::prerequisitesOf, degree.declaredEquivalences,
        )
        return suggestions.map { ScheduleReorderSuggestions.option(it, origin) }
    }

    fun applyReorder(option: ScheduleReorderOption) {
        val degree = _user.value.degreePlans.firstOrNull { it.isPrimary } ?: return
        updateDegree(degree.id) { option.apply(it) }
    }

    fun updatePlan(id: String, block: (SchedulePlan) -> SchedulePlan) {
        update { data -> data.copy(plans = data.plans.map { if (it.id == id) block(it).copy(updatedAt = System.currentTimeMillis()) else it }) }
        publishWidget()
    }

    fun createSchedule(name: String) {
        val term = _index.value?.term?.code ?: "202710"
        val plan = SchedulePlan(
            name = name.ifBlank { PlanEditing.nextPlanName(_user.value.plans.map { it.name }) },
            termCode = term,
            isPrimary = _user.value.plans.none { it.termCode == term },
        )
        update { it.copy(plans = it.plans + plan) }
    }

    fun setPrimarySchedule(id: String) {
        val term = _user.value.plans.firstOrNull { it.id == id }?.termCode ?: return
        update { data -> data.copy(plans = data.plans.map { if (it.termCode == term) it.copy(isPrimary = it.id == id) else it }) }
        publishWidget()
    }

    fun deleteSchedule(id: String) {
        update { it.copy(plans = it.plans.filter { plan -> plan.id != id }) }
    }

    fun duplicateSchedule(id: String) {
        val plan = _user.value.plans.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val copy = plan.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${plan.name} Copy",
            isPrimary = false,
            createdAt = now,
            updatedAt = now,
        )
        update { it.copy(plans = it.plans + copy) }
    }

    fun removeCourse(planId: String, key: CourseKey, crn: String? = null) {
        updatePlan(planId) { plan ->
            val manual = crn?.trim()?.takeIf { it.isNotEmpty() }
            val removing = plan.courses.filter { it.key == key && (manual == null || it.manualCRN == manual) }
            val kept = plan.courses.filter { it !in removing }
            val protected = kept.mapNotNull { it.manualCRN }.toSet()
            val catalog = if (manual == null) _index.value?.sections(key)?.map { it.crn }?.toSet().orEmpty() else emptySet()
            val drop = removing.mapNotNull { it.manualCRN }.toSet() + catalog
            plan.copy(
                courses = kept,
                selectedCRNs = plan.selectedCRNs.filter { it !in drop || it in protected },
                favoriteCombinations = plan.favoriteCombinations.filter { combo -> combo.none { it in drop && it !in protected } },
            )
        }
    }

    fun forgetSections(planId: String, crns: List<String>) {
        val drop = crns.toSet()
        updatePlan(planId) { plan ->
            plan.copy(
                selectedCRNs = plan.selectedCRNs.filter { it !in drop },
                favoriteCombinations = plan.favoriteCombinations.filter { combo -> combo.none { it in drop } },
            )
        }
    }

    fun setPreferences(planId: String, preferences: SchedulePreferences) = updatePlan(planId) { it.copy(preferences = preferences) }

    fun applyPreset(planId: String, preset: SchedulePreset) {
        updatePlan(planId) { it.copy(preferences = preset.toggled(it.preferences ?: SchedulePreferences())) }
    }

    fun setFilters(planId: String, filters: ScheduleFilters) = updatePlan(planId) { it.copy(filters = filters) }

    fun selectCombination(planId: String, crns: List<String>) = updatePlan(planId) { plan ->
        PlanEditing.applyCombination(crns, plan) { crn -> _index.value?.section(crn)?.courseKey }
    }

    fun applyAutofill(planId: String, suggestion: ScheduleAutofillSuggestion) {
        updatePlan(planId) { plan ->
            val existing = plan.courses.map { it.key }.toSet()
            var next = plan
            for (key in suggestion.courses) {
                if (key in existing) continue
                next = next.copy(courses = next.courses + PlanCourse(key, colorIndex = next.nextColorIndex))
            }
            next
        }
    }

    fun suggestions(plan: SchedulePlan): List<ScheduleAutofillSuggestion> {
        val term = _index.value?.term ?: return emptyList()
        return DegreePlanAutofill.scheduleSuggestions(
            term, _user.value.degreePlans, _user.value.plans, plan.id, plan.courses.map { it.key }.toSet(), _index.value,
        )
    }

    fun addDegreePlan(fromSchedule: Boolean = false) {
        val source = _user.value.degreePlans.firstOrNull { it.isPrimary } ?: _user.value.degreePlans.firstOrNull()
        val plan = if (fromSchedule) {
            DegreePlanAutofill.newDegreePlanDraft(
                "Degree Plan",
                source,
                _user.value.plans,
                { code -> if (code == _index.value?.term?.code) _index.value else null },
            ).copy(isPrimary = _user.value.degreePlans.isEmpty())
        } else DegreePlan(name = "Degree Plan", isPrimary = _user.value.degreePlans.isEmpty())
        update { it.copy(degreePlans = it.degreePlans + plan) }
    }

    fun updateDegree(id: String, block: (DegreePlan) -> DegreePlan) {
        update { data ->
            data.copy(degreePlans = data.degreePlans.map {
                if (it.id == id) block(it).copy(updatedAt = System.currentTimeMillis()) else it
            })
        }
    }

    fun setPrimaryDegree(id: String) {
        update { data -> data.copy(degreePlans = data.degreePlans.map { it.copy(isPrimary = it.id == id) }) }
    }

    fun duplicateDegree(id: String) {
        val plan = _user.value.degreePlans.firstOrNull { it.id == id } ?: return
        val copy = DegreePlanEditing.duplicate(plan, _user.value.degreePlans.map { it.name })
        update { it.copy(degreePlans = it.degreePlans + copy) }
    }

    fun deleteDegree(id: String) {
        update { data ->
            val rest = data.degreePlans.filter { it.id != id }
            val promoted = if (rest.isNotEmpty() && rest.none { it.isPrimary }) {
                rest.mapIndexed { index, plan -> if (index == 0) plan.copy(isPrimary = true) else plan }
            } else rest
            data.copy(degreePlans = promoted)
        }
    }

    fun addCompleted(planId: String, course: DegreePlanCourse) = updateDegree(planId) { plan ->
        if (!plan.admits(course)) plan else plan.copy(completed = plan.completed + course)
    }

    fun addPlanned(planId: String, termId: String, course: DegreePlanCourse) {
        updateDegree(planId) { plan ->
            if (!plan.admits(course)) plan
            else plan.copy(terms = plan.terms.map { term -> if (term.id == termId) term.copy(courses = term.courses + course) else term })
        }
    }

    fun addDuplicateCourse(
        planId: String,
        course: DegreePlanCourse,
        existingPins: Map<String, String>,
        titles: Map<String, String> = emptyMap(),
        termId: String? = null,
        season: PlanSeason? = null,
        year: Int? = null,
        completed: Boolean = false,
    ) {
        updateDegree(planId) { plan ->
            var updated = plan
            val destination = when {
                completed -> null
                termId != null -> termId
                season != null && year != null -> {
                    val existing = updated.terms.firstOrNull { it.season == season && it.year == year }
                    if (existing != null) existing.id
                    else {
                        val created = DegreePlanTerm(season = season, year = year)
                        updated = updated.copy(terms = (updated.terms + created).sortedBy { it.sortKey })
                        created.id
                    }
                }
                else -> return@updateDegree plan
            }
            DegreePlanEditing.addDuplicate(updated, destination, course, existingPins, titles)
        }
    }

    fun setDegreeSection(planId: String, courseId: String, crn: String, title: String? = null) {
        updateDegree(planId) { DegreePlanEditing.setSection(it, courseId, crn, title) }
    }

    fun pinDegreeRows(planId: String, pins: Map<String, String>, titles: Map<String, String> = emptyMap()) {
        updateDegree(planId) { DegreePlanEditing.pinRows(it, pins, titles) }
    }

    fun moveDegreeCourse(planId: String, courseId: String, termId: String) {
        updateDegree(planId) { DegreePlanEditing.move(courseId, termId, Int.MAX_VALUE, it) }
    }

    fun addTerm(planId: String, season: PlanSeason, year: Int) {
        updateDegree(planId) { plan ->
            if (plan.terms.any { it.season == season && it.year == year }) plan
            else plan.copy(terms = (plan.terms + DegreePlanTerm(season = season, year = year)).sortedBy { it.sortKey })
        }
    }

    fun removeDegreeCourse(planId: String, courseId: String) {
        updateDegree(planId) { plan ->
            plan.copy(
                completed = plan.completed.filter { it.id != courseId },
                terms = plan.terms.map { it.copy(courses = it.courses.filter { course -> course.id != courseId }) },
            )
        }
    }

    fun addEquivalence(planId: String, course: CourseKey, countsAs: CourseKey, note: String) {
        updateDegree(planId) {
            it.copy(courseEquivalences = it.courseEquivalences + CourseEquivalence(course = course, countsAs = countsAs, note = note))
        }
    }

    fun addSubstitution(planId: String, substitution: RequirementSubstitution) {
        updateDegree(planId) { it.copy(substitutions = it.substitutions + substitution) }
    }

    fun removeWatch(id: String) = update { it.copy(watchlist = it.watchlist.filter { item -> item.id != id }) }

    fun setAppPreferences(block: (app.termorganize.logic.degree.AppPreferences) -> app.termorganize.logic.degree.AppPreferences) {
        update { it.copy(preferences = block(it.preferences)) }
    }

    fun rememberSearch(text: String) {
        val trimmed = text.trim()
        if (trimmed.length < 2) return
        update { data ->
            data.copy(searchHistory = (listOf(trimmed) + data.searchHistory.filter { !it.equals(trimmed, true) }).take(12))
        }
    }

    fun clearError() { _error.value = null }
    fun report(message: String) { _error.value = message }

    private fun readCatalog(url: String): String =
        java.net.URL(url).openStream().bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun fetchPrograms(page: String = "https://catalog.northeastern.edu/undergraduate/") {
        viewModelScope.launch {
            _status.value = "Loading the academic catalog…"
            val roots = if (page.contains("/graduate/")) listOf(page) else listOf(page, "https://catalog.northeastern.edu/graduate/")
            val found = runCatching {
                withContext(Dispatchers.IO) {
                    roots.flatMap { start ->
                        runCatching { ProgramCatalogWalker.programs(start, ::readCatalog) }.getOrDefault(emptyList())
                    }.distinctBy { it.path }
                }
            }
            found.onSuccess { programs ->
                _programs.value = programs
                _status.value = ""
                if (programs.isEmpty()) _error.value = "The catalog page didn’t list any programs."
            }.onFailure { _error.value = it.message ?: "Couldn’t reach the academic catalog"; _status.value = "" }
        }
    }

    fun fetchRequirements(program: CatalogProgram, year: CatalogYear = CatalogYear.containing(System.currentTimeMillis())) {
        viewModelScope.launch {
            _status.value = "Loading ${program.title}…"
            val url = "https://catalog.northeastern.edu/${program.path.removePrefix("/")}"
            val html = runCatching { withContext(Dispatchers.IO) { java.net.URL(url).readText() } }
            html.onSuccess { text ->
                val parsed = ProgramCatalogParser.requirements(text, year, program)
                _program.value = parsed
                _status.value = ""
                if (parsed == null) _error.value = "That catalog page has no requirement tables."
            }.onFailure { _error.value = it.message ?: "Couldn’t load program requirements"; _status.value = "" }
        }
    }

    fun loadRequirementsHtml(title: String, path: String, html: String) {
        val program = CatalogProgram(title.ifBlank { path }, path.ifBlank { "pasted" })
        _program.value = ProgramCatalogParser.requirements(html, CatalogYear.containing(System.currentTimeMillis()), program)
        if (_program.value == null) _error.value = "That HTML has no requirement tables."
    }

    fun checkOlderTerms(key: CourseKey) {
        viewModelScope.launch {
            _olderNote.value = "Checking Banner for other terms…"
            val note = runCatching {
                withContext(Dispatchers.IO) {
                    val client = BannerClient()
                    try {
                        val terms = client.fetchTerms().filter { it.college == Term.College.NEU && !it.isViewOnly }
                        _otherTerms.value = terms
                        val current = _index.value?.term?.code
                        val older = terms.map { it.code }.filter { current == null || it < current }.take(4)
                        val found = mutableListOf<String>()
                        for (code in older) {
                            val cookie = runCatching { client.openSession(code) }.getOrNull() ?: continue
                            val sections = client.searchSections(code, cookie, key.subject, key.number, pageSize = 20)
                            val exact = sections.filter { it.subject == key.subject && it.courseNumber == key.number }
                            if (exact.isNotEmpty()) found += "${Term(code, "").name}: ${exact.size} sections"
                        }
                        if (found.isEmpty()) "No older Banner sections found for ${key.description}."
                        else found.joinToString("\n")
                    } finally {
                        client.close()
                    }
                }
            }.getOrElse { it.message ?: "Banner is unreachable. The bundled catalog is Fall 2026." }
            _olderNote.value = note
        }
    }

    fun applyPreferences(planId: String, sentence: String) {
        update { data ->
            data.copy(plans = data.plans.map { plan ->
                if (plan.id != planId) plan
                else plan.copy(preferences = PreferencesParser.parse(sentence, plan.preferences ?: SchedulePreferences()))
            })
        }
    }

    fun watch(section: CourseSection, title: String) {
        val term = _index.value?.term?.code ?: return
        val item = WatchItem(
            term, section.crn, section.courseKey, title, section.scheduleSummary,
            lastSeatsRemaining = section.seatsRemaining,
            lastWaitlistRemaining = section.waitlistRemaining,
            seatCapacity = section.seatCapacity,
            notifyOnWaitlist = true,
        )
        update { data ->
            if (data.watchlist.any { it.id == item.id }) data
            else data.copy(watchlist = data.watchlist + item)
        }
    }

    fun importText(name: String, text: String) {
        try {
            val parsed = DegreePlanFormat.parse(text, name)
            update { data ->
                val primary = data.degreePlans.isEmpty()
                data.copy(degreePlans = data.degreePlans.map { it.copy(isPrimary = if (primary) false else it.isPrimary) } + parsed.plan.copy(isPrimary = primary || parsed.plan.isPrimary))
            }
            _error.value = null
        } catch (e: DegreePlanImportException) {
            _error.value = e.message
        }
    }

    fun importUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val name = uri.lastPathSegment ?: "plan.pdf"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
            val text = if (name.endsWith(".pdf", true) || bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte()) {
                DegreePlanPdf.textFrom(bytes) ?: bytes.decodeToString()
            } else bytes.decodeToString()
            withContext(Dispatchers.Main) { importText(name, text) }
        }
    }

    fun exportPlanPdf(plan: DegreePlan): Uri {
        val bytes = DegreePlanPdf.render(plan, includeStudent = plan.studentName != null)
        return writeShare("${plan.name.ifBlank { "Degree Plan" }} – view-plan.pdf", bytes, "application/pdf")
    }

    fun exportIcs(plan: SchedulePlan): Uri {
        val index = _index.value
        val text = if (index == null) {
            ScheduleExporter.ics(plan.termCode, plan.name, emptyList(), null, null)
        } else {
            val (events, _) = ScheduleExporter.events(plan, index)
            ScheduleExporter.calendarText(events, "${plan.name} – ${index.term.name}")
        }
        return writeShare("${plan.name}.ics", text.toByteArray(), "text/calendar")
    }

    /** Writes every weekly meeting and every final into the device calendar. */
    fun insertCalendar(plan: SchedulePlan): String {
        val index = _index.value ?: return "Open a catalog before adding classes to the calendar."
        val (events, summary) = ScheduleExporter.events(plan, index)
        return when (val outcome = ScheduleExporter.insertAll(getApplication(), events)) {
            is ScheduleCalendarOutcome.Added ->
                "Added ${outcome.count} events (${summary.weeklyMeetings} meetings, ${summary.finalExams} finals)."
            ScheduleCalendarOutcome.NothingToAdd -> "There are no scheduled classes to export yet."
            ScheduleCalendarOutcome.Denied -> "Calendar permission is required to add every meeting."
            is ScheduleCalendarOutcome.Failed -> outcome.message
        }
    }

    fun shareImage(plan: SchedulePlan): Uri {
        val index = _index.value
        val sections = plan.selectedCRNs.mapNotNull { index?.section(it) }
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#123C3C"); textSize = 42f }
        canvas.drawText(plan.name, 48f, 72f, paint)
        paint.textSize = 28f
        paint.color = Color.parseColor("#0F6E6E")
        sections.take(12).forEachIndexed { i, section ->
            canvas.drawText("${section.courseKey.description}  ${section.scheduleSummary}  ${section.crn}", 48f, 140f + i * 48f, paint)
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return writeShare("${plan.name}.png", stream.toByteArray(), "image/png")
    }

    fun copyCrns(plan: SchedulePlan) {
        val text = plan.selectedCRNs.joinToString(", ")
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CRNs", text))
    }

    fun calendarIntent(plan: SchedulePlan): Intent? {
        val section = plan.selectedCRNs.firstNotNullOfOrNull { _index.value?.section(it) } ?: return null
        val meeting = section.weeklyMeetings.firstOrNull() ?: return null
        return Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, "${section.courseKey.description} ${section.title}")
            .putExtra(CalendarContract.Events.EVENT_LOCATION, meeting.locationLabel)
            .putExtra(CalendarContract.Events.DESCRIPTION, "CRN ${section.crn}")
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, System.currentTimeMillis())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, System.currentTimeMillis() + ((meeting.duration ?: 60) * 60_000L))
    }

    fun ensureCourseDetails(key: CourseKey) {
        val open = _index.value ?: return
        val course = open.course(key) ?: return
        if (course.detailsLoaded) return
        val term = open.term.code
        viewModelScope.launch {
            val info = runCatching {
                withContext(Dispatchers.IO) {
                    BannerClient.shared { client ->
                        client.fetchCourseInfo(key, term) {
                            BannerParsers.SubjectLookup(client.fetchSubjects(term))
                        }
                    }
                }
            }.getOrNull() ?: return@launch
            val current = _index.value ?: return@launch
            if (current.term.code != term) return@launch
            val existing = current.course(key) ?: return@launch
            if (existing.detailsLoaded) return@launch
            val updated = existing.copy(
                title = info.title.ifBlank { existing.title },
                description = info.description.ifBlank { existing.description },
                minCredits = info.minCredits ?: existing.minCredits,
                maxCredits = info.maxCredits ?: existing.maxCredits,
                prereqs = info.prerequisites ?: existing.prereqs,
                coreqs = info.corequisites ?: existing.coreqs,
                college = info.college ?: existing.college,
                department = info.department ?: existing.department,
                detailsLoaded = true,
            )
            val next = current.catalog.copy(
                courses = current.catalog.courses.map { if (it.key == key) updated else it },
            ).recomputePostreqs()
            withContext(Dispatchers.IO) { catalogs.save(next) }
            adopt(next)
        }
    }

    fun refreshCourse(key: CourseKey, onDone: (List<CourseSection>) -> Unit) {
        val term = _index.value?.term?.code ?: return
        viewModelScope.launch {
            val fresh = runCatching {
                withContext(Dispatchers.IO) { BannerClient().let { client -> try { client.refreshCourse(term, key, sections(key)) } finally { client.close() } } }
            }
            fresh.onSuccess { sections ->
                _index.value = _index.value?.replacingCourse(key, null, sections)
                onDone(sections)
            }.onFailure { _error.value = it.message ?: "Couldn’t refresh seats" }
        }
    }

    fun exportUser(): Uri = writeShare("term-organize-data.json", UserJson.encode(_user.value).toByteArray(), "application/json")

    fun importUser(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }?.decodeToString() ?: return@launch
            val decoded = runCatching { UserJson.decode(text) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                _user.value = decoded
                dao.upsert(UserRow(json = UserJson.encode(decoded)))
                publishWidget()
            }
        }
    }

    fun publishWidget() {
        val plan = _user.value.plans.firstOrNull { it.isPrimary && it.selectedCRNs.isNotEmpty() } ?: return
        val index = _index.value ?: return
        val meetings = plan.selectedCRNs.mapNotNull { index.section(it) }.flatMap { section ->
            section.weeklyMeetings.flatMap { meeting ->
                meeting.days.indices.map { day ->
                    WidgetMeeting(
                        "${section.crn}-$day-${meeting.start}",
                        section.crn,
                        section.courseKey.description,
                        section.title,
                        meeting.locationLabel,
                        section.classType,
                        day,
                        meeting.start ?: 0,
                        meeting.end ?: 0,
                        plan.courses.firstOrNull { it.key == section.courseKey }?.colorIndex ?: 0,
                        meeting.startDate,
                        meeting.endDate,
                    )
                }
            }
        }
        val data = WidgetScheduleData(System.currentTimeMillis(), plan.termCode, index.term.name, plan.name, meetings = meetings)
        WidgetFiles.save(getApplication(), data)
        viewModelScope.launch {
            UpNextWidget().updateAll(getApplication())
            AgendaWidget().updateAll(getApplication())
        }
    }

    private fun writeShare(name: String, bytes: ByteArray, unusedType: String): Uri {
        val dir = File(getApplication<Application>().cacheDir, "share").apply { mkdirs() }
        val file = File(dir, name.replace(Regex("""[\\/:*?"<>|]"""), "-"))
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(getApplication(), getApplication<Application>().packageName + ".files", file)
    }

    fun term(): Term? = _index.value?.term
    fun conflicts(sections: List<CourseSection>): Boolean =
        sections.indices.any { i -> sections.indices.any { j -> j > i && sections[i].conflicts(sections[j]) } }

    fun freeDayLabel(days: DaySet) = days.shortLabel
}

private class BannerOfferingFetcher : OfferingFetching {
    override suspend fun terms(): List<Term> = BannerClient.shared { it.fetchTerms() }
    override suspend fun offeredCourses(term: String, courses: List<CourseKey>): Set<CourseKey> =
        BannerClient.shared { it.fetchOfferedCourses(term, courses) }
}

private class BannerAttributeFetcher : AttributeFetching {
    override suspend fun terms(): List<Term> = BannerClient.shared { it.fetchTerms() }
    override suspend fun sectionAttributes(term: String, courses: List<CourseKey>): Map<CourseKey, Set<String>> {
        val sections = BannerClient.shared { it.fetchSections(term, courses) }
        return sections.filterValues { it.isNotEmpty() }.mapValues { (_, listed) ->
            listed.flatMap { it.sectionAttributes.orEmpty() }.map { it.code }.filter { it != HONORS_ATTRIBUTE_CODE }.toSet()
        }
    }
}

private class BannerCourseInfoFetcher : CourseInfoFetching {
    private val subjects = HashMap<String, BannerParsers.SubjectLookup>()
    override suspend fun terms(): List<Term> = BannerClient.shared { it.fetchTerms() }
    override suspend fun catalogEntry(key: CourseKey, term: String): CourseInfo? = BannerClient.shared { client ->
        client.fetchCourseInfo(key, term) {
            subjects[term] ?: BannerParsers.SubjectLookup(client.fetchSubjects(term)).also { subjects[term] = it }
        }
    }
}

/** Prerequisites learned for courses the open term does not list, plus equivalences from that lookup. */
data class CourseInfoSnapshot(
    val infos: Map<CourseKey, CourseInfo> = emptyMap(),
    val equivalences: CourseEquivalences = CourseEquivalences.none,
    val attributes: DegreeCourseAttributes = DegreeCourseAttributes.none,
) {
    fun lookup(): (CourseKey) -> RequisiteAnswer = { key ->
        val info = infos[key]
        if (info == null) RequisiteAnswer.Unknown else RequisiteAnswer.Known(info.prerequisites)
    }
}
