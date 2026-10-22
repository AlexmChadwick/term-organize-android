// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.termorganize.core.model.CourseSection

internal data class SectionGroup(val id: String, val title: String)

@Composable
internal fun DegreeSectionPicker(
    sections: List<CourseSection>,
    groups: List<SectionGroup>,
    takenCrns: Set<String>,
    confirmLabel: String,
    emptyText: String,
    onConfirm: (Map<String, String>) -> Unit,
) {
    var selected by remember { mutableStateOf(mapOf<String, String>()) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 28.dp).testTag("duplicate-picker"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pick a section", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (sections.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        groups.forEach { group ->
            Text(group.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val chosenElsewhere = selected.filterKeys { it != group.id }.values.toSet()
            sections.forEach { section ->
                val unavailable = section.crn in takenCrns || section.crn in chosenElsewhere
                val isSelected = selected[group.id] == section.crn
                Card(
                    Modifier.fillMaxWidth().testTag("section-${group.id}-${section.crn}").clickable(enabled = !unavailable) {
                        if (groups.size == 1) onConfirm(mapOf(group.id to section.crn))
                        else selected = selected + (group.id to section.crn)
                    },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(section.title.ifBlank { section.courseKey.description }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(section.instructorLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(section.scheduleSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val note = when {
                            isSelected -> "CRN ${section.crn} · Selected"
                            section.crn in takenCrns -> "CRN ${section.crn} · Already in the plan"
                            section.crn in chosenElsewhere -> "CRN ${section.crn} · Already chosen"
                            else -> "CRN ${section.crn}"
                        }
                        Text(note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (groups.size > 1) {
            val ready = groups.all { selected[it.id] != null } && selected.values.distinct().size == groups.size
            Button(
                onClick = { if (ready) onConfirm(selected) },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("confirm-sections"),
            ) { Text(confirmLabel) }
        }
    }
}
