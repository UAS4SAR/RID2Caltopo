package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class ManagedVideoStreamAdvertisement {
    @NonNull public final String sessionId;
    @NonNull public final String droneDesignator;
    public final int sourceWidth;
    public final int sourceHeight;
    public final double sourceFps;
    public final long sourceBitrateBps;
    public final long sourceSizeBytes;
    @NonNull public final String sourceCodec;
    @NonNull public final String mediaKind;
    @Nullable public final String recordedAt;
    public final long durationMs;
    @NonNull public final String thumbnailRevision;
    @Nullable public final String thumbnailJpegBase64;

    public ManagedVideoStreamAdvertisement(
            @NonNull String sessionId,
            @NonNull String droneDesignator,
            int sourceWidth,
            int sourceHeight,
            double sourceFps,
            long sourceBitrateBps,
            @NonNull String sourceCodec) {
        this(sessionId, droneDesignator, sourceWidth, sourceHeight, sourceFps,
                sourceBitrateBps, sourceCodec, "live", null, 0L, "", null);
    }

    public ManagedVideoStreamAdvertisement(
            @NonNull String sessionId,
            @NonNull String droneDesignator,
            int sourceWidth,
            int sourceHeight,
            double sourceFps,
            long sourceBitrateBps,
            @NonNull String sourceCodec,
            @NonNull String mediaKind,
            @Nullable String recordedAt,
            long durationMs,
            @NonNull String thumbnailRevision,
            @Nullable String thumbnailJpegBase64) {
        this(sessionId, droneDesignator, sourceWidth, sourceHeight, sourceFps,
                sourceBitrateBps, sourceCodec, mediaKind, recordedAt, durationMs,
                thumbnailRevision, thumbnailJpegBase64, 0L);
    }

    public ManagedVideoStreamAdvertisement(
            @NonNull String sessionId, @NonNull String droneDesignator,
            int sourceWidth, int sourceHeight, double sourceFps, long sourceBitrateBps,
            @NonNull String sourceCodec, @NonNull String mediaKind,
            @Nullable String recordedAt, long durationMs,
            @NonNull String thumbnailRevision, @Nullable String thumbnailJpegBase64,
            long sourceSizeBytes) {
        this.sourceSizeBytes = Math.max(0L, sourceSizeBytes);
        this.sessionId = sessionId;
        this.droneDesignator = droneDesignator;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.sourceFps = sourceFps;
        this.sourceBitrateBps = sourceBitrateBps;
        this.sourceCodec = sourceCodec;
        this.mediaKind = mediaKind;
        this.recordedAt = recordedAt;
        this.durationMs = durationMs;
        this.thumbnailRevision = thumbnailRevision;
        this.thumbnailJpegBase64 = thumbnailJpegBase64;
    }
}
