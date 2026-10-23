# Wiring libmpv into OpenReel

This is the one step the scaffold can't do for you: adding the native playback
engine. Once done, everything in `MpvCore` / `PlayerActivity` / `PlayerControls`
/ track pickers starts working for real. Budget ~half a day the first time.

## 0. Prerequisites

- Android Studio with **SDK 35** and **NDK** installed (SDK Manager → SDK Tools).
- The scaffold already references `app/src/main/jniLibs/` and
  `is.xyz.mpv.MPVLib` — you're filling those in.

## 1. Get prebuilt libmpv `.so` files (easiest path)

You need `libmpv.so` plus its dependencies (ffmpeg, libass, …) for each ABI.
Two options:

- **Copy from mpv-android** (recommended): clone
  <https://github.com/mpv-android/mpv-android>, build once with its `buildall.py`,
  or pull the `.so` set out of a published release APK's `lib/<abi>/` folder.
- **Build yourself** with its `buildscripts/` if you want full control / newer ffmpeg.

You should end up with, per ABI, something like:

```
libmpv.so  libavcodec.so  libavformat.so  libavutil.so  libswscale.so
libswresample.so  libass.so  libavdevice.so  libavfilter.so  (+ a few more)
```

## 2. Place them

```
app/src/main/jniLibs/arm64-v8a/*.so
app/src/main/jniLibs/armeabi-v7a/*.so
app/src/main/jniLibs/x86_64/*.so
```

(`.gitignore` already excludes `jniLibs/` — these are built artifacts, kept out
of the repo. For an F-Droid *reproducible* build they're compiled from source by
the build server, not committed; see §7.)

## 3. Add the JNI binding

Copy `MPVLib.java` from mpv-android into:

```
app/src/main/java/is/xyz/mpv/MPVLib.java
```

Keep its **LGPL header**. It declares the `native` methods and the event/observer
interfaces OpenReel calls. Do not rename the package — the `.so` expects
`is/xyz/mpv/MPVLib` JNI symbol names.

## 4. Fill in MpvCore

Replace the `TODO(libmpv)` bodies in
[`player/MpvCore.kt`](../app/src/main/java/com/mkodekar/openreel/player/MpvCore.kt).
The method names already line up with MPVLib. Concretely:

```kotlin
import `is`.xyz.mpv.MPVLib

fun init() {
    MPVLib.create(context)
    MPVLib.setOptionString("vo", "gpu")
    MPVLib.setOptionString("hwdec", "auto-safe")   // overridden by setHardwareDecoding()
    MPVLib.setOptionString("sub-auto", "fuzzy")     // auto-load sibling subs
    MPVLib.setOptionString("cache", "yes")          // smooth network/torrent streaming
    MPVLib.init()
    // Ask mpv to push the properties the UI observes:
    MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_INT64)
    MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_INT64)
    MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
    MPVLib.observeProperty("track-list", MPVLib.MPV_FORMAT_NONE)
    MPVLib.addObserver(bridge)   // see §5
}

fun play(uri: String)                = MPVLib.command(arrayOf("loadfile", uri))
fun togglePause()                    { paused = !paused; MPVLib.setPropertyBoolean("pause", paused); observer?.onPauseChanged(paused) }
fun setHardwareDecoding(on: Boolean) = MPVLib.setOptionString("hwdec", if (on) "auto-safe" else "no")
fun seekBy(seconds: Int)             = MPVLib.command(arrayOf("seek", seconds.toString(), "relative"))
fun seekTo(ms: Long)                 { MPVLib.command(arrayOf("seek", (ms/1000).toString(), "absolute")); positionMs = ms }
fun setSpeed(speed: Double)          = MPVLib.setPropertyDouble("speed", speed)
fun setAudioDelayMs(ms: Int)         = MPVLib.setPropertyDouble("audio-delay", ms/1000.0)
fun setSubDelayMs(ms: Int)           = MPVLib.setPropertyDouble("sub-delay", ms/1000.0)
fun addSubtitle(uri: String)         = MPVLib.command(arrayOf("sub-add", uri))
fun selectAudio(id: Int)             = MPVLib.setPropertyInt("aid", id)
fun selectSubtitle(id: Int)          = MPVLib.setPropertyInt("sid", id)
fun disableSubtitle()                = MPVLib.setPropertyString("sid", "no")
fun release()                        = MPVLib.destroy()
```

Surface hookup (already stubbed in the callbacks):

```kotlin
override fun surfaceCreated(holder: SurfaceHolder)   { MPVLib.attachSurface(holder.surface); MPVLib.setOptionString("force-window","yes"); MPVLib.setPropertyString("vid","auto") }
override fun surfaceDestroyed(holder: SurfaceHolder) { MPVLib.setPropertyString("vid","no"); MPVLib.detachSurface() }
```

## 5. Bridge mpv's event thread to the Observer

MPVLib delivers events on its own thread. Add a small bridge inside `MpvCore`
that forwards to the existing `onPropertyChange` / `observer`:

```kotlin
private val bridge = object : MPVLib.EventObserver {
    override fun eventProperty(p: String) {
        if (p == "track-list") refreshTracks()
    }
    override fun eventProperty(p: String, v: Long)    = onPropertyChange(p, v)
    override fun eventProperty(p: String, v: Boolean) { if (p == "pause") { paused = v; observer?.onPauseChanged(v) } }
    override fun eventProperty(p: String, v: Double)  {}
    override fun eventProperty(p: String, v: String)  {}
    override fun event(eventId: Int) {}
}

private fun refreshTracks() {
    val n = MPVLib.getPropertyInt("track-list/count") ?: 0
    tracks = (0 until n).map { i ->
        Track(
            id = MPVLib.getPropertyInt("track-list/$i/id") ?: -1,
            type = MPVLib.getPropertyString("track-list/$i/type") ?: "",
            title = MPVLib.getPropertyString("track-list/$i/title") ?: "",
            lang = MPVLib.getPropertyString("track-list/$i/lang"),
            selected = MPVLib.getPropertyBoolean("track-list/$i/selected") ?: false,
        )
    }
}
```

The `PlayerControls` overlay and the track-picker dialog in `PlayerActivity`
already read `mpv.audioTracks()` / `mpv.subtitleTracks()` and the position/pause
observer — no UI changes needed once this bridge is live.

> The exact `MPVLib` method/interface names can differ slightly between
> mpv-android versions. Match these calls to the `MPVLib.java` you copied; the
> semantics (property names like `time-pos`, `aid`, `sid`, `hwdec`) are stable.

## 6. Test checklist

1. Play a local file (Videos tab) — picture + sound, scrubber moves.
2. Toggle **Hardware decoding** in Settings, replay — still plays (SW path works).
3. Open a network URL (Network tab) and an SMB share.
4. Load an external `.srt`/`.ass` via the tracks button → "Add subtitle file".
5. Paste a legal magnet (e.g. a Linux ISO) — playback starts before completion.

## 7. For the F-Droid reproducible build

F-Droid won't accept committed prebuilt `.so` blobs. Add a `prebuild`/`build`
step in the fdroiddata metadata that compiles libmpv from source (or references a
reproducible mpv-android build). This is the last thing to sort before submission,
not before local testing — keep local prebuilts out of git via `.gitignore`.
