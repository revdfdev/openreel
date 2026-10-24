// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.torrent

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mkodekar.openreel.OpenReelApp
import java.io.File

/**
 * Foreground service that owns the torrent session. The player binds to it to
 * start a magnet and read progress, so downloading keeps running when the app is
 * backgrounded and the stream-while-download cache keeps filling.
 */
class TorrentService : Service() {

    inner class LocalBinder : Binder() {
        val service: TorrentService get() = this@TorrentService
    }

    private val binder = LocalBinder()
    lateinit var engine: TorrentEngine
        private set

    override fun onCreate() {
        super.onCreate()
        val dir = File(getExternalFilesDir(null), "torrents").apply { mkdirs() }
        engine = TorrentEngine(dir).also { it.start() }
        startForeground(
            TorrentNotifications.SUMMARY_ID,
            TorrentNotifications.summary(this, emptyList()),
        )

        // libtorrent has no push for status, so poll the snapshot the UI
        // observes. Deliberately NOT on the main looper: every entry crosses
        // into native code, and this used to run on the main thread.
        ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "torrent-status").apply { isDaemon = true }
        }.also { exec ->
            exec.scheduleWithFixedDelay(
                {
                    runCatching {
                        engine.refresh()
                        publishNotifications()
                    }
                },
                1, 1, java.util.concurrent.TimeUnit.SECONDS,
            )
        }
    }

    /** Queue a magnet as an ordinary download. */
    fun add(magnet: String) = engine.add(magnet)

    /** Switch one download to sequential order so it can be streamed. */
    fun stream(id: String) = engine.setStreaming(id)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(TorrentNotifications.EXTRA_ID)
        if (id != null) {
            when (intent.action) {
                TorrentNotifications.ACTION_PAUSE -> engine.pause(id)
                TorrentNotifications.ACTION_RESUME -> engine.resume(id)
                TorrentNotifications.ACTION_REMOVE -> engine.remove(id, deleteFiles = false)
            }
            engine.refresh()
            publishNotifications()
        }
        // Downloads should survive the process being reclaimed.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private var ticker: java.util.concurrent.ScheduledExecutorService? = null

    override fun onDestroy() {
        ticker?.shutdownNow()
        ticker = null
        engine.stop()
        super.onDestroy()
    }

    /** Per-torrent notifications, in the shape LibreTorrent uses. */
    private fun publishNotifications() {
        val items = engine.items.value
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.notify(TorrentNotifications.SUMMARY_ID, TorrentNotifications.summary(this, items))
            TorrentNotifications.publish(this, items, publishedIds)
        }
    }

    /** Notification ids we have published, so stale ones can be cleared. */
    private val publishedIds = mutableSetOf<Int>()

}
