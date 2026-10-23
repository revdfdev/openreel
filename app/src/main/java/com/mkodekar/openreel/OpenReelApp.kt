package com.mkodekar.openreel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class OpenReelApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, "Playback", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TORRENT, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SHARE, "Share", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_PLAYBACK = "playback"
        const val CHANNEL_TORRENT = "torrent"
        const val CHANNEL_SHARE = "share"
    }
}
