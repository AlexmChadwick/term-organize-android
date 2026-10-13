// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.termorganize.R
import app.termorganize.core.banner.BannerClient
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.CourseSection
import app.termorganize.data.AppDatabase
import app.termorganize.data.UserRow
import app.termorganize.logic.degree.UserJson
import app.termorganize.logic.watchlist.WatchlistComparison
import java.util.concurrent.TimeUnit

class SeatCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = AppDatabase.create(applicationContext).userDao()
        val stored = dao.get() ?: return Result.success()
        val data = UserJson.decode(stored.json)
        if (!data.preferences.backgroundRefreshEnabled || data.watchlist.isEmpty()) return Result.success()
        val live = mutableMapOf<String, MutableMap<CourseKey, MutableList<CourseSection>>>()
        val client = BannerClient()
        try {
            for (item in data.watchlist.distinctBy { it.termCode to it.courseKey }) {
                val sections = runCatching { client.refreshCourse(item.termCode, item.courseKey, emptyList()) }.getOrDefault(emptyList())
                if (sections.isEmpty()) continue
                val byCourse = live.getOrPut(item.termCode) { mutableMapOf() }
                byCourse.getOrPut(item.courseKey) { mutableListOf() }.addAll(sections)
            }
        } finally {
            client.close()
        }
        if (live.isEmpty()) return Result.retry()
        val outcome = WatchlistComparison.compare(data.watchlist, live.mapValues { it.value.mapValues { e -> e.value } }, System.currentTimeMillis())
        dao.upsert(UserRow(json = UserJson.encode(data.copy(watchlist = outcome.items))))
        if (outcome.alerts.isNotEmpty()) notify(outcome.alerts.first().title, outcome.alerts.first().body)
        return Result.success()
    }

    private fun notify(title: String, body: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channelId = "seat-alerts"
        manager.createNotificationChannel(NotificationChannel(channelId, "Seat alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_stat_schedule)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        manager.notify(title.hashCode(), notification)
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SeatCheckWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("seat-alerts", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
