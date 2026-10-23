// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.torrent

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mkodekar.openreel.OpenReelApp
import org.libtorrent4j.TorrentHandle
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
    }

    /** Add a magnet and begin a sequential (streamable) download. */
    fun stream(magnet: String, onReady: (TorrentHandle) -> Unit) =
        engine.addMagnet(magnet, onReady)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
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
