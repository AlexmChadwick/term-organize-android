// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Teal = Color(0xFF0F6E6E)
private val TealDark = Color(0xFF7DCFC6)
private val Ink = Color(0xFF142226)
private val Paper = Color(0xFFF6F1E8)

private val Light = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8EBE7),
    onPrimaryContainer = Color(0xFF042E2E),
    secondary = Color(0xFF8C4524),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3EBE7),
    onSecondaryContainer = Color(0xFF0C3F3E),
    tertiary = Color(0xFF6E4E12),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF8E2C4),
    onTertiaryContainer = Color(0xFF3A2800),
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFFFCF7),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7E0D4),
    onSurfaceVariant = Color(0xFF3E4948),
    surfaceTint = Teal,
    inverseSurface = Color(0xFF2B3332),
    inverseOnSurface = Color(0xFFF3F7F5),
    inversePrimary = TealDark,
    outline = Color(0xFF5E6E6C),
    outlineVariant = Color(0xFFD5CEC3),
    scrim = Color(0xFF000000),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF7F1),
    surfaceContainer = Color(0xFFEFEAE0),
    surfaceContainerHigh = Color(0xFFE7E1D6),
    surfaceContainerHighest = Color.White,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF134E4B),
    onPrimaryContainer = Color(0xFFC8F5F0),
    secondary = Color(0xFFF0B898),
    onSecondary = Color(0xFF3A1E10),
    secondaryContainer = Color(0xFF3D4F4C),
    onSecondaryContainer = Color(0xFFD5F3EE),
    tertiary = Color(0xFFE8C18A),
    onTertiary = Color(0xFF3A2800),
    tertiaryContainer = Color(0xFF5A4318),
    onTertiaryContainer = Color(0xFFF8E2C4),
    background = Color(0xFF101616),
    onBackground = Color(0xFFE7F0EC),
    surface = Color(0xFF171F1F),
    onSurface = Color(0xFFE7F0EC),
    surfaceVariant = Color(0xFF2A3434),
    onSurfaceVariant = Color(0xFFC1CECA),
    surfaceTint = TealDark,
    inverseSurface = Color(0xFFE7F0EC),
    inverseOnSurface = Color(0xFF1C2626),
    inversePrimary = Teal,
    outline = Color(0xFF8A9A96),
    outlineVariant = Color(0xFF3A4846),
    scrim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF0B1111),
    surfaceContainerLow = Color(0xFF141C1C),
    surfaceContainer = Color(0xFF1C2626),
    surfaceContainerHigh = Color(0xFF24302F),
    surfaceContainerHighest = Color(0xFF2C3938),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun TermOrganizeTheme(dynamic: Boolean, dark: Boolean?, content: @Composable () -> Unit) {
    val system = isSystemInDarkTheme()
    val useDark = dark ?: system
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 && useDark -> dynamicDarkColorScheme(context)
        dynamic && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        useDark -> Dark
        else -> Light
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !useDark
                isAppearanceLightNavigationBars = !useDark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
