// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.torrent

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.mkodekar.openreel.OpenReelApp
import com.mkodekar.openreel.ui.MainActivity
import kotlin.math.abs

/**
 * Download notifications in the shape LibreTorrent uses: one entry per
 * torrent, each with its own progress bar, speed and ETA, and Pause/Resume and
 * Remove actions, all collapsed under a group summary.
 *
 * The single "Download service running" line this replaces said nothing about
 * what was happening, which is useless for something that runs for an hour in
 * the background.
 */
object TorrentNotifications {

    /** Foreground notification id; the per-torrent ones are derived from hashes. */
    const val SUMMARY_ID = 42
    private const val GROUP = "openreel.torrents"

    const val ACTION_PAUSE = "com.mkodekar.openreel.torrent.PAUSE"
    const val ACTION_RESUME = "com.mkodekar.openreel.torrent.RESUME"
    const val ACTION_REMOVE = "com.mkodekar.openreel.torrent.REMOVE"
    const val EXTRA_ID = "torrent_id"

    /** Stable, non-negative notification id for a torrent. */
    private fun idFor(torrentId: String) = 1000 + abs(torrentId.hashCode() % 100_000)

    fun summary(context: Context, items: List<TorrentEngine.Item>): Notification {
        // Count the three states separately: lumping "not active" together
        // reported finished downloads as paused.
        val downloading = items.count { !it.finished && !it.paused }
        val paused = items.count { !it.finished && it.paused }
        val done = items.count { it.finished }
        val text = when {
            items.isEmpty() -> "No downloads"
            downloading > 0 -> buildString {
                append(if (downloading == 1) "1 downloading" else "$downloading downloading")
                if (paused > 0) append(", $paused paused")
                if (done > 0) append(", $done done")
            }
            paused > 0 -> if (paused == 1) "1 paused" else "$paused paused"
            done == 1 -> "Download complete"
            else -> "$done downloads complete"
        }
        return base(context)
            .setContentTitle("OpenReel")
            .setContentText(text)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setContentIntent(openApp(context))
            .build()
    }

    /** Publishes one notification per torrent and clears ones that went away. */
    fun publish(context: Context, items: List<TorrentEngine.Item>, known: MutableSet<Int>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val live = mutableSetOf<Int>()

        items.forEach { item ->
            val nid = idFor(item.id)
            live += nid
            runCatching { nm.notify(nid, forItem(context, item)) }
        }
        // Anything we published before that is no longer present.
        (known - live).forEach { runCatching { nm.cancel(it) } }
        known.clear()
        known += live
    }

    private fun forItem(context: Context, item: TorrentEngine.Item): Notification {
        val pct = (item.progress * 100).toInt()
        val b = base(context)
            .setContentTitle(item.name)
            .setContentText(line(item))
            .setSubText(if (item.totalBytes > 0) bytes(item.totalBytes) else null)
            .setGroup(GROUP)
            .setContentIntent(openApp(context))

        if (item.finished) {
            b.setProgress(0, 0, false)
                .setOngoing(false)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
        } else {
            // Indeterminate until metadata lands and a real percentage exists.
            b.setProgress(100, pct, !item.hasMetadata)
                .setOngoing(!item.paused)
            b.addAction(
                if (item.paused) android.R.drawable.ic_media_play
                else android.R.drawable.ic_media_pause,
                if (item.paused) "Resume" else "Pause",
                serviceAction(
                    context,
                    if (item.paused) ACTION_RESUME else ACTION_PAUSE,
                    item.id,
                ),
            )
        }
        b.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Remove",
            serviceAction(context, ACTION_REMOVE, item.id),
        )
        return b.build()
    }

    /** "45% · 120 MB of 263 MB · 4.1 MB/s · 12m left" */
    private fun line(item: TorrentEngine.Item): String {
        if (!item.hasMetadata) return "Fetching metadata…"
        if (item.finished) return "Completed"
        if (item.paused) return "Paused · ${(item.progress * 100).toInt()}%"
        val parts = mutableListOf("${(item.progress * 100).toInt()}%")
        if (item.totalBytes > 0) {
            parts += "${bytes(item.doneBytes)} of ${bytes(item.totalBytes)}"
        }
        if (item.downloadRate > 0) {
            parts += "${bytes(item.downloadRate.toLong())}/s"
            val remaining = item.totalBytes - item.doneBytes
            if (remaining > 0) parts += "${eta(remaining / item.downloadRate)} left"
        }
        return parts.joinToString("  ·  ")
    }

    private fun eta(seconds: Long): String = when {
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m"
        else -> "${seconds}s"
    }

    private fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> "%.1f MB".format(b.toDouble() / (1L shl 20))
        b > 0 -> "%.0f KB".format(b.toDouble() / (1L shl 10))
        else -> "0"
    }

    private fun base(context: Context) =
        NotificationCompat.Builder(context, OpenReelApp.CHANNEL_TORRENT)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)       // progress updates must not buzz
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra("section", "TORRENT")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun serviceAction(context: Context, action: String, torrentId: String): PendingIntent {
        val intent = Intent(context, TorrentService::class.java)
            .setAction(action)
            .putExtra(EXTRA_ID, torrentId)
        // Distinct request codes, or the extras of one action overwrite another.
        val request = (action + torrentId).hashCode()
        return PendingIntent.getService(
            context,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
