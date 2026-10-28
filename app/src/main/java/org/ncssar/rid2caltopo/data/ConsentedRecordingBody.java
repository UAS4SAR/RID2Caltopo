package org.ncssar.rid2caltopo.data;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

/** Called by OkHttp's worker, with authorization checked before every write. */
final class ConsentedRecordingBody extends RequestBody {
    private final File file;
    private final long offset;
    private final long length;
    private final BooleanSupplier authorized;

    ConsentedRecordingBody(File file, long offset, long length, BooleanSupplier authorized) {
        this.file = file; this.offset = offset; this.length = length; this.authorized = authorized;
    }
    @Override public MediaType contentType() { return MediaType.get("video/mp4"); }
    @Override public long contentLength() { return length; }
    private void check() throws IOException {
        if (!authorized.getAsBoolean()) throw new IOException("Recording consent revoked");
    }
    @Override public void writeTo(BufferedSink sink) throws IOException {
        check();
        try (FileInputStream input = new FileInputStream(file)) {
            long skipped = 0;
            while (skipped < offset) {
                check();
                long step = input.skip(offset - skipped);
                if (step <= 0) throw new EOFException("Unable to seek recording chunk");
                skipped += step;
            }
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                check();
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new EOFException("Recording ended during upload");
                check();
                sink.write(buffer, 0, count);
                remaining -= count;
            }
        }
    }
}
