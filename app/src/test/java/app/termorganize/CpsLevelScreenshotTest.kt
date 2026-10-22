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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.termorganize.core.model.Course
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.CpsRestriction
import app.termorganize.core.model.RestrictionKind
import app.termorganize.core.model.SectionRestriction
import app.termorganize.core.model.Subject
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.logic.search.CatalogIndex
import app.termorganize.logic.search.CourseLevelFilter
import app.termorganize.logic.search.SearchFilters
import app.termorganize.ui.FilterSheet
import app.termorganize.ui.TermOrganizeTheme
import org.junit.Assert.assertEquals
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
class CpsLevelScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun lightAndDarkShowTheLevelControlAndCpsNote() {
        val course = Course("ITC", "1000", "Information Technology", minCredits = 4.0, maxCredits = 4.0, detailsLoaded = true)
        val section = CourseSection(
            crn = "34087",
            subject = "ITC",
            number = "1000",
            title = course.title,
            restrictions = listOf(
                SectionRestriction(RestrictionKind.MUST, "Levels", listOf("CPS - Undergraduate Semester (UC)")),
            ),
        )
        val index = CatalogIndex.build(
            TermCatalog(
                term = Term("202730", "Spring 2027 Semester"),
                courses = listOf(course),
                sections = listOf(section),
                subjects = listOf(Subject("ITC", "Information Technology")),
            ),
        )
        var filters by mutableStateOf(SearchFilters())
        var dark by mutableStateOf(false)
        compose.setContent {
            TermOrganizeTheme(dynamic = false, dark = dark) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("ITC 1000", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(course.title, style = MaterialTheme.typography.bodyLarge)
                            Text("CRN 34087", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                CpsRestriction.courseNote(listOf(section))!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilterSheet(index, filters, { filters = it }, {})
                    }
                }
            }
        }
        compose.onNodeWithText(CpsRestriction.SECTION_NOTE).assertExists()
        compose.onNodeWithTag("filter-level-undergrad").performClick()
        compose.waitForIdle()
        assertEquals(CourseLevelFilter.UNDERGRAD, filters.courseLevel)
        compose.onNodeWithTag("filter-level-all").performClick()
        compose.waitForIdle()
        assertEquals(CourseLevelFilter.ALL, filters.courseLevel)
        compose.onNodeWithTag("filter-level-undergrad").performClick()
        compose.waitForIdle()
        save(draw(), "cps-level-light.png")
        dark = true
        compose.waitForIdle()
        save(draw(), "cps-level-dark.png")
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
        for (dir in listOf(File("/tmp/cps-level"), File("/opt/cursor/artifacts/screenshots"))) {
            dir.mkdirs()
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
