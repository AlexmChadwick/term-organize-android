// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.termorganize.core.banner.StartupBannerFixture
import app.termorganize.ui.AppModel
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StartupBannerFixtureTest {
    private val banner = StartupBannerFixture()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val launch = RuleChain.outerRule(banner).around(compose)

    @Test
    fun startupUsesTheDeviceTestFixture() {
        val model = compose.runOnIdle { ViewModelProvider(compose.activity)[AppModel::class.java] }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.runOnIdle { model.error.value != null || model.availableTerms.value.size > 1 }
        }
        assertNull(model.status.value, model.error.value)
        assertTrue("Startup must fetch terms through the fixture", banner.termWasFetched)
    }
}
