// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import app.termorganize.ui.AppModel
import app.termorganize.ui.TermOrganizeRoot

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    private val pending = mutableStateOf<String?>(null)
    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importUri(uri)
    }
    private val openJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importUser(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pending.value = route(intent)
        setContent {
            TermOrganizeRoot(
                model = model,
                startRoute = pending.value,
                onImportPdf = { openPdf.launch(arrayOf("application/pdf", "text/plain")) },
                onImportJson = { openJson.launch(arrayOf("application/json", "text/plain")) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pending.value = route(intent)
        if (intent.type == "application/pdf" && intent.data != null) model.importUri(intent.data!!)
    }

    private fun route(intent: Intent?): String? {
        val data: Uri = intent?.data ?: return null
        val scheme = data.scheme?.lowercase()
        if (scheme != "termorganize" && scheme != "neuclasssearch") {
            if (intent.type == "application/pdf") model.importUri(data)
            return "degree"
        }
        val host = data.host.orEmpty()
        val parts = data.pathSegments
        return when (host) {
            "search" -> "search?q=" + (data.getQueryParameter("q") ?: "")
            "schedule" -> "schedule"
            "next" -> "home"
            "course" -> if (parts.size >= 3) "course/${parts[1]}/${parts[2]}" else if (parts.size >= 2) "course/${parts[0]}/${parts[1]}" else "search"
            "section" -> if (parts.size >= 2) "section/${parts[1]}" else if (parts.isNotEmpty()) "section/${parts[0]}" else "search"
            else -> when {
                parts.firstOrNull() == "course" && parts.size >= 4 -> "course/${parts[2]}/${parts[3]}"
                parts.firstOrNull() == "section" && parts.size >= 2 -> "section/${parts.last()}"
                else -> host.ifBlank { "home" }
            }
        }
    }
}
