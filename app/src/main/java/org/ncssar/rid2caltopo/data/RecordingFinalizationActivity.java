package org.ncssar.rid2caltopo.data;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Small in-memory status only; never hold this monitor during recording I/O. */
public final class RecordingFinalizationActivity {
    public static final RecordingFinalizationActivity shared = new RecordingFinalizationActivity();
    private final Map<String, Integer> pending = new HashMap<>();

    public synchronized Lease begin(String path) {
        String key = normalize(path);
        pending.put(key, pending.getOrDefault(key, 0) + 1);
        return new Lease(key);
    }

    public synchronized boolean isActive(String[] designators) {
        for (String candidate : designators) {
            String key = normalize(candidate);
            if (!key.isEmpty() && pending.containsKey(key)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        String trimmed = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    public final class Lease implements AutoCloseable {
        private final String key;
        private boolean closed;
        private Lease(String key) { this.key = key; }

        @Override public void close() {
            synchronized (RecordingFinalizationActivity.this) {
                if (closed) return;
                closed = true;
                int count = pending.getOrDefault(key, 0);
                if (count <= 1) pending.remove(key);
                else pending.put(key, count - 1);
            }
        }
    }
}
