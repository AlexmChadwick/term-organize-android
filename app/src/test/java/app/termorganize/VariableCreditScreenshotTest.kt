// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.termorganize.ui.CreditStepper
import app.termorganize.ui.TermOrganizeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h840dp-mdpi")
class VariableCreditScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun lightAndDarkShowTheStepperOnAVariableRowOnly() {
        var chosen by mutableStateOf(4.0)
        var dark by mutableStateOf(false)
        compose.setContent {
            TermOrganizeTheme(dynamic = false, dark = dark) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("CS 8982", fontWeight = FontWeight.SemiBold)
                        Text("Readings")
                        CreditStepper(chosen, 1.0, 8.0) { chosen = it }
                        Text("CS 3000", fontWeight = FontWeight.SemiBold)
                        Text("Algorithms and Data")
                        Text("4.0 hours")
                    }
                }
            }
        }
        compose.onNodeWithText("Credits: 4 (1 to 8)", substring = false).assertExists()
        compose.onNodeWithContentDescription("More credits").performClick()
        compose.onNodeWithText("Credits: 5 (1 to 8)", substring = false).assertExists()
        compose.onNodeWithContentDescription("Fewer credits").performClick()
        compose.onNodeWithText("Credits: 4 (1 to 8)", substring = false).assertExists()
        compose.onAllNodesWithText("Credits:", substring = true).assertCountEquals(1)
        compose.onNodeWithText("4.0 hours", substring = false).assertExists()
        compose.waitForIdle()
        save(draw(), "variable-credits-light.png")
        dark = true
        compose.waitForIdle()
        save(draw(), "variable-credits-dark.png")
    }

    private fun draw(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val first = pixels.first()
        var different = 0
        for (pixel in pixels) {
            if (pixel != first) {
                different++
                if (different > 40) break
            }
        }
        check(different > 40) { "$name is blank" }
        for (dir in listOf(File("/tmp/variable-credits"), File("/opt/cursor/artifacts/screenshots"))) {
            dir.mkdirs()
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
