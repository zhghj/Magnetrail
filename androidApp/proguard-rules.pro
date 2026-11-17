# jlibtorrent SWIG JNI bindings must survive minification.
-keep class com.frostwire.jlibtorrent.** { *; }
-keepclassmembers class com.frostwire.jlibtorrent.** { *; }

# sherpa-onnx JNI（native 方法按类名/方法名查找，混淆后会 UnsatisfiedLinkError）
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# Native library entry points
-keep class com.frostwire.jlibtorrent.swig.libtorrent_jni { *; }
-keep class com.frostwire.jlibtorrent.swig.libtorrent { *; }

# Media3 / ExoPlayer
-dontwarn androidx.media3.**
