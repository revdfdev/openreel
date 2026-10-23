package com.mkodekar.openreel.ui

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Settings. Mirror MX Player's preference surface (all re-implemented):
 *   Player   -> resume, speed step, orientation/screen lock, double-tap seek amount
 *   Decoder  -> HW / HW+ / SW toggle (maps to mpv "hwdec")
 *   Audio    -> default track, audio delay step, boost
 *   Subtitle -> font size, colour, position, encoding, auto-load, delay step
 *   General  -> theme, language, file-extension associations
 *
 * Use AndroidX Preference or a Compose settings screen. Left as an entry point.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: build the five preference groups above.
    }
}
