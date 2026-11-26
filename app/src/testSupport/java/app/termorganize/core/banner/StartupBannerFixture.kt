// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.core.banner

import app.termorganize.core.model.Term
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StartupBannerFixture : TestRule {
    private val fetchedTerms = CountDownLatch(1)
    val termWasFetched: Boolean get() = fetchedTerms.count == 0L

    fun awaitTermFetch(): Boolean = fetchedTerms.await(30, TimeUnit.SECONDS)

    override fun apply(base: Statement, description: Description): Statement = base
}
