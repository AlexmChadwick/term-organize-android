// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.core.banner

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * JUnit rule that installs a FakeBannerClient factory for the duration of a test or test suite
 * and restores the previous factory when finished.
 */
class FakeBannerRule(
    private val supplier: () -> BannerClient = { FakeBannerClient() }
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val previous = BannerClient.factory
            BannerClient.factory = supplier
            try {
                base.evaluate()
            } finally {
                BannerClient.factory = previous
            }
        }
    }
}
