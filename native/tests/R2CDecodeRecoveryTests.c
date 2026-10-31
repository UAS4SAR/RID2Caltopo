#include "../../apple/Native/FFmpeg/R2CDecodeRecovery.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    R2CDecodeRecovery state = {0};
    /* File errors, startup failures and unrelated failures retain fail-fast behavior. */
    assert(!R2CDecodeRecoveryRetry(&state, false, true, true, 100));
    assert(!R2CDecodeRecoveryRetry(&state, true, false, true, 100));
    assert(!R2CDecodeRecoveryRetry(&state, true, true, false, 100));
    assert(state.errors == 0);

    for (int i = 0; i < 60; ++i)
        assert(R2CDecodeRecoveryRetry(&state, true, true, true, 100 + 16 * i));
    assert(!R2CDecodeRecoveryRetry(&state, true, true, true, 1100));

    R2CDecodeRecoveryFrameDecoded(&state);
    assert(state.errors == 0);
    assert(R2CDecodeRecoveryRetry(&state, true, true, true, 2000));
    assert(R2CDecodeRecoveryRetry(&state, true, true, true, 4500));
    assert(!R2CDecodeRecoveryRetry(&state, true, true, true, 4501));

    /* Getting packets alone must not reset the budget; a real frame does. */
    R2CDecodeRecoveryFrameDecoded(&state);
    assert(R2CDecodeRecoveryRetry(&state, true, true, true, 5000));
    assert(state.errors == 1);
    R2CDecodeRecoveryFrameDecoded(&state);
    assert(R2CDecodeRecoveryRetry(&state, true, true, true, 9000));
    assert(state.errors == 1);

    R2CDecodeRecoveryFrameDecoded(&state);
    assert(R2CDecodeRecoveryRetry(&state, true, true, true, 0));
    assert(!R2CDecodeRecoveryRetry(&state, true, true, true, 2501));

    R2CDecodeRecoveryFrameDecoded(&state);
    R2CDecodeRecoveryBeginKeyframeWait(&state, 10000);
    assert(state.waitForKeyframe);
    assert(R2CDecodeRecoveryRetry(&state, true, true, true, 10000));
    assert(R2CDecodeRecoveryRetry(&state, true, true, true, 17999));
    assert(!R2CDecodeRecoveryRetry(&state, true, true, true, 18001));
    R2CDecodeRecoveryFrameDecoded(&state);
    assert(!state.waitForKeyframe);
    puts("R2CDecodeRecoveryTests passed");
    return 0;
}
