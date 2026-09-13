# Android map-label and live-video pacing review — September 12, 2026

Evidence: project-root `ScreenRecording.mp4` (4,097,419 bytes; 12.638 seconds; 1728×1080), fresh `/private/tmp/a5-video-glitch-logcat.txt`, and `/private/tmp/a5-video-glitch-app.txt`. The running A5 Pro was not restarted. Frame contact sheets were inspected at one-second and quarter-second intervals. The screen recording has variable frame timing; its rate is not the drone source rate.

## Video

At 12:40 the native renderer repeatedly logged `render queue trimmed to live edge`, dropping roughly 20–37 frames per event. Examples include 21 dropped at 12:40:00.556 and 12:40:01.355. The rendering interval stayed near 44 ms while its source estimate was approximately 39–41 ms. Queue timestamp spans imply substantially faster media: 1,044 ms across 64 queued frames is approximately 16.6 ms per frame. Device load logs around the same interval reported CPU 8%, UI 15%, thermal cool. This supports a pacing/queue problem; it does not by itself prove every decoding operation is fast.

The arrival-based cadence estimate can be biased by burst delivery. The normal timestamp relock rejected estimates far from the existing cadence, while the fast relock required a buffered-time threshold. A queue cap calculated from the incorrect slow cadence could trim frames before reaching that threshold. The controller also rounded an integer smoothing step back to its previous value (44 ms toward 41 ms at 15 percent), preventing convergence.

Changes:
- Before live queue sizing/trimming, use an ordered window of media timestamps when at least eight frames are queued. Re-evaluate at most every 250 ms. Missing, duplicate, or reversed timestamps reject the window; arrival estimation remains the fallback.
- Retain queue capacity limits and live catch-up trimming for actual excessive backlog.
- Permit at least a one-millisecond step toward the target when nonzero integer smoothing would otherwise stall.
- Log media cadence corrections for physical retest.

## Map label

Quarter-second crops show the telemetry strip expanding when values become POS?, then shrinking when numeric values return. Layout uses the drawable's actual width, so these substitutions move the strip's edges. The Android status drawable now reserves the width needed for a full row of POS? fields; normal numeric/pending substitutions keep that width. Real position updates and existing collision avoidance remain active. This addresses text-driven shifting; physical retest is needed to determine whether any additional predicted-position motion remains objectionable.

## Validation and deployment limits

Android full debug tests and native Android compilation passed. Native harness: 5,210 checks passed, zero failed, including cadence correction and smoothing convergence in both directions. Two existing smoothing expectations were updated to require progress instead of the old rounding deadband. Logs: `/private/tmp/a5-video-native-final.log`, `/private/tmp/a5-video-android-tests.log`, `/private/tmp/a5-video-native-android-final.log`.

No installation or physical playback retest has been performed. Source also includes the previously validated deferred cache initialization startup fix. These Android changes have not been presented as iPad playback changes.


## Correction before device retest

The user confirmed repeated DJI media-timestamp unreliability, including the recurring approximately 44 ms behavior in 30 fps streams. The queued timestamps therefore do not establish the actual source cadence. The new timestamp-forcing logic and timestamp-validation changes were removed before installation. Existing sample-based adaptive pacing and its prior timestamp safeguards remain unchanged. The render-controller integer-rounding fix, label-width stabilization, and preceding deferred-cache startup fix are retained for build 213. This build does not claim to resolve all live queue trimming; the requested physical retest will determine the remaining behavior.
