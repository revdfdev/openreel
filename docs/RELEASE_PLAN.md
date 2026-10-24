# Release plan

Two separate tracks. They share a codebase and almost nothing else.

- **OpenReel** (`foss` flavour) → F-Droid, GPL, no DRM.
- **Meizo Video Player** (`meizo` flavour) → Meizo's own channel, needs an
  engine swap before it can be closed-source or play OTT.

---

# Part 1 — Releasing OpenReel on F-Droid

## Where we already comply

| Requirement | State |
|---|---|
| Public source repository | `github.com/revdfdev/openreel` |
| FOSS licence | GPL-3.0-or-later, every file |
| No proprietary dependencies | confirmed, see LICENSING.md |
| No analytics, ads or trackers | none present |
| Builds from a clean clone | yes; signing falls back to unsigned when `keystore.properties` is absent |

## The one thing to confirm first

F-Droid builds everything itself and is wary of prebuilt binaries. We ship two
dependencies that contain native `.so` files:

- `dev.jdtech.mpv:libmpv` (libmpv + FFmpeg)
- `org.libtorrent4j:libtorrent4j-android-*`

F-Droid's Inclusion Policy permits prebuilt FLOSS binaries from trusted Maven
repositories, and names Maven Central among them — both of ours come from
there. What the policy does not spell out is the condition it attaches to
native code and reproducibility.

**Do this before any other work:** open a thread on
`forum.f-droid.org` describing both dependencies and ask whether they are
acceptable as-is. The answer decides between:

- *Accepted* → ordinary submission, roughly a day of packaging work.
- *Rejected* → libmpv must be built from source in the build recipe. mpv-android
  does exactly this, so it is proven but substantial; its buildscripts are the
  model. Budget days, not hours.

Getting this answer first avoids doing the packaging twice.

## Steps once that is settled

1. **Version properly.** `versionCode` must increase monotonically;
   `versionName` is what users see. Currently `1` / `0.1.0`.
2. **Tag releases, signed.** `git tag -s v0.1.0 -m "..."` — F-Droid prefers
   signed tags and builds from them, not from `main`.
3. **Add Fastlane metadata**, which F-Droid reads directly from the repo:
   ```
   fastlane/metadata/android/en-US/
     title.txt
     short_description.txt      (≤80 chars)
     full_description.txt
     changelogs/1.txt           (filename = versionCode)
     images/phoneScreenshots/   (1-8 PNGs)
     images/icon.png            (512x512)
   ```
4. **Write the build recipe** for `fdroiddata`. The key line is pinning the
   flavour so F-Droid never builds the Meizo branding:
   ```yaml
   Categories: [Multimedia]
   License: GPL-3.0-or-later
   SourceCode: https://github.com/revdfdev/openreel
   IssueTracker: https://github.com/revdfdev/openreel/issues
   AutoName: OpenReel
   RepoType: git
   Repo: https://github.com/revdfdev/openreel.git
   Builds:
     - versionName: 0.1.0
       versionCode: 1
       commit: v0.1.0
       subdir: app
       gradle:
         - foss          # never the meizo flavour
   AutoUpdateMode: Version
   UpdateCheckMode: Tags
   ```
5. **Submit** as a merge request to `gitlab.com/fdroid/fdroiddata`. Review
   typically takes weeks, and they will ask for changes.

## The faster alternative: your own F-Droid repo

If waiting on review is unattractive, publish your own repository with
`fdroidserver`. Users add one URL and get updates normally.

- Full control, releases in minutes, no review, no policy argument about
  prebuilt native libraries.
- Costs you discovery: nobody finds it by browsing F-Droid.

Doing both is normal — your own repo now, the official one when review lands.

## Before shipping to strangers

The app has only ever run on one device. Worth doing first:

- Test on a second device, ideally armeabi-v7a, and an Android 13+ one for the
  `READ_MEDIA_VIDEO` path that API 31 never exercised.
- Decide whether the torrent feature stays. It is lawful, but it attracts
  scrutiny and is the main reason an app like this gets attention it does not
  want. F-Droid will not object; app stores might.
- Add a privacy note: the app makes no network calls of its own except those
  the user initiates (stream URLs, torrents, local sharing).

---

# Part 2 — Meizo Video Player

The branding flavour already builds. What it cannot yet do is be
closed-source, or play DRM-protected OTT. Both have the same fix.

## The engine split

`MpvCore` already isolates playback behind one class, which is what makes this
cheap. The shape:

```
app/src/main/java/.../player/PlaybackEngine.kt     interface
app/src/foss/java/.../player/MpvEngine.kt          current MpvCore
app/src/meizo/java/.../player/Media3Engine.kt      new, with Widevine
```

```kotlin
// build.gradle.kts
fossImplementation(libs.libmpv)                    // GPL, stays in FOSS only
meizoImplementation(libs.androidx.media3.exoplayer)
meizoImplementation(libs.androidx.media3.exoplayer.dash)  // OTT streaming
meizoImplementation(libs.androidx.media3.exoplayer.hls)
```

The interface is close to what `MpvCore` already exposes: `init`, `play`,
`togglePause`, `seekTo`, `seekBy`, `setSpeed`, track selection, subtitle
loading, zoom, plus the `Observer` callbacks. `PlayerActivity` and
`PlayerOverlay` talk to the interface and do not change.

**What this buys:** the Meizo flavour stops linking libmpv, so the GPL
obligation disappears with it and the flavour can be closed-source. It gains
Widevine, so OTT becomes possible. One change, both problems.

**What it costs:** Media3 plays fewer formats than FFmpeg. Exotic codecs and
styled `.ass` subtitles are mpv's strength and Media3 will not match them. For
an OTT app that is the right trade; for a local-file player it is not, which
is exactly why the flavours should differ here.

## Order of work

1. Extract `PlaybackEngine` and move `MpvCore` into `src/foss`. Verify the FOSS
   flavour still plays — this step alone should change no behaviour.
2. Add `Media3Engine` in `src/meizo` for local files first, no DRM. Verify.
3. Add DASH/HLS, then Widevine with a licence URL from whoever supplies the
   content.
4. Relicense the first-party sources for the Meizo flavour. Meizo owns the
   copyright, so this is a decision, not a negotiation — but it must actually
   be made and recorded, not assumed.
5. Decide what the Meizo flavour drops. Torrent and local Wi-Fi sharing are
   unlikely to belong in a commercial OTT product, and removing them also
   removes libtorrent4j and NanoHTTPD.

## Worth saying plainly

Steps 1-3 are a few days of work. Step 4 is a conversation with whoever owns
legal. Step 5 is a product decision, and the largest one: once the Meizo build
has a different engine, a different licence and a different feature set, it is
a sibling project sharing a UI layer, not a build flavour of this app. That is
a perfectly good outcome — but it is worth going into knowingly rather than
discovering it halfway.
