// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import app.termorganize.core.banner.BannerClient
import app.termorganize.core.banner.FakeBannerClient
import app.termorganize.core.banner.FakeBannerRule
import app.termorganize.core.model.CourseKey
import app.termorganize.ui.AppModel
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the real TermOrganizeRoot on the bundled catalogs, so hoisted Schedule state is exercised where it lives. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TermOrganizeApp::class, qualifiers = "w411dp-h840dp-mdpi")
class ScheduleRootTest {
    companion object {
        @BeforeClass @JvmStatic fun fakeBanner() {
            BannerClient.factory = { FakeBannerClient() }
        }
        @AfterClass @JvmStatic fun resetBanner() {
            BannerClient.resetFactory()
        }
    }

    @get:Rule(order = 0)
    val fakeBannerRule = FakeBannerRule()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var model: AppModel

    @Before fun freshDatabase() {
        System.setProperty("termorganize.freshdb", "1")
        System.setProperty("termorganize.dbname", "schedule-root.db")
    }

    private fun hasNode(matcher: SemanticsMatcher) = rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun openSchedule() {
        rule.waitUntil(timeoutMillis = 90_000) { hasNode(hasText("Search the catalog")) && !hasNode(hasText("—")) }
        model = ViewModelProvider(rule.activity)[AppModel::class.java]
        model.addToPlan(CourseKey("CS", "3000"))
        model.addToPlan(CourseKey("AACE", "6000"), "35746")
        rule.onNodeWithText("Schedule").performClick()
        rule.waitUntil(timeoutMillis = 10_000) { hasNode(hasText("Generate schedules")) }
    }

    private fun generate() {
        rule.onNodeWithText("Generate schedules").performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 10_000) { hasNode(hasTestTag("schedule-option")) }
    }

    private fun options() = rule.onAllNodesWithTag("schedule-option").fetchSemanticsNodes().size

    @Test fun untimedRowBackReturnsToScheduleWithPlanStateKept() {
        openSchedule()
        rule.onNodeWithText("New plan").performTextInput("Kept draft")
        generate()
        val shown = options()
        rule.onNodeWithTag("untimed-row").performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 10_000) { hasNode(hasTestTag("back")) }
        rule.onNodeWithTag("back").performClick()
        rule.waitUntil(timeoutMillis = 10_000) { hasNode(hasText("Generate schedules")) }
        rule.onNode(hasText("Schedule") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Kept draft").assertExists()
        assertEquals(shown, options())
    }

    @Test fun generatedOptionsAreDroppedWhenThePlanChangesAndCannotWriteOldCrns() {
        openSchedule()
        generate()
        val plan = model.user.value.plans.single()
        val before = plan.selectedCRNs
        model.setFilters(plan.id, plan.filters.copy(hideFullSections = !plan.filters.hideFullSections))
        rule.waitForIdle()
        assertEquals(0, options())
        assertEquals(before, model.user.value.plans.single().selectedCRNs)
    }
}
