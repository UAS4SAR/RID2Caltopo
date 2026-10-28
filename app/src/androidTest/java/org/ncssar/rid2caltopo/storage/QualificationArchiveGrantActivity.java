package org.ncssar.rid2caltopo.storage;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

/** Test APK only; grants an isolated test tree from its owning UID. */
public final class QualificationArchiveGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!"ranchu".equals(Build.HARDWARE)) throw new SecurityException("Emulator only");
        new Thread(() -> {
            Bundle result = getContentResolver().call(Uri.parse("content://org.ncssar.rid2caltopo.test.storage"), "newQualificationRoot", getIntent().getStringExtra("root"), null);
            grantUriPermission("org.ncssar.rid2caltopo", Uri.parse(result.getString("uri")), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            runOnUiThread(this::finish);
        }, "test-archive-grant").start();
    }
}
