#ifndef R2C_DECODE_RECOVERY_H
#define R2C_DECODE_RECOVERY_H

#include <stdbool.h>
#include <stdint.h>

/* Worker-owned streak, reset only when a decoded frame actually arrives. At 60 fps
 * this permits a keyframe interval while bounding both attempts and elapsed time. */
typedef struct {
    unsigned errors;
    int64_t firstErrorMs;
} R2CDecodeRecovery;

static inline void R2CDecodeRecoveryFrameDecoded(R2CDecodeRecovery *state) {
    state->errors = 0;
    state->firstErrorMs = 0;
}

static inline bool R2CDecodeRecoveryRetry(R2CDecodeRecovery *state, bool live,
                                        bool hasDecodedFrame, bool recoverable,
                                        int64_t nowMs) {
    if (!live || !hasDecodedFrame || !recoverable) return false;
    if (state->errors == 0) state->firstErrorMs = nowMs;
    state->errors += 1;
    return state->errors <= 60 && nowMs >= state->firstErrorMs &&
        nowMs - state->firstErrorMs <= 2500;
}

#endif
