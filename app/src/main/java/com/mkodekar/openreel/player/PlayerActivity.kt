// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.player

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.mkodekar.openreel.settings.Settings
import com.mkodekar.openreel.torrent.TorrentService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The player screen (MX Player's ActivityScreen).
 *
 * Input URIs:
 *   content:// or file://  -> play directly
 *   http/smb/rtsp/...      -> libmpv streams it (module 2)
 *   magnet:                -> torrent handoff (module 3): start the download,
 *                             wait for the header, then play off disk while it
 *                             keeps downloading.
 *
 * Gestures, as MX has them: horizontal drag = seek, vertical on the left half =
 * brightness, right half = volume, single tap = toggle chrome, double tap =
 * play/pause. Each shows a HUD while in progress.
 *
 * The chrome is Compose (PlayerOverlay) over the SurfaceView libmpv renders
 * into, so it reuses the same widgets as the browse UI.
 */
class PlayerActivity : ComponentActivity(), MpvCore.Observer {

    private lateinit var mpv: MpvCore
    private lateinit var surface: SurfaceView
    private lateinit var gestures: GestureDetector
    private lateinit var resume: ResumeStore
    private lateinit var settings: Settings
    private lateinit var audio: AudioManager

    private var mediaKey: String = ""
    private var pendingResumeMs = 0L
    private var zoomMode = 0

    // --- state the Compose overlay reads ---
    private var uiPaused by mutableStateOf(false)
    private var uiPosition by mutableLongStateOf(0L)
    private var uiDuration by mutableLongStateOf(0L)
    private var uiTitle by mutableStateOf("")
    private var uiLocked by mutableStateOf(false)
    private var uiHud by mutableStateOf<Hud?>(null)
    private var uiBuffering by mutableStateOf(false)
    private var uiBufferPercent by mutableIntStateOf(-1)
    private var hudJob: kotlinx.coroutines.Job? = null

    // --- torrent service binding (magnet URIs only) ---
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

        // Full-bleed video; system bars stay hidden until swiped for.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        surface = SurfaceView(this)
        mpv = MpvCore(this).apply {
            observer = this@PlayerActivity
            init()                 // engine must exist before the surface callback
            attach(surface)
            setHardwareDecoding(settings.hardwareDecoding)
            setAutoLoadSubtitles(settings.autoLoadSubtitles)
            setSubtitleScale(settings.subtitleFontScale)
        }

        val overlay = ComposeView(this).apply {
            setContent {
                PlayerOverlay(
                    title = uiTitle,
                    paused = uiPaused,
                    positionMs = uiPosition,
                    durationMs = uiDuration,
                    locked = uiLocked,
                    hud = uiHud,
                    buffering = uiBuffering,
                    bufferPercent = uiBufferPercent,
                    seekStepSeconds = settings.seekStepSeconds,
                    onTogglePause = { mpv.togglePause() },
                    onSeekTo = { mpv.seekTo(it) },
                    onSeekBy = { mpv.seekBy(it) },
                    onBack = { finish() },
                    onToggleLock = { uiLocked = !uiLocked },
                    onPip = { enterPip() },
                    onTracks = { showTrackMenu() },
                    onOptions = { showOptionsMenu() },
                    onZoom = { cycleZoom() },
                )
            }
        }

        setContentView(
            FrameLayout(this).apply {
                addView(surface, FrameLayout.LayoutParams(-1, -1))
                addView(overlay, FrameLayout.LayoutParams(-1, -1))
            }
        )

        gestures = GestureDetector(this, PlayerGestures())
        surface.setOnTouchListener { _, e ->
            if (!uiLocked) {
                gestures.onTouchEvent(e)
                if (e.actionMasked == MotionEvent.ACTION_UP) commitSeek()
            }
            true
        }

        handleUri(intent?.dataString)
    }

    /**
     * The activity is singleTask, so picking another video while the player is
     * already open arrives here rather than through onCreate. Without this the
     * new file was simply ignored and the old one kept playing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Remember where we were in the outgoing file before switching.
        if (mediaKey.isNotEmpty()) resume.save(mediaKey, uiPosition, uiDuration)
        uiPosition = 0
        uiDuration = 0
        uiLocked = false
        handleUri(intent.dataString)
    }


    /**
     * A human title for the top bar. The last path segment of a content:// URI
     * is the MediaStore row id ("1000050633"), so ask the provider for
     * DISPLAY_NAME and only fall back to the path for file/network URIs.
     */
    private fun displayNameFor(uri: String): String {
        if (uri.startsWith("content://")) {
            runCatching {
                contentResolver.query(
                    android.net.Uri.parse(uri),
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null, null, null,
                )?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) {
                        c.getString(i)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        if (uri.startsWith("magnet:", ignoreCase = true)) {
            // dn= is the magnet display-name parameter.
            Regex("[?&]dn=([^&]+)").find(uri)?.groupValues?.get(1)?.let {
                return runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }
            return "Torrent"
        }
        return uri.substringAfterLast('/').substringBefore('?').ifBlank { "Playing" }
    }

    // --- URI routing ---------------------------------------------------------

    private fun handleUri(uri: String?) {
        if (uri == null) { finish(); return }
        mediaKey = uri
        uiTitle = displayNameFor(uri)

        if (uri.startsWith("magnet:", ignoreCase = true)) {
            pendingMagnet = uri
            Intent(this, TorrentService::class.java).also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it)
                else startService(it)
                bindService(it, conn, Context.BIND_AUTO_CREATE)
            }
        } else {
            startPlayback(uri)
        }
    }

    private fun startPlayback(uri: String) {
        // Resume is applied in onFileLoaded(): mpv silently drops a seek issued
        // before the demuxer has the file open, which is why the previous code
        // never actually resumed anything.
        pendingResumeMs = if (settings.autoResume) resume.load(mediaKey) else 0L
        mpv.play(uri)
    }

    private fun beginTorrentStream(magnet: String) {
        val svc = torrent ?: return
        showHud(Hud.Message("Fetching torrent…"), 2000)
        svc.stream(magnet) { }

        lifecycleScope.launch {
            val engine = svc.engine
            while (isActive && !(engine.hasMetadata() && engine.readyToStartPlayback())) {
                delay(500)
            }
            val file = engine.primaryVideoPath() ?: return@launch
            startPlayback("file://${file.absolutePath}")

            // Keep racing pieces ahead of the playhead.
            while (isActive) {
                engine.prioritizeAround(fileIndex = 0, playedFraction = mpv.playedFraction())
                delay(2000)
            }
        }
    }

    // --- MpvCore.Observer ----------------------------------------------------

    override fun onPosition(positionMs: Long, durationMs: Long) {
        runOnUiThread { uiPosition = positionMs; uiDuration = durationMs }
    }

    override fun onPauseChanged(paused: Boolean) {
        runOnUiThread { uiPaused = paused }
    }

    override fun onBuffering(buffering: Boolean, percent: Int) {
        runOnUiThread { uiBuffering = buffering; uiBufferPercent = percent }
    }

    override fun onFileLoaded() {
        runOnUiThread {
            mpv.mediaTitle()?.takeIf { it.isNotBlank() }?.let { uiTitle = it }
            if (pendingResumeMs > 0) {
                mpv.seekTo(pendingResumeMs)
                showHud(Hud.Message("Resumed at ${fmtTime(pendingResumeMs)}"), 1600)
                pendingResumeMs = 0
            }
            mpv.setZoomMode(zoomMode)
        }
    }

    // --- menus ---------------------------------------------------------------

    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    private fun showOptionsMenu() {
        android.app.AlertDialog.Builder(this)
            .setItems(
                arrayOf("Playback speed", "Sleep timer", "Audio delay", "Subtitle delay")
            ) { _, which ->
                when (which) {
                    0 -> showSpeedMenu()
                    1 -> showSleepMenu()
                    2 -> showDelayMenu(audioTrack = true)
                    else -> showDelayMenu(audioTrack = false)
                }
            }
            .show()
    }

    private fun showSpeedMenu() {
        val speeds = doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, i ->
                mpv.setSpeed(speeds[i])
                showHud(Hud.Message("${speeds[i]}x"), 1200)
            }
            .show()
    }

    private fun showDelayMenu(audioTrack: Boolean) {
        val step = settings.audioDelayStepMs
        val values = intArrayOf(-4 * step, -2 * step, -step, 0, step, 2 * step, 4 * step)
        android.app.AlertDialog.Builder(this)
            .setTitle(if (audioTrack) "Audio delay" else "Subtitle delay")
            .setItems(values.map { "${if (it > 0) "+" else ""}$it ms" }.toTypedArray()) { _, i ->
                if (audioTrack) mpv.setAudioDelayMs(values[i]) else mpv.setSubDelayMs(values[i])
                showHud(Hud.Message("${values[i]} ms"), 1200)
            }
            .show()
    }

    private fun showSleepMenu() {
        val mins = intArrayOf(0, 15, 30, 60, 90)
        android.app.AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(mins.map { if (it == 0) "Off" else "$it min" }.toTypedArray()) { _, i ->
                setSleepTimer(mins[i])
            }
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        if (minutes <= 0) { showHud(Hud.Message("Sleep timer off"), 1400); return }
        sleepRunnable = Runnable {
            mpv.setPaused(true)
            showHud(Hud.Message("Paused by sleep timer"), 2500)
        }.also { sleepHandler.postDelayed(it, minutes * 60_000L) }
        showHud(Hud.Message("Sleeping in $minutes min"), 1600)
    }

    private val pickSubtitle =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let {
                mpv.addSubtitle(it.toString())
                showHud(Hud.Message("Subtitle added"), 1400)
            }
        }

    private fun showTrackMenu() {
        android.app.AlertDialog.Builder(this)
            .setItems(arrayOf("Subtitles", "Audio", "Add subtitle file…")) { _, which ->
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
            addAll(tracks.map { t ->
                listOfNotNull(t.title.ifBlank { "Track ${t.id}" }, t.lang).joinToString(" · ")
            })
        }
        if (labels.isEmpty()) { showHud(Hud.Message("No $title tracks"), 1400); return }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, i ->
                val idx = if (allowOff) i - 1 else i
                onPick(if (idx < 0) null else tracks[idx].id)
            }
            .show()
    }

    private fun cycleZoom() {
        zoomMode = (zoomMode + 1) % 3
        mpv.setZoomMode(zoomMode)
        showHud(
            Hud.Message(when (zoomMode) { 1 -> "Crop to fill"; 2 -> "Stretch"; else -> "Fit" }),
            1200,
        )
    }

    // --- HUD -----------------------------------------------------------------

    private fun showHud(h: Hud, millis: Long = 900) {
        uiHud = h
        hudJob?.cancel()
        hudJob = lifecycleScope.launch { delay(millis); uiHud = null }
    }

    // --- gestures ------------------------------------------------------------

    /** Pending drag-seek, committed when the finger lifts. */
    private var seekAccumMs = 0L

    private inner class PlayerGestures : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean { seekAccumMs = 0; return true }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            mpv.togglePause(); return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float,
        ): Boolean {
            if (e1 == null) return false
            val horizontal = abs(e2.x - e1.x) > abs(e2.y - e1.y)
            when {
                horizontal -> {
                    // Accumulate and preview the landing point, as MX does,
                    // rather than seeking on every pixel of movement.
                    seekAccumMs += (-dx * 120).toLong()
                    val target = (uiPosition + seekAccumMs)
                        .coerceIn(0, uiDuration.coerceAtLeast(0))
                    showHud(Hud.Seek(target, seekAccumMs), 900)
                }
                e1.x < surface.width / 2f -> adjustBrightness(dy)
                else -> adjustVolume(dy)
            }
            return true
        }
    }

    private fun commitSeek() {
        if (seekAccumMs == 0L || uiDuration <= 0) { seekAccumMs = 0; return }
        mpv.seekTo((uiPosition + seekAccumMs).coerceIn(0, uiDuration))
        seekAccumMs = 0
    }

    private fun adjustBrightness(dy: Float) {
        val lp = window.attributes
        val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
        val next = (cur + dy / 1200f).coerceIn(0.01f, 1f)
        lp.screenBrightness = next
        window.attributes = lp
        showHud(Hud.Brightness(next))
    }

    private fun adjustVolume(dy: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (cur + if (dy > 0) 1 else -1).coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        showHud(Hud.Volume(if (max > 0) next.toFloat() / max else 0f))
    }

    // --- Picture-in-Picture --------------------------------------------------

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!uiPaused) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPip: Boolean, newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        // The PiP window is far too small for the chrome, so treat it as locked.
        uiLocked = isInPip
    }

    // --- lifecycle -----------------------------------------------------------

    override fun onPause() {
        super.onPause()
        resume.save(mediaKey, uiPosition, uiDuration)
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
        if (!inPip && !uiPaused) mpv.setPaused(true)
    }

    override fun onDestroy() {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        resume.save(mediaKey, uiPosition, uiDuration)
        if (pendingMagnet != null) runCatching { unbindService(conn) }
        mpv.observer = null
        mpv.release()
        super.onDestroy()
    }
}
