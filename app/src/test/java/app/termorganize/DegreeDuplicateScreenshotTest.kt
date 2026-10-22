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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.DaySet
import app.termorganize.core.model.Instructor
import app.termorganize.core.model.Meeting
import app.termorganize.logic.degree.DegreeSectionChoice
import app.termorganize.ui.DegreeSectionPicker
import app.termorganize.ui.SectionGroup
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

    @Test fun lightPickerAndDarkEmptyTerm() {
        val sections = listOf(
            section("11111", "Machine Learning", listOf(1, 3, 4), 10 * 60, 11 * 60 + 5),
            section("22222", "Networks", listOf(2, 5), 14 * 60, 15 * 60 + 5),
        )
        val groups = listOf(
            SectionGroup("old", "Section for the course already in Fall 2026"),
            SectionGroup("new", "Section for the new row"),
        )
        var dark by mutableStateOf(false)
        var empty by mutableStateOf(false)
        compose.setContent {
            TermOrganizeTheme(dynamic = false, dark = dark) {
                Surface(Modifier.fillMaxSize()) {
                    DegreeSectionPicker(
                        sections = if (empty) emptyList() else sections,
                        groups = groups,
                        takenCrns = emptySet(),
                        confirmLabel = "Add both sections",
                        emptyText = DegreeSectionChoice.message("Spring 2027"),
                        onConfirm = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("section-old-11111").performScrollTo().performClick()
        compose.onNodeWithTag("section-new-22222").performScrollTo().performClick()
        compose.onNodeWithText("Add both sections").assertExists()
        compose.waitForIdle()
        save(draw(), "degree-duplicate-light.png")
        dark = true
        compose.waitForIdle()
        save(draw(), "degree-duplicate-dark.png")
        empty = true
        compose.waitForIdle()
        compose.onNodeWithText("Pick a section to add this course twice. No sections are listed for Spring 2027 yet.").assertExists()
        save(draw(), "degree-duplicate-empty.png")
    }

    private fun section(crn: String, title: String, days: List<Int>, start: Int, end: Int) = CourseSection(
        crn = crn,
        subject = "CS",
        number = "4973",
        title = title,
        instructors = listOf(Instructor("Doe, Jane", primary = true)),
        meetings = listOf(Meeting(DaySet(days), start, end, "WVH", "West Village H", "210", "BOS")),
        seatsRemaining = 12,
        seatCapacity = 30,
    )

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
        for (dir in listOf(File("/tmp/degree-duplicate"), File("/opt/cursor/artifacts/screenshots"))) {
            dir.mkdirs()
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
