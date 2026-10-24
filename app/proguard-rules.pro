# --- libmpv -----------------------------------------------------------------
# MPVLib is reached from native code, so its names must survive shrinking.
# NOTE: the binding lives in dev.jdtech.mpv, not the upstream is.xyz.mpv --
# a rule naming the old package matches nothing and lets R8 strip the class,
# which then fails at runtime with an UnsatisfiedLinkError.
-keep class dev.jdtech.mpv.** { *; }
-keep interface dev.jdtech.mpv.** { *; }

# --- libtorrent4j -----------------------------------------------------------
-keep class org.libtorrent4j.** { *; }
-keep class org.libtorrent4j.swig.** { *; }

# --- NanoHTTPD (reflection on handler methods) ------------------------------
-keep class fi.iki.elonen.** { *; }

# --- ZXing ------------------------------------------------------------------
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# Anything with native methods, generally.
-keepclasseswithmembernames class * { native <methods>; }

# Keep the callback interfaces native code invokes back into.
-keepclassmembers class * implements dev.jdtech.mpv.MPVLib$EventObserver { *; }
-keepclassmembers class * implements dev.jdtech.mpv.MPVLib$LogObserver { *; }
