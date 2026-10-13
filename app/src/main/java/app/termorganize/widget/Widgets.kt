// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.termorganize.data.WidgetFiles
import app.termorganize.logic.widget.WidgetScheduleData
import java.time.ZonedDateTime

class UpNextWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetFiles.load(context)
        val next = data?.occurrences(ZonedDateTime.now(WidgetScheduleData.zone), 1)?.firstOrNull()
        provideContent { GlanceTheme { WidgetCard(title = "Up next", body = next?.let { "${it.meeting.courseCode} · ${it.meeting.location}" } ?: "No upcoming class") } }
    }
}

class AgendaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetFiles.load(context)
        val lines = data?.classes(ZonedDateTime.now(WidgetScheduleData.zone)).orEmpty().take(6)
        val body = if (lines.isEmpty()) "Nothing on the calendar today" else lines.joinToString("\n") { "${it.meeting.courseCode}  ${it.meeting.location}" }
        provideContent { GlanceTheme { WidgetCard(title = data?.planName ?: "Today", body = body) } }
    }
}

@Composable
private fun WidgetCard(title: String, body: String) {
    Column(GlanceModifier.padding(12.dp)) {
        Text(title, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp))
        Text(body, style = TextStyle(fontSize = 13.sp))
    }
}

class UpNextReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = UpNextWidget()
}

class AgendaReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = AgendaWidget()
}
