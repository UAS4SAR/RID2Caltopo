# 2.5.0 (339) local regression build — October 6, 2026

Source includes the unified-workspace changes layered on `a38459360`, plus explicit light/dark link colors in About. Both platforms use version 2.5.0/build 339 and the October 6 release date. At that October 6 checkpoint, source remained uncommitted and no tag, push, store upload, or submission had been performed. Subsequent source-release preparation is recorded below.

About links use white on #242424 in dark mode (15.52:1 calculated contrast) and black on #F0F0F0 in light mode (18.43:1). Both keep underlines and normal body text sizing. Calculated contrast does not substitute for visual acceptance on the devices.

## Validation

- Android: all 1,389 unit tests passed; debug APK built. `/tmp/r2c-250-android.log`.
- Apple: all 80 XCTest and 562 Swift Testing tests passed. `/tmp/r2c-250-swift.log`.
- Canonical release notes and local store-note metadata validation passed.
- `git diff --check` passed.
- Signed iPad build log: `/tmp/r2c-250-apple.log`.

## Morning device regression focus

- About links in light/dark mode, 2.5.0 (339) metadata, and uptime.
- Portrait/landscape and large text; fullscreen warning clearance; status-chip scrolling.
- Stable bridge RSSI width through live-value/missing-value transitions and Bluetooth Stats.
- Bell hidden on fresh launch; actual alert playback reveals it; per-category counts/time, mute/unmute, clear, repeat, and restart.
- Settings/main-menu order; map selection and credentials; unknown/ignored/published drone inspection and bearing lines.
- Real streams, PiP, fullscreen, clues, flight archives, and background/reconnect behavior.

Device installation results are recorded after completion below. No app data reset or uninstall is used.

## Installation result

- A5 Pro `R52Y90C9XST`: in-place install and launch succeeded; device package metadata confirms 2.5.0 (339).
- Ken’s iPad `694108CB-8CBE-593D-ABE1-D9EDD947B901`: signed device build passed; artifact metadata confirms 2.5.0 (339); in-place install and launch succeeded.

Physical night-mode link acceptance and full field regression remain for the operator's planned testing.

## October 8 — local track merge fix and connected-device refresh

Added “Fixed crossing lines when merging local flight history with live video positions” to canonical 2.5.0 notes, Google Play notes, and synchronized Apple metadata. Verified both newly built apps bundle the updated notes. App Store metadata checks and store-copy checks passed; Google Play notes are 459 characters.

Android now retains original receipt times in track snapshots and merges recovered history chronologically before trimming the display buffer. Archived recordings and publication limits are unchanged. Regression coverage uses coordinates from the supplied October 7 11:44:32 flight and synthetic interpolated video samples to exercise repeated backfill into a full display buffer; it is not a reconstruction of the missing live SEI history.

- Android: fresh debug build succeeded; 29 focused track tests passed. Log: `/tmp/r2c-250-track-install-android.log`. A5 Pro SM-X350 (`R52Y90C9XST`) in-place install and launch succeeded; device readback confirms 2.5.0 (339).
- Apple: fresh signed Debug device build succeeded; three existing track-timeline tests passed. Logs: `/tmp/r2c-250-track-install-apple.log`, `/tmp/r2c-250-track-install-swift.log`. Ken’s iPad (`694108CB-8CBE-593D-ABE1-D9EDD947B901`) in-place installation and launch succeeded. Artifact metadata confirms 2.5.0 (339); CoreDevice returned no app inventory rows, so installed-version readback remains unavailable.
- Apple artifact: `apple/Build/DeviceInstall/Build/Products/Debug-iphoneos/RID2CaltopoApple.app`.
- Android artifact: `app/build/outputs/apk/debug/app-debug.apk`.

Both installations preserved app data without uninstall/reset. Physical UI and live-flight verification remain unperformed. No store upload or submission was made.

## October 8 — v2.5.0rc1 source release preparation

Expanded canonical release notes for the completed Settings Save/Cancel, named incident selection, Aircraft and Reception, download dimensions/area/centroid, AOL size guidance, Streams Server performance/headroom, and storage-control changes. Synchronized the Apple metadata mirror and refreshed the 452-character Google Play summary. Marketing version/build remain 2.5.0 (339).

Release-note metadata validation, four Apple UI-copy regression tests, and the UI-copy scan passed. Staged source was reviewed for accidental artifacts and high-confidence credential patterns; no matches were found. The ignored coordinate-only track fixture is explicitly included so the new flight-backfill regression runs from a checkout. Unrelated untracked files remain outside the release commit.

The first Android release-gate attempt failed only the Color AD full-scan timing criterion (Red2 p95 163.142 ms, limit 150 ms) while native Apple dependencies and Android code were compiling concurrently. Candidate outputs were identical. The report is retained locally as `/tmp/r2c-250rc1-color-first-attempt.json`. The same qualification reran successfully in the Apple gate after those competing compilations ended. The Android completion run reuses that passed qualification and excludes external Crashlytics mapping/symbol uploads; it does not change the timing threshold or skip the underlying qualification.

No store upload, submission change, server deployment, or new device installation is part of this source publication. Prior installation and field evidence above remains dated.

The Android completion run then stopped at `thirdPartyRuntimeVerification`: local ignored `app/src/main/assets/mediamtx` is a symlink to `mediamtx-1.16.2-rid2caltopo`, and the target SHA-256 (`91c4d0cdad21d4f651fd4e06166d1551276b2d7db96783796c3a2698671e956b`) differs from the manifest's accepted release hashes. These pre-existing generated assets are preserved, not included in Git, and not silently replaced. Therefore the Android full release gate is **not passed** and store readiness is **not established** by this release-candidate source tag. Log: `/tmp/r2c-250rc1-android-final.log`.

Final October 8 validation:

- Android: 1,400 unit tests passed with zero failures/errors/skips; tracker coordination checks passed (`/tmp/r2c-250rc1-android-tests.log`). Full release gate remains blocked by the local runtime prerequisite above.
- Apple: full 10-step release gate passed, including rebuilt native libraries, 5,209 native anomaly tests, shared Color AD/person relevance qualifications, 80 XCTest tests, 572 Swift Testing tests, clean Simulator link, and clean unsigned arm64 device archive verification (`/tmp/r2c-250rc1-apple-gate.log`). The gate's temporary archive is cleaned up by the script; this is validation evidence, not a retained store upload artifact.
- Release-note metadata and UI-copy checks passed. Staged whitespace check passed.

Requested publication: commit the reviewed changes on `main` and create annotated source tag `v2.5.0rc1`. Do not interpret this tag as proof of store acceptance or physical field qualification.
