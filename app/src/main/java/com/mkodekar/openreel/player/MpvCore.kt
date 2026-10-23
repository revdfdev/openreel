// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.player

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Thin wrapper over libmpv's JNI binding (is.xyz.mpv.MPVLib).
 *
 * libmpv does the heavy lifting that MX Player's libffmpeg.mx.so / libmxass.so did:
 *   - decoding (hardware + software), with a runtime HW/SW switch
 *   - embedded + external subtitles incl. styled .ass/.ssa (libass is built into mpv)
 *   - network protocols: http(s), rtsp, smb, ftp -- covers module 2 (streaming)
 *
 * WIRING (see README "Wiring libmpv"): add MPVLib.java + the prebuilt .so files,
 * then replace the TODO calls below with the real MPVLib.* calls. The surface
 * and property model below is exactly what mpv-android uses.
 */
class MpvCore(private val context: Context) : SurfaceHolder.Callback {

    /** Observer the controls overlay binds to for live position/state. */
    interface Observer {
        fun onPosition(positionMs: Long, durationMs: Long)
        fun onPauseChanged(paused: Boolean)
    }

    /** One selectable audio or subtitle track from mpv's "track-list". */
    data class Track(val id: Int, val type: String, val title: String, val lang: String?, val selected: Boolean)

    private var attached = false
    var observer: Observer? = null

    /** Updated from the "track-list" property once libmpv is wired. */
    @Volatile var tracks: List<Track> = emptyList(); private set

    fun audioTracks() = tracks.filter { it.type == "audio" }
    fun subtitleTracks() = tracks.filter { it.type == "sub" }

    fun selectAudio(id: Int) { /* TODO: MPVLib.setPropertyInt("aid", id) */ }
    fun selectSubtitle(id: Int) { /* TODO: MPVLib.setPropertyInt("sid", id) */ }
    fun disableSubtitle() { /* TODO: MPVLib.setPropertyString("sid", "no") */ }

    @Volatile var paused = false; private set
    @Volatile var positionMs = 0L; private set
    @Volatile var durationMs = 0L; private set

    fun attach(surfaceView: SurfaceView) {
        surfaceView.holder.addCallback(this)
    }

    /** Called from the libmpv event thread (MPV_EVENT_PROPERTY_CHANGE) once wired. */
    fun onPropertyChange(name: String, value: Long) {
        when (name) {
            "time-pos" -> positionMs = value * 1000
            "duration" -> durationMs = value * 1000
        }
        observer?.onPosition(positionMs, durationMs)
    }

    /** Fraction 0..1 of the current file played -- feeds torrent piece prioritisation. */
    fun playedFraction(): Double =
        if (durationMs > 0) positionMs.toDouble() / durationMs else 0.0

    fun seekTo(ms: Long) {
        // TODO: MPVLib.command(arrayOf("seek", (ms/1000).toString(), "absolute"))
        positionMs = ms
    }

    fun init() {
        // TODO(libmpv): MPVLib.create(context); MPVLib.init()
        // Recommended defaults (set via MPVLib.setOptionString):
        //   "hwdec" -> "auto-safe"      // module: HW decoding (DecoderPreferences)
        //   "vo" -> "gpu"
        //   "sub-auto" -> "fuzzy"       // auto-load sibling subtitle files
        //   "force-window" -> "no"
        //   "cache" -> "yes"            // smooth network/torrent streaming
    }

    fun play(uri: String) {
        // TODO(libmpv): MPVLib.command(arrayOf("loadfile", uri))
    }

    fun togglePause() {
        paused = !paused
        // TODO: MPVLib.setPropertyBoolean("pause", paused)
        observer?.onPauseChanged(paused)
    }

    /** MX-style HW/SW decoder switch. */
    fun setHardwareDecoding(enabled: Boolean) {
        // TODO: MPVLib.setOptionString("hwdec", if (enabled) "auto-safe" else "no")
    }

    fun seekBy(seconds: Int) {
        // TODO: MPVLib.command(arrayOf("seek", seconds.toString(), "relative"))
    }

    fun setSpeed(speed: Double) { /* TODO: setPropertyDouble("speed", speed) */ }
    fun setAudioDelayMs(ms: Int) { /* TODO: setPropertyDouble("audio-delay", ms/1000.0) */ }
    fun setSubDelayMs(ms: Int) { /* TODO: setPropertyDouble("sub-delay", ms/1000.0) */ }
    fun addSubtitle(uri: String) { /* TODO: command(["sub-add", uri]) */ }
    fun setAbLoop(startSec: Double, endSec: Double) { /* TODO: ab-loop-a / ab-loop-b */ }

    fun release() {
        // TODO(libmpv): MPVLib.destroy()
        attached = false
    }

    // SurfaceHolder.Callback -------------------------------------------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        // TODO(libmpv): MPVLib.attachSurface(holder.surface); set "vo"/"vid"
        attached = true
    }
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // TODO(libmpv): MPVLib.detachSurface()
        attached = false
    }
}
