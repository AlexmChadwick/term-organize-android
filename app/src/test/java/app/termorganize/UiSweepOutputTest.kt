// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UiSweepOutputTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun sweepsKeepIndependentFilesUnderTheConfiguredRoot() {
        val root = temp.root.resolve("screenshots")
        val key = "termorganize.screenshot.dir"
        val previous = System.setProperty(key, root.path)
        try {
            val first = createSweepOutputDirectory()
            val second = createSweepOutputDirectory()
            assertEquals(root, first.parentFile)
            assertEquals(root, second.parentFile)
            assertNotEquals(first, second)
            first.resolve("phone-light-home.png").writeText("first")
            second.resolve("phone-light-home.png").writeText("second")
            assertEquals("first", first.resolve("phone-light-home.png").readText())
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}
