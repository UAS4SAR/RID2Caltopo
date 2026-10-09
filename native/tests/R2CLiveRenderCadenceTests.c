#include "anomaly_runtime_budget.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    // Recorded A5 Pro: 60-fps PTS, ~25-fps render estimate; trimming occurred below 700ms.
    assert(anomaly_detector_runtime_budget_should_relock_live_pts(36, 574, 700, 1000, 39, 16));
    assert(anomaly_detector_runtime_budget_should_relock_live_pts(24, 384, 700, 250, 40, 17));
    // Small buffers, invalid timestamps, matching cadence and cooldown must not force catch-up.
    assert(!anomaly_detector_runtime_budget_should_relock_live_pts(7, 800, 700, 1000, 40, 17));
    assert(!anomaly_detector_runtime_budget_should_relock_live_pts(23, 368, 700, 1000, 40, 17));
    assert(!anomaly_detector_runtime_budget_should_relock_live_pts(36, 574, 700, 249, 39, 16));
    assert(!anomaly_detector_runtime_budget_should_relock_live_pts(36, 574, 700, 1000, 33, 33));
    assert(!anomaly_detector_runtime_budget_should_relock_live_pts(36, 574, 700, 1000, 39, 0));
    assert(!anomaly_detector_runtime_budget_should_relock_live_pts(36, 574, 700, 1000, 33, 42));
    assert(anomaly_detector_runtime_budget_should_relock_live_pts(10, 750, 700, 1000, 40, 33));
    puts("Live render cadence regression checks passed");
}
