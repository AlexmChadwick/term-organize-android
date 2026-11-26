// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.core.banner

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

class FakeBannerRuleTest {
    @get:Rule
    val fakeBannerRule = FakeBannerRule()

    @Test
    fun ruleInstallsFakeBannerClient() {
        val client = appBannerProvider().create()
        assertTrue("Provider must produce FakeBannerClient under FakeBannerRule", client is FakeBannerClient)
    }

    @Test
    fun ruleRestoresProviderAfterExecution() {
        val originalProvider = DebugBannerComposition.overrideProvider
        val customClient = FakeBannerClient(emptyList())
        val customRule = FakeBannerRule { customClient }
        val statement = customRule.apply(
            object : Statement() {
                override fun evaluate() {
                    assertSame(customClient, appBannerProvider().create())
                }
            },
            Description.EMPTY
        )
        statement.evaluate()
        assertSame(originalProvider, DebugBannerComposition.overrideProvider)
    }
}
