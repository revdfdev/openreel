// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.player

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.jdtech.mpv.MPVLib

/**
 * Real libmpv-backed playback core.
 *
 * libmpv bundles FFmpeg + libass, the same decoding stack MX Player ships
 * natively (libffmpeg.mx.so + libmxass.so + libmxmcdec.21.so for MediaCodec).
 * See docs/MX_PLAYER_SPEC.md section 1.
 *
 * NOTE on the binding: dev.jdtech.mpv:libmpv 0.5.1 exposes a STATIC Java API
 * with flat constants (MPVLib.MPV_FORMAT_INT64). Version 1.0.0 switched to an
 * instance API with nested constants and requires compileSdk 36, so we pin
 * 0.5.1. If you ever bump the version, re-check these call sites.
 *
 * Because the native side is a process-wide singleton, only one MpvCore may be
 * live at a time; release() must be called before creating another.
 */
class MpvCore(private val context: Context) : SurfaceHolder.Callback {

    /** Observer the controls overlay binds to for live position/state. */
    interface Observer {
        fun onPosition(positionMs: Long, durationMs: Long)
        fun onPauseChanged(paused: Boolean)
        /**
         * Fired once the demuxer has the file open. Seeking before this lands
         * is silently dropped by mpv, which is why resume has to wait for it.
         */
        fun onFileLoaded() {}
        /**
         * Buffering state. [percent] is mpv's cache-buffering-state, or -1
         * when it is not a cache stall (e.g. still opening the file).
         */
        fun onBuffering(buffering: Boolean, percent: Int) {}
    }

    /** One selectable audio or subtitle track from the mpv "track-list". */
    data class Track(val id: Int, val type: String, val title: String, val lang: String?, val selected: Boolean)

    private var created = false
    private var attached = false
    /**
     * Queued until BOTH the engine exists and a surface is attached. mpv
     * handles "loadfile" on its own thread, so without this gate it can reach
     * the demuxer before surfaceCreated() lands; vo/gpu then fails with
     * "Missing surface pointer", mpv drops the video track for good and the
     * file plays as audio only. That race is why playback worked on a cold
     * start and died on a warm one.
     */
    private var pendingUri: String? = null
    var observer: Observer? = null

    @Volatile var tracks: List<Track> = emptyList(); private set
    @Volatile var paused = false; private set
    @Volatile var buffering = false; private set
    @Volatile var cachePercent = -1; private set
    private var coreIdle = false
    private var pausedForCache = false
    @Volatile var positionMs = 0L; private set
    @Volatile var durationMs = 0L; private set

    fun audioTracks() = tracks.filter { it.type == "audio" }
    fun subtitleTracks() = tracks.filter { it.type == "sub" }

    // --- lifecycle -----------------------------------------------------------

    fun attach(surfaceView: SurfaceView) {
        surfaceView.holder.addCallback(this)
    }

    fun init() {
        if (created) return
        // MPVLib is static, so a relaunch can begin before the previous
        // activity has torn its engine down. Clear any stale one rather than
        // bailing out, which would leave this instance permanently dead.
        if (!engineLive.compareAndSet(false, true)) {
            runCatching { MPVLib.destroy() }
            engineLive.set(true)
        }
        MPVLib.create(context)

        // Defaults chosen to match MX Player behaviour.
        MPVLib.setOptionString("config", "no")
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("hwdec", "auto-safe")   // the MX HW/SW decoder switch
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("sub-auto", "fuzzy")    // auto-load sibling .srt/.ass
        // Android has no fontconfig; libass falls back to its own provider.
        // Forcing "none" made it log "can't find selected font provider" and
        // left styled .ass/.ssa without a usable face.
        MPVLib.setOptionString("sub-font", "Roboto")
        MPVLib.setOptionString("sub-fonts-dir", "/system/fonts")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("keep-open", "always")
        MPVLib.setOptionString("cache", "yes")         // smooth network/torrent streaming
        MPVLib.setOptionString("demuxer-max-bytes", "32MiB")
        MPVLib.setOptionString("audio-buffer", "0.3")   // was underrunning at the default
        MPVLib.setOptionString("idle", "yes")

        MPVLib.init()
        created = true

        MPVLib.addObserver(events)
        MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("track-list/count", MPVLib.MPV_FORMAT_INT64)
        // Drives the loading spinner: core-idle covers opening/seeking,
        // paused-for-cache covers a network stall mid-playback.
        MPVLib.observeProperty("core-idle", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("cache-buffering-state", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observeProperty("eof-reached", MPVLib.MPV_FORMAT_FLAG)

        // Deliberately NOT starting playback here: see pendingUri. The surface
        // callback is what releases it.
        maybeStartPending()
    }

    fun release() {
        if (!created) return
        runCatching { MPVLib.removeObserver(events) }
        runCatching { MPVLib.detachSurface() }
        runCatching { MPVLib.destroy() }
        created = false
        attached = false
        pendingUri = null
        engineLive.set(false)
    }

    private companion object {
        /** True while a libmpv engine exists anywhere in this process. */
        val engineLive = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    // --- playback ------------------------------------------------------------

    fun play(uri: String) {
        pendingUri = uri
        maybeStartPending()
    }

    /** Issues the queued loadfile once the engine and surface are both ready. */
    private fun maybeStartPending() {
        val uri = pendingUri ?: return
        if (!created || !attached) return
        pendingUri = null
        MPVLib.command(arrayOf("loadfile", uri))
    }

    fun togglePause() {
        if (!created) return
        val now = !(MPVLib.getPropertyBoolean("pause") ?: false)
        MPVLib.setPropertyBoolean("pause", now)
        paused = now
        observer?.onPauseChanged(now)
    }

    fun seekTo(ms: Long) {
        if (!created) { positionMs = ms; return }
        MPVLib.command(arrayOf("seek", (ms / 1000.0).toString(), "absolute"))
    }

    fun seekBy(seconds: Int) {
        if (created) MPVLib.command(arrayOf("seek", seconds.toString(), "relative"))
    }

    /** MX-style HW/SW decoder switch. */
    fun setHardwareDecoding(enabled: Boolean) {
        if (created) MPVLib.setOptionString("hwdec", if (enabled) "auto-safe" else "no")
    }

    fun setSpeed(speed: Double) { if (created) MPVLib.setPropertyDouble("speed", speed) }
    fun setAudioDelayMs(ms: Int) { if (created) MPVLib.setPropertyDouble("audio-delay", ms / 1000.0) }
    fun setSubDelayMs(ms: Int) { if (created) MPVLib.setPropertyDouble("sub-delay", ms / 1000.0) }
    fun addSubtitle(uri: String) { if (created) MPVLib.command(arrayOf("sub-add", uri, "cached")) }

    fun setAbLoop(startSec: Double, endSec: Double) {
        if (!created) return
        MPVLib.setPropertyDouble("ab-loop-a", startSec)
        MPVLib.setPropertyDouble("ab-loop-b", endSec)
    }

    /** Subtitle size as a percentage, matching the Settings stepper. */
    fun setSubtitleScale(percent: Int) {
        if (created) MPVLib.setPropertyDouble("sub-scale", percent / 100.0)
    }

    /** MX's auto-load-sibling-subtitles toggle. */
    fun setAutoLoadSubtitles(enabled: Boolean) {
        if (created) MPVLib.setOptionString("sub-auto", if (enabled) "fuzzy" else "no")
    }

    /**
     * Cycles MX's zoom modes: fit the frame, fill the screen (crop), then
     * stretch. panscan 1.0 crops to fill; keepaspect=no stretches.
     */
    fun setZoomMode(mode: Int) {
        if (!created) return
        when (mode) {
            1 -> { MPVLib.setPropertyString("keepaspect", "yes"); MPVLib.setPropertyDouble("panscan", 1.0) }
            2 -> { MPVLib.setPropertyString("keepaspect", "no"); MPVLib.setPropertyDouble("panscan", 0.0) }
            else -> { MPVLib.setPropertyString("keepaspect", "yes"); MPVLib.setPropertyDouble("panscan", 0.0) }
        }
    }

    /** Current media title, for the player's top bar. */
    fun mediaTitle(): String? =
        if (created) MPVLib.getPropertyString("media-title") else null

    fun setPaused(value: Boolean) {
        if (!created) return
        MPVLib.setPropertyBoolean("pause", value)
        paused = value
        observer?.onPauseChanged(value)
    }

    fun selectAudio(id: Int) { if (created) MPVLib.setPropertyInt("aid", id) }
    fun selectSubtitle(id: Int) { if (created) MPVLib.setPropertyInt("sid", id) }
    fun disableSubtitle() { if (created) MPVLib.setPropertyString("sid", "no") }

    /** Fraction 0..1 of the current file played -- feeds torrent piece prioritisation. */
    fun playedFraction(): Double =
        if (durationMs > 0) positionMs.toDouble() / durationMs else 0.0

    // --- track list ----------------------------------------------------------

    /**
     * Spinner on when mpv is stalled but we did not ask it to pause: either it
     * is still opening/seeking (core-idle) or the network cache ran dry
     * (paused-for-cache). Without this a network stream shows several seconds
     * of black and simply looks broken.
     */
    private fun recomputeBuffering() {
        val now = (coreIdle || pausedForCache) && !paused
        if (now == buffering) return
        buffering = now
        observer?.onBuffering(now, if (pausedForCache) cachePercent else -1)
    }

    private fun reloadTracks() {
        if (!created) return
        val count = MPVLib.getPropertyInt("track-list/count") ?: return
        tracks = (0 until count).mapNotNull { i ->
            val type = MPVLib.getPropertyString("track-list/$i/type") ?: return@mapNotNull null
            val id = MPVLib.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null
            Track(
                id = id,
                type = type,
                title = MPVLib.getPropertyString("track-list/$i/title") ?: "",
                lang = MPVLib.getPropertyString("track-list/$i/lang"),
                selected = MPVLib.getPropertyBoolean("track-list/$i/selected") ?: false,
            )
        }
    }

    // --- libmpv events (delivered on the mpv thread) ------------------------

    private val events = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {}
        override fun eventProperty(property: String, value: String) {}
        override fun eventProperty(property: String, value: Double) {}

        override fun eventProperty(property: String, value: Long) {
            when (property) {
                "time-pos" -> positionMs = value * 1000
                "duration" -> durationMs = value * 1000
                "track-list/count" -> { reloadTracks(); return }
                "cache-buffering-state" -> {
                    cachePercent = value.toInt()
                    if (buffering) observer?.onBuffering(true, cachePercent)
                    return
                }
                else -> return
            }
            observer?.onPosition(positionMs, durationMs)
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> {
                    paused = value
                    observer?.onPauseChanged(value)
                }
                "core-idle" -> { coreIdle = value; recomputeBuffering() }
                "paused-for-cache" -> { pausedForCache = value; recomputeBuffering() }
            }
        }

        override fun event(eventId: Int) {
            if (eventId == MPVLib.MPV_EVENT_FILE_LOADED) {
                reloadTracks()
                observer?.onFileLoaded()
            }
        }
    }

    // --- SurfaceHolder.Callback ---------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!created) return
        MPVLib.attachSurface(holder.surface)
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setOptionString("vid", "auto")
        attached = true
        // Now that vo/gpu has something to draw into, it is safe to load.
        maybeStartPending()
    }

    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
        if (created) MPVLib.setPropertyString("android-surface-size", "${w}x$h")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (created) {
            MPVLib.setOptionString("vid", "no")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.detachSurface()
        }
        attached = false
    }
}
