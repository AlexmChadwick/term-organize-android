// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TermOrganizeApp::class)
class AppUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsTheDisclaimerAndOpensSearch() {
        rule.waitUntil(timeoutMillis = 60_000) {
            rule.onAllNodes(hasText("—")).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodes(hasText("Search the catalog")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Unofficial, not affiliated with Northeastern or Sandbox.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Search the catalog").performClick()
        rule.onNodeWithTag("search-field").performTextInput("CS 3000")
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("CS 3000", substring = true)).fetchSemanticsNodes().size > 1
        }
        rule.onNodeWithText("Algorithms", substring = true).assertIsDisplayed()
    }
}
