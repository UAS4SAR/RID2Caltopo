#ifndef R2C_FFMPEG_MOBILE_H
#define R2C_FFMPEG_MOBILE_H

#include <CoreVideo/CoreVideo.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct R2CFFmpegSession R2CFFmpegSession;

typedef enum R2CFFmpegStatus {
    R2C_FFMPEG_STATUS_CONNECTING = 0,
    R2C_FFMPEG_STATUS_STREAMING = 1,
    R2C_FFMPEG_STATUS_ENDED = 2,
    R2C_FFMPEG_STATUS_FAILED = 3,
    R2C_FFMPEG_STATUS_STOPPED = 4,
} R2CFFmpegStatus;

// Live streams dequeue the next adaptively paced frame and its matching camera metadata.
// Returns NULL while buffering or before the next render deadline. Local files retain newest-frame behavior.
CVPixelBufferRef R2CFFmpegSessionCopyFrameWithCamera(
    R2CFFmpegSession *session, uint64_t *sequence, int64_t *frameTimestamp,
    double *values, int capacity, int64_t *cameraTimestamp, uint64_t *cameraSequence
);

// Starts a single RTSP/H.264 decoder on a background thread. Returns NULL
// when the session cannot be allocated or its worker cannot be started.
R2CFFmpegSession *R2CFFmpegSessionCreate(const char *url);

// Starts real-time, single-pass playback of a local H.264 recording. The
// decoder inspects non-picture packets for metadata but does not
// submit standalone SEI samples to VideoToolbox.
R2CFFmpegSession *R2CFFmpegSessionCreatePlayback(const char *url);

// Rewrites a MediaMTX H.264 recording into an iOS-friendly MP4 without
// re-encoding. Standalone SEI samples are merged into the following picture
// sample and picture timestamps are normalized to a constant 30 fps timeline.
// Returns 0 on success and writes a human-readable result to detail.
int R2CFFmpegNormalizeRecording(
    const char *sourcePath,
    const char *destinationPath,
    char *detail,
    int detailCapacity
);

// Interrupts network I/O, joins the worker, and releases all retained frames.
void R2CFFmpegSessionDestroy(R2CFFmpegSession *session);

// Returns the newest hardware-decoded frame with a +1 retain count. The
// caller owns the returned CVPixelBufferRef. For local playback only; live playback
// uses CopyFrameWithCamera so frames cannot be separated from buffered telemetry.
CVPixelBufferRef R2CFFmpegSessionCopyLatestFrame(
    R2CFFmpegSession *session,
    uint64_t *sequence,
    int64_t *presentationTimeMicroseconds
);

int64_t R2CFFmpegSessionRenderAgeMilliseconds(R2CFFmpegSession *session);

bool R2CFFmpegSessionCopyRenderDiagnostics(R2CFFmpegSession *session, char *detail, int capacity);

R2CFFmpegStatus R2CFFmpegSessionGetStatus(
    R2CFFmpegSession *session,
    char *detail,
    int detailCapacity
);

uint64_t R2CFFmpegSessionDecodedFrameCount(R2CFFmpegSession *session);

// Copies the newest gimbal pitch carried by FFmpeg format, stream, packet, or
// frame metadata. Returns false until the stream has supplied a finite value.
bool R2CFFmpegSessionCopyLatestGimbalPitchDegrees(
    R2CFFmpegSession *session,
    double *gimbalPitchDegrees
);
bool R2CFFmpegSessionCopyLatestCameraYawDegrees(
    R2CFFmpegSession *session,
    double *cameraYawDegrees
);
bool R2CFFmpegSessionCopyLatestHeadingDegrees(
    R2CFFmpegSession *session,
    double *headingDegrees
);
bool R2CFFmpegSessionCopyLatestDJICameraTelemetry(
    R2CFFmpegSession *session,
    double *azimuthDegrees,
    double *tiltDegrees,
    double *horizontalFovDegrees,
    double *verticalFovDegrees,
    double *attitudeAnglesDegrees,
    int attitudeAngleCapacity,
    // aircraft lat/lon, relative-up, reference lat/lon/alt, motion course
    double *positionValues,
    int positionValueCapacity,
    int64_t *sourceTimestampMicroseconds,
    uint64_t *sequence
);

// Copies the exact decoded type-245 payload retained with the latest DJI SEI
// telemetry sample. The caller supplies at least 128 bytes.
bool R2CFFmpegSessionCopyLatestDJISEIPayload(
    R2CFFmpegSession *session,
    uint8_t *payload,
    int payloadCapacity,
    int *payloadSize,
    int32_t *northMillimeters,
    int32_t *eastMillimeters,
    int32_t *downMillimeters,
    int64_t *sourceTimestampMicroseconds,
    uint64_t *sequence
);

const char *R2CFFmpegVersion(void);

#ifdef __cplusplus
}
#endif

#endif
