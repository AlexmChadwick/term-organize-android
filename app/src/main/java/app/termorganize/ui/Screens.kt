// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.termorganize.logic.search.CatalogIndex
import app.termorganize.R
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.EquivalenceSuggester
import app.termorganize.core.model.PrerequisiteListing
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.NUPath
import app.termorganize.core.model.Requisite
import app.termorganize.core.model.TimeOfDay
import app.termorganize.logic.degree.CourseNumberLimit
import app.termorganize.logic.degree.DegreeAudit
import app.termorganize.logic.degree.DegreePetitionQuestions
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
import app.termorganize.logic.schedule.ResolvedPlan
import app.termorganize.logic.schedule.ScheduleFilters
import app.termorganize.logic.schedule.ScheduleGenerationResult
import app.termorganize.logic.schedule.ScheduleNotice
import app.termorganize.logic.schedule.ScheduleNoResultsText
import app.termorganize.logic.schedule.ScheduleNotices
import app.termorganize.logic.schedule.SchedulePreferences
import app.termorganize.logic.schedule.SchedulePreset
import app.termorganize.logic.search.CourseAppSearch
import app.termorganize.logic.search.InstructionFilter
import app.termorganize.logic.search.SearchFilters
import app.termorganize.logic.watchlist.WatchlistFormat
import app.termorganize.logic.watchlist.WatchlistStatus
import app.termorganize.logic.widget.WidgetScheduleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String) { Home("Home"), Search("Search"), Schedule("Schedule"), Degree("Degree"), More("More") }

private val PageMaxWidth = 760.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, tag: String? = null, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = if (tag == null) Modifier else Modifier.testTag(tag),
        )
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BackTitle(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back, modifier = Modifier.offset(x = (-8).dp).testTag("back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun countText(count: Int, singular: String, plural: String): String =
    if (count == 1) "1 $singular" else "$count $plural"

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
            route.startsWith("search") || route.startsWith("course/") || route.startsWith("section/") -> Tab.Search
            route.startsWith("schedule") -> Tab.Schedule
            route.startsWith("degree") -> Tab.Degree
            route.startsWith("more") || route.startsWith("room") || route.startsWith("professor") || route.startsWith("watch") || route.startsWith("settings") -> Tab.More
            else -> Tab.Home
        }
        NavigationSuiteScaffold(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
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
            Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = PageMaxWidth).fillMaxHeight().fillMaxWidth()) {
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
}

private fun queryOf(route: String): String {
    val q = route.substringAfter("q=", "")
    return if (route.startsWith("search")) java.net.URLDecoder.decode(q, "UTF-8") else ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(model: AppModel, status: String, loading: Boolean, open: (String) -> Unit) {
    val index by model.index.collectAsState()
    val user by model.user.collectAsState()
    val terms by model.availableTerms.collectAsState()
    val sync by model.syncLabel.collectAsState()
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Term Organize", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(index?.term?.name ?: "Catalog", style = MaterialTheme.typography.titleLarge)
        if (terms.isNotEmpty()) {
            ChipFlow {
                terms.forEach { term ->
                    FilterChip(
                        selected = term.code == index?.term?.code,
                        onClick = { model.openTerm(term) },
                        label = { Text(if (term.college == app.termorganize.core.model.Term.College.NEU) term.shortName else term.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        if (sync.isNotBlank()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(sync, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status.ifBlank { "Loading the catalog…" }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (index != null) {
            TextButton(onClick = { model.refreshOpenTerm() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh this term from Banner") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Up next", style = MaterialTheme.typography.titleMedium)
                if (next.isEmpty()) EmptyText("No class on the primary schedule. Build one and it shows up here and on the home-screen widget.")
                next.forEach { item ->
                    val whenLabel = item.start.format(DateTimeFormatter.ofPattern("EEE h:mm a"))
                    Text("${item.meeting.courseCode} · $whenLabel · ${item.meeting.location}", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(index?.courses?.size?.toString() ?: "—", "courses", Modifier.weight(1f))
            Stat(index?.catalog?.sections?.size?.toString() ?: "—", "sections", Modifier.weight(1f))
            Stat(user.plans.size.toString(), "plans", Modifier.weight(1f))
        }
        Button(onClick = { open("search") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Search the catalog") }
        OutlinedButton(onClick = { open("schedule") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open the schedule builder") }
        OutlinedButton(onClick = { open("degree") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Degree plan and audit") }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(model: AppModel, initial: String, open: (String) -> Unit) {
    val index by model.index.collectAsState()
    val user by model.user.collectAsState()
    var text by remember(initial) { mutableStateOf(initial) }
    var filters by remember { mutableStateOf(SearchFilters()) }
    var showFilters by remember { mutableStateOf(false) }
    val response = remember(text, filters, index) { model.search(text, filters) }
    var indexed by remember { mutableStateOf<List<CourseAppSearch.Hit>>(emptyList()) }
    LaunchedEffect(text, filters, response?.results?.size, index?.term?.code) {
        val missed = text.isNotBlank() && filters.isEmpty && response != null && response.results.isEmpty() && response.crnMatch == null
        indexed = if (!missed) emptyList() else withContext(Dispatchers.IO) { model.courseIndexHits(text) }
    }
    val browsing = response == null || (text.isBlank() && filters.isEmpty)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().testTag("search-field"),
                label = { Text("Course, professor, CRN, or shorthand") },
                singleLine = true,
                supportingText = { Text("Try orgo, fundies, cs3000, or a five-digit CRN. Typos are allowed.") },
            )
            ChipFlow {
                FilterChip(selected = showFilters, onClick = { showFilters = true }, label = { Text(if (filters.activeCount == 0) "Filters" else "Filters · ${filters.activeCount}") })
                if (text.isNotBlank()) TextButton(onClick = { model.rememberSearch(text) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save search") }
            }
        }
        when {
            index == null -> CircularProgressIndicator(Modifier.padding(24.dp))
            browsing -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recent searches", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                if (user.searchHistory.isEmpty()) EmptyText("Nothing saved yet.")
                ChipFlow {
                    user.searchHistory.forEach { item -> AssistChip(onClick = { text = item }, label = { Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                }
            }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val crn = response.crnMatch
                if (crn != null) {
                    item {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { open("section/${crn.crn}") }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("CRN ${crn.crn}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("${crn.courseKey.description} · ${crn.title}", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (response.instructors.isNotEmpty()) {
                    item { Text("Professors", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp)) }
                    items(response.instructors.take(5), key = { it.id }) { person ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(person.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    countText(person.courseKeys.size, "course", "courses"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.clickable { open("professor/${java.net.URLEncoder.encode(person.name, "UTF-8")}") },
                        )
                    }
                }
                item {
                    Text(
                        countText(response.totalCount, "course", "courses"),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (response.results.isEmpty()) {
                    item {
                        Text(
                            if (indexed.isEmpty()) "No courses match. Loosen a filter or try a shorter query." else "Nothing in the main search. The on-device index found:",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    items(indexed, key = { "index-${it.code}" }) { hit ->
                        CourseHit(hit.code, hit.title, "From the AppSearch index") { open("course/${hit.subject}/${hit.number}") }
                    }
                }
                items(response.results.take(80), key = { it.id.description }) { result ->
                    val meta = listOfNotNull(result.course.creditsLabel, result.stats.availabilityLabel, result.matchNote).joinToString(" · ")
                    CourseHit(result.course.code, result.course.title, meta) { open("course/${result.course.subject}/${result.course.number}") }
                }
            }
        }
    }
    if (showFilters && index != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showFilters = false }, sheetState = sheetState) {
            FilterSheet(index!!, filters, { filters = it }, { showFilters = false })
        }
    }
}

@Composable
private fun CourseHit(code: String, title: String, meta: String, open: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = open)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
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
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text("Subjects", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
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
        Text("NUpath", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
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
        Text("Level", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
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
        SettingSwitch("Open seats only", filters.openSeatsOnly) { onChange(filters.copy(openSeatsOnly = it)) }
        SettingSwitch("Honors only", filters.honorsOnly) { onChange(filters.copy(honorsOnly = it)) }
        Text("Credits", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
            listOf(null to "Any", 1.0 to "1+", 3.0 to "3+", 4.0 to "4").forEach { (value, label) ->
                FilterChip(
                    selected = filters.minCredits == value,
                    onClick = { onChange(filters.copy(minCredits = value)) },
                    label = { Text(label) },
                )
            }
        }
        if (index.campusNames.isNotEmpty()) {
            Text("Campus", style = MaterialTheme.typography.titleSmall)
            ChipFlow {
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
            Text("Section type", style = MaterialTheme.typography.titleSmall)
            ChipFlow {
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
        Text("Instruction", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
            InstructionFilter.entries.forEach { mode ->
                FilterChip(selected = filters.instruction == mode, onClick = { onChange(filters.copy(instruction = mode)) }, label = { Text(mode.label) })
            }
        }
        Text("Starts after", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
            listOf(null to "Any", 8 * 60 to "8:00", 9 * 60 to "9:00", 10 * 60 to "10:00", 12 * 60 to "12:00").forEach { (value, label) ->
                FilterChip(selected = filters.startsAfter == value, onClick = { onChange(filters.copy(startsAfter = value)) }, label = { Text(label) })
            }
        }
        Text("Ends by", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
            listOf(null to "Any", 12 * 60 to "12:00", 15 * 60 to "3:00", 17 * 60 to "5:00", 19 * 60 to "7:00").forEach { (value, label) ->
                FilterChip(selected = filters.endsBefore == value, onClick = { onChange(filters.copy(endsBefore = value)) }, label = { Text(label) })
            }
        }
        Text("Days the section may meet", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
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
        TextButton(onClick = { onChange(SearchFilters()); close() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear filters") }
        Button(onClick = close, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
    }
}

@Composable
private fun CourseRoute(route: String, model: AppModel, back: () -> Unit) {
    val parts = route.removePrefix("course/").split("/")
    val key = if (parts.size >= 2) CourseKey(parts[0], parts[1]) else return
    val course = model.course(key)
    if (course == null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BackTitle("Course", back)
            EmptyText("That course isn’t in the bundled Fall 2026 catalog.")
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BackTitle("Section", back)
            EmptyText("CRN $crn isn’t in the bundled catalog.")
        }
        return
    }
    val course = model.course(section.courseKey)
    if (course != null) CourseScreen(course, model, back, highlight = crn) else {
        Column(Modifier.padding(16.dp)) {
            BackTitle(section.title, back)
        }
    }
}

@Composable
private fun CourseScreen(course: Course, model: AppModel, back: () -> Unit, highlight: String? = null) {
    val index by model.index.collectAsState()
    val sections = model.sections(course.key)
    var live by remember(course.key) { mutableStateOf<List<CourseSection>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val shown = live ?: sections
    val older by model.olderNote.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackTitle(course.code, back)
        Text(course.title, style = MaterialTheme.typography.titleLarge)
        Text(
            "${course.creditsLabel}${course.college?.let { " · $it" } ?: ""}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (course.nupaths.isNotEmpty()) {
            Text(course.nupaths.joinToString(" · ") { "${it.short} ${it.title}" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (course.description.isNotBlank()) Text(course.description, style = MaterialTheme.typography.bodyLarge)
        ChipFlow {
            Button(onClick = { model.addToPlan(course.key) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add to schedule") }
            OutlinedButton(
                onClick = {
                    refreshing = true
                    model.refreshCourse(course.key) {
                        live = it
                        refreshing = false
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (refreshing) "Refreshing…" else "Refresh seats") }
            OutlinedButton(onClick = { model.checkOlderTerms(course.key) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Older terms") }
        }
        if (older != null) Text(older!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Prerequisites", style = MaterialTheme.typography.titleMedium)
        RequisiteTree(course.prereqs, index)
        Text("Corequisites", style = MaterialTheme.typography.titleMedium)
        RequisiteTree(course.coreqs, index)
        Text("Prerequisite for", style = MaterialTheme.typography.titleMedium)
        if (course.postreqs.isEmpty()) EmptyText("Nothing in this catalog lists it as a prerequisite.")
        course.postreqs.forEach { key ->
            val title = index?.course(key)?.title.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(key.description, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text("Sections", style = MaterialTheme.typography.titleMedium)
        if (shown.isEmpty()) EmptyText("No sections this term.")
        shown.forEach { section ->
            val marked = section.crn == highlight
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${section.classType} ${section.sectionNumber}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "CRN ${section.crn}" + if (marked) " · opened from link" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(section.scheduleSummary, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${section.seatsLabel} · waitlist ${section.waitlistRemaining} of ${section.waitlistCapacity}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(section.instructorLabel, style = MaterialTheme.typography.bodyLarge)
                    if (section.honors) Text("Honors", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { model.watch(section, course.title) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Alert me if a seat opens") }
                }
            }
        }
    }
}

@Composable
private fun RequisiteTree(requisite: Requisite?, index: CatalogIndex?) {
    if (requisite == null) {
        EmptyText("None listed.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RequisiteNode(requisite.simplified() ?: requisite, 0, index)
    }
}

@Composable
private fun RequisiteNode(node: Requisite, depth: Int, index: CatalogIndex?) {
    val pad = Modifier.padding(start = (depth * 16).dp)
    when (node) {
        is Requisite.CourseReq -> {
            val title = index?.course(node.key)?.title.orEmpty()
            Column(pad, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(node.key.description, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.bodyLarge)
            }
        }
        is Requisite.TestReq -> Text(node.score?.let { "${node.name} ($it)" } ?: node.name, style = MaterialTheme.typography.bodyLarge, modifier = pad)
        is Requisite.And -> {
            Text("All of", style = MaterialTheme.typography.titleSmall, modifier = pad)
            node.items.forEach { RequisiteNode(it, depth + 1, index) }
        }
        is Requisite.Or -> {
            Text("One of", style = MaterialTheme.typography.titleSmall, modifier = pad)
            node.items.forEach { RequisiteNode(it, depth + 1, index) }
        }
    }
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Schedule", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(index?.term?.name ?: "Fall 2026", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("New plan") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { model.createSchedule(name); name = "" }, modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp)) { Text("Add") }
        }
        ChipFlow {
            plans.forEach { item ->
                FilterChip(selected = item.id == selectedId, onClick = { selectedId = item.id; generated = null }, label = { Text(item.name + if (item.isPrimary) " · primary" else "") })
            }
        }
        val others = user.plans.filter { term != null && it.termCode != term }.groupBy { it.termCode }
        if (others.isNotEmpty()) {
            Text("Other terms", style = MaterialTheme.typography.titleSmall)
            others.keys.sortedDescending().forEach { code ->
                val count = others.getValue(code).size
                val schedules = if (count == 1) "1 schedule" else "$count schedules"
                TextButton(onClick = { model.openTermCode(code) }) { Text("${model.termLabel(code)} · $schedules") }
            }
        }
        if (plan == null) {
            EmptyText("Add the courses you might take, then generate combinations that don’t overlap.")
            return
        }
        ChipFlow {
            if (!plan.isPrimary) TextButton(onClick = { model.setPrimarySchedule(plan.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Make primary") }
            TextButton(onClick = { model.duplicateSchedule(plan.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Duplicate") }
            TextButton(onClick = { model.deleteSchedule(plan.id); selectedId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete plan", color = MaterialTheme.colorScheme.error) }
        }
        val resolved = ResolvedPlan(plan, index)
        ScheduleNotices.make(resolved, index?.term?.name ?: "this term", catalogUnavailable = index == null).forEach { notice ->
            val color = when (notice.severity) {
                ScheduleNotice.Severity.ERROR -> MaterialTheme.colorScheme.error
                ScheduleNotice.Severity.WARNING -> MaterialTheme.colorScheme.tertiary
                ScheduleNotice.Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(notice.title, color = color, style = MaterialTheme.typography.titleSmall)
            notice.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            notice.fix?.let { fix ->
                TextButton(onClick = { model.forgetSections(plan.id, fix.removeSections) }) { Text(notice.fixTitle ?: "Remove") }
            }
        }
        val selected = plan.selectedCRNs.mapNotNull { index?.section(it) }
        WeekCalendar(selected, user.preferences.showWeekends, user.preferences.calendarStartHour, user.preferences.calendarEndHour)
        Text("Courses", style = MaterialTheme.typography.titleMedium)
        if (plan.courses.isEmpty()) EmptyText("Search for a course and tap Add to schedule.")
        plan.courses.forEach { course ->
            val title = index?.course(course.key)?.title.orEmpty()
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(course.key.description, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { model.removeCourse(plan.id, course.key) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
        }
        Text("Autofill", style = MaterialTheme.typography.titleMedium)
        val suggestions = model.suggestions(plan)
        if (suggestions.isEmpty()) EmptyText("Import a degree plan and the matching semester can fill this schedule.")
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
        ChipFlow {
            SchedulePreset.entries.forEach { preset ->
                FilterChip(selected = preset.isApplied(prefs), onClick = { model.applyPreset(plan.id, preset) }, label = { Text(preset.title) })
            }
        }
        Text(if (prefs.isDefault) "No extra wishes yet." else prefs.summary(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            sentence,
            { sentence = it },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            label = { Text("Describe the week") },
            supportingText = { Text("Example: morning person, no class after 4pm, Fridays off, in person.") },
        )
        Button(onClick = { model.applyPreferences(plan.id, sentence) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Apply description") }
        SettingSwitch("Hide full sections", plan.filters.hideFullSections) { model.setFilters(plan.id, plan.filters.copy(hideFullSections = it)) }
        SettingSwitch("Include online", plan.filters.includeOnline) { model.setFilters(plan.id, plan.filters.copy(includeOnline = it)) }
        Button(onClick = { generated = model.generate(plan) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Generate schedules") }
        val result = generated
        if (result != null) {
            Text("${result.schedules.size} of ${result.totalFound} combinations", style = MaterialTheme.typography.titleMedium)
            if (result.schedules.isEmpty()) {
                val analysis = result.noResults
                Text(analysis?.let { ScheduleNoResultsText.headline(result.diagnostics, it.blockers) } ?: "Nothing fit.")
                analysis?.blockers?.options?.forEach { option ->
                    Text(ScheduleNoResultsText.detail(option, analysis.blockers, index?.term?.name ?: "this term"), style = MaterialTheme.typography.bodySmall)
                }
                val moves = analysis?.blockers?.blockingCourses?.let { model.scheduleMoves(it) }.orEmpty()
                moves.forEach { option ->
                    TextButton(onClick = { model.applyReorder(option); generated = null }) { Text(option.title) }
                    if (option.scheduleEffect.isNotEmpty()) Text(option.scheduleEffect, style = MaterialTheme.typography.bodySmall)
                }
                result.diagnostics.suggestions.forEach { Text("• $it") }
                result.diagnostics.courses.filter { it.isBlocked }.forEach { report ->
                    Text("${report.key.description} has no remaining sections. ${report.rejections.entries.joinToString { "${it.value} ${it.key}" }}")
                }
                if (result.diagnostics.conflictingPairs.isNotEmpty()) {
                    Text("Conflicts: ${result.diagnostics.conflictingPairs.joinToString { "${it.first.description} & ${it.second.description}" }}")
                }
            }
            result.schedules.take(12).forEachIndexed { indexNumber, schedule ->
                Card(Modifier.fillMaxWidth().testTag("schedule-option").clickable { model.selectCombination(plan.id, schedule.crns) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Option ${indexNumber + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Score ${"%.1f".format(schedule.score)} · ${schedule.classDays} days",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        schedule.sections.forEach { section ->
                            Text("${section.courseKey.description} · ${section.scheduleSummary} · ${section.crn}", style = MaterialTheme.typography.bodyLarge)
                        }
                        schedule.reasons.forEach { reason ->
                            Text(
                                (if (reason.isCaveat) "Caveat: " else "") + reason.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (reason.isCaveat) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Text("Export", style = MaterialTheme.typography.titleMedium)
        val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            model.report(if (granted) model.insertCalendar(plan) else "Calendar permission is required to add every meeting.")
        }
        ChipFlow {
            OutlinedButton(onClick = { share(context, model.exportIcs(plan), "text/calendar") }) { Text(".ics") }
            OutlinedButton(onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
                if (granted) model.report(model.insertCalendar(plan)) else calendarPermission.launch(Manifest.permission.WRITE_CALENDAR)
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

private data class PlacedMeeting(val section: CourseSection, val start: Int, val end: Int, val lane: Int, val lanes: Int)

private fun placeMeetings(sections: List<CourseSection>, day: Int, windowStart: Int, windowEnd: Int): List<PlacedMeeting> {
    val items = sections.flatMap { section ->
        section.weeklyMeetings.filter { it.days.contains(day) }.mapNotNull { meeting ->
            val start = meeting.start ?: return@mapNotNull null
            val end = meeting.end ?: return@mapNotNull null
            if (end <= windowStart || start >= windowEnd) null
            else Triple(section, start.coerceAtLeast(windowStart), end.coerceAtMost(windowEnd))
        }
    }.sortedBy { it.second }
    val laneEnds = mutableListOf<Int>()
    val placed = items.map { (section, start, end) ->
        var lane = laneEnds.indexOfFirst { it <= start }
        if (lane < 0) {
            lane = laneEnds.size
            laneEnds += end
        } else {
            laneEnds[lane] = end
        }
        Triple(section, start to end, lane)
    }
    val count = laneEnds.size.coerceAtLeast(1)
    return placed.map { (section, range, lane) -> PlacedMeeting(section, range.first, range.second, lane, count) }
}

private fun blockColors(argb: Int): Pair<Color, Color> {
    var color = Color(argb)
    val ink = Color(0xFF142226)
    repeat(5) {
        val onWhite = contrastRatio(color, Color.White)
        val onInk = contrastRatio(color, ink)
        if (maxOf(onWhite, onInk) >= 4.5f) return color to if (onWhite >= onInk) Color.White else ink
        color = Color(red = color.red * 0.84f, green = color.green * 0.84f, blue = color.blue * 0.84f)
    }
    return color to if (contrastRatio(color, Color.White) >= contrastRatio(color, ink)) Color.White else ink
}

private fun contrastRatio(background: Color, foreground: Color): Float {
    val hi = maxOf(background.luminance(), foreground.luminance())
    val lo = minOf(background.luminance(), foreground.luminance())
    return (hi + 0.05f) / (lo + 0.05f)
}

@Composable
private fun WeekCalendar(sections: List<CourseSection>, weekends: Boolean, startHour: Int, endHour: Int) {
    val days = if (weekends) (0..6).toList() else (1..5).toList()
    val windowStart = startHour * 60
    val windowEnd = endHour * 60
    val span = (windowEnd - windowStart).coerceAtLeast(60).toFloat()
    val gridHeight = 480.dp
    val header = 32.dp
    val gutter = 40.dp
    val hourCount = (endHour - startHour).coerceAtLeast(1)
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = maxWidth - gutter
        val dayWidth = if (available / days.size >= 64.dp) (available / days.size).coerceAtMost(168.dp) else 72.dp
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row(Modifier.width(gutter + dayWidth * days.size).height(gridHeight + header)) {
            Column(Modifier.width(gutter).padding(top = header, end = 4.dp)) {
                (startHour until endHour).forEach { hour ->
                    Text(
                        TimeOfDay.hourAxisLabel(hour),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.height(gridHeight / hourCount),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            days.forEach { day ->
                val placed = placeMeetings(sections, day, windowStart, windowEnd)
                Column(Modifier.width(dayWidth).padding(end = 4.dp)) {
                    Text(
                        DaySet.SHORT[day],
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.height(header).fillMaxWidth(),
                        maxLines = 1,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(gridHeight)
                            .background(scheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            for (i in 1 until hourCount) {
                                val y = size.height * i / hourCount
                                drawLine(scheme.outlineVariant, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1.dp.toPx())
                            }
                        }
                        placed.forEach { meeting ->
                            val top = ((meeting.start - windowStart) / span).coerceIn(0f, 1f)
                            val bottom = ((meeting.end - windowStart) / span).coerceIn(top, 1f)
                            val (fill, label) = blockColors(CourseColors.forCode(meeting.section.courseKey.description))
                            val fraction = (bottom - top).coerceAtLeast(0f)
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val laneWidth = maxWidth / meeting.lanes
                                Box(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .offset(x = laneWidth * meeting.lane, y = maxHeight * top)
                                        .width(laneWidth)
                                        .height((maxHeight * fraction).coerceAtLeast(22.dp))
                                        .padding(2.dp)
                                        .background(fill, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        "${meeting.section.courseKey.subject}\n${meeting.section.courseKey.number}",
                                        color = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = if (fraction >= 0.08f) 2 else 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
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
    val offering by model.offering.collectAsState()
    val context = LocalContext.current
    val courseInfo by model.courseInfo.collectAsState()
    LaunchedEffect(plan?.id, plan?.updatedAt, index?.term?.code) {
        val current = plan ?: return@LaunchedEffect
        model.refreshOffering(current)
        model.refreshCourseInfo(current)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Degree plan", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipFlow {
            Button(onClick = onImportPdf, modifier = Modifier.heightIn(min = 48.dp)) { Text("Import PDF") }
            OutlinedButton(onClick = { model.addDegreePlan(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("New") }
            OutlinedButton(onClick = { model.addDegreePlan(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("From schedule") }
        }
        ChipFlow {
            user.degreePlans.forEach { item ->
                FilterChip(selected = item.id == selected, onClick = { selected = item.id }, label = { Text(item.name.ifBlank { "Untitled" } + if (item.isPrimary) " · primary" else "") })
            }
        }
        if (plan == null) {
            EmptyText("Open a Northeastern degree-plan PDF, or start an empty plan and add courses.")
            return
        }
        Text(
            "${plan.completedCredits} completed · ${plan.plannedCredits} planned · ${plan.completedCredits + plan.plannedCredits} total hours",
            style = MaterialTheme.typography.bodyLarge,
        )
        val duplicates = DegreePlanAnalysis.duplicateKeys(plan)
        if (duplicates.isNotEmpty()) Text("Repeated: ${duplicates.joinToString { it.description }}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        ChipFlow {
            if (!plan.isPrimary) TextButton(onClick = { model.setPrimaryDegree(plan.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Make primary") }
            TextButton(onClick = { model.duplicateDegree(plan.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Duplicate") }
            TextButton(onClick = { share(context, model.exportPlanPdf(plan), "application/pdf") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Export PDF") }
            TextButton(onClick = { model.deleteDegree(plan.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        Text("Completed", style = MaterialTheme.typography.titleMedium)
        plan.completed.forEach { course -> CourseRow(plan, course, model, index, courseInfo, completed = true) }
        Text("Planned", style = MaterialTheme.typography.titleMedium)
        plan.terms.forEach { term ->
            Text("${term.shortName} (${term.credits} hours)", fontWeight = FontWeight.SemiBold)
            term.courses.forEach { course ->
                CourseRow(plan, course, model, index, courseInfo, termIndex = plan.terms.indexOf(term))
                val line = model.offeringLine(course.key, term.season)
                if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (offering.checkedTerms.isNotEmpty()) {
                val moves = model.moveSuggestions(plan, term)
                moves.forEach { suggestion ->
                    TextButton(onClick = { model.applyMove(plan.id, suggestion) }) { Text(suggestion.title) }
                    suggestion.reasons.take(3).forEach { reason ->
                        Text(reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("Add a course", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(code, { code = it }, label = { Text("Code, such as CS 3000") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(credits, { credits = it }, label = { Text("Hours") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val parsedKey = CourseKey.parse(code)
        ChipFlow {
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
        ChipFlow {
            DegreePlanAutofill.nextSemesters(plan).forEach { term ->
                AssistChip(onClick = { model.addTerm(plan.id, term.season, term.year) }, label = { Text(term.shortName) })
            }
            PlanSeason.entries.forEach { season ->
                AssistChip(onClick = { model.addTerm(plan.id, season, 2027) }, label = { Text("${season.shortName} 2027") })
            }
        }
        Text("Equivalences and renumbered courses", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(equivA, { equivA = it }, label = { Text("Taken as") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        OutlinedTextField(equivB, { equivB = it }, label = { Text("Counts as") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val a = CourseKey.parse(equivA)
            val b = CourseKey.parse(equivB)
            if (a != null && b != null) model.addEquivalence(plan.id, a, b, "")
        }) { Text("Count one course as another") }
        plan.courseEquivalences.forEach { Text("${it.course.description} → ${it.countsAs.description}") }
        OutlinedButton(onClick = {
            val catalog = index ?: return@OutlinedButton
            val listings = catalog.courses.mapNotNull { course -> course.prereqs?.let { PrerequisiteListing(course.key, it) } }
            val titles = catalog.courses.associate { it.key to it.title }
            hints = plan.completed.mapNotNull { course ->
                val key = course.key ?: return@mapNotNull null
                if (catalog.course(key) != null) return@mapNotNull null
                val planTitle = course.title.trim().ifEmpty { null }
                val found = EquivalenceSuggester.suggestions(
                    retired = key,
                    prerequisites = listings,
                    isCurrent = { catalog.course(it) != null },
                    excluding = plan.declaredEquivalences,
                    title = { candidate -> if (candidate == key) planTitle else titles[candidate] },
                    titleCandidates = catalog.courses.map { it.key },
                )
                if (found.isEmpty()) null else "${course.label.ifBlank { course.title }}: ${found.joinToString { "${it.candidate.description} (${it.evidence})" }}"
            }
        }) { Text("Suggest equivalences") }
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
            ChipFlow {
                requirements.concentrations.forEach { section ->
                    FilterChip(
                        selected = plan.concentrationID == section.id,
                        onClick = { model.updateDegree(plan.id) { it.copy(concentrationID = section.id, catalogProgramPath = requirements.program.path, catalogProgramTitle = requirements.program.title) } },
                        label = { Text(section.concentrationName.ifBlank { section.title }) },
                    )
                }
            }
            val fromCatalog = app.termorganize.logic.degree.DegreeCourseAttributes(
                nupaths = (plan.completed + plan.terms.flatMap { it.courses }).mapNotNull { course ->
                    val key = course.key ?: return@mapNotNull null
                    val paths = index?.course(key)?.nupaths?.toSet() ?: return@mapNotNull null
                    key to paths
                }.toMap(),
            )
            val attributes = courseInfo.attributes.overlay(fromCatalog)
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
            DegreePetitionQuestions.questions(plan, requirements, audit = audit).forEach { question ->
                Text(question.question, fontWeight = FontWeight.SemiBold)
                Text(question.explanation, style = MaterialTheme.typography.bodySmall)
                ChipFlow {
                    TextButton(
                        onClick = { model.updateDegree(plan.id) { it.setCourseNumbers(CourseNumberLimit.AnyNumber, question.id) } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Any course number") }
                    TextButton(
                        onClick = { model.updateDegree(plan.id) { it.setCourseNumbers(CourseNumberLimit.SameAsRequirement, question.id) } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Same numbers") }
                }
            }
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
    courseInfo: CourseInfoSnapshot,
    completed: Boolean = false,
    termIndex: Int = 0,
) {
    val status = course.key?.let {
        DegreePlanAnalysis.prerequisiteStatus(
            it,
            if (completed) -1 else termIndex,
            plan,
            index,
            courseInfo.lookup(),
            courseInfo.equivalences,
        )
    } ?: DegreePlanAnalysis.PrerequisiteStatus.Met
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    course.label.ifBlank { course.key?.description ?: "Course" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (course.title.isNotBlank()) {
                    Text(course.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("${course.credits} hours", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { model.removeDegreeCourse(plan.id, course.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        }
        if (status is DegreePlanAnalysis.PrerequisiteStatus.Missing) {
            Text("Needs ${status.text}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun MoreScreen(route: String, model: AppModel, onImportJson: () -> Unit, open: (String) -> Unit) {
    when {
        route.startsWith("more/rooms") || route == "rooms" -> RoomsScreen(model) { open("room/$it") }
        route.startsWith("more/professors") || route == "professors" -> ProfessorsScreen(model) { open("professor/${java.net.URLEncoder.encode(it, "UTF-8")}") }
        route.startsWith("more/watch") || route == "watch" -> WatchScreen(model)
        route.startsWith("more/settings") || route == "settings" -> SettingsScreen(model, onImportJson)
        else -> Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
            Text("More", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() })
            Text(
                "Rooms, professors, seat alerts, and settings.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
            MoreRow("Free rooms", "Rooms that are open right now") { open("more/rooms") }
            MoreRow("Professors", "Who is teaching this term") { open("more/professors") }
            MoreRow("Seat alerts", "Watch a CRN and get notified") { open("more/watch") }
            MoreRow("Settings and legal", "Theme, backups, and the license") { open("more/settings") }
        }
    }
}

@Composable
private fun MoreRow(title: String, detail: String, open: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.clickable(onClick = open),
    )
}

@Composable
private fun RoomsScreen(model: AppModel, open: (String) -> Unit) {
    val index by model.index.collectAsState()
    var minutes by remember { mutableStateOf(30) }
    var query by remember { mutableStateOf("") }
    val moment = remember { RoomAvailability.Moment.now() }
    val rooms = index?.buildings?.flatMap { it.rooms }.orEmpty().filter { query.isBlank() || it.label.contains(query, true) || it.buildingName.contains(query, true) }
    val free = remember(rooms, minutes) { RoomAvailability.freeRooms(rooms, moment, minutes) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Free rooms", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                "Right now: ${moment.label}. Short half-term bookings are ignored when the day isn’t known.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(query, { query = it }, label = { Text("Building or room") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true)
            ChipFlow {
                listOf(30, 60, 90).forEach { option ->
                    FilterChip(selected = minutes == option, onClick = { minutes = option }, label = { Text("$option min") })
                }
            }
        }
        if (free.isEmpty()) {
            Text(
                "No rooms are free for the next $minutes minutes.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(free.take(80), key = { it.first.id }) { (room, status) ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(room.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                room.buildingName + (status.until?.let { " · until ${TimeOfDay.label(it)}" } ?: " · open the rest of the day"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.testTag("room-row").clickable { open(room.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomRoute(id: String, model: AppModel, back: () -> Unit) {
    val room = model.index.collectAsState().value?.buildings?.flatMap { it.rooms }?.firstOrNull { it.id == id }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BackTitle(room?.label ?: "Room", back)
        if (room == null) {
            EmptyText("Room not found.")
            return
        }
        Text(room.buildingName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (room.bookings.isEmpty()) EmptyText("Nothing is booked in the bundled catalog.")
        room.bookings.sortedWith(compareBy<app.termorganize.logic.search.RoomSchedule.Booking> { it.days.rawValue }.thenBy { it.start }).forEach { booking ->
            Text(
                "${booking.days.compactLabel} ${TimeOfDay.rangeLabel(booking.start, booking.end)} · ${booking.course.description}",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ProfessorsScreen(model: AppModel, open: (String) -> Unit) {
    val index by model.index.collectAsState()
    var query by remember { mutableStateOf("") }
    val people = index?.instructors.orEmpty().filter { query.isBlank() || it.displayName.contains(query, true) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Professors", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            OutlinedTextField(query, { query = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true)
        }
        if (people.isEmpty()) {
            Text(
                "No professors match that name.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(people.take(60), key = { it.id }) { person ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(person.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                person.courseKeys.joinToString { it.description },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.testTag("professor-row").clickable { open(person.name) },
                    )
                }
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BackTitle(person?.displayName ?: "Professor", back)
        if (index == null || person == null) {
            EmptyText("Professor not found.")
            return
        }
        val sections = ProfessorPage.sections(person, index)
        Text(ProfessorPage.summaryText(sections), style = MaterialTheme.typography.bodyLarge)
        person.email?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
        TextButton(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ProfessorPage.rateMyProfessorsUrl(person.displayName))))
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Rate My Professors") }
        val colors = ProfessorPage.courseColors(person.courseKeys)
        Text("Teaching calendar", style = MaterialTheme.typography.titleMedium)
        WeekCalendar(sections, false, 8, 21)
        sections.forEach { section ->
            val swatch = colors[section.courseKey] ?: CourseColors.at(0)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(width = 4.dp, height = 36.dp).background(Color(swatch), RoundedCornerShape(2.dp)))
                Text(
                    "${section.courseKey.description} ${section.sectionNumber} · ${section.scheduleSummary}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WatchScreen(model: AppModel) {
    val user by model.user.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Seat alerts", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(
            "WorkManager refreshes watched CRNs about every 15 minutes and notifies you when a seat or waitlist spot opens.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (user.watchlist.isEmpty()) EmptyText("Open a section and tap Alert me if a seat opens.")
        user.watchlist.forEach { item ->
            val status = WatchlistStatus.of(item, false)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${item.courseKey.description} · CRN ${item.crn}", style = MaterialTheme.typography.titleMedium)
                    Text(status.detail(item), style = MaterialTheme.typography.bodyLarge)
                    Text(item.sectionLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    item.lastCheckedAt?.let {
                        Text(
                            "Checked ${WatchlistFormat.ago(it, System.currentTimeMillis())}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { model.removeWatch(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop watching") }
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        SettingSwitch("Dynamic color", prefs.dynamicColor, tag = "dynamic-color") { model.setAppPreferences { p -> p.copy(dynamicColor = it) } }
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                FilterChip(selected = prefs.darkTheme == value, onClick = { model.setAppPreferences { it.copy(darkTheme = value) } }, label = { Text(label) })
            }
        }
        SettingSwitch("Show weekends", prefs.showWeekends) { on -> model.setAppPreferences { it.copy(showWeekends = on) } }
        SettingSwitch("Background seat checks", prefs.backgroundRefreshEnabled) { on -> model.setAppPreferences { it.copy(backgroundRefreshEnabled = on) } }
        Text(
            "iCloud sync from the Apple apps is not available on Android. Export a JSON backup and import it on another device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { share(context, model.exportUser(), "application/json") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Export data") }
        OutlinedButton(onClick = onImportJson, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Import data") }
        Text("Legal", style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodyLarge)
        Text(
            "Term Organize is free software under the GNU Affero General Public License, version 3 only. Portions of the Banner client, catalog builder, requisites, schedule generator, and watchlist rules are derived from SearchNEU. Course data is published by Northeastern University and is not covered by that license. This app is unofficial and is not affiliated with Northeastern University or Sandbox.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (license.isNotBlank()) {
            Text("GNU AGPL-3.0-only", style = MaterialTheme.typography.titleSmall)
            Text(license.take(1800) + if (license.length > 1800) "…" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Version 1.0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
