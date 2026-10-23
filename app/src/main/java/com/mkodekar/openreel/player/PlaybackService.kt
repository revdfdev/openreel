// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.player

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mkodekar.openreel.OpenReelApp

/**
 * Keeps audio playing when the player screen is backgrounded (background audio).
 * Wire a MediaSession here for lock-screen/notification transport controls.
 */
class PlaybackService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationCompat.Builder(this, OpenReelApp.CHANNEL_PLAYBACK)
            .setContentTitle("OpenReel")
            .setContentText("Playing in background")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null // TODO: MediaSession token

    private companion object { const val NOTIF_ID = 41 }
}
