package com.mkodekar.openreel.settings

import android.content.Context

/**
 * App settings, backed by SharedPreferences. Mirrors MX Player's preference surface
 * (Player / Decoder / Audio / Subtitle / General), re-implemented.
 * Player code reads these; SettingsActivity writes them.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Decoder
    var hardwareDecoding: Boolean
        get() = prefs.getBoolean(HW_DECODE, true)
        set(v) = prefs.edit().putBoolean(HW_DECODE, v).apply()

    // Player
    var autoResume: Boolean
        get() = prefs.getBoolean(AUTO_RESUME, true)
        set(v) = prefs.edit().putBoolean(AUTO_RESUME, v).apply()

    var seekStepSeconds: Int
        get() = prefs.getInt(SEEK_STEP, 10)
        set(v) = prefs.edit().putInt(SEEK_STEP, v).apply()

    // Audio
    var audioDelayStepMs: Int
        get() = prefs.getInt(AUDIO_STEP, 50)
        set(v) = prefs.edit().putInt(AUDIO_STEP, v).apply()

    // Subtitle
    var subtitleFontScale: Int   // percent, 50..300
        get() = prefs.getInt(SUB_SCALE, 100)
        set(v) = prefs.edit().putInt(SUB_SCALE, v).apply()

    var autoLoadSubtitles: Boolean
        get() = prefs.getBoolean(SUB_AUTOLOAD, true)
        set(v) = prefs.edit().putBoolean(SUB_AUTOLOAD, v).apply()

    // General
    var theme: String            // "system" | "light" | "dark"
        get() = prefs.getString(THEME, "system") ?: "system"
        set(v) = prefs.edit().putString(THEME, v).apply()

    private companion object {
        const val HW_DECODE = "hw_decode"
        const val AUTO_RESUME = "auto_resume"
        const val SEEK_STEP = "seek_step"
        const val AUDIO_STEP = "audio_step"
        const val SUB_SCALE = "sub_scale"
        const val SUB_AUTOLOAD = "sub_autoload"
        const val THEME = "theme"
    }
}
