# Licensing: what can and cannot be shipped closed-source

Written because the branding flavours (`foss` → OpenReel, `meizo` → Meizo Video
Player) raise an obvious next question, and the answer is not the one people
usually expect.

## Short version

**Rebranding and selling it: fine. Closing the source: not as it stands.**

The blocker is not your own code. It is the playback engine.

## Your own code is not the problem

Every source file carries `SPDX-License-Identifier: GPL-3.0-or-later`, but
Meizo holds the copyright on all of it. A copyright holder may relicense their
own work however they like. Those headers are a decision, not a trap — they can
be changed by the owner at any time.

## The engine is the problem

We link `dev.jdtech.mpv:libmpv:0.5.1`. That build reports its own feature list
at runtime:

```
List of enabled features: aaudio android android-media-ndk audiotrack
build-date cplugins dvbin egl-android ffmpeg gl glibc-thread-name gpl libass
libavdevice libdl libplacebo linux-fstatfs lua memrchr opensles posix ppoll
pthread-condattr-setclock vector vt.h zlib
```

Note `gpl`. mpv and FFmpeg can each be built two ways:

| Build | Licence | Closed-source app? |
|---|---|---|
| `--enable-gpl` (what we ship) | GPL-2.0+ | **No** |
| LGPL-only (no `--enable-gpl`) | LGPL-2.1+ | Yes, if dynamically linked and relinkable |

Shipping GPL components inside a proprietary app is not permitted, regardless
of how your own code is licensed.

## What the other dependencies allow

| Component | Licence | Closed-source friendly |
|---|---|---|
| libmpv / FFmpeg (current build) | **GPL** | **No** |
| libtorrent4j | permissive (BSD/MIT lineage) | Yes |
| NanoHTTPD | BSD-3 | Yes |
| ZXing | Apache-2.0 | Yes |
| Muli font | SIL OFL | Yes |
| AndroidX / Compose | Apache-2.0 | Yes |

So the engine is the only thing standing in the way.

## If closed-source is actually required

1. **Replace the engine.** Build libmpv and FFmpeg without `--enable-gpl`, link
   dynamically, and keep the object files or a relinking path available as
   LGPL requires. Cost: real work, and an LGPL FFmpeg loses some filters and
   codecs. Common playback formats are unaffected.
2. **Relicense the first-party code.** Meizo owns it, so this is a decision
   rather than a negotiation.
3. **Reconsider shipping a torrent client commercially.** Legally permitted,
   but a business and store-policy question rather than a licensing one. Google
   Play has removed torrent apps before.

## About OTT

Worth knowing before planning around the current engine: **mpv has no Widevine
support**, and DRM-protected OTT content cannot be played without it. No
licence change fixes that — it is a missing capability, not a permission.

OTT would mean a second playback path on **Media3/ExoPlayer**, which has
Widevine built in and is Apache-2.0, so it is closed-source friendly on its own
terms. That is the natural shape:

- `MpvCore` already isolates playback behind one class.
- Promote it to an interface with two implementations: mpv (local files, every
  codec, FOSS flavour) and Media3 (OTT, DRM, proprietary flavour).
- The proprietary flavour could then drop libmpv altogether, which removes the
  GPL dependency and the problem along with it.

That is the cheapest route to a lawful closed-source OTT build: not relicensing
what exists, but giving the proprietary flavour a different engine.
