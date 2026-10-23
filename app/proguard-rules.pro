# libtorrent4j uses JNI + SWIG bindings reached via native code -- keep them.
-keep class org.libtorrent4j.swig.** { *; }
-keep class org.libtorrent4j.** { *; }

# mpv JNI binding is called from native code by name.
-keep class is.xyz.mpv.MPVLib { *; }
-keepclasseswithmembernames class * { native <methods>; }

# NanoHTTPD reflection-free, but keep the server base to be safe.
-keep class fi.iki.elonen.** { *; }
