// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.termorganize.R
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.CourseTitleMatcher
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.NUPath
import app.termorganize.core.model.Requisite
import app.termorganize.core.model.TimeOfDay
import app.termorganize.logic.degree.DegreeAudit
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanAnalysis
import app.termorganize.logic.degree.DegreePlanAutofill
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.degree.RequirementSubstitution
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.degree.SubstitutionChange
import app.termorganize.logic.professors.CourseColors
import app.termorganize.logic.professors.ProfessorPage
import app.termorganize.logic.rooms.RoomAvailability
import app.termorganize.logic.schedule.ScheduleFilters
import app.termorganize.logic.schedule.ScheduleGenerationResult
import app.termorganize.logic.schedule.SchedulePreferences
import app.termorganize.logic.schedule.SchedulePreset
import app.termorganize.logic.search.InstructionFilter
import app.termorganize.logic.search.SearchFilters
import app.termorganize.logic.watchlist.WatchlistFormat
import app.termorganize.logic.watchlist.WatchlistStatus
import app.termorganize.logic.widget.WidgetScheduleData
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String) { Home("Home"), Search("Search"), Schedule("Schedule"), Degree("Degree"), More("More") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermOrganizeRoot(
    model: AppModel,
    startRoute: String?,
    onImportPdf: () -> Unit,
    onImportJson: () -> Unit,
) {
    val user by model.user.collectAsState()
    val index by model.index.collectAsState()
    val status by model.status.collectAsState()
    val error by model.error.collectAsState()
    var route by remember { mutableStateOf(startRoute ?: "home") }
    LaunchedEffect(startRoute) { if (!startRoute.isNullOrBlank()) route = startRoute }
    val dark = when (user.preferences.darkTheme) {
        "dark" -> true
        "light" -> false
        else -> null
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        snack.showSnackbar(message)
        model.clearError()
    }
    TermOrganizeTheme(dynamic = user.preferences.dynamicColor, dark = dark) {
        val tab = when {
            route.startsWith("search") -> Tab.Search
            route.startsWith("schedule") -> Tab.Schedule
            route.startsWith("degree") -> Tab.Degree
            route.startsWith("more") || route.startsWith("rooms") || route.startsWith("professors") || route.startsWith("watch") || route.startsWith("settings") -> Tab.More
            else -> Tab.Home
        }
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                Tab.entries.forEach { item ->
                    item(
                        selected = tab == item,
                        onClick = { route = item.name.lowercase() },
                        icon = {
                            Icon(
                                when (item) {
                                    Tab.Home -> Icons.Default.Home
                                    Tab.Search -> Icons.Default.Search
                                    Tab.Schedule -> Icons.Default.CalendarMonth
                                    Tab.Degree -> Icons.Default.School
                                    Tab.More -> Icons.Default.MoreHoriz
                                },
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            },
        ) {
            Scaffold(snackbarHost = { SnackbarHost(snack) }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when {
                        route.startsWith("course/") -> CourseRoute(route, model) { route = "search" }
                        route.startsWith("section/") -> SectionRoute(route, model) { route = "search" }
                        route.startsWith("professor/") -> ProfessorRoute(route.removePrefix("professor/"), model) { route = "more/professors" }
                        route.startsWith("room/") -> RoomRoute(route.removePrefix("room/"), model) { route = "more/rooms" }
                        tab == Tab.Home -> HomeScreen(model, status, index == null) { route = it }
                        tab == Tab.Search -> SearchScreen(model, queryOf(route)) { route = it }
                        tab == Tab.Schedule -> ScheduleScreen(model)
                        tab == Tab.Degree -> DegreeScreen(model, onImportPdf)
                        else -> MoreScreen(route, model, onImportJson) { route = it }
                    }
                }
            }
        }
    }
}

private fun queryOf(route: String): String {
    val q = route.substringAfter("q=", "")
    return if (route.startsWith("search")) java.net.URLDecoder.decode(q, "UTF-8") else ""
}

@Composable
private fun HomeScreen(model: AppModel, status: String, loading: Boolean, open: (String) -> Unit) {
    val index by model.index.collectAsState()
    val user by model.user.collectAsState()
    val plan = user.plans.firstOrNull { it.isPrimary && it.selectedCRNs.isNotEmpty() }
    val next = remember(plan, index) {
        val meetings = plan?.selectedCRNs.orEmpty().mapNotNull { index?.section(it) }.flatMap { section ->
            section.weeklyMeetings.flatMap { meeting ->
                meeting.days.indices.map { day ->
                    app.termorganize.logic.widget.WidgetMeeting(
                        "${section.crn}-$day", section.crn, section.courseKey.description, section.title,
                        meeting.locationLabel, section.classType, day, meeting.start ?: 0, meeting.end ?: 0, 0,
                        meeting.startDate, meeting.endDate,
                    )
                }
            }
        }
        WidgetScheduleData(0, index?.term?.code ?: "", index?.term?.name ?: "", plan?.name ?: "", meetings = meetings)
            .occurrences(ZonedDateTime.now(WidgetScheduleData.zone), 3)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Term Organize", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(index?.term?.name ?: "Fall 2026", style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status.ifBlank { "Loading the Fall 2026 catalog…" })
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Up next", style = MaterialTheme.typography.titleMedium)
                if (next.isEmpty()) Text("No class on the primary schedule. Build one and it shows up here and on the home-screen widget.")
                next.forEach { item ->
                    val whenLabel = item.start.format(DateTimeFormatter.ofPattern("EEE h:mm a"))
                    Text("${item.meeting.courseCode} · $whenLabel · ${item.meeting.location}")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(index?.courses?.size?.toString() ?: "—", "courses")
            Stat(index?.catalog?.sections?.size?.toString() ?: "—", "sections")
            Stat(user.plans.size.toString(), "plans")
        }
        Button(onClick = { open("search") }, modifier = Modifier.fillMaxWidth()) { Text("Search the catalog") }
        OutlinedButton(onClick = { open("schedule") }, modifier = Modifier.fillMaxWidth()) { Text("Open the schedule builder") }
        OutlinedButton(onClick = { open("degree") }, modifier = Modifier.fillMaxWidth()) { Text("Degree plan and audit") }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(Modifier.padding(end = 16.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(model: AppModel, initial: String, open: (String) -> Unit) {
    val index by model.index.collectAsState()
    val user by model.user.collectAsState()
    var text by remember(initial) { mutableStateOf(initial) }
    var filters by remember { mutableStateOf(SearchFilters()) }
    var showFilters by remember { mutableStateOf(false) }
    val response = remember(text, filters, index) { model.search(text, filters) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("search-field"),
            label = { Text("Course, professor, CRN, or shorthand") },
            singleLine = true,
            supportingText = { Text("Try orgo, fundies, cs3000, or a five-digit CRN. Typos are allowed.") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = showFilters, onClick = { showFilters = true }, label = { Text(if (filters.activeCount == 0) "Filters" else "Filters · ${filters.activeCount}") })
            if (text.isNotBlank()) TextButton(onClick = { model.rememberSearch(text) }) { Text("Save search") }
        }
        if (index == null) {
            CircularProgressIndicator(Modifier.padding(24.dp))
            return
        }
        if (response == null || (text.isBlank() && filters.isEmpty)) {
            Text("Recent searches", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            if (user.searchHistory.isEmpty()) Text("Nothing saved yet.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                user.searchHistory.forEach { item -> AssistChip(onClick = { text = item }, label = { Text(item) }) }
            }
            return
        }
        val crn = response.crnMatch
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (crn != null) {
                item {
                    Card(Modifier.fillMaxWidth().clickable { open("section/${crn.crn}") }) {
                        Text("CRN ${crn.crn} · ${crn.courseKey.description} ${crn.title}", Modifier.padding(12.dp))
                    }
                }
            }
            if (response.instructors.isNotEmpty()) {
                item { Text("Professors", style = MaterialTheme.typography.titleSmall) }
                items(response.instructors.take(5), key = { it.id }) { person ->
                    ListItem(
                        headlineContent = { Text(person.displayName) },
                        supportingContent = { Text("${person.courseKeys.size} courses") },
                        modifier = Modifier.clickable { open("professor/${java.net.URLEncoder.encode(person.name, "UTF-8")}") },
                    )
                }
            }
            item { Text("${response.totalCount} courses", style = MaterialTheme.typography.titleSmall) }
            if (response.results.isEmpty()) {
                item { Text("No courses match. Loosen a filter or try a shorter query.") }
            }
            items(response.results.take(80), key = { it.id.description }) { result ->
                ListItem(
                    headlineContent = { Text("${result.course.code}  ${result.course.title}") },
                    supportingContent = {
                        Text("${result.course.creditsLabel} · ${result.stats.availabilityLabel}" + (result.matchNote?.let { " · $it" } ?: ""))
                    },
                    modifier = Modifier.clickable { open("course/${result.course.subject}/${result.course.number}") },
                )
            }
        }
    }
    if (showFilters && index != null) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            FilterSheet(index!!, filters, { filters = it }, { showFilters = false })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    index: app.termorganize.logic.search.CatalogIndex,
    filters: SearchFilters,
    onChange: (SearchFilters) -> Unit,
    close: () -> Unit,
) {
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Filters", style = MaterialTheme.typography.titleLarge)
        Text("Subjects")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            index.activeSubjects.take(24).forEach { subject ->
                FilterChip(
                    selected = subject.code in filters.subjects,
                    onClick = {
                        val next = filters.subjects.toMutableSet()
                        if (!next.add(subject.code)) next.remove(subject.code)
                        onChange(filters.copy(subjects = next))
                    },
                    label = { Text(subject.code) },
                )
            }
        }
        Text("NUpath")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NUPath.entries.forEach { path ->
                FilterChip(
                    selected = path in filters.nupaths,
                    onClick = {
                        val next = filters.nupaths.toMutableSet()
                        if (!next.add(path)) next.remove(path)
                        onChange(filters.copy(nupaths = next))
                    },
                    label = { Text(path.short) },
                )
            }
        }
        Text("Level")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..9).forEach { level ->
                FilterChip(
                    selected = level in filters.levels,
                    onClick = {
                        val next = filters.levels.toMutableSet()
                        if (!next.add(level)) next.remove(level)
                        onChange(filters.copy(levels = next))
                    },
                    label = { Text("${level}000") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Open seats only", Modifier.weight(1f))
            Switch(filters.openSeatsOnly, { onChange(filters.copy(openSeatsOnly = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Honors only", Modifier.weight(1f))
            Switch(filters.honorsOnly, { onChange(filters.copy(honorsOnly = it)) })
        }
        Text("Credits")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(null to "Any", 1.0 to "1+", 3.0 to "3+", 4.0 to "4").forEach { (value, label) ->
                FilterChip(
                    selected = filters.minCredits == value,
                    onClick = { onChange(filters.copy(minCredits = value)) },
                    label = { Text(label) },
                )
            }
        }
        if (index.campusNames.isNotEmpty()) {
            Text("Campus")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                index.campusNames.take(8).forEach { campus ->
                    FilterChip(
                        selected = campus in filters.campuses,
                        onClick = {
                            val next = filters.campuses.toMutableSet()
                            if (!next.add(campus)) next.remove(campus)
                            onChange(filters.copy(campuses = next))
                        },
                        label = { Text(campus) },
                    )
                }
            }
        }
        if (index.classTypes.isNotEmpty()) {
            Text("Section type")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                index.classTypes.take(6).forEach { type ->
                    FilterChip(
                        selected = type in filters.classTypes,
                        onClick = {
                            val next = filters.classTypes.toMutableSet()
                            if (!next.add(type)) next.remove(type)
                            onChange(filters.copy(classTypes = next))
                        },
                        label = { Text(type) },
                    )
                }
            }
        }
        Text("Instruction")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InstructionFilter.entries.forEach { mode ->
                FilterChip(selected = filters.instruction == mode, onClick = { onChange(filters.copy(instruction = mode)) }, label = { Text(mode.label) })
            }
        }
        Text("Starts after")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(null to "Any", 8 * 60 to "8:00", 9 * 60 to "9:00", 10 * 60 to "10:00", 12 * 60 to "12:00").forEach { (value, label) ->
                FilterChip(selected = filters.startsAfter == value, onClick = { onChange(filters.copy(startsAfter = value)) }, label = { Text(label) })
            }
        }
        Text("Ends by")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(null to "Any", 12 * 60 to "12:00", 15 * 60 to "3:00", 17 * 60 to "5:00", 19 * 60 to "7:00").forEach { (value, label) ->
                FilterChip(selected = filters.endsBefore == value, onClick = { onChange(filters.copy(endsBefore = value)) }, label = { Text(label) })
            }
        }
        Text("Days the section may meet")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DaySet.SHORT.forEachIndexed { day, label ->
                val allowed = filters.allowedDays ?: DaySet.ALL
                FilterChip(
                    selected = filters.allowedDays == null || allowed.contains(day),
                    onClick = {
                        val base = (filters.allowedDays ?: DaySet.ALL).rawValue
                        val bit = 1 shl day
                        val next = if (base and bit != 0) base and bit.inv() else base or bit
                        onChange(filters.copy(allowedDays = if (next and 0x7F == 0x7F) null else DaySet(next and 0x7F)))
                    },
                    label = { Text(label) },
                )
            }
        }
        TextButton(onClick = { onChange(SearchFilters()); close() }) { Text("Clear filters") }
        Button(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun CourseRoute(route: String, model: AppModel, back: () -> Unit) {
    val parts = route.removePrefix("course/").split("/")
    val key = if (parts.size >= 2) CourseKey(parts[0], parts[1]) else return
    val course = model.course(key)
    if (course == null) {
        Column(Modifier.padding(20.dp)) {
            Text("That course isn’t in the bundled Fall 2026 catalog.")
            TextButton(onClick = back) { Text("Back") }
        }
        return
    }
    CourseScreen(course, model, back)
}

@Composable
private fun SectionRoute(route: String, model: AppModel, back: () -> Unit) {
    val crn = route.removePrefix("section/").substringBefore("/")
    val section = model.index.collectAsState().value?.section(crn)
    if (section == null) {
        Column(Modifier.padding(20.dp)) {
            Text("CRN $crn isn’t in the bundled catalog.")
            TextButton(onClick = back) { Text("Back") }
        }
        return
    }
    val course = model.course(section.courseKey)
    if (course != null) CourseScreen(course, model, back, highlight = crn) else Text(section.title)
}

@Composable
private fun CourseScreen(course: Course, model: AppModel, back: () -> Unit, highlight: String? = null) {
    val index by model.index.collectAsState()
    val sections = model.sections(course.key)
    var live by remember(course.key) { mutableStateOf<List<CourseSection>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val shown = live ?: sections
    val older by model.olderNote.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(course.code, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        Text(course.title, style = MaterialTheme.typography.titleMedium)
        Text("${course.creditsLabel}${course.college?.let { " · $it" } ?: ""}")
        if (course.nupaths.isNotEmpty()) Text(course.nupaths.joinToString(" · ") { "${it.short} ${it.title}" }, style = MaterialTheme.typography.bodySmall)
        if (course.description.isNotBlank()) Text(course.description)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.addToPlan(course.key) }) { Text("Add to schedule") }
            OutlinedButton(onClick = {
                refreshing = true
                model.refreshCourse(course.key) {
                    live = it
                    refreshing = false
                }
            }) { Text(if (refreshing) "Refreshing…" else "Refresh seats") }
            OutlinedButton(onClick = { model.checkOlderTerms(course.key) }) { Text("Older terms") }
        }
        if (older != null) Text(older!!, style = MaterialTheme.typography.bodySmall)
        Text("Prerequisites", style = MaterialTheme.typography.titleMedium)
        RequisiteTree(course.prereqs, index)
        Text("Corequisites", style = MaterialTheme.typography.titleMedium)
        RequisiteTree(course.coreqs, index)
        Text("Prerequisite for", style = MaterialTheme.typography.titleMedium)
        if (course.postreqs.isEmpty()) Text("Nothing in this catalog lists it as a prerequisite.")
        course.postreqs.forEach { key ->
            val title = index?.course(key)?.title ?: ""
            Text("${key.description}  $title")
        }
        Text("Sections", style = MaterialTheme.typography.titleMedium)
        if (shown.isEmpty()) Text("No sections this term.")
        shown.forEach { section ->
            val marked = section.crn == highlight
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${section.classType} ${section.sectionNumber} · CRN ${section.crn}" + if (marked) " · opened from link" else "", fontWeight = FontWeight.SemiBold)
                    Text(section.scheduleSummary)
                    Text("${section.seatsLabel} · waitlist ${section.waitlistRemaining} of ${section.waitlistCapacity}")
                    Text(section.instructorLabel)
                    if (section.honors) Text("Honors")
                    TextButton(onClick = { model.watch(section, course.title) }) { Text("Alert me if a seat opens") }
                }
            }
        }
    }
}

@Composable
private fun RequisiteTree(requisite: Requisite?, index: app.termorganize.logic.search.CatalogIndex?) {
    if (requisite == null) {
        Text("None listed.")
        return
    }
    fun line(node: Requisite, depth: Int): List<String> = when (node) {
        is Requisite.CourseReq -> listOf("  ".repeat(depth) + node.key.description + (index?.course(node.key)?.title?.let { " — $it" } ?: ""))
        is Requisite.TestReq -> listOf("  ".repeat(depth) + (node.score?.let { "${node.name} ($it)" } ?: node.name))
        is Requisite.And -> listOf("  ".repeat(depth) + "All of") + node.items.flatMap { line(it, depth + 1) }
        is Requisite.Or -> listOf("  ".repeat(depth) + "One of") + node.items.flatMap { line(it, depth + 1) }
    }
    line(requisite.simplified() ?: requisite, 0).forEach { Text(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleScreen(model: AppModel) {
    val user by model.user.collectAsState()
    val index by model.index.collectAsState()
    val term = index?.term?.code
    val plans = user.plans.filter { term == null || it.termCode == term }
    var selectedId by remember(plans) { mutableStateOf(plans.firstOrNull { it.isPrimary }?.id ?: plans.firstOrNull()?.id) }
    val plan = plans.firstOrNull { it.id == selectedId }
    var generated by remember(selectedId) { mutableStateOf<ScheduleGenerationResult?>(null) }
    var sentence by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Schedule", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(index?.term?.name ?: "Fall 2026")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, label = { Text("New plan") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { model.createSchedule(name); name = "" }, modifier = Modifier.padding(start = 8.dp)) { Text("Add") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            plans.forEach { item ->
                FilterChip(selected = item.id == selectedId, onClick = { selectedId = item.id; generated = null }, label = { Text(item.name + if (item.isPrimary) " · primary" else "") })
            }
        }
        if (plan == null) {
            Text("Add the courses you might take, then generate combinations that don’t overlap.")
            return
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!plan.isPrimary) TextButton(onClick = { model.setPrimarySchedule(plan.id) }) { Text("Make primary") }
            TextButton(onClick = { model.deleteSchedule(plan.id); selectedId = null }) { Text("Delete plan") }
        }
        val selected = plan.selectedCRNs.mapNotNull { index?.section(it) }
        if (model.conflicts(selected)) Text("Selected sections overlap.", color = MaterialTheme.colorScheme.error)
        WeekCalendar(selected, user.preferences.showWeekends, user.preferences.calendarStartHour, user.preferences.calendarEndHour)
        Text("Courses", style = MaterialTheme.typography.titleMedium)
        if (plan.courses.isEmpty()) Text("Search for a course and tap Add to schedule.")
        plan.courses.forEach { course ->
            val title = index?.course(course.key)?.title ?: ""
            ListItem(
                headlineContent = { Text("${course.key.description}  $title") },
                trailingContent = { TextButton(onClick = { model.removeCourse(plan.id, course.key) }) { Text("Remove") } },
            )
        }
        Text("Autofill", style = MaterialTheme.typography.titleMedium)
        val suggestions = model.suggestions(plan)
        if (suggestions.isEmpty()) Text("Import a degree plan and the matching semester can fill this schedule.")
        suggestions.forEach { suggestion ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(suggestion.title)
                    Text(suggestion.detail, style = MaterialTheme.typography.bodySmall)
                    Text(suggestion.courses.joinToString { it.description })
                    if (suggestion.unavailable.isNotEmpty()) Text("Not offered: ${suggestion.unavailable.joinToString { it.description }}", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { model.applyAutofill(plan.id, suggestion) }) { Text("Add these courses") }
                }
            }
        }
        Text("Preferences", style = MaterialTheme.typography.titleMedium)
        val prefs = plan.preferences ?: SchedulePreferences()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SchedulePreset.entries.forEach { preset ->
                FilterChip(selected = preset.isApplied(prefs), onClick = { model.applyPreset(plan.id, preset) }, label = { Text(preset.title) })
            }
        }
        Text(if (prefs.isDefault) "No extra wishes yet." else "${prefs.activeCount} preference rules active.")
        OutlinedTextField(
            sentence,
            { sentence = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Describe the week") },
            supportingText = { Text("Example: morning person, no class after 4pm, Fridays off, in person.") },
        )
        Button(onClick = { model.applyPreferences(plan.id, sentence) }) { Text("Apply description") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hide full sections", Modifier.weight(1f))
            Switch(plan.filters.hideFullSections, { model.setFilters(plan.id, plan.filters.copy(hideFullSections = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include online", Modifier.weight(1f))
            Switch(plan.filters.includeOnline, { model.setFilters(plan.id, plan.filters.copy(includeOnline = it)) })
        }
        Button(onClick = { generated = model.generate(plan) }, modifier = Modifier.fillMaxWidth()) { Text("Generate schedules") }
        val result = generated
        if (result != null) {
            Text("${result.schedules.size} of ${result.totalFound} combinations", style = MaterialTheme.typography.titleMedium)
            if (result.schedules.isEmpty()) {
                Text("Nothing fit.")
                result.diagnostics.suggestions.forEach { Text("• $it") }
                result.diagnostics.courses.filter { it.isBlocked }.forEach { report ->
                    Text("${report.key.description} has no remaining sections. ${report.rejections.entries.joinToString { "${it.value} ${it.key}" }}")
                }
                if (result.diagnostics.conflictingPairs.isNotEmpty()) {
                    Text("Conflicts: ${result.diagnostics.conflictingPairs.joinToString { "${it.first.description} & ${it.second.description}" }}")
                }
            }
            result.schedules.take(12).forEachIndexed { indexNumber, schedule ->
                Card(Modifier.fillMaxWidth().clickable { model.selectCombination(plan.id, schedule.crns) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("#${indexNumber + 1}  score ${"%.1f".format(schedule.score)}  · ${schedule.classDays} days", fontWeight = FontWeight.SemiBold)
                        schedule.sections.forEach { Text("${it.courseKey.description} ${it.scheduleSummary} · ${it.crn}") }
                        schedule.reasons.forEach { reason ->
                            Text((if (reason.isCaveat) "Caveat: " else "") + reason.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Text("Export", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { share(context, model.exportIcs(plan), "text/calendar") }) { Text(".ics") }
            OutlinedButton(onClick = {
                val intent = model.calendarIntent(plan)
                if (intent != null) context.startActivity(intent)
            }) { Text("Android Calendar") }
            OutlinedButton(onClick = { share(context, model.shareImage(plan), "image/png") }) { Text("Image") }
            OutlinedButton(onClick = { model.copyCrns(plan) }) { Text("Copy CRNs") }
        }
    }
}

private fun share(context: android.content.Context, uri: android.net.Uri, type: String) {
    val intent = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share"))
}

@Composable
private fun WeekCalendar(sections: List<CourseSection>, weekends: Boolean, startHour: Int, endHour: Int) {
    val days = if (weekends) (0..6).toList() else (1..5).toList()
    val start = startHour * 60
    val span = ((endHour - startHour).coerceAtLeast(1)) * 60f
    val height = 420.dp
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(height + 28.dp)) {
        Column(Modifier.width(36.dp).padding(top = 28.dp)) {
            (startHour until endHour).forEach { hour ->
                Text(TimeOfDay.hourAxisLabel(hour), style = MaterialTheme.typography.labelSmall, modifier = Modifier.height(height / (endHour - startHour)))
            }
        }
        days.forEach { day ->
            Column(Modifier.width(92.dp)) {
                Text(DaySet.SHORT[day], style = MaterialTheme.typography.labelMedium, modifier = Modifier.height(28.dp))
                Box(Modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))) {
                    Canvas(Modifier.fillMaxSize()) {
                        val grid = (endHour - startHour)
                        for (i in 1 until grid) {
                            val y = size.height * i / grid
                            drawLine(Color(0x22000000), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y))
                        }
                    }
                    sections.forEach { section ->
                        section.weeklyMeetings.filter { it.days.contains(day) }.forEach { meeting ->
                            val top = (((meeting.start ?: start) - start) / span).coerceIn(0f, 1f)
                            val bottom = (((meeting.end ?: start) - start) / span).coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp)
                                    .fillMaxSize()
                                    .then(Modifier),
                            )
                            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                                val blockTop = maxHeight * top
                                val blockHeight = (maxHeight * (bottom - top)).coerceAtLeast(18.dp)
                                Text(
                                    section.courseKey.subject + " " + section.courseKey.number,
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .padding(top = blockTop, start = 2.dp, end = 2.dp)
                                        .fillMaxWidth()
                                        .height(blockHeight)
                                        .background(Color(CourseColors.forCode(section.courseKey.description)), RoundedCornerShape(4.dp))
                                        .padding(2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DegreeScreen(model: AppModel, onImportPdf: () -> Unit) {
    val user by model.user.collectAsState()
    val index by model.index.collectAsState()
    val program by model.program.collectAsState()
    val programs by model.programs.collectAsState()
    var selected by remember(user.degreePlans) { mutableStateOf(user.degreePlans.firstOrNull { it.isPrimary }?.id ?: user.degreePlans.firstOrNull()?.id) }
    val plan = user.degreePlans.firstOrNull { it.id == selected }
    var code by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var credits by remember { mutableStateOf("4") }
    var programQuery by remember { mutableStateOf("") }
    var equivA by remember { mutableStateOf("") }
    var equivB by remember { mutableStateOf("") }
    var hints by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Degree plan", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImportPdf) { Text("Import PDF") }
            OutlinedButton(onClick = { model.addDegreePlan(false) }) { Text("New") }
            OutlinedButton(onClick = { model.addDegreePlan(true) }) { Text("From schedule") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            user.degreePlans.forEach { item ->
                FilterChip(selected = item.id == selected, onClick = { selected = item.id }, label = { Text(item.name.ifBlank { "Untitled" } + if (item.isPrimary) " · primary" else "") })
            }
        }
        if (plan == null) {
            Text("Open a Northeastern degree-plan PDF, or start an empty plan and add courses.")
            return
        }
        Text("${plan.completedCredits} completed · ${plan.plannedCredits} planned · ${plan.completedCredits + plan.plannedCredits} total hours")
        val duplicates = DegreePlanAnalysis.duplicateKeys(plan)
        if (duplicates.isNotEmpty()) Text("Repeated: ${duplicates.joinToString { it.description }}", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!plan.isPrimary) TextButton(onClick = { model.setPrimaryDegree(plan.id) }) { Text("Make primary") }
            TextButton(onClick = { share(context, model.exportPlanPdf(plan), "application/pdf") }) { Text("Export PDF") }
            TextButton(onClick = { model.deleteDegree(plan.id) }) { Text("Delete") }
        }
        Text("Completed", style = MaterialTheme.typography.titleMedium)
        plan.completed.forEach { course -> CourseRow(plan, course, model, index, completed = true) }
        Text("Planned", style = MaterialTheme.typography.titleMedium)
        plan.terms.forEach { term ->
            Text("${term.shortName} (${term.credits} hours)", fontWeight = FontWeight.SemiBold)
            term.courses.forEach { course -> CourseRow(plan, course, model, index, termIndex = plan.terms.indexOf(term)) }
        }
        Text("Add a course", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(code, { code = it }, label = { Text("Code, such as CS 3000") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(credits, { credits = it }, label = { Text("Hours") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val parsedKey = CourseKey.parse(code)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val course = DegreePlanCourse.of(parsedKey, title.ifBlank { parsedKey?.description ?: code }, credits.toDoubleOrNull() ?: 0.0, code)
                model.addCompleted(plan.id, course)
            }) { Text("Completed") }
            val next = DegreePlanAutofill.nextSemesters(plan, 1).firstOrNull()
            if (next != null) {
                OutlinedButton(onClick = {
                    model.addTerm(plan.id, next.season, next.year)
                    val termId = model.user.value.degreePlans.first { it.id == plan.id }.terms.first { it.season == next.season && it.year == next.year }.id
                    model.addPlanned(plan.id, termId, DegreePlanCourse.of(parsedKey, title.ifBlank { parsedKey?.description ?: code }, credits.toDoubleOrNull() ?: 0.0, code))
                }) { Text("Next semester") }
            }
        }
        Text("Semesters", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DegreePlanAutofill.nextSemesters(plan).forEach { term ->
                AssistChip(onClick = { model.addTerm(plan.id, term.season, term.year) }, label = { Text(term.shortName) })
            }
            PlanSeason.entries.forEach { season ->
                AssistChip(onClick = { model.addTerm(plan.id, season, 2027) }, label = { Text("${season.shortName} 2027") })
            }
        }
        Text("Equivalences and renumbered courses", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(equivA, { equivA = it }, label = { Text("Taken as") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(equivB, { equivB = it }, label = { Text("Counts as") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val a = CourseKey.parse(equivA)
            val b = CourseKey.parse(equivB)
            if (a != null && b != null) model.addEquivalence(plan.id, a, b, "")
        }) { Text("Count one course as another") }
        plan.courseEquivalences.forEach { Text("${it.course.description} → ${it.countsAs.description}") }
        OutlinedButton(onClick = {
            val catalog = index ?: return@OutlinedButton
            hints = plan.completed.mapNotNull { course ->
                val key = course.key
                if (key != null && catalog.course(key) != null) return@mapNotNull null
                val matches = catalog.courses.mapNotNull { other ->
                    val score = CourseTitleMatcher.similarity(course.title, other.title) ?: return@mapNotNull null
                    other.code to score
                }.sortedByDescending { it.second }.take(3)
                if (matches.isEmpty()) null else "${course.label.ifBlank { course.title }} looks like ${matches.joinToString { it.first }}"
            }
        }) { Text("Find similar titles") }
        hints.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Requirements audit", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(programQuery, { programQuery = it }, label = { Text("Program name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.fetchPrograms() }) { Text("Load catalog programs") }
        }
        programs.filter { programQuery.isBlank() || it.title.contains(programQuery, true) }.take(8).forEach { item ->
            ListItem(
                headlineContent = { Text(item.title) },
                supportingContent = { Text(item.path) },
                modifier = Modifier.clickable { model.fetchRequirements(item) },
            )
        }
        val requirements = program
        if (requirements != null) {
            Text(requirements.program.title, fontWeight = FontWeight.SemiBold)
            if (requirements.concentrations.isNotEmpty() && plan.concentrationID == null) {
                Text("Choose a concentration before the audit can finish.")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                requirements.concentrations.forEach { section ->
                    FilterChip(
                        selected = plan.concentrationID == section.id,
                        onClick = { model.updateDegree(plan.id) { it.copy(concentrationID = section.id, catalogProgramPath = requirements.program.path, catalogProgramTitle = requirements.program.title) } },
                        label = { Text(section.concentrationName.ifBlank { section.title }) },
                    )
                }
            }
            val attributes = app.termorganize.logic.degree.DegreeCourseAttributes(
                nupaths = (plan.completed + plan.terms.flatMap { it.courses }).mapNotNull { course ->
                    val key = course.key ?: return@mapNotNull null
                    val paths = index?.course(key)?.nupaths?.toSet() ?: return@mapNotNull null
                    key to paths
                }.toMap(),
            )
            val audit = DegreeAudit.run(plan, requirements, attributes = attributes)
            Text("${audit.satisfiedCount} of ${audit.totalCount} requirements satisfied · ${audit.completedHours} completed hours")
            if (audit.needsConcentration) Text("A concentration is still required.")
            audit.sections.forEach { section ->
                Text(section.section.title, fontWeight = FontWeight.SemiBold)
                section.rules.forEach { rule ->
                    Text("${rule.status.name.lowercase()} · ${rule.rule.displayLabel}")
                    if (rule.used.isNotEmpty()) Text(rule.used.joinToString { it.key.description }, style = MaterialTheme.typography.bodySmall)
                }
            }
            audit.noteChecks.forEach { note -> Text("${note.status.name.lowercase()} · ${note.detail}") }
            if (audit.unused.isNotEmpty()) Text("Unused: ${audit.unused.joinToString { it.key.description }}")
            Text("Substitution or waiver", style = MaterialTheme.typography.titleSmall)
            requirements.allRules.take(12).forEach { rule ->
                TextButton(onClick = {
                    model.addSubstitution(plan.id, RequirementSubstitution(ruleID = rule.id, ruleTitle = rule.displayLabel, change = SubstitutionChange.Waive, note = "Entered on device"))
                }) { Text("Waive ${rule.displayLabel}") }
            }
        }
    }
}

@Composable
private fun CourseRow(
    plan: DegreePlan,
    course: DegreePlanCourse,
    model: AppModel,
    index: app.termorganize.logic.search.CatalogIndex?,
    completed: Boolean = false,
    termIndex: Int = 0,
) {
    val catalog = course.key?.let { index?.course(it) }
    val before = if (completed) plan.completedKeys else DegreePlanAnalysis.coursesBefore(termIndex, plan)
    val status = DegreePlanAnalysis.status(catalog?.prereqs, before, plan.declaredEquivalences)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${course.label.ifBlank { course.key?.description ?: "Course" }}  ${course.title}  (${course.credits})", Modifier.weight(1f))
            TextButton(onClick = { model.removeDegreeCourse(plan.id, course.id) }) { Text("Remove") }
        }
        if (status is DegreePlanAnalysis.PrerequisiteStatus.Missing) {
            Text("Needs ${status.text}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}

@Composable
private fun MoreScreen(route: String, model: AppModel, onImportJson: () -> Unit, open: (String) -> Unit) {
    when {
        route.startsWith("more/rooms") || route == "rooms" -> RoomsScreen(model) { open("room/$it") }
        route.startsWith("more/professors") || route == "professors" -> ProfessorsScreen(model) { open("professor/${java.net.URLEncoder.encode(it, "UTF-8")}") }
        route.startsWith("more/watch") || route == "watch" -> WatchScreen(model)
        route.startsWith("more/settings") || route == "settings" -> SettingsScreen(model, onImportJson)
        else -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("More", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { open("more/rooms") }, modifier = Modifier.fillMaxWidth()) { Text("Free rooms") }
            Button(onClick = { open("more/professors") }, modifier = Modifier.fillMaxWidth()) { Text("Professors") }
            Button(onClick = { open("more/watch") }, modifier = Modifier.fillMaxWidth()) { Text("Seat alerts") }
            Button(onClick = { open("more/settings") }, modifier = Modifier.fillMaxWidth()) { Text("Settings and legal") }
        }
    }
}

@Composable
private fun RoomsScreen(model: AppModel, open: (String) -> Unit) {
    val index by model.index.collectAsState()
    var minutes by remember { mutableStateOf(30) }
    var query by remember { mutableStateOf("") }
    val moment = remember { RoomAvailability.Moment.now() }
    val rooms = index?.buildings?.flatMap { it.rooms }.orEmpty().filter { query.isBlank() || it.label.contains(query, true) || it.buildingName.contains(query, true) }
    val free = remember(rooms, minutes) { RoomAvailability.freeRooms(rooms, moment, minutes) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Free rooms", style = MaterialTheme.typography.headlineSmall)
        Text("Right now: ${moment.label}. Short half-term bookings are ignored when the day isn’t known.")
        OutlinedTextField(query, { query = it }, label = { Text("Building or room") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60, 90).forEach { option ->
                FilterChip(selected = minutes == option, onClick = { minutes = option }, label = { Text("$option min") })
            }
        }
        LazyColumn {
            items(free.take(80), key = { it.first.id }) { (room, status) ->
                ListItem(
                    headlineContent = { Text(room.label) },
                    supportingContent = { Text(room.buildingName + (status.until?.let { " · until ${TimeOfDay.label(it)}" } ?: " · open the rest of the day")) },
                    modifier = Modifier.clickable { open(room.id) },
                )
            }
        }
    }
}

@Composable
private fun RoomRoute(id: String, model: AppModel, back: () -> Unit) {
    val room = model.index.collectAsState().value?.buildings?.flatMap { it.rooms }?.firstOrNull { it.id == id }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = back) { Text("Back") }
        if (room == null) {
            Text("Room not found.")
            return
        }
        Text(room.label, style = MaterialTheme.typography.headlineSmall)
        Text(room.buildingName)
        room.bookings.sortedWith(compareBy<app.termorganize.logic.search.RoomSchedule.Booking> { it.days.rawValue }.thenBy { it.start }).forEach { booking ->
            Text("${booking.days.compactLabel} ${TimeOfDay.rangeLabel(booking.start, booking.end)} · ${booking.course.description}")
        }
    }
}

@Composable
private fun ProfessorsScreen(model: AppModel, open: (String) -> Unit) {
    val index by model.index.collectAsState()
    var query by remember { mutableStateOf("") }
    val people = index?.instructors.orEmpty().filter { query.isBlank() || it.displayName.contains(query, true) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Professors", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(query, { query = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        LazyColumn {
            items(people.take(60), key = { it.id }) { person ->
                ListItem(
                    headlineContent = { Text(person.displayName) },
                    supportingContent = { Text(person.courseKeys.joinToString { it.description }) },
                    modifier = Modifier.clickable { open(person.name) },
                )
            }
        }
    }
}

@Composable
private fun ProfessorRoute(encoded: String, model: AppModel, back: () -> Unit) {
    val name = java.net.URLDecoder.decode(encoded, "UTF-8")
    val index = model.index.collectAsState().value
    val person = index?.instructor(name)
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = back) { Text("Back") }
        if (index == null || person == null) {
            Text("Professor not found.")
            return
        }
        Text(person.displayName, style = MaterialTheme.typography.headlineSmall)
        val sections = ProfessorPage.sections(person, index)
        Text(ProfessorPage.summaryText(sections))
        person.email?.let { Text(it) }
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ProfessorPage.rateMyProfessorsUrl(person.displayName))))
        }) { Text("Rate My Professors") }
        val colors = ProfessorPage.courseColors(person.courseKeys)
        Text("Teaching calendar", style = MaterialTheme.typography.titleMedium)
        WeekCalendar(sections, false, 8, 21)
        sections.forEach { section ->
            Text("${section.courseKey.description} ${section.sectionNumber} · ${section.scheduleSummary}", color = Color(colors[section.courseKey] ?: CourseColors.at(0)))
        }
    }
}

@Composable
private fun WatchScreen(model: AppModel) {
    val user by model.user.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Seat alerts", style = MaterialTheme.typography.headlineSmall)
        Text("WorkManager refreshes watched CRNs about every 15 minutes and notifies you when a seat or waitlist spot opens.")
        if (user.watchlist.isEmpty()) Text("Open a section and tap Alert me if a seat opens.")
        user.watchlist.forEach { item ->
            val status = WatchlistStatus.of(item, false)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${item.courseKey.description} · CRN ${item.crn}", fontWeight = FontWeight.SemiBold)
                    Text(status.detail(item))
                    Text(item.sectionLabel, style = MaterialTheme.typography.bodySmall)
                    item.lastCheckedAt?.let { Text("Checked ${WatchlistFormat.ago(it, System.currentTimeMillis())}", style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { model.removeWatch(item.id) }) { Text("Stop watching") }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(model: AppModel, onImportJson: () -> Unit) {
    val user by model.user.collectAsState()
    val prefs = user.preferences
    val context = LocalContext.current
    var license by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        license = runCatching { context.assets.open("legal/LICENSE.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dynamic color", Modifier.weight(1f)); Switch(prefs.dynamicColor, { model.setAppPreferences { p -> p.copy(dynamicColor = it) } })
        }
        Text("Theme")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                FilterChip(selected = prefs.darkTheme == value, onClick = { model.setAppPreferences { it.copy(darkTheme = value) } }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show weekends", Modifier.weight(1f)); Switch(prefs.showWeekends, { on -> model.setAppPreferences { it.copy(showWeekends = on) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Background seat checks", Modifier.weight(1f)); Switch(prefs.backgroundRefreshEnabled, { on -> model.setAppPreferences { it.copy(backgroundRefreshEnabled = on) } })
        }
        Text("iCloud sync from the Apple apps is not available on Android. Export a JSON backup and import it on another device.")
        Button(onClick = { share(context, model.exportUser(), "application/json") }, modifier = Modifier.fillMaxWidth()) { Text("Export data") }
        OutlinedButton(onClick = onImportJson, modifier = Modifier.fillMaxWidth()) { Text("Import data") }
        Text("Legal", style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.disclaimer))
        Text("Term Organize is free software under the GNU Affero General Public License, version 3 only. Portions of the Banner client, catalog builder, requisites, schedule generator, and watchlist rules are derived from SearchNEU. Course data is published by Northeastern University and is not covered by that license. This app is unofficial and is not affiliated with Northeastern University or Sandbox.")
        if (license.isNotBlank()) {
            Text("GNU AGPL-3.0-only", fontWeight = FontWeight.SemiBold)
            Text(license.take(1800) + if (license.length > 1800) "…" else "", style = MaterialTheme.typography.bodySmall)
        }
        Text("Version 1.0", style = MaterialTheme.typography.bodySmall)
    }
}
