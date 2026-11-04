package org.ncssar.rid2caltopo.data;

import androidx.annotation.Nullable;

/**
 * Shared presence packaging rules. Keep wire behavior aligned with Apple
 * {@code ManagedVideoPresencePolicy}.
 */
public final class ManagedVideoPresencePolicy {
    private ManagedVideoPresencePolicy() { }

    /**
     * Include {@code thumbnailJpegBase64} only when this revision has JPEG bytes and
     * was not already delivered. Presence still carries {@code thumbnailRevision}.
     */
    public static boolean shouldIncludeThumbnailJpeg(
            @Nullable String revision,
            @Nullable String jpegBase64,
            @Nullable String lastSentRevision) {
        if (jpegBase64 == null || jpegBase64.isEmpty()) return false;
        if (revision == null) return false;
        String trimmed = revision.trim();
        if (trimmed.isEmpty()) return false;
        return !trimmed.equals(lastSentRevision);
    }
}
