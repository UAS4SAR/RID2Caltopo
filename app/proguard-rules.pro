# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-keep class org.ncssar.rid2caltopo.data.MediaMTXNative {
    public static void onMediaMtxLogLine(java.lang.String);
}
-keep class org.ncssar.rid2caltopo.data.MediaMTXNative {
    *;
}
-keepclasseswithmembers class * {
    native <methods>;
}

# FFmpeg JNI bridge relies on stable class/method names for native callbacks.
-keep class org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridge { *; }
-keep class org.ncssar.rid2caltopo.video.ffmpeg.FfmpegTelemetry { *; }

# The WebRTC SDK's native library resolves these Java entry points by name.
# Its published AAR does not include consumer R8 rules, so a minified release
# otherwise removes org.jni_zero.JniInit and crashes in JNI_OnLoad.
-keep class org.jni_zero.** { *; }
-keep class org.webrtc.** { *; }

# Eclipse Paho MQTT client — uses reflection internally; preserve all classes.
-keep class org.eclipse.paho.** { *; }

-keep class org.ncssar.rid2caltopo.video.surface.SurfaceNative { *; }
