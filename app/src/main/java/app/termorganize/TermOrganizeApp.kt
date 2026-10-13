// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import android.app.Application
import androidx.work.Configuration
import app.termorganize.work.SeatCheckWorker

class TermOrganizeApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        SeatCheckWorker.schedule(this)
    }
}
