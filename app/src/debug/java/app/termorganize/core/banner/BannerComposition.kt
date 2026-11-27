// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Banner registration client. Endpoint shape follows SearchNEU (AGPL-3.0). See NOTICE.md.

package app.termorganize.core.banner

internal object DebugBannerComposition {
    @Volatile var overrideProvider: BannerProvider? = null
}

internal fun appBannerProvider(): BannerProvider =
    DebugBannerComposition.overrideProvider ?: liveBannerProvider
