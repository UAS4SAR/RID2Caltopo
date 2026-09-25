# Apple live-video callback correction — 2026-09-25

## Measured problem

Build 268's 09:29–09:33 iPad flight decoded about 56.7 frames/s but submitted about 42.2 frames/s during the steady interval. The actual queue remained around 1.56 seconds despite a typical 700 ms target, and 3,116 frames were discarded at its memory ceiling. The log did not measure actual display-callback cadence or per-callback processing cost. It therefore established a consumer-throughput shortfall, not a specific operating-system or CPU root cause.

## Correction

- Keep bounded scheduling debt instead of using Android's sleeping-worker missed-tick behavior directly in a sampled display callback.
- Advance through all frames due on the adaptive timeline in a callback, presenting the final due frame with its own camera sample. Earlier due frames are explicitly counted as `presentationSkipped`; future frames and the adaptive reserve stay queued. At a refresh rate below the source rate, some frames cannot physically be presented; deliberate timeline selection prevents these from accumulating until the memory ceiling.
- Relock promptly when a majority of recent source timestamp deltas establishes a substantial cadence change, such as a possible preview-to-flight rate transition. The build 268 log suggests a cadence change but does not prove the controller changed its configured source rate. Small fluctuations still use the shared cadence smoothing.
- Avoid republishing unchanged dimensions, aspect ratio, stream state, quantized delay and camera angles on every frame. Publish the frame-count UI at most four times per second while preserving an exact private counter for snapshots.
- Log actual callback frequency, mean callback processing time and maximum callback gap alongside queue statistics and presentation-skip counts.

The target range, startup reserve, memory limits, local-recording pacing and Android source behavior are unchanged. Apple presentation scheduling deliberately accounts for display callbacks rather than assuming an independently scheduled render thread.

## Validation

The added 120-second simulation changes incoming video from 24 to 60 fps and samples at approximately 60, 42 and 30 Hz with timing jitter. The old one-frame-per-callback selection fails the no-overflow assertion. Corrected runs finish with 500–516 ms buffered, zero memory-overflow drops, zero underruns and matching frame/camera timestamps. Slower callbacks intentionally skip due presentations; tests verify accounting and release every retained frame.

Queue tests pass with AddressSanitizer, UndefinedBehaviorSanitizer and warnings-as-errors. Existing burst, starvation, memory-limit, discontinuity and missing-timestamp cases pass. Shared native regressions: 5,209 passed, zero failed. Final device and simulator application builds passed. Constant-60-fps cases also pass at simulated 60 and 42 Hz callbacks: no continued memory eviction after startup, zero underruns, and a final buffer duration around 500 ms. The unchanged two-second startup at 720p/60 exceeds the 128 MiB cap and discards 25–26 startup frames in these cases; this is explicitly separated from sustained overflow.

## Limits

Synthetic callback timing is not proof of device throughput or visible smoothness. Repeat the iPad flight and inspect `callbackHz`, `callbackWorkMeanMs`, `callbackMaxGapMs`, `presentationSkipped`, target/buffer duration and overflow counts. No physical claim is made from compilation or tests.

## Source-rate clarification

DJI's Mini 4 Pro specifications list live view up to 24 fps in ground standby and up to 30 or 60 fps in flight (https://www.dji.com/mini-4-pro/specs). This makes a transition plausible, but does not prove the controller RTMP output rate or camera sampling rate in this flight.

The copied 09:29 recording reports 30 fps, but the app normalizes recording timestamps to a fixed 30 fps timeline. Its 12,659 frames span about 422 seconds in the normalized file versus about 229 seconds of live streaming. The normalized file therefore cannot establish the original source cadence; recording timeline accuracy warrants separate review.

Additional constant-input simulations pass at 30 fps with approximately 60, 42 and 30 Hz callbacks, and at 24 fps with approximately 60 and 30 Hz callbacks. All have zero memory overflow and zero underruns, ending with 583–633 ms buffered. At 30 fps input with slightly slower-than-30-Hz jittered callbacks, due presentations are coalesced; at faster callbacks every queued frame is presented. No assumption of a source-rate transition is required by the correction. These test-only additions do not change the previously built application. The correction was subsequently installed as build 269; see deployment evidence below.

## Requested iPad deployment

On 2026-09-25 at 09:56 PDT, built the current signed Debug device application with `CURRENT_PROJECT_VERSION=269` (release metadata unchanged). Signed build and strict signature verification passed. Installed in place over 2.3.6 (268) on Ken’s iPad, CoreDevice `694108CB-8CBE-593D-ABE1-D9EDD947B901`, without uninstalling or clearing app data. CoreDevice launch succeeded and the installed-app query confirmed `org.ncssar.RID2CaltopoApple` version 2.3.6, build 269. The user subsequently tested build 269 and reported: “That was fantastic.” This confirms a positive operator assessment of the tested playback; no new log-based latency measurement or broader field qualification is claimed.

Build log: `/private/tmp/ipad-buffer-install-269.log`. App: `/private/tmp/ipad-buffer-install-268/Build/Products/Debug-iphoneos/RID2CaltopoApple.app` (reused build directory; embedded build verified as 269).
