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
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.Subject
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.logic.search.CatalogIndex
import app.termorganize.logic.search.SearchFilters
import app.termorganize.ui.FilterSheet
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
class RequisiteFilterScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun lightAndDarkShowTheRequisiteSwitches() {
        val courses = listOf(
            Course("CS", "1200", "First Year Seminar", minCredits = 1.0, maxCredits = 1.0, detailsLoaded = true),
            Course("CS", "3000", "Algorithms and Data", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true),
        )
        val index = CatalogIndex.build(TermCatalog(
            term = Term("202710", "Fall 2026 Semester"),
            courses = courses,
            sections = courses.map { CourseSection(it.number, it.subject, it.number, seatsRemaining = 8, seatCapacity = 20, campus = "Boston") },
            subjects = listOf(Subject("CS", "Computer Science")),
        ))
        var filters by mutableStateOf(SearchFilters())
        var dark by mutableStateOf(false)
        compose.setContent {
            TermOrganizeTheme(dynamic = false, dark = dark) {
                Surface(Modifier.fillMaxSize()) {
                    FilterSheet(index, filters, { filters = it }, {})
                }
            }
        }
        compose.onNodeWithText("No prerequisites").performScrollTo()
        compose.onNodeWithTag("filter-no-prerequisites").assertIsOff().performClick()
        compose.onNodeWithTag("filter-no-corequisites").assertIsOff().performClick()
        compose.onNodeWithTag("filter-no-prerequisites").assertIsOn()
        compose.onNodeWithTag("filter-no-corequisites").assertIsOn()
        compose.waitForIdle()
        save(draw(), "req-filters-light.png")
        dark = true
        compose.waitForIdle()
        compose.onNodeWithText("No prerequisites").performScrollTo()
        compose.waitForIdle()
        save(draw(), "req-filters-dark.png")
    }

    private fun draw(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val varied = bitmap.width * bitmap.height
        val pixels = IntArray(varied)
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
        for (dir in listOf(File("/tmp/req-filters"), File("/opt/cursor/artifacts/screenshots"))) {
            dir.mkdirs()
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
