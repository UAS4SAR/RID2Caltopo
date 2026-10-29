package org.ncssar.rid2caltopo.qualification;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.StrictMode;
import android.util.Log;

/** Benchmark-only: starts before application initialization, absent from distributed apps. */
public final class QualificationPolicyProvider extends ContentProvider {
    @Override public boolean onCreate() {
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork()
                .penaltyListener(java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread thread = new Thread(r, "qualification-violations"); thread.setDaemon(true); return thread;
                }), violation -> {
                    Log.e("R2CQualification", "MAIN_THREAD_IO_CANDIDATE", violation);
                }).build());
        calibrateDetector();
        return true;
    }
    /** Kept only in this benchmark variant so retrace can prove the detector is active. */
    @androidx.annotation.Keep
    private void calibrateDetector() {
        try (java.io.FileInputStream input = new java.io.FileInputStream("/proc/self/cmdline")) {
            input.read();
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String s, String[] a) { throw new UnsupportedOperationException(); }
}
