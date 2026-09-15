# 2.2.8 release acceptance — 2026-09-15

The release owner reported testing all new features successfully and authorized committing, tagging v2.2.8, pushing, Google Play production publication and TestFlight internal distribution.

The S11U was updated in place to the corrected release APK 2.2.8 (240); launch and installed SHA-256 were verified as 0b5d369d3c5f30c72f4926e7d3b9f76b8ade890469c851dd870daec22787b84d before the user's acceptance. This is user-reported physical acceptance; automated qualification and store processing are recorded separately in the release artifacts.

The following is historical diagnostic/handoff context, superseded by that acceptance and publication authorization.

# 2.2.8 deployment and test handoff — 2026-09-15

## Current state

- This session updated local source and release notes only after the last heading-fix installation. It did not publish mobile builds or deploy Tracker.
- Last verified installations: iPad and S11U both 2.2.8(240), successfully installed in place and launched. These contain the shared Matrice heading decoder correction. S11U lastUpdateTime was 2026-09-15 11:51:29 local; the iPad launched at 11:47:43.
- The recording-metadata/file-size changes and these updated notes are NOT installed on either tablet. The signed iPad build contains the metadata change but predates this release-note update; rebuild final artifacts before installation.
- Tracker changes are prepared under v1.4.92 in changes.txt, without a deployed marker. The preceding entry is v1.4.91: deployed; live service status was not rechecked for this documentation update.
- Both repositories have substantial existing uncommitted changes. Review the complete release scope before committing/tagging. Do not discard unrelated work. No new source commit, tag, version number or deployment was created in this notes update.

## Pending coordinated change

Both tablet apps advertise completed-recording resolution, frame rate, codec, bitrate, duration and sourceSizeBytes when available. iPad reads properties asynchronously, caches by path/size/modification time, bounds concurrent reads and retries failures. Android adds actual file size, bitrate, codec, frame-rate fallbacks and rotation-aware dimensions; optional track-metadata failures must not hide an otherwise readable recording.

Tracker accepts sourceSizeBytes, stores source_size_bytes using an additive BIGINT column with default zero, preserves known values when old clients omit them, and displays decimal MB beside dimensions/frame rate. Existing-row source labels update during normal browser refresh without a page reload or thumbnail change. Live streams do not display file size.

Relevant source:

- apple/App/AppleTrackerCoordinator.swift
- app/src/main/java/org/ncssar/rid2caltopo/video/ManagedVideoSessionRecordingCatalog.kt
- app/src/main/java/org/ncssar/rid2caltopo/data/ManagedVideoStreamAdvertisement.java
- app/src/main/java/org/ncssar/rid2caltopo/data/TrackerPeerCoordinator.java
- r2c-tracker/control_plane.py, main.py, templates/organization_streams.html, static/organization_streams_live.js

## Evidence already collected

- Metadata change: six Android recording-association/advertisement tests passed; the signed iPad build passed. Exact production iOS metadata reader exercised against the recovered Matrice MP4 and a missing-file case; reported 1920x1088, 30 fps, H.264, 260733 ms, consistent with independent ffprobe inspection.
- Tracker: 151 control-plane/organization-route tests passed. Additional migration test passed after removing the size column from an existing test database and rerunning startup initialization; retained resolution was preserved. Browser test passed for source-label updates with unchanged membership and thumbnail, without reload.
- Heading correction: shared native decoder and Python tests passed; replay of 7,821 actual Matrice samples removed 17 jumps above 10 degrees. At 22.400–22.433 seconds, a real 0.6696217-degree turn had formerly decoded as a 58.812757-degree jump. Android native build, 36 Android heading/map tests and 370 Apple core tests passed before the heading-fix installation.
- User confirmed the stream touch-handling/readability fixes worked before the heading investigation. Absolute corrected compass alignment and the latest recording-metadata changes still need explicit field acceptance.

Local evidence (do not treat temporary logs as durable release artifacts):

- outputs/matrice-heading-20260915/: original recovered Matrice recording, payload/camera CSVs, flight log and heading analysis.
- outputs/matrice-recording-20260915/ipad-flight.log: four-clue flight recording completed at 11:55:57.811, less than one second after recorder stop. This rules out a minute-long finalization delay for that flight; the exact cause of its delayed Tracker listing was not established.
- /private/tmp/r2c-recording-parity-ipad.log
- /private/tmp/r2c-recording-android-tests.log
- /private/tmp/r2c-recording-tracker-full.log
- /private/tmp/r2c-recording-migration.log
- Tracker browser check: node --test tests/test_organization_streams_source_label.js

## Deployment and acceptance

1. Inspect current repository/device/service state in the fresh task. Rebuild final artifacts after notes synchronization; confirm the intended release build numbers, signing and complete dirty-worktree scope.
2. Use Tracker's RELEASE.md normal guarded release workflow. This change includes backend, protocol and schema work, so the presentation-only bypass does not apply. Complete qualification, additive-migration compatibility, candidate/staging checks and the live-activity gate before promotion. Publication/promotion is not authorized by this documentation update.
3. Install mobile updates in place; never uninstall or clear app data for this change. The attached device IDs used here were S11U R5GL430HQGL and iPad 694108CB-8CBE-593D-ABE1-D9EDD947B901; recheck attachment before installing. Coordinate app restarts with active flights.
4. Test newly completed Mini 4 Pro and Matrice recordings from each platform, including an already-open Tracker catalog, retained recordings after restart, and a client without sourceSizeBytes. Compare file size, resolution, fps and duration against the actual served file. MB means 1,000,000 bytes; filesystem tools may use binary units.
5. Retest Matrice heading through east at gimbal 0 and -90, including absolute controller alignment. Exercise single/double/long taps, crosshair MSL/REF and point AOL, CAL controls, Bluetooth-off video continuation, map follow and MA surface-package round-trip. See testflight.txt for the full checklist.
6. Verify build, install, launch and displayed version separately from physical/field behavior. Save fresh logs and recordings for any discrepancy.

## Storage location clarification

New iPad artifacts are in On My iPad > RID2Caltopo > FlightStorage > local date, with recordings under the stream designator. Legacy CapturedStreams, Tracks, Logs and Clues folders remain available. No migration or uninstall is required.

The four-clue Matrice flight was saved as 1sar7djmtrc4td_15Sep2026_114929_PDT.mp4 under FlightStorage/2026-09-15/1sar7djmtrc4td, with all four clue images and a clue KMZ. Its listing delay should not be described as a missing recording or slow finalization.
