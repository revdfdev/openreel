package com.mkodekar.openreel.player

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import kotlin.math.abs

/**
 * The player screen (equivalent of MX Player's ActivityScreen).
 *
 * Gesture model (functional UX, re-implemented from scratch):
 *   - horizontal drag           -> seek
 *   - vertical drag, left half   -> screen brightness
 *   - vertical drag, right half  -> volume
 *   - single tap                 -> toggle controls
 *   - double tap                 -> play/pause
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var mpv: MpvCore
    private lateinit var surface: SurfaceView
    private lateinit var gestures: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surface = SurfaceView(this)
        setContentView(surface)

        mpv = MpvCore(this).apply {
            attach(surface)
            init()
        }

        // Accept content/http/smb/magnet URIs from the launch intent.
        intent?.dataString?.let { mpv.play(it) }

        gestures = GestureDetector(this, PlayerGestures())
        surface.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }
    }

    private inner class PlayerGestures : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            mpv.togglePause(); return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
        ): Boolean {
            if (e1 == null) return false
            val horizontal = abs(e2.x - e1.x) > abs(e2.y - e1.y)
            when {
                horizontal -> mpv.seekBy(if (dx < 0) 2 else -2)
                e1.x < surface.width / 2f -> adjustBrightness(dy)
                else -> adjustVolume(dy)
            }
            return true
        }
    }

    private fun adjustBrightness(dy: Float) {
        val lp = window.attributes
        val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
        lp.screenBrightness = (cur + dy / 1000f).coerceIn(0.01f, 1f)
        window.attributes = lp
    }

    private fun adjustVolume(dy: Float) {
        // TODO: AudioManager STREAM_MUSIC +/- based on dy
    }

    override fun onDestroy() {
        mpv.release()
        super.onDestroy()
    }
}
