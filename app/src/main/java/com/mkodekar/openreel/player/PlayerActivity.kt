// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mkodekar.openreel.settings.Settings
import com.mkodekar.openreel.torrent.TorrentService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The player screen (equivalent of MX Player's ActivityScreen).
 *
 * Handles three kinds of input URI:
 *   - content:// or file://  -> play directly
 *   - http/smb/rtsp/...      -> libmpv streams it (module 2)
 *   - magnet:                -> torrent handoff (module 3): start the download,
 *                               wait for the header to buffer, then play the file
 *                               off disk while it keeps downloading.
 *
 * Gestures: horizontal = seek, vertical-left = brightness, vertical-right = volume,
 * single tap = toggle controls, double tap = play/pause.
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var mpv: MpvCore
    private lateinit var surface: SurfaceView
    private lateinit var controls: PlayerControls
    private lateinit var gestures: GestureDetector
    private lateinit var resume: ResumeStore
    private lateinit var settings: Settings
    private lateinit var audio: AudioManager

    private var mediaKey: String = ""

    // --- torrent service binding (only used for magnet URIs) ---
    private var torrent: TorrentService? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            torrent = (b as TorrentService.LocalBinder).service
            pendingMagnet?.let { beginTorrentStream(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { torrent = null }
    }
    private var pendingMagnet: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resume = ResumeStore(this)
        settings = Settings(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager

        surface = SurfaceView(this)
        mpv = MpvCore(this).apply {
            attach(surface)
            init()
            setHardwareDecoding(settings.hardwareDecoding)
        }
        controls = PlayerControls(this, mpv, onTracks = { showTrackMenu() }, onOptions = { showOptionsMenu() })

        val root = FrameLayout(this).apply {
            addView(surface, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)

        gestures = GestureDetector(this, PlayerGestures())
        surface.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }

        handleUri(intent?.dataString)
    }

    private fun handleUri(uri: String?) {
        if (uri == null) { finish(); return }
        mediaKey = uri
        if (uri.startsWith("magnet:", ignoreCase = true)) {
            pendingMagnet = uri
            Intent(this, TorrentService::class.java).also {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(it)
                } else {
                    startService(it)
                }
                bindService(it, conn, Context.BIND_AUTO_CREATE)
            }
        } else {
            startPlayback(uri)
        }
    }

    private fun beginTorrentStream(magnet: String) {
        val svc = torrent ?: return
        Toast.makeText(this, "Fetching torrentâ€¦", Toast.LENGTH_SHORT).show()
        svc.stream(magnet) { /* handle added; polling below drives the rest */ }

        lifecycleScope.launch {
            val engine = svc.engine
            // Wait for metadata + a buffered header, then start playing off disk.
            while (isActive && !(engine.hasMetadata() && engine.readyToStartPlayback())) {
                delay(500)
            }
            val file = engine.primaryVideoPath() ?: return@launch
            startPlayback("file://${file.absolutePath}")

            // Keep racing pieces ahead of the playhead as it moves.
            while (isActive) {
                engine.prioritizeAround(fileIndex = 0, playedFraction = mpv.playedFraction())
                delay(2000)
            }
        }
    }

    // --- Options: playback speed + sleep timer (one menu, no screen clutter) -
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    private fun showOptionsMenu() {
        android.app.AlertDialog.Builder(this)
            .setItems(arrayOf("Playback speed", "Sleep timer")) { _, which ->
                if (which == 0) showSpeedMenu() else showSleepMenu()
            }
            .show()
    }

    private fun showSpeedMenu() {
        val speeds = doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, i -> mpv.setSpeed(speeds[i]) }
            .show()
    }

    private fun showSleepMenu() {
        val mins = intArrayOf(0, 15, 30, 60, 90)
        val labels = mins.map { if (it == 0) "Off" else "$it min" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(labels) { _, i -> setSleepTimer(mins[i]) }
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        if (minutes <= 0) {
            Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show(); return
        }
        sleepRunnable = Runnable {
            if (!mpv.paused) mpv.togglePause()
            Toast.makeText(this, "Paused by sleep timer", Toast.LENGTH_LONG).show()
        }.also { sleepHandler.postDelayed(it, minutes * 60_000L) }
        Toast.makeText(this, "Sleeping in $minutes min", Toast.LENGTH_SHORT).show()
    }

    // --- Picture-in-Picture (auto when you leave the player; zero UI) --------
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !mpv.paused) {
            runCatching {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder().build()
                )
            }
        }
    }

    // --- Audio / subtitle tracks (kept to one simple menu) ------------------
    private val pickSubtitle =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { mpv.addSubtitle(it.toString()) }
        }

    private fun showTrackMenu() {
        val options = arrayOf("Subtitles", "Audio", "Add subtitle fileâ€¦")
        android.app.AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> chooseTrack("Subtitles", mpv.subtitleTracks(), allowOff = true) { id ->
                        if (id == null) mpv.disableSubtitle() else mpv.selectSubtitle(id)
                    }
                    1 -> chooseTrack("Audio", mpv.audioTracks(), allowOff = false) { id ->
                        id?.let { mpv.selectAudio(it) }
                    }
                    2 -> pickSubtitle.launch(arrayOf("*/*"))
                }
            }
            .show()
    }

    private fun chooseTrack(
        title: String, tracks: List<MpvCore.Track>, allowOff: Boolean, onPick: (Int?) -> Unit,
    ) {
        val labels = buildList {
            if (allowOff) add("Off")
            addAll(tracks.map { t -> listOfNotNull(t.title.ifBlank { "Track ${t.id}" }, t.lang).joinToString(" Â· ") })
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, "No $title tracks", Toast.LENGTH_SHORT).show(); return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, i ->
                val idx = if (allowOff) i - 1 else i
                onPick(if (idx < 0) null else tracks[idx].id)
            }
            .show()
    }

    private fun startPlayback(uri: String) {
        mpv.play(uri)
        if (settings.autoResume) {
            val at = resume.load(mediaKey)
            if (at > 0) mpv.seekTo(at)
        }
    }

    private inner class PlayerGestures : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            controls.toggleVisibility(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            mpv.togglePause(); return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e1 == null) return false
            val horizontal = abs(e2.x - e1.x) > abs(e2.y - e1.y)
            when {
                horizontal -> mpv.seekBy(if (dx < 0) settings.seekStepSeconds else -settings.seekStepSeconds)
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
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = if (dy > 0) 1 else -1
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (cur + step).coerceIn(0, max),
            0
        )
    }

    override fun onPause() {
        super.onPause()
        resume.save(mediaKey, mpv.positionMs, mpv.durationMs)
    }

    override fun onPictureInPictureModeChanged(
        isInPip: Boolean, newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        controls.visibility = if (isInPip) android.view.View.GONE else android.view.View.VISIBLE
    }

    override fun onDestroy() {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        if (pendingMagnet != null) runCatching { unbindService(conn) }
        mpv.release()
        super.onDestroy()
    }
}
