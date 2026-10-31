#ifndef R2C_DECODE_RECOVERY_H
#define R2C_DECODE_RECOVERY_H

#include <stdbool.h>
#include <stdint.h>

/* Worker-owned streak, reset only when a decoded frame actually arrives. At 60 fps
 * this permits a keyframe interval while bounding both attempts and elapsed time.
 * After a hardware-accelerator fault, waitForKeyframe extends the budget so the
 * worker can flush and resync on the next IDR instead of tearing the session down.
 * hardwareResetCount tracks in-place VT recreations; preferSoftware escalates after
 * repeated VT faults without destroying the RTSP format context. */
typedef struct {
    unsigned errors;
    int64_t firstErrorMs;
    bool waitForKeyframe;
    int64_t keyframeWaitStartedMs;
    unsigned hardwareResetCount;
    int64_t firstHardwareResetMs;
    bool preferSoftware;
    bool needsDecoderRecreate;
} R2CDecodeRecovery;

static inline void R2CDecodeRecoveryFrameDecoded(R2CDecodeRecovery *state) {
    state->errors = 0;
    state->firstErrorMs = 0;
    state->waitForKeyframe = false;
    state->keyframeWaitStartedMs = 0;
    state->needsDecoderRecreate = false;
}

static inline void R2CDecodeRecoveryBeginKeyframeWait(R2CDecodeRecovery *state, int64_t nowMs) {
    state->waitForKeyframe = true;
    state->keyframeWaitStartedMs = nowMs;
    state->errors = 0;
    state->firstErrorMs = 0;
}

static inline bool R2CDecodeRecoveryShouldPreferSoftware(R2CDecodeRecovery *state, int64_t nowMs) {
    if (state->preferSoftware) return true;
    if (state->hardwareResetCount == 0) return false;
    if (state->firstHardwareResetMs == 0) state->firstHardwareResetMs = nowMs;
    /* Two in-place VT resets within 30s → software decode on the same RTSP session. */
    if (state->hardwareResetCount >= 2 &&
        nowMs - state->firstHardwareResetMs <= 30000) {
        state->preferSoftware = true;
        return true;
    }
    if (nowMs - state->firstHardwareResetMs > 30000) {
        state->hardwareResetCount = 0;
        state->firstHardwareResetMs = 0;
    }
    return false;
}

static inline void R2CDecodeRecoveryNoteHardwareReset(R2CDecodeRecovery *state, int64_t nowMs) {
    if (state->hardwareResetCount == 0) state->firstHardwareResetMs = nowMs;
    state->hardwareResetCount += 1;
    state->needsDecoderRecreate = true;
    R2CDecodeRecoveryBeginKeyframeWait(state, nowMs);
}

static inline bool R2CDecodeRecoveryRetry(R2CDecodeRecovery *state, bool live,
                                        bool hasDecodedFrame, bool recoverable,
                                        int64_t nowMs) {
    if (!live || !hasDecodedFrame || !recoverable) return false;
    /* After VT + software in-place resets still fail, allow the session to fail. */
    if (state->preferSoftware && state->hardwareResetCount >= 4) return false;
    if (state->waitForKeyframe) {
        if (nowMs >= state->keyframeWaitStartedMs &&
            nowMs - state->keyframeWaitStartedMs <= 8000) {
            return true;
        }
        /* Keyframe wait exhausted: request an in-place decoder recreate instead of
         * failing the whole RTSP session into a multi-second reconnect. */
        R2CDecodeRecoveryNoteHardwareReset(state, nowMs);
        return true;
    }
    if (state->errors == 0) state->firstErrorMs = nowMs;
    state->errors += 1;
    if (state->errors <= 60 && nowMs >= state->firstErrorMs &&
        nowMs - state->firstErrorMs <= 2500) {
        return true;
    }
    R2CDecodeRecoveryNoteHardwareReset(state, nowMs);
    return true;
}

#endif
