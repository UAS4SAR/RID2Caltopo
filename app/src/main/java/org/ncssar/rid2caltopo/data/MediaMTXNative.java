package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;

import org.ncssar.rid2caltopo.app.MediaMTXService;

public final class MediaMTXNative {

    static {
        CTDebug("MediaMTXNative", "Loading libmediamtx_jni.so");
        // Load the JNI library first, so its symbols are available.
        System.loadLibrary("mediamtx_jni");
    }

    public static native int start(String binPath, String configPath);
    public static native void stop();
    public static native int currentPid();

    public static void onNativeProcessExit(int pid, int status, int signaled) {
        MediaMTXService.onNativeProcessExit(pid, status, signaled);
    }

    public static void onMediaMTXLogLine(String line) {
        // Most lifecycle signaling comes through structured JSON events, but some
        // controller-specific publisher shutdown cues only appear in raw log lines
        // (for example FCUnpublish/deleteStream before the path timeout elapses).
        // Feed both paths so StreamRegistry can react immediately when the logs
        // contain a stronger stop signal than the structured event stream.
        // The dispatcher logs each line once before parsing lifecycle events.
        MediaMTXLogDispatcher.dispatch(line);
    }

    public static void onMediaMTXEventJson(String json) {
        MediaMTXStructuredDispatcher.dispatchEventJson(json);
    }
}
