#ifndef R2C_ARCHIVE_TIMESTAMP_H
#define R2C_ARCHIVE_TIMESTAMP_H
#include <stdbool.h>
#include <stdint.h>
#include <limits.h>

typedef struct {
    bool initialized;
    int64_t lastSourceDts;
    int64_t lastWrittenDts;
} R2CArchiveTimestamp;

/* Keep the source clock and composition offset. Equal DTS values need only one
 * output-timescale tick, not a new fixed frame cadence. True clock reversals fail. */
static inline bool R2CArchiveTimestampApply(R2CArchiveTimestamp *state,
                                           int64_t *pts, int64_t *dts) {
    const int64_t source = *dts;
    if (state->initialized && source < state->lastSourceDts) return false;
    if (state->initialized && source <= state->lastWrittenDts) {
        if (state->lastWrittenDts == INT64_MAX) return false;
        int64_t next = state->lastWrittenDts + 1;
        if (source < 0 && next > INT64_MAX + source) return false;
        int64_t shift = next - source;
        if (*pts > INT64_MAX - shift) return false;
        *pts += shift;
        *dts = next;
    }
    state->initialized = true;
    state->lastSourceDts = source;
    state->lastWrittenDts = *dts;
    return true;
}
#endif
