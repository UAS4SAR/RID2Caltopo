# Stream startup: housekeeping never holds up the RTMP listener (2.6.0 (445), November 12, 2026)

Rule (Ken): background housekeeping is fine, but it must never hold up anything the
operator is doing. Not installed on any device; numbers below are code-path estimates
plus log evidence. Times PST.

## Baseline (A5 Pro, 1c366086, launch at 16:40:39.034)

| Step | Time |
| --- | --- |
| Process start → MediaMTXService start | 3.26 s (39.03 → 42.29), including a 448 ms main-thread `System.loadLibrary(ffmpeg_bridge)` at 42.04 |
| Recorder prep (`requireRecorderReady`) | 4.7 s (42.29 → 46.97) |
| `prepareRecordingStart` archive scan (`Sized flights reason=cold`) | 13.1 s (47.36 → 41:00.46) |
| RTMP listener opened on :1935 | 41:00.606, **21.6 s** after process start |

## Changes

| Item | Platform | Commit | What changed |
| --- | --- | --- | --- |
| P1+P2 | Android | 2458e465 | Listener first. Start on `FlightStorage.recordingStartEstimate` (no lock, no scan; memory/last read/persisted usage + free space; 90% allowance; 1 GB reserve). Record settings written with `record: no` until allowed; `MediaMTXRecordingReconciler` switches recording via `PATCH /v3/config/pathdefaults/patch`; restart only if `record=no` is refused after retries (50 ms … 3.2 s). Recorder prep runs on `recordingSyncExecutor` and sets the root through the API. |
| P3 | Android | 0b761264 | Clue saves (`prepareAppPrivateWrite`) only post a check; `snapshot()` returns the cached value when cleanup holds the lock. |
| P5 | iPad | 74513db6 | Same rules as P1: `AppleFlightStorage.recordingStartEstimate`, `MediaMTXRuntimeConfiguration.build(recordNow:)`, Swift `MediaMTXRecordingReconciler` + `MediaMTXRecordingAPI`; `maintain()` runs after start in the background; storage changes no longer restart MediaMTX. |
| P6 | iPad | 97c9e380 | Event loop delivers stream lifecycle inline; recording finalization (protect, localize, normalize, deliver, release) runs on a `SerialTaskChain`. Android already finalizes on `recordingSyncExecutor`. |
| P4 | Android | 7354bb68 | `FfmpegBridge.preload()` on an `ffmpeg-preload` thread from `R2CApplication.onCreate`. |

## Estimated time to open :1935

- **Android:** process start → service start ≈ 2.8–3.3 s (3.26 s baseline minus up to
  0.45 s main-thread FFmpeg load), + orphan snapshot (directory list) + `extractAsset`
  0.22 s (baseline) + config write + `MediaMTXNative.start` (in-process; a darwin build
  of the same MediaMTX opened its listener 740 ms after exec including process spawn).
  **≈ 3–4 s vs 21.6 s.** No recorder prep or archive scan remains on the path.
- **iPad:** field logs Nov 9–11 (n=20) show log-open → RTMP listener 0.34–0.61 s; the
  awaited `maintain()` was inside that window, so P5 removes at most that sweep time
  today, and keeps a large Flight Storage from growing it.
- **Emulator (R2C_Units_Qualification, Android 15 arm64, fresh data):** confirmed
  `libffmpeg_bridge.so` now loads on the preload thread (17:30:54.528, tid ≠ main) and
  the remaining first-frame StrictMode reads are 5/4/2 ms (were reported as 78/78/57/32 ms,
  which was frame time after the FFmpeg load). The listener time could not be measured:
  a fresh install stops at the Safety & License screen and has no Archive Dir.

## P7/P8

- P7 (`MapCacheRootResolver`): the monitor is only held during the first resolve for an
  Archive Dir; a waiting caller would otherwise do the same SAF lookup. No change.
- P8 (DEM session mutex in clue refine): measured from iPad field logs Nov 9–11,
  `Clue ray-march totalMs` n=75, p50 33 ms, p95 40 ms, max 42 ms, the hold time a
  concurrent DEM reader could wait. No Android ray-march lines in the field logs. No change.

## Known limits

- The first seconds of a publish may not be recorded until the storage check (or, on
  Android, recorder prep) enables recording through the API.
- The start allowance uses persisted usage from the previous sweep; a scanned blocked
  verdict and free-space/reserve checks still apply, and the background sweep corrects
  it (recording turned off through the API if over).
- Android storage screens other than `snapshot()` (protection, unuploaded count, day
  lists, deletes) still serialize with cleanup.
