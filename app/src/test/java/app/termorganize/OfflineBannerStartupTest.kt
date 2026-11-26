// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import app.termorganize.core.banner.BannerClient
import app.termorganize.core.banner.appBannerProvider
import app.termorganize.core.model.Term
import app.termorganize.data.CatalogStore
import app.termorganize.ui.AppModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineBannerStartupTest {
    private val cachedTerm = Term("209910", "Cached test term")

    @get:Rule(order = 0)
    val seedLocalTerms = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                CatalogStore(File(context.filesDir, "catalogs")).saveRemoteTerms(listOf(cachedTerm))
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun startupWithoutFakeBannerRuleUsesLocalTerms() {
        val model = compose.runOnIdle { ViewModelProvider(compose.activity)[AppModel::class.java] }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.runOnIdle { model.error.value != null || model.otherTerms.value.isNotEmpty() }
        }
        assertNull(model.status.value, model.error.value)

        assertEquals(listOf(cachedTerm), model.otherTerms.value)
        assertThrows(IllegalStateException::class.java) { appBannerProvider().create() }
        assertThrows(IllegalStateException::class.java) { runBlocking { BannerClient().fetchTerms() } }
    }
}
