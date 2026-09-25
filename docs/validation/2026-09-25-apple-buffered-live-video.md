# Apple adaptive buffered live video — 2026-09-25

## Trigger and device evidence

Ken's iPad (iPad13,4), iPadOS 26.6.2, RID2Caltopo 2.3.6 (264) reported jumpy video during the September 25 flight. A fresh, read-only device-container copy showed one live decoder/publisher session from 07:54:22 to 08:04:53 PDT. The controller explicitly unpublished at the end. The log identified the newest-frame decoder path, but did not record frame timing or Wi-Fi RSSI. Source inspection confirmed newest-only replacement and immediate display; this is a plausible contributor, not measured proof of the flight's root cause.

The copied pre-change log is retained locally at `outputs/ipad-buffer-review-20260925/before-buffering-flight.txt`.

## Implementation

Apple native live RTSP playback now retains a FIFO of decoded hardware surfaces, each with its own timestamp, sequence, and camera metadata. The consumer receives the next due frame rather than the latest available frame. It uses Android's existing `anomaly_runtime_budget` implementations for target latency, source-rate adjustment, gap/stall estimation and decay, adaptive render intervals, and render deadlines. The app already links these shared native functions through R2CAnomalyApple.

Timing uses Android's 2-second startup observation, 300 ms stall floor, 700–1800 ms buffer target, 100 ms processing margin, 12% base / 40% maximum interval adjustment, and 15% interval smoothing. Recent source timestamp deltas provide a robust cadence estimate; missing timestamps fall back to decode cadence. A backward timestamp or jump greater than 10 seconds resets the buffered timeline.

Apple display callbacks request 60–120 Hz for live playback to avoid rounding an adaptive ~33–37 ms interval to a 66 ms callback at 30 Hz. A display callback can dequeue at most one due frame. Local-file playback retains its existing newest-frame behavior and 30 Hz callback preference; HLS fallback still uses AVFoundation pacing. Android source behavior is unchanged.

Apple-specific memory bounds limit the queue to 240 surfaces and 128 MiB accounted pixel storage. Overflow releases oldest frames and records drops. At high resolutions this may constrain achievable buffer duration; diagnostics make this visible. Session destruction releases queued surfaces; timestamp resets preserve cumulative counters.

The local-delay indicator includes the displayed frame's residence time and ages during a hold. Two-second logs identify stream path, decoded/dequeued counts, queue depth/duration/bytes, target latency, source/render intervals, underruns, overflow drops, timeline resets, display submissions and busy-surface drops. Submissions are not proof of physical presentation.

## Validation

- New portable queue simulation passes under AddressSanitizer and UndefinedBehaviorSanitizer with compiler warnings treated as errors: bursty 30 fps, prolonged starvation/slowdown, FIFO camera pairing, frame release accounting, queue count/byte bounds, timeline reset, missing timestamps and bursty 60 fps.
- Shared native regression suite: 5,209 passed, zero failed.
- Swift suite: 410 passed.
- Rebuilt device and simulator FFmpeg bridge libraries using existing pinned FFmpeg dependencies.
- Final unsigned Debug application builds passed for arm64 iOS device and arm64 iOS Simulator. Build metadata remains 2.3.6 (264); no release/build-number change was made.
- Existing H.264 packet and DJI camera telemetry native tests passed.
- Shell syntax checks and `git diff --check` passed.
- Existing unrelated Swift warnings remain in RIDTrackMapView and AppleTrackerCoordinator. Full release qualification was not run.

The queue simulation is now part of `apple/release-check.sh`, with new native API symbol checks. `REBUILD_BRIDGE_ONLY=true` rebuilds both wrapper slices using previously built FFmpeg archives; the default script still performs a full dependency rebuild.

## Remaining proof

At 09:21 PDT, a signed Debug build 2.3.6 (268) was installed in place on Ken’s iPad (694108CB-8CBE-593D-ABE1-D9EDD947B901). Signature verification, installation, launch, and the installed version query succeeded. The existing app was not uninstalled. Build 268 was supplied as a build-time override; project version metadata remains unchanged. Repeat the same controller/drone flight on the updated iPad to verify visible smoothness, delay, thermal/memory behavior, and camera/clue alignment. Exercise stream stop/restart, UI background/resume and controller/network interruption. This work does not establish field qualification or a store release.
