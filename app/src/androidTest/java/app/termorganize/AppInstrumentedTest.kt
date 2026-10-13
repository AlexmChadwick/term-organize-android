// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Device test for the home disclaimer and catalog search. Robolectric covers the
// same flow in AppUiTest when no emulator is attached.

package app.termorganize

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsTheDisclaimerAndFindsAlgorithms() {
        rule.waitUntil(timeoutMillis = 60_000) {
            rule.onAllNodes(hasText("—")).fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("Unofficial, not affiliated with Northeastern or Sandbox.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Search the catalog").performClick()
        rule.onNodeWithTag("search-field").performTextInput("CS 3000")
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodes(hasText("Algorithms", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
