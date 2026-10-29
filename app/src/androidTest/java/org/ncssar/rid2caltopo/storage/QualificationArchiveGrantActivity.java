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
        if (getIntent().getBooleanExtra("systemProvider", false)) {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(getIntent().getStringExtra("initial")))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(picker, 42);
            return;
        }
        new Thread(() -> {
            Bundle options = new Bundle();
            options.putBoolean("transformMediaReads", getIntent().getBooleanExtra("transformMediaReads", false));
            Bundle result = getContentResolver().call(Uri.parse("content://org.ncssar.rid2caltopo.test.storage"), "newQualificationRoot", getIntent().getStringExtra("root"), options);
            grantUriPermission("org.ncssar.rid2caltopo", Uri.parse(result.getString("uri")), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            runOnUiThread(this::finish);
        }, "test-archive-grant").start();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 42 && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int access = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, access);
            grantUriPermission("org.ncssar.rid2caltopo", uri, access | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        finish();
    }
}
