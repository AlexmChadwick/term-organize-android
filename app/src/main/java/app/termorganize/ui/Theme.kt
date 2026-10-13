// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF0F6E6E)
private val Copper = Color(0xFFB15A32)
private val Ink = Color(0xFF142226)
private val Paper = Color(0xFFF6F1E8)

private val Light = lightColorScheme(primary = Teal, secondary = Copper, background = Paper, surface = Color.White, onBackground = Ink)
private val Dark = darkColorScheme(primary = Color(0xFF7DCFC6), secondary = Color(0xFFE0A07A), background = Color(0xFF101616), surface = Color(0xFF1A2222))

@Composable
fun TermOrganizeTheme(dynamic: Boolean, dark: Boolean?, content: @Composable () -> Unit) {
    val system = isSystemInDarkTheme()
    val useDark = dark ?: system
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 && useDark -> dynamicDarkColorScheme(context)
        dynamic && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        useDark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
