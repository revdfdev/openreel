# OpenReel

A simple, open-source Android media player: local playback, network streaming,
torrent download + stream-while-downloading, and device-to-device local sharing.
No ads, no accounts, no trackers, no cloud. Built for F-Droid.

> **Clean-room notice.** OpenReel is an original implementation. It is **not**
> derived from, and contains no code or assets from, MX Player or any other
> proprietary app. MX Player was used only as a reference for *which features to
> build* (functional ideas, which aren't copyrightable) — never as a source of
> code, layouts, icons, strings, or native libraries. Keep it that way: do not
> paste decompiled code or copy another app's artwork/branding into this repo.

## Status

Early scaffold. The structure, build config, manifest, and the torrent/share
engines are in place. The **playback engine (libmpv) is not yet wired** — see
below. There is no Android SDK/NDK on the machine this was scaffolded on, so it
has not been compiled yet.

## Modules

| Package | Module | Backed by |
|---|---|---|
| `browse` + `ui` | Local video browse/list | MediaStore (scoped storage) |
| `player` | Player screen, gestures, HW/SW decode, subtitles, audio sync | **libmpv + libass** |
| `stream` | Play network URLs / SMB·FTP·DLNA shares | libmpv (built-in protocols) |
| `torrent` | Download + sequential stream-while-downloading | **libtorrent4j** (Apache-2.0) |
| `share` | Device-to-device transfer, QR pairing | NanoHTTPD + ZXing (same Wi-Fi/hotspot; Wi-Fi Direct is a future enhancement) |

Dropped on purpose: cloud drives, Chromecast, music widgets/lyrics, accounts,
any bundled online content catalog.

## Build

Requires Android Studio with **Android SDK 35** and the **NDK** installed.

```
git clone <this repo>
# add libmpv (see below), then:
./gradlew assembleDebug
```

## Wiring libmpv (the one manual step)

libmpv isn't on Maven, so you add its prebuilt native libs + JNI binding, exactly
like the FOSS players mpv-android / mpvKt do:

1. Get `libmpv.so` (+ its ffmpeg/libass deps) built for each ABI. Easiest source:
   the **mpv-android** build scripts (<https://github.com/mpv-android/mpv-android>),
   or copy the prebuilt `.so` set from an mpv-android release.
2. Drop them in `app/src/main/jniLibs/<abi>/` (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
3. Copy `MPVLib.java` from mpv-android into
   `app/src/main/java/is/xyz/mpv/MPVLib.java` (keep its LGPL header).
4. Replace the `TODO(libmpv)` calls in [`player/MpvCore.kt`](app/src/main/java/com/mkodekar/openreel/player/MpvCore.kt)
   with the real `MPVLib.*` calls (notes are inline).

**Full step-by-step with exact method bodies: [docs/libmpv-wiring.md](docs/libmpv-wiring.md).**

mpv gives you, in one library, what MX Player split across `libffmpeg.mx.so`
(decode), `libmxass.so`/`libft2.mx.so`/`libfribidi.so` (styled subtitles), and
`libsmb2.so` (network shares).

## F-Droid submission checklist

- [x] 100% FOSS dependencies (libmpv GPL/LGPL, libtorrent4j Apache-2.0, NanoHTTPD, ZXing)
- [x] No Google Play Services / Firebase / proprietary SDKs
- [x] Minimal permissions, each tied to a real feature
- [x] Full GPL-3.0 license text in `LICENSE`
- [x] SPDX license headers on all source files
- [x] Adaptive launcher icon in place (generic play glyph — rebrand `res/drawable/ic_launcher_*.xml` if you want a custom look)
- [ ] Reproducible build verified (compile libmpv from source — see docs/libmpv-wiring.md §7)
- [ ] Submit a metadata YAML to <https://gitlab.com/fdroid/fdroiddata>
      (template in `fastlane/` + `metadata/com.mkodekar.openreel.yml`)

### Anti-features to declare honestly

F-Droid will want the torrent and share modules described plainly. Torrent
clients are accepted (e.g. LibreTorrent), but note the feature in the app
description so users know what network activity to expect.

## License

GPL-3.0-or-later (required: linking GPL mpv makes the app GPL). See `LICENSE`.
