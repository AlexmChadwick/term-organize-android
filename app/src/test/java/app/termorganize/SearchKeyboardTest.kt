// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import app.termorganize.ui.CourseResultList
import app.termorganize.ui.CourseResultRow
import app.termorganize.ui.TermOrganizeTheme
import app.termorganize.ui.movedResultIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class ResultArrowTests {
    @Test fun upAndDownMoveOneRowAndStopAtTheEnds() {
        assertEquals(1, movedResultIndex(Key.DirectionDown, 0, 3))
        assertEquals(2, movedResultIndex(Key.DirectionDown, 1, 3))
        assertEquals(2, movedResultIndex(Key.DirectionDown, 2, 3))
        assertEquals(1, movedResultIndex(Key.DirectionUp, 2, 3))
        assertEquals(0, movedResultIndex(Key.DirectionUp, 0, 3))
        assertNull(movedResultIndex(Key.DirectionLeft, 1, 3))
        assertNull(movedResultIndex(Key.DirectionRight, 1, 3))
        assertNull(movedResultIndex(Key.DirectionDown, 0, 0))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w1280dp-h800dp-mdpi")
class SearchKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test fun selectingARowUpdatesTheDetailAndTheSearchFieldKeepsArrows() {
        val rows = listOf(
            CourseResultRow("CS 1200", "First Year Seminar", "", "CS", "1200"),
            CourseResultRow("CS 3000", "Algorithms and Data", "", "CS", "3000"),
            CourseResultRow("CS 8982", "Readings", "", "CS", "8982"),
        )
        var selected by mutableIntStateOf(0)
        var text by mutableStateOf("")
        compose.setContent {
            TermOrganizeTheme(dynamic = false, dark = false) {
                Surface(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(0.42f).fillMaxHeight()) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier.fillMaxWidth().testTag("search-field"),
                                label = { Text("Search") },
                                singleLine = true,
                            )
                            CourseResultList(
                                rows = rows,
                                selected = selected,
                                onSelected = { selected = it },
                                listDetail = true,
                                onOpen = {},
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                        Text(rows[selected].title, modifier = Modifier.weight(0.58f).testTag("detail-title"))
                    }
                }
            }
        }
        compose.onNodeWithTag("detail-title").assertTextContains("First Year Seminar")
        compose.onNodeWithText("CS 3000").performClick()
        compose.waitForIdle()
        assertEquals(1, selected)
        compose.onNodeWithTag("detail-title").assertTextContains("Algorithms and Data")
        compose.onNodeWithTag("search-field").performClick()
        compose.onNodeWithTag("search-field").performTextInput("cs")
        compose.onNodeWithTag("search-field").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithTag("search-field").assertTextContains("cs")
        assertEquals(1, selected)
    }
}
