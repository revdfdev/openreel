package com.mkodekar.openreel.share

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mkodekar.openreel.OpenReelApp
import java.io.File

/** Foreground service hosting the local ShareServer while a transfer is active. */
class ShareService : Service() {

    private var server: ShareServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val paths = intent?.getStringArrayListExtra(EXTRA_FILES).orEmpty().map(::File)
        server = ShareServer(PORT, paths).also { it.start() }

        val notif = NotificationCompat.Builder(this, OpenReelApp.CHANNEL_SHARE)
            .setContentTitle("OpenReel")
            .setContentText("Sharing ${paths.size} file(s) on the local network")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_FILES = "files"
        const val PORT = 52525
        private const val NOTIF_ID = 43
    }
}
