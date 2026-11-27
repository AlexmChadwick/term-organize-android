// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsOn
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
import app.termorganize.core.banner.FakeBannerRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = TermOrganizeApp::class, qualifiers = "w411dp-h840dp-mdpi")
class UiSweepPhoneTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rule: TestRule = RuleChain.outerRule(FakeBannerRule())
        .around(FreshDatabase("sweep-phone.db"))
        .around(compose)

    @Test fun lightAndDark() = sweep(compose, "phone")
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = TermOrganizeApp::class, qualifiers = "w1280dp-h900dp-mdpi")
class UiSweepTabletTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rule: TestRule = RuleChain.outerRule(FakeBannerRule())
        .around(FreshDatabase("sweep-tablet.db"))
        .around(compose)

    @Test fun lightAndDark() = sweep(compose, "tablet")
}

private class FreshDatabase(private val name: String) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val previousFreshDb = System.setProperty("termorganize.freshdb", "1")
            val previousDbName = System.setProperty("termorganize.dbname", name)
            try {
                base.evaluate()
            } finally {
                restoreProperty("termorganize.freshdb", previousFreshDb)
                restoreProperty("termorganize.dbname", previousDbName)
            }
        }
    }

    private fun restoreProperty(key: String, value: String?) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }
}

private fun sweep(rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>, form: String) =
    withSweepOutputDirectory { outputDir ->
        println("Sweep screenshots: ${outputDir.absolutePath}")
        runSweep(rule, form, outputDir)
    }

private fun runSweep(
    rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    form: String,
    outputDir: File,
) {
    fun capture(theme: String, name: String) = captureScreenshot(rule, outputDir, theme, name)

    step("catalog") {
        rule.waitUntil(timeoutMillis = 90_000) {
            rule.onAllNodes(hasText("—")).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodes(hasText("Search the catalog")).fetchSemanticsNodes().isNotEmpty()
        }
    }
    step("theme-light") {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Settings and legal").performClick()
        val dynamic = rule.onNode(hasTestTag("dynamic-color"), useUnmergedTree = true)
        val dynamicOn = runCatching { dynamic.assertIsOn() }.isSuccess
        if (dynamicOn) dynamic.performClick()
        rule.onNodeWithText("Light").performClick()
        rule.waitForIdle()
    }
    capture("$form-light", "settings")
    scrollTo(rule, "Version 1.1.0")
    capture("$form-light", "settings-legal")
    step("home") {
        rule.onNodeWithText("Home").performClick()
        rule.waitForIdle()
    }
    capture("$form-light", "home")
    step("search-empty") {
        rule.onNodeWithText("Search the catalog").performClick()
        rule.waitUntil(timeoutMillis = 10_000) { tagged(rule, "search-field") }
    }
    capture("$form-light", "search-empty")
    step("search-results") {
        replace(rule, "search-field", "CS 3000")
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Algorithms", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
    capture("$form-light", "search-results")
    step("filters") {
        rule.onNodeWithText("Filters").performClick()
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Subjects")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }
    capture("$form-light", "search-filters")
    step("filters-done") {
        scrollTo(rule, "Done")
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
    }
    step("course") {
        openCourseResult(rule)
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Prerequisites")).fetchSemanticsNodes().isNotEmpty() }
    }
    capture("$form-light", "course")
    scrollTo(rule, "Alert me if a seat opens")
    capture("$form-light", "course-sections")
    goBack(rule)
    capture("$form-light", "after-back")
    step("search-none") {
        if (!tagged(rule, "search-field")) rule.onNodeWithText("Search").performClick()
        rule.waitUntil(timeoutMillis = 10_000) { tagged(rule, "search-field") }
        replace(rule, "search-field", "zzzznotacourse")
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("No courses match", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }
    capture("$form-light", "search-none")
    step("schedule-empty") {
        rule.onNodeWithText("Schedule").performClick()
        rule.waitForIdle()
    }
    capture("$form-light", "schedule-empty")
    step("degree-empty") {
        rule.onNodeWithText("Degree").performClick()
        rule.waitForIdle()
    }
    capture("$form-light", "degree-empty")
    step("more") {
        rule.onNodeWithText("More").performClick()
        rule.waitForIdle()
    }
    capture("$form-light", "more")
    step("rooms") {
        rule.onNodeWithText("Free rooms").performClick()
        rule.waitForIdle()
    }
    capture("$form-light", "rooms")
    if (tagged(rule, "room-row")) {
        step("room") {
            rule.onAllNodes(hasTestTag("room-row"), useUnmergedTree = true)[0].performClick()
            rule.waitForIdle()
        }
        capture("$form-light", "room")
        goBack(rule)
    }
    step("professors") {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Professors").performClick()
        rule.waitForIdle()
    }
    capture("$form-light", "professors")
    if (tagged(rule, "professor-row")) {
        step("professor") {
            rule.onAllNodes(hasTestTag("professor-row"), useUnmergedTree = true)[0].performClick()
            rule.waitForIdle()
        }
        capture("$form-light", "professor")
        goBack(rule)
    }
    step("watch-empty") {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Seat alerts").performClick()
        rule.waitForIdle()
    }
    capture("$form-light", "watch-empty")
    step("theme-dark-empty") {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Settings and legal").performClick()
        rule.onNodeWithText("Dark").performClick()
        rule.waitForIdle()
    }
    rule.onNodeWithText("Home").performClick()
    rule.waitForIdle()
    capture("$form-dark", "home")
    rule.onNodeWithText("Schedule").performClick()
    capture("$form-dark", "schedule-empty")
    rule.onNodeWithText("Degree").performClick()
    capture("$form-dark", "degree-empty")
    rule.onNodeWithText("More").performClick()
    rule.onNodeWithText("Seat alerts").performClick()
    capture("$form-dark", "watch-empty")
    step("theme-light-again") {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Settings and legal").performClick()
        rule.onNodeWithText("Light").performClick()
        rule.waitForIdle()
    }
    step("add-course") {
        rule.onNodeWithText("Search").performClick()
        rule.waitUntil(timeoutMillis = 10_000) { tagged(rule, "search-field") }
        replace(rule, "search-field", "CS 3000")
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Algorithms", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        openCourseResult(rule)
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
    capture("$form-light", "schedule")
    step("generate") {
        scrollTo(rule, "Generate schedules")
        rule.onNodeWithText("Generate schedules").performClick()
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("Option 1")).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("Nothing fit", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodes(hasText("Option 1")).fetchSemanticsNodes().isNotEmpty()) scrollTo(rule, "Option 1")
    }
    capture("$form-light", "schedule-generated")
    if (tagged(rule, "schedule-option")) {
        step("pick") {
            scrollTo(rule, "Option 1")
            rule.onNodeWithText("Option 1").performClick()
            rule.waitForIdle()
            scrollTo(rule, "New plan")
        }
        capture("$form-light", "schedule-picked")
        rule.onNodeWithText("Home").performClick()
        rule.waitForIdle()
        capture("$form-light", "home-upcoming")
    }
    step("degree-plan") {
        rule.onNodeWithText("Degree").performClick()
        rule.onNodeWithText("New").performClick()
        rule.waitForIdle()
    }
    capture("$form-light", "degree-plan")
    step("watch") {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Seat alerts").performClick()
        rule.waitForIdle()
    }
    capture("$form-light", "watch")
    step("theme-dark") {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Settings and legal").performClick()
        rule.onNodeWithText("Dark").performClick()
        rule.waitForIdle()
    }
    capture("$form-dark", "settings")
    scrollTo(rule, "Version 1.1.0")
    capture("$form-dark", "settings-legal")
    rule.onNodeWithText("Home").performClick()
    rule.waitForIdle()
    capture("$form-dark", "home-upcoming")
    rule.onNodeWithText("Search").performClick()
    rule.waitUntil(timeoutMillis = 10_000) { tagged(rule, "search-field") }
    capture("$form-dark", "search-empty")
    replace(rule, "search-field", "zzzznotacourse")
    rule.waitUntil(timeoutMillis = 10_000) {
        rule.onAllNodes(hasText("No courses match", substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    capture("$form-dark", "search-none")
    replace(rule, "search-field", "CS 3000")
    rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Algorithms", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    capture("$form-dark", "search-results")
    rule.onNodeWithText("Filters").performClick()
    rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Subjects")).fetchSemanticsNodes().isNotEmpty() }
    capture("$form-dark", "search-filters")
    scrollTo(rule, "Done")
    rule.onNodeWithText("Done").performClick()
    openCourseResult(rule)
    rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText("Prerequisites")).fetchSemanticsNodes().isNotEmpty() }
    capture("$form-dark", "course")
    scrollTo(rule, "Alert me if a seat opens")
    capture("$form-dark", "course-sections")
    goBack(rule)
    rule.onNodeWithText("Schedule").performClick()
    rule.waitForIdle()
    capture("$form-dark", "schedule")
    rule.onNodeWithText("Degree").performClick()
    rule.waitForIdle()
    capture("$form-dark", "degree-plan")
    rule.onNodeWithText("More").performClick()
    capture("$form-dark", "more")
    rule.onNodeWithText("Free rooms").performClick()
    capture("$form-dark", "rooms")
    if (tagged(rule, "room-row")) {
        rule.onAllNodes(hasTestTag("room-row"), useUnmergedTree = true)[0].performClick()
        rule.waitForIdle()
        capture("$form-dark", "room")
        goBack(rule)
    }
    rule.onNodeWithText("More").performClick()
    rule.onNodeWithText("Professors").performClick()
    capture("$form-dark", "professors")
    if (tagged(rule, "professor-row")) {
        rule.onAllNodes(hasTestTag("professor-row"), useUnmergedTree = true)[0].performClick()
        rule.waitForIdle()
        capture("$form-dark", "professor")
        goBack(rule)
    }
    rule.onNodeWithText("More").performClick()
    rule.onNodeWithText("Seat alerts").performClick()
    capture("$form-dark", "watch")
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

private fun openCourseResult(rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>) {
    val results = rule.onAllNodes(hasTestTag("search-result-0"), useUnmergedTree = true)
    if (results.fetchSemanticsNodes().isNotEmpty()) results[0].performClick()
    else rule.onNodeWithText("Algorithms", substring = true).performClick()
}

private fun goBack(rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>) {
    if (!tagged(rule, "back")) return
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

internal const val SCREENSHOT_DIR_PROPERTY = "termorganize.screenshot.dir"

internal fun createSweepOutputDirectory(): File {
    val root = File(System.getProperty(SCREENSHOT_DIR_PROPERTY) ?: System.getProperty("java.io.tmpdir"))
    Files.createDirectories(root.toPath())
    return Files.createTempDirectory(root.toPath(), "sweep-").toFile()
}

/** Screenshots are kept only when the output property asks for them; otherwise the temp dir is removed. */
internal fun <T> withSweepOutputDirectory(block: (File) -> T): T {
    val outputDir = createSweepOutputDirectory()
    try {
        return block(outputDir)
    } finally {
        if (System.getProperty(SCREENSHOT_DIR_PROPERTY) == null) outputDir.deleteRecursively()
    }
}

private fun captureScreenshot(
    rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    outputDir: File,
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
    File(outputDir, "$theme-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
