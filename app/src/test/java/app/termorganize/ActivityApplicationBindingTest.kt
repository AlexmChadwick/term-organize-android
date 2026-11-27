// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import app.termorganize.ui.AppModel
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric builds a fresh Application per test while the process-wide ViewModel factory cache survives,
 * so the model must bind to the current application. The method order is fixed so the second activity
 * always runs after the first, which makes a regression to plain `by viewModels()` fail every time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ActivityApplicationBindingTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun a_firstApplicationBindsFirstActivityModel() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertSame(application, modelApplication())
        firstApplication = application
    }

    @Test
    fun b_secondApplicationBindsSecondActivityModel() {
        val first = firstApplication
        assertNotNull("a_ test must run first", first)
        val second = ApplicationProvider.getApplicationContext<Application>()
        assertNotSame(first, second)
        assertSame(second, modelApplication())
    }

    private fun modelApplication(): Application =
        compose.runOnIdle { ViewModelProvider(compose.activity)[AppModel::class.java] }.getApplication()

    private companion object {
        var firstApplication: Application? = null
    }
}
