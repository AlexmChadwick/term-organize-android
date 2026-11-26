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
        assertTrue("Factory must produce FakeBannerClient under FakeBannerRule", client is FakeBannerClient)
    }

    @Test
    fun ruleRestoresFactoryAfterExecution() {
        val originalFactory = DebugBannerComposition.overrideProvider
        val customFactory: () -> BannerClient = { FakeBannerClient(emptyList()) }
        val customRule = FakeBannerRule(customFactory)
        val statement = customRule.apply(
            object : Statement() {
                override fun evaluate() {
                    assertTrue(appBannerProvider().create() is FakeBannerClient)
                    assertTrue(appBannerProvider() !== originalFactory)
                }
            },
            Description.EMPTY
        )
        statement.evaluate()
        assertSame(originalFactory, DebugBannerComposition.overrideProvider)
    }
}
