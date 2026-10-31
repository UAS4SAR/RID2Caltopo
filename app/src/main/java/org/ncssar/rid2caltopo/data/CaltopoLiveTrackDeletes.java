package org.ncssar.rid2caltopo.data;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Set;

/** One in-flight or completed LiveTrack DELETE per map and track, for this process. */
final class CaltopoLiveTrackDeletes {
    private static final Set<String> claimed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private CaltopoLiveTrackDeletes() { }

    /** @return false when a DELETE for this track was already sent or is in flight. */
    static boolean claim(@Nullable String mapId, @Nullable String trackId) {
        String key = key(mapId, trackId);
        if (key == null) return true;
        return claimed.add(key);
    }

    /** Lets a failed DELETE be retried. Success and already-gone stay claimed. */
    static void release(@Nullable String mapId, @Nullable String trackId) {
        String key = key(mapId, trackId);
        if (key != null) claimed.remove(key);
    }

    static void resetForTests() {
        claimed.clear();
    }

    @Nullable
    private static String key(@Nullable String mapId, @Nullable String trackId) {
        if (mapId == null || trackId == null) return null;
        String map = mapId.trim();
        String track = trackId.trim();
        if (map.isEmpty() || track.isEmpty()) return null;
        return map.toLowerCase(Locale.US) + "\n" + track.toLowerCase(Locale.US);
    }
}
