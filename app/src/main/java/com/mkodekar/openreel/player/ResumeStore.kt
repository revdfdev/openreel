package com.mkodekar.openreel.player

import android.content.Context

/** Remembers playback position per media item so videos resume where you left off. */
class ResumeStore(context: Context) {

    private val prefs = context.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun save(key: String, positionMs: Long, durationMs: Long) {
        // Don't bother if we're within 5s of either end.
        if (positionMs < 5_000 || (durationMs > 0 && positionMs > durationMs - 5_000)) {
            prefs.edit().remove(key).apply()
            return
        }
        prefs.edit().putLong(key, positionMs).apply()
    }

    fun load(key: String): Long = prefs.getLong(key, 0L)

    fun clear(key: String) = prefs.edit().remove(key).apply()
}
