# The page calls these through the JavaScript bridge
-keepclassmembers class com.notdxbi.dxbi.tv.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# The torrent engine: libtorrent's Java side is called from its native code
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**
-keep class fi.iki.elonen.** { *; }

# The FFmpeg audio decoder: Media3 finds it by name and builds it by reflection
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# The Dolby Digital encoder's native side
-keep class com.notdxbi.dxbi.tv.Ac3Native { *; }

# Marquee's trailers: NewPipe's extractor (Rhino runs YouTube's player script)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn com.google.re2j.**
-dontwarn org.jsoup.**
-dontwarn org.schabi.newpipe.extractor.**
