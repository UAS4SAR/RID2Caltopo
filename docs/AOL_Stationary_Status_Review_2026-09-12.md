# Stationary iPad AOL status review — September 12, 2026

Fresh evidence: copied `Documents/RID2Caltopo/Logs/2026-09-12/Log_12Sep2026-114541-PDT-0700.txt` from Ken's iPad, installed build 210, to `/private/tmp/aol-ipad-status-210.txt`. No app restart or installation was performed.

The log changes from `Takeoff-relative altitude unavailable` at 11:53:48.687 to `Available` at 11:53:53.117. Subsequent Bluetooth summaries continue accepting locations. Existing logging records calculation reasons but not every displayed pending/stale transition; it cannot establish why the user saw only AOL display POS? while ATO/AGL remained numeric.

Two source defects explain unnecessary pending transitions:

- Every observation reset AOL to pending, regardless of whether position, takeoff reference, or height changed.
- Every scheduler attempt removed the current request identifier, even if the work gate denied starting a replacement. The one-second aging timer could consequently discard a still-running result.

The coordinator now retains the result while calculation inputs match exactly. Changed inputs still invalidate the result. The scheduler retains a matching in-flight request, rejects results whose inputs no longer match, and removes the completed identifier before admitting another request. Periodic recalculation still permits newly prepared surface data to take effect.

One freshness sample now determines all altitude statuses in a display snapshot. New transition logging includes displayed ATO, AGL, AOL, shared stale flag, and reason. Regression tests cover unchanged observations, five-second staleness across all three fields, recovery without unnecessary pending, and invalidation when position changes.

Android already keys results and requests by calculation inputs rather than resetting on every observation. Its scheduler was audited; no matching source change was needed.

This does not loosen the five-second telemetry freshness threshold, smooth physical position changes, or reinterpret negative AOL. Physical retest is needed, particularly to trace the reported AOL-only POS? transition.

The user clarified that “parked” meant hovering east of launch, not on the ground. Input retention requires exact equality; ordinary hover position/height changes remain new calculation inputs.

Validation: all 331 Swift Testing tests plus 10 XCTest tests passed; signed iPad build 211 passed. Not installed; the iPad remains on build 210.

## Build 211 retest and bounded refresh display

Fresh `Log_12Sep2026-120555-PDT-0700.txt` was copied to `/private/tmp/aol-ipad-status-211.txt`. It contains 47 display transitions, including 20 pending-to-available refreshes lasting 0.041–1.086 seconds (median 0.7485 seconds). All three recorded stale events set ATO, AGL, and AOL to POS? together. At other moments AGL briefly reported Unk while terrain refreshed; this review does not change its terrain policy.

Exact-input retention in build 211 was insufficient for real flight telemetry changes. Both platforms now retain the last completed numeric AOL measurement for at most 1.5 seconds after a replacement becomes necessary. The retained number describes the last completed sample, so it can briefly lag current position/height during that window. Repeated new observations do not restart the window. If the replacement has not completed by the deadline, AOL displays pending. True telemetry staleness still overrides this immediately, and unavailable results or launch-reference changes clear the retained value. Initial calculations have no prior numeric result to retain.

Tests cover deadline expiry under repeated updates, replacement results, unavailable results, missing height/reference changes, and shared telemetry staleness. No device installation is part of this review.

Validation for this follow-up: Apple suite passed (332 Swift Testing tests plus 10 XCTest tests), Android refresh-window and altitude-coordinator tests passed, and signed iPad build 212 passed. Not installed; iPad remains on 211 and A5 Pro on 210.
