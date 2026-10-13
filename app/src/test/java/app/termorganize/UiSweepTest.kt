// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = TermOrganizeApp::class, qualifiers = "w411dp-h840dp-mdpi")
class UiSweepPhoneTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun lightAndDark() = sweep(rule, "phone")
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = TermOrganizeApp::class, qualifiers = "w1280dp-h900dp-mdpi")
class UiSweepTabletTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun lightAndDark() = sweep(rule, "tablet")
}

private fun sweep(rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>, form: String) {
    step("catalog") {
        rule.waitUntil(timeoutMillis = 90_000) {
            rule.onAllNodes(hasText("—")).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodes(hasText("Search the catalog")).fetchSemanticsNodes().isNotEmpty()
        }
    }
    step("theme-light") {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Settings and legal").performClick()
        rule.onNode(hasTestTag("dynamic-color"), useUnmergedTree = true).performClick()
        rule.onNodeWithText("Light").performClick()
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "settings")
    scrollTo(rule, "Version 1.0")
    capture(rule, "$form-light", "settings-legal")
    step("home") {
        rule.onNodeWithText("Home").performClick()
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "home")
    step("search-empty") {
        rule.onNodeWithText("Search the catalog").performClick()
        rule.waitUntil(timeoutMillis = 10_000) { tagged(rule, "search-field") }
    }
    capture(rule, "$form-light", "search-empty")
    step("search-results") {
        replace(rule, "search-field", "CS 3000")
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Algorithms", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
    capture(rule, "$form-light", "search-results")
    step("filters") {
        rule.onNodeWithText("Filters").performClick()
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Subjects")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "search-filters")
    step("filters-done") {
        scrollTo(rule, "Done")
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
    }
    step("course") {
        rule.onNodeWithText("Algorithms", substring = true).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Prerequisites")).fetchSemanticsNodes().isNotEmpty() }
    }
    capture(rule, "$form-light", "course")
    scrollTo(rule, "Alert me if a seat opens")
    capture(rule, "$form-light", "course-sections")
    goBack(rule)
    step("search-none") {
        rule.waitUntil(timeoutMillis = 10_000) { tagged(rule, "search-field") }
        replace(rule, "search-field", "zzzznotacourse")
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "search-none")
    step("schedule-empty") {
        rule.onNodeWithText("Schedule").performClick()
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "schedule-empty")
    step("degree-empty") {
        rule.onNodeWithText("Degree").performClick()
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "degree-empty")
    step("more") {
        rule.onNodeWithText("More").performClick()
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "more")
    step("rooms") {
        rule.onNodeWithText("Free rooms").performClick()
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "rooms")
    if (tagged(rule, "room-row")) {
        step("room") {
            rule.onAllNodes(hasTestTag("room-row"), useUnmergedTree = true)[0].performClick()
            rule.waitForIdle()
        }
        capture(rule, "$form-light", "room")
        goBack(rule)
    }
    step("professors") {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Professors").performClick()
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "professors")
    if (tagged(rule, "professor-row")) {
        step("professor") {
            rule.onAllNodes(hasTestTag("professor-row"), useUnmergedTree = true)[0].performClick()
            rule.waitForIdle()
        }
        capture(rule, "$form-light", "professor")
        goBack(rule)
    }
    step("watch-empty") {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Seat alerts").performClick()
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "watch-empty")
    step("add-course") {
        rule.onNodeWithText("Search").performClick()
        rule.waitUntil(timeoutMillis = 10_000) { tagged(rule, "search-field") }
        replace(rule, "search-field", "CS 3000")
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Algorithms", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Algorithms", substring = true).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Add to schedule")).fetchSemanticsNodes().isNotEmpty() }
        scrollTo(rule, "Add to schedule")
        rule.onNodeWithText("Add to schedule").performClick()
        scrollTo(rule, "Alert me if a seat opens")
        rule.onAllNodesWithText("Alert me if a seat opens")[0].performClick()
        rule.waitForIdle()
    }
    step("schedule") {
        rule.onNodeWithText("Schedule").performClick()
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "schedule")
    step("generate") {
        scrollTo(rule, "Generate schedules")
        rule.onNodeWithText("Generate schedules").performClick()
        rule.waitForIdle()
        if (rule.onAllNodes(hasText("combinations", substring = true)).fetchSemanticsNodes().isNotEmpty()) {
            scrollTo(rule, "combinations")
        }
    }
    capture(rule, "$form-light", "schedule-generated")
    if (tagged(rule, "schedule-option")) {
        step("pick") {
            rule.onAllNodes(hasTestTag("schedule-option"), useUnmergedTree = true)[0].performClick()
            rule.waitForIdle()
            scrollTo(rule, "New plan")
        }
        capture(rule, "$form-light", "schedule-picked")
        rule.onNodeWithText("Home").performClick()
        rule.waitForIdle()
        capture(rule, "$form-light", "home-upcoming")
    }
    step("degree-plan") {
        rule.onNodeWithText("Degree").performClick()
        rule.onNodeWithText("New").performClick()
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "degree-plan")
    step("watch") {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Seat alerts").performClick()
        rule.waitForIdle()
    }
    capture(rule, "$form-light", "watch")
    step("theme-dark") {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Settings and legal").performClick()
        rule.onNodeWithText("Dark").performClick()
        rule.waitForIdle()
    }
    capture(rule, "$form-dark", "settings")
    rule.onNodeWithText("Home").performClick()
    rule.waitForIdle()
    capture(rule, "$form-dark", "home")
    rule.onNodeWithText("Search").performClick()
    rule.waitUntil(timeoutMillis = 10_000) { tagged(rule, "search-field") }
    capture(rule, "$form-dark", "search-empty")
    replace(rule, "search-field", "CS 3000")
    rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Algorithms", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    capture(rule, "$form-dark", "search-results")
    rule.onNodeWithText("Filters").performClick()
    rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Subjects")).fetchSemanticsNodes().isNotEmpty() }
    capture(rule, "$form-dark", "search-filters")
    scrollTo(rule, "Done")
    rule.onNodeWithText("Done").performClick()
    rule.onNodeWithText("Algorithms", substring = true).performClick()
    rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Prerequisites")).fetchSemanticsNodes().isNotEmpty() }
    capture(rule, "$form-dark", "course")
    rule.onNodeWithText("Schedule").performClick()
    rule.waitForIdle()
    capture(rule, "$form-dark", "schedule")
    rule.onNodeWithText("Degree").performClick()
    rule.waitForIdle()
    capture(rule, "$form-dark", "degree-plan")
    rule.onNodeWithText("More").performClick()
    capture(rule, "$form-dark", "more")
    rule.onNodeWithText("Free rooms").performClick()
    capture(rule, "$form-dark", "rooms")
    rule.onNodeWithText("More").performClick()
    rule.onNodeWithText("Professors").performClick()
    capture(rule, "$form-dark", "professors")
    rule.onNodeWithText("More").performClick()
    rule.onNodeWithText("Seat alerts").performClick()
    capture(rule, "$form-dark", "watch")
}

private fun tagged(rule: SemanticsNodeInteractionsProvider, tag: String): Boolean =
    rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

private fun replace(rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>, tag: String, value: String) {
    rule.onNode(hasTestTag(tag), useUnmergedTree = true).performTextReplacement(value)
}

private fun scrollTo(rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>, text: String) {
    val nodes = rule.onAllNodes(hasText(text, substring = true))
    check(nodes.fetchSemanticsNodes().isNotEmpty()) { "missing text $text" }
    nodes[0].performScrollTo()
}

private fun goBack(rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>) {
    rule.onNode(hasTestTag("back"), useUnmergedTree = true).performClick()
    rule.waitForIdle()
}

private fun step(name: String, block: () -> Unit) {
    try {
        block()
    } catch (error: Throwable) {
        throw AssertionError("sweep failed at $name: ${error.message}", error)
    }
}

private fun capture(
    rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    theme: String,
    name: String,
) {
    rule.waitForIdle()
    val activity = draw(rule.activity.window.decorView)
    val dialog = ShadowDialog.getLatestDialog()
    val bitmap = if (dialog != null && dialog.isShowing && dialog.window != null) {
        composite(activity, draw(dialog.window!!.decorView))
    } else {
        activity
    }
    val dir = File("/tmp/sweep")
    dir.mkdirs()
    File(dir, "$theme-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val varied = bitmap.width * bitmap.height
    val pixels = IntArray(varied)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val first = pixels.first()
    var different = 0
    for (pixel in pixels) {
        if (pixel != first) {
            different++
            if (different > 400) break
        }
    }
    check(different > 400) { "$theme-$name is blank" }
}

private fun draw(view: android.view.View): Bitmap {
    val width = view.width.coerceAtLeast(1)
    val height = view.height.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    return bitmap
}

private fun composite(base: Bitmap, overlay: Bitmap): Bitmap {
    val width = minOf(base.width, overlay.width)
    val height = minOf(base.height, overlay.height)
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val basePixels = IntArray(width * height)
    val overPixels = IntArray(width * height)
    base.getPixels(basePixels, 0, width, 0, 0, width, height)
    overlay.getPixels(overPixels, 0, width, 0, 0, width, height)
    for (index in overPixels.indices) {
        val pixel = overPixels[index]
        val alpha = pixel ushr 24
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        if (alpha < 16) continue
        basePixels[index] = if (red + green + blue < 24) blend(basePixels[index], 0xFF000000.toInt(), 0.32f) else pixel
    }
    out.setPixels(basePixels, 0, width, 0, 0, width, height)
    return out
}

private fun blend(bottom: Int, top: Int, amount: Float): Int {
    fun channel(shift: Int): Int {
        val from = (bottom shr shift) and 0xFF
        val to = (top shr shift) and 0xFF
        return (from + (to - from) * amount).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
