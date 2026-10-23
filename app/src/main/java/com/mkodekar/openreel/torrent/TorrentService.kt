package com.mkodekar.openreel.torrent

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mkodekar.openreel.OpenReelApp
import java.io.File

/**
 * Foreground service that keeps the torrent session alive while downloading/seeding.
 * Bind the UI to this and read TorrentEngine progress; it survives the app going
 * to the background so stream-while-download keeps filling the cache.
 */
class TorrentService : Service() {

    lateinit var engine: TorrentEngine
        private set

    override fun onCreate() {
        super.onCreate()
        val dir = File(getExternalFilesDir(null), "torrents").apply { mkdirs() }
        engine = TorrentEngine(dir).also { it.start() }

        val notif = NotificationCompat.Builder(this, OpenReelApp.CHANNEL_TORRENT)
            .setContentTitle("OpenReel")
            .setContentText("Download service running")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null // TODO: LocalBinder for UI

    private companion object { const val NOTIF_ID = 42 }
}
