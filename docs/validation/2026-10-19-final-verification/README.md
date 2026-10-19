# Local final verification candidate — 2.5.2 (396)

Scope: recent 2.5.2 operator-visible mapping/unit changes listed in canonical
release notes, plus the new GeoTIFF exporter and optional CalTopo limit. This is
an in-place development install for the owner's final regression run, not a store
release or completed field qualification.

## Release notes and packaged consistency

- Updated canonical `release-notes/2.5.2/whats_new.txt` with compressed GeoTIFFs,
  full saved resolution, assignment filenames, the optional 40 MB mode, and
  background planner estimates. Retained the earlier unit, mission-library,
  capture/transit, connection, camera, reconstruction and cleanup changes.
- Synchronized the store mirror and verified metadata/copy checks: 3,547 characters
  against the 4,000-character store limit; platform-neutral notes check passed.
- Compared exact bytes in Android `assets/release_notes.txt`, signed Apple
  `whats_new.txt`, the canonical notes and the store mirror: identical.
- Compared all eight shared Pilot 2 and 3D viewer assets in source, APK and signed
  Apple app: identical bytes.
- Both source configurations and final app artifacts use version 2.5.2/build 396;
  version release date is October 19, 2026.
- Notes SHA-256: `758a8e34a23ce5d69426d6cc9b9917b061eb62fbbe3e515c093737cbccb2a3b7`.

## Recent behavior review

| Workflow | Matching behavior and evidence |
| --- | --- |
| Imperial/Metric | Unit conversions, saved/cancelled Settings state, mapping fields, displays and map scale; platform unit suites and prior simulator/UI evidence retained. |
| Assignment → Map / Local Maps | Same planner entry point; plans, originals and results; incident-independent browsing, planning/capture/result timestamps and original-photo access. Existing platform library/original-access/location tests cover persistence and relocation. |
| Deletion | Separate result-only and whole-flight deletion, retained-original policy, connection closure and tolerant library scans. Platform deletion/library tests pass. Android's disappearing-directory fix is renderer/runtime-specific, not a missing Apple feature. |
| Mission planning | Wide/3×/7× profiles, brush/tree vs open ground, obstacle-top input, 15 m default clearance, same field order, validation, metric/imperial conversion and route limits. Camera/mission/route suites pass. |
| Capture/transit speed | Independent 4 m/s defaults, 1–15 m/s inputs, photo-spacing cap and downward capture-speed rounding, separate WPML transitional speed; neither setting claims control over aircraft Return-to-Home speed. Route/mission tests pass on both. |
| Pilot 2 setup and return | Shared native controller assets, visible-lens selection, VIS-only routes and automatic original return. Camera 4K/4:3 remains an explicit Pilot 2 check; automatic setting of those options is not claimed. Wide-camera 24 mm metadata compatibility and retained-photo recovery are exercised by platform tests. |
| Transfer completion | Persisted completed-selection semantics and automatic reconstruction, including recovered transfers, with stale callback/selection guards. Original/intake/native protocol tests pass. |
| Reconstruction | Shared solver, regional processing, bounded raster cache, resume/checkpoints, overhead/ray visibility, approximate-map warnings and 3D scale/region selection. C++ fixtures, real Kotlin/JNI adapter and shared viewer tests pass. |
| Map overzoom | Both retain native local imagery above its saved zoom. Android's cache protection is specific to osmdroid; Apple has a different renderer. Existing overzoom regression retained. CalTopo web-viewer zoom limits are external to these apps. |
| GeoTIFF | Same lossless Deflate, RGBA transparency, EPSG:3857 coordinates, assignment-based filename and full-resolution default. Both have the same unchecked-by-default `Limit to 40 MB for CalTopo` checkbox. Checked mode tries actual compressed output and lowers only the saved zoom if required; size/reduction shown. Changing mode invalidates prepared UI share state; no-fit/error/cancellation cleans partial output. |
| ZIP / 3D sharing | Existing ZIP still contains full source tiles and metadata; originals shared separately; shared viewer/GLB behavior retained. Native share sheets differ by operating system. |
| Marker cleanup | Durable interrupted-publication cleanup retry behavior retained; platform core tests pass. |

No new functional gap was found in the reviewed recent changes. Native share/file
pickers, removable-storage access, background-execution restrictions, and older
unrelated backlog items remain documented platform differences; this review does
not claim universal UI or radio parity.

## Corrections found during this review

1. Two Android route tests still expected the action list from before visible-camera
   selection was added. Both production generators already use the lens-selection
   action. Updated those tests to verify its focal length, actuator index, order,
   sequential IDs and first-leg directory naming. No flight behavior was weakened.
2. Android's planner constructed route geometry and storage estimates in composition.
   Moved calculations to a serial background dispatcher, with a short edit debounce,
   cancelled-request handling and an identity check that hides stale results.
   Removable-storage discovery, capacity queries and credential loading use IO.
3. Apple's planner serialized mission geometry before detaching, and initial
   credentials were loaded by a view initializer. Moved parsing, estimates,
   filesystem and Keychain work to a serial actor with cancellation checks.
4. Both release-note screens read their bundled files from UI execution paths.
   They now load on background workers and publish only text back to the UI.

Source/compilation review confirms that the changed worker paths never wait on a
worker lock or future from the UI. GeoTIFF workers remain cancellable and retain
immutable input snapshots. No original or saved-plan migration was introduced.

## Validation

- Android full unit suite: 1,600 tests; 0 failures/errors; 1 existing skip.
- Apple complete package suites: 27 mapping XCTest cases (1 local-flight fixture
  skip), 137 core XCTest cases, and 587 Swift Testing cases; 0 failures.
- Shared C++ mapping native, pipeline and surface-refinement fixtures passed.
- Actual Android Kotlin/JNI adapter matched C API camera/depth arrays; callbacks,
  sampling, invalid inputs and cancellation passed.
- Shared JS collection/selection, boundary, perspective scale and native controller
  bridge tests passed.
- Store notes, metadata and UI copy checks passed; `git diff --check` passed.
- Android development APK and development-signed physical iPad app built with the
  same candidate version/build. Notes and common assets verified in those artifacts.

No automated physical-device test, data injection, data clearing, uninstall,
controller flight, photo capture, or reconstruction was initiated. The owner's
final device regression and live CalTopo import remain operator verification.

## Installation confirmed

Both in-place installation commands succeeded. Independent device queries confirm
A5 Pro versionName 2.5.2/versionCode 396 and iPad version 2.5.2/bundleVersion 396.
No app data was cleared. Apps were not launched into an operational session.

Installed-artifact fingerprints:
- Android APK SHA-256: `4160ad1d7285895da77d4b5e75494bfbf5b54d74b9dbb2100d5ddc8f80182854`
- Signed Apple executable SHA-256: `fe139524613af8e1d66d55b957b54efc85f5402d98b49e5dc1e64b61de58bec3`
