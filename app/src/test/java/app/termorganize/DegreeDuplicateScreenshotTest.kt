// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.termorganize.core.model.CourseKey
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.ui.DegreeDuplicateRows
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
class DegreeDuplicateScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun lightAndDarkWarnOnEachRepeatedRow() {
        val key = CourseKey("CS", "4973")
        val warning = "CS 4973 is in your plan more than once. That's fine for special topics; otherwise remove the extra copy."
        val plan = DegreePlan(
            name = "Topics",
            terms = listOf(
                DegreePlanTerm(
                    season = PlanSeason.FALL,
                    year = 2026,
                    courses = listOf(
                        DegreePlanCourse.of(key, "Special Topics in Computer Science", 4.0, id = "row-a"),
                        DegreePlanCourse.of(key, "Special Topics in Computer Science", 4.0, id = "row-b"),
                    ),
                ),
            ),
        )
        var dark by mutableStateOf(false)
        compose.setContent {
            TermOrganizeTheme(dynamic = false, dark = dark) {
                Surface(Modifier.fillMaxSize()) {
                    DegreeDuplicateRows(plan)
                }
            }
        }
        compose.onAllNodesWithText(warning, substring = false).assertCountEquals(2)
        compose.onAllNodesWithText("CS 4973", substring = false).assertCountEquals(2)
        compose.waitForIdle()
        save(draw(), "degree-duplicates-light.png")
        dark = true
        compose.waitForIdle()
        compose.onAllNodesWithText(warning, substring = false).assertCountEquals(2)
        save(draw(), "degree-duplicates-dark.png")
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
        for (dir in listOf(File("/tmp/degree-duplicates"), File("/opt/cursor/artifacts/screenshots"))) {
            dir.mkdirs()
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
