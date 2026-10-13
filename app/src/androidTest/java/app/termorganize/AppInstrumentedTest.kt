// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Device test for the home disclaimer and catalog search. Robolectric covers the
// same flow in AppUiTest when no emulator is attached.
//
// UiAutomator is used instead of the Compose test rule. An indeterminate progress
// indicator and the catalog decode keep the main looper busy, so Espresso's idle
// check never succeeds on a slow emulator.

package app.termorganize

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppInstrumentedTest {
    @get:Rule
    val scenario = ActivityScenarioRule(MainActivity::class.java)

    @Test fun homeShowsTheDisclaimerAndFindsAlgorithms() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val disclaimer = device.wait(Until.findObject(By.textContains("Unofficial, not affiliated")), 180_000)
        assertNotNull(disclaimer)
        device.findObject(By.text("Wait"))?.click()
        val loaded = device.wait(Until.findObject(By.text("Refresh this term from Banner")), 480_000)
        if (loaded == null) {
            org.junit.Assert.fail("catalog did not finish loading. visible: ${visible(device)}")
        }
        val search = scrollTo(device, "Search the catalog")
        if (search == null) fail("search button missing. visible: ${visible(device)}")
        search.click()
        val field = device.wait(
            Until.findObject(By.textContains("Course, professor, CRN")),
            90_000,
        ) ?: device.findObject(By.res("app.termorganize:id/search-field"))
            ?: device.findObject(By.clazz("android.widget.EditText"))
        if (field == null) fail("search field missing. visible: ${visible(device)}")
        typeQuery(device, field)
        val hit = device.wait(Until.findObject(By.textContains("Algorithms")), 90_000)
        if (hit == null) fail("CS 3000 did not find Algorithms. visible: ${visible(device)}")
    }

    /** Compose draws its own field, so a label node's text is not the query. */
    private fun typeQuery(device: UiDevice, field: androidx.test.uiautomator.UiObject2) {
        field.click()
        val edit = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 4_000)
        if (edit != null) {
            edit.text = "CS 3000"
            return
        }
        device.executeShellCommand("input text CS%s3000")
    }

    private fun visible(device: UiDevice): List<String> =
        device.findObjects(By.enabled(true)).mapNotNull { it.text?.takeIf { text -> text.isNotBlank() } }.take(20)

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private fun scrollTo(device: UiDevice, text: String): androidx.test.uiautomator.UiObject2? {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            device.findObject(By.text("Wait"))?.click()
            val found = device.findObject(By.text(text))
            if (found != null) return found
            val width = device.displayWidth
            val height = device.displayHeight
            device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 30)
        }
        return device.findObject(By.text(text))
    }
}
