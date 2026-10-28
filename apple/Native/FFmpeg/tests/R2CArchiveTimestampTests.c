#include "../R2CArchiveTimestamp.h"
#include <assert.h>

int main(void) {
    R2CArchiveTimestamp state = {0};
    int64_t pts = 0, dts = 0;
    assert(R2CArchiveTimestampApply(&state, &pts, &dts));
    pts = 8000; dts = 8000;
    assert(R2CArchiveTimestampApply(&state, &pts, &dts));
    assert(pts == 8000 && dts == 8000);
    pts = 8000; dts = 8000;
    assert(R2CArchiveTimestampApply(&state, &pts, &dts));
    assert(pts == 8001 && dts == 8001);
    // A transmission gap and subsequent normal frames stay on their source clock.
    pts = 50000; dts = 50000;
    assert(R2CArchiveTimestampApply(&state, &pts, &dts));
    assert(pts == 50000 && dts == 50000);
    pts = 40000; dts = 40000;
    assert(!R2CArchiveTimestampApply(&state, &pts, &dts));

    R2CArchiveTimestamp reordered = {0};
    pts = 2; dts = -2;
    assert(R2CArchiveTimestampApply(&reordered, &pts, &dts));
    pts = 0; dts = -2;
    assert(R2CArchiveTimestampApply(&reordered, &pts, &dts));
    assert(pts == 1 && dts == -1); // composition offset preserved

    R2CArchiveTimestamp end = {0};
    pts = INT64_MAX; dts = INT64_MAX;
    assert(R2CArchiveTimestampApply(&end, &pts, &dts));
    assert(!R2CArchiveTimestampApply(&end, &pts, &dts));
    return 0;
}
