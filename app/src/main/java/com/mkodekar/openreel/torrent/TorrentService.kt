// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.torrent

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
        startForeground(NOTIF_ID, notification("Download service running"))

        // libtorrent has no push for status, so refresh the snapshot the UI
        // observes on a timer while the service is alive.
        ticker = android.os.Handler(android.os.Looper.getMainLooper())
        ticker?.post(object : Runnable {
            override fun run() {
                engine.refresh()
                ticker?.postDelayed(this, 1000)
            }
        })
    }

    /** Queue a magnet as an ordinary download. */
    fun add(magnet: String) = engine.add(magnet)

    /** Switch one download to sequential order so it can be streamed. */
    fun stream(id: String) = engine.setStreaming(id)

    override fun onBind(intent: Intent?): IBinder = binder

    private var ticker: android.os.Handler? = null

    override fun onDestroy() {
        ticker?.removeCallbacksAndMessages(null)
        ticker = null
        engine.stop()
        super.onDestroy()
    }

    private fun notification(text: String) =
        NotificationCompat.Builder(this, OpenReelApp.CHANNEL_TORRENT)
            .setContentTitle("OpenReel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    private companion object { const val NOTIF_ID = 42 }
}
