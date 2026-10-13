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
import app.termorganize.core.model.AppJson
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.data.AppDatabase
import app.termorganize.data.UserRow
import app.termorganize.data.WidgetFiles
import app.termorganize.logic.degree.CatalogProgram
import app.termorganize.logic.degree.CatalogYear
import app.termorganize.logic.degree.CourseEquivalence
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanAutofill
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanFormat
import app.termorganize.logic.degree.DegreePlanImportException
import app.termorganize.logic.degree.DegreePlanPdf
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.PlanCourse
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.degree.ProgramCatalogParser
import app.termorganize.logic.degree.ProgramRequirements
import app.termorganize.logic.degree.RequirementSubstitution
import app.termorganize.logic.degree.ScheduleAutofillSuggestion
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.degree.UserData
import app.termorganize.logic.degree.UserJson
import app.termorganize.logic.degree.WatchItem
import app.termorganize.logic.nl.PreferencesParser
import app.termorganize.logic.schedule.ScheduleCourseInput
import app.termorganize.logic.schedule.ScheduleExporter
import app.termorganize.logic.schedule.ScheduleFilters
import app.termorganize.logic.schedule.ScheduleGenerationResult
import app.termorganize.logic.schedule.ScheduleGenerator
import app.termorganize.logic.schedule.SchedulePreferences
import app.termorganize.logic.schedule.SchedulePreset
import app.termorganize.logic.search.CatalogIndex
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
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

class AppModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.create(app).userDao()
    private val _index = MutableStateFlow<CatalogIndex?>(null)
    private val _user = MutableStateFlow(UserData())
    private val _status = MutableStateFlow("Loading the Fall 2026 catalog…")
    private val _error = MutableStateFlow<String?>(null)
    private val _program = MutableStateFlow<ProgramRequirements?>(null)
    private val _programs = MutableStateFlow<List<CatalogProgram>>(emptyList())
    private val _otherTerms = MutableStateFlow<List<Term>>(emptyList())
    private val _olderNote = MutableStateFlow<String?>(null)
    val index: StateFlow<CatalogIndex?> = _index
    val user: StateFlow<UserData> = _user
    val status: StateFlow<String> = _status
    val error: StateFlow<String?> = _error
    val program: StateFlow<ProgramRequirements?> = _program
    val programs: StateFlow<List<CatalogProgram>> = _programs
    val otherTerms: StateFlow<List<Term>> = _otherTerms
    val olderNote: StateFlow<String?> = _olderNote

    init {
        viewModelScope.launch {
            try {
                val stored = withContext(Dispatchers.IO) { dao.get()?.let { UserJson.decode(it.json) } }
                if (stored != null) _user.value = stored
                val catalog = withContext(Dispatchers.IO) { loadBundled() }
                _index.value = CatalogIndex.build(catalog)
                _status.value = ""
                publishWidget()
            } catch (error: Throwable) {
                _status.value = "Couldn’t load the catalog. ${error::class.java.simpleName}: ${error.message}"
                _error.value = error.message ?: error.javaClass.simpleName
            }
        }
    }

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

    fun addToPlan(key: CourseKey) {
        val plan = ensurePlan()
        if (plan.contains(key)) return
        val course = PlanCourse(key, colorIndex = plan.nextColorIndex)
        update { data -> data.copy(plans = data.plans.map { if (it.id == plan.id) it.copy(courses = it.courses + course) else it }) }
    }

    fun toggleCrn(planId: String, crn: String) {
        update { data ->
            data.copy(plans = data.plans.map { plan ->
                if (plan.id != planId) plan
                else {
                    val selected = if (crn in plan.selectedCRNs) plan.selectedCRNs - crn else plan.selectedCRNs + crn
                    plan.copy(selectedCRNs = selected)
                }
            })
        }
        publishWidget()
    }

    fun generate(plan: SchedulePlan): ScheduleGenerationResult {
        val index = _index.value ?: return ScheduleGenerationResult()
        val inputs = plan.courses.map { course ->
            ScheduleCourseInput(
                course.key,
                true,
                index.sections(course.key),
                course.excludedCRNs,
                minCredits = index.course(course.key)?.minCredits?.toInt() ?: 0,
                maxCredits = index.course(course.key)?.maxCredits?.toInt() ?: 0,
            )
        }
        return ScheduleGenerator.generate(inputs, plan.filters, plan.preferences ?: SchedulePreferences())
    }

    fun updatePlan(id: String, block: (SchedulePlan) -> SchedulePlan) {
        update { data -> data.copy(plans = data.plans.map { if (it.id == id) block(it).copy(updatedAt = System.currentTimeMillis()) else it }) }
        publishWidget()
    }

    fun createSchedule(name: String) {
        val term = _index.value?.term?.code ?: "202710"
        val plan = SchedulePlan(
            name = name.ifBlank { "Schedule" },
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

    fun removeCourse(planId: String, key: CourseKey) {
        updatePlan(planId) { plan ->
            val crns = _index.value?.sections(key)?.map { it.crn }?.toSet().orEmpty()
            plan.copy(courses = plan.courses.filter { it.key != key }, selectedCRNs = plan.selectedCRNs.filter { it !in crns })
        }
    }

    fun setPreferences(planId: String, preferences: SchedulePreferences) = updatePlan(planId) { it.copy(preferences = preferences) }

    fun applyPreset(planId: String, preset: SchedulePreset) {
        updatePlan(planId) { it.copy(preferences = preset.toggled(it.preferences ?: SchedulePreferences())) }
    }

    fun setFilters(planId: String, filters: ScheduleFilters) = updatePlan(planId) { it.copy(filters = filters) }

    fun selectCombination(planId: String, crns: List<String>) = updatePlan(planId) { it.copy(selectedCRNs = crns) }

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

    fun deleteDegree(id: String) {
        update { data ->
            val rest = data.degreePlans.filter { it.id != id }
            val promoted = if (rest.isNotEmpty() && rest.none { it.isPrimary }) {
                rest.mapIndexed { index, plan -> if (index == 0) plan.copy(isPrimary = true) else plan }
            } else rest
            data.copy(degreePlans = promoted)
        }
    }

    fun addCompleted(planId: String, course: DegreePlanCourse) = updateDegree(planId) { it.copy(completed = it.completed + course) }

    fun addPlanned(planId: String, termId: String, course: DegreePlanCourse) {
        updateDegree(planId) { plan ->
            plan.copy(terms = plan.terms.map { term -> if (term.id == termId) term.copy(courses = term.courses + course) else term })
        }
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

    fun fetchPrograms(page: String = "https://catalog.northeastern.edu/undergraduate/") {
        viewModelScope.launch {
            _status.value = "Loading the academic catalog…"
            val html = runCatching { withContext(Dispatchers.IO) { java.net.URL(page).readText() } }
            html.onSuccess { text ->
                _programs.value = ProgramCatalogParser.programs(text)
                _status.value = ""
                if (_programs.value.isEmpty()) _error.value = "The catalog page didn’t list any programs."
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
        val sections = plan.selectedCRNs.mapNotNull { index?.section(it) }
        val text = ScheduleExporter.ics(index?.term?.name ?: plan.termCode, plan.name, sections, "2026-09-09", "2026-12-20")
        return writeShare("${plan.name}.ics", text.toByteArray(), "text/calendar")
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
