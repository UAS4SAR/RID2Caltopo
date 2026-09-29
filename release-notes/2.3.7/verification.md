# 2.3.7 (304) release — 2026-09-29

Release owner: Kenneth Taylor. The operator reported that the latest installed A5 Pro and iPad builds work as expected, and explicitly authorized committing/tagging/pushing v2.3.7, Google Play production, and Apple TestFlight. Apple App Store production is outside this request; the existing 2.3.6 review submission remains unchanged.

Candidate source remains in /Users/kjt/Projects/RID2Caltopo on main. Includes accumulated cross-platform standalone-flight publication, connectivity recovery, map/NOTAM, clue description, network identity, shared update interval, Bridge-chip navigation, full-screen camera and flight-confirmation changes. Unrelated recordings, diagnostics, legal files, presentations and build outputs are excluded from Git.

Physical acceptance is the user's report for the installed development builds 304. It does not establish a separate test of the Play-distributed or TestFlight-exported artifact or the entire historical device matrix. Proceeding to the requested destinations uses that explicit release authorization. The signed packages and full automated gates are checked separately below; no additional physical qualification is claimed.

Current release artifacts and logs: outputs/release-2.3.7-304/. Application source fingerprints are retained and checked for drift before tagging/upload.

## Build 304 automated and package evidence

Android full `:app:releaseCheck :app:bundleRelease` passed in 6m37s. JVM reports: 1,232 tests, zero failures/errors/skips. Release signing, notes and packaged R8 mapping verified; mapping/native symbol uploads completed. Package org.ncssar.rid2caltopo, version 2.3.7, build 304.

SHA-256:
- APK: 94f1d7ab66b155318cc184adeb26a4d4c46dcbc2901e70c6e1c8baef8defad69
- AAB: 12a1a1e253538b1a413aacf4b6214515a71255315a7300a01dbd0bd4b185f150

Apple native rebuild/ABI checks, 5,209 anomaly regressions, color/person qualifications, 441 Swift Testing tests and 34 XCTest tests passed. The clean arm64 Simulator link and device archive passed, followed by archive/privacy/binary/WebRTC checks and distribution-signed IPA export verification. Bundle org.ncssar.RID2CaltopoApple; Apple Distribution team 94UV79S6LR; version 2.3.7 (304). Packaged release notes match the canonical notes.

IPA SHA-256: bf65336f07212f8b5afacc3417592a159e78c4543810246136fb3be77b0f97b3

## Historical preparation evidence

The following records concern earlier candidates and do not qualify build 304.

# 2.3.7 (286) preparation

Scope: user-approved narrow-screen Android header, shared version bump and release notes, and current Google Play "For your next release" findings. No store submission requested in this task.

- Android app/build.gradle and both Apple Xcode configurations: 2.3.7 (286).
- Canonical notes and App Store mirror synchronized; metadata verification passed.
- Live Google Play review completed against production 2.3.6 (285); exact findings and dispositions are recorded in play-feedback.md. Only the obsolete testing-track pause was submitted, not a new app release.
- A5 Pro operator review of the header change in installed build 285: user reported "That looks fine." Not evidence of SM-S931U or full large-font/device qualification.

First Android full-gate attempt stopped at colorRealtimeQualification: red1-reviewed full-scan worst max 262.350 ms exceeded 250.000 ms. All candidate detection outputs were identical. Apple was compiling concurrently; host contention is a plausible cause, not a proven diagnosis. Preserved report/log under outputs/release-2.3.7-286. Thresholds and native detection code are unchanged; rerun after Apple finishes.

Apple full gate passed: native rebuild/verification, 5,209 anomaly checks, color/person qualifications, 414 Swift tests, clean Simulator link, clean arm64 unsigned archive, privacy/metadata/binary/WebRTC verification. Archive: apple/Build/RID2CaltopoApple-unsigned-286.xcarchive. Verified CFBundleShortVersionString 2.3.7, CFBundleVersion 286, and byte-for-byte equality of packaged whats_new.txt with the final canonical notes. Log: /private/tmp/r2c-237-apple-release-check.log. No Apple install, signed IPA export, or upload was performed.

After Apple finished, the Android color timing qualification passed unchanged. The full Android releaseCheck and bundleRelease completed successfully on this rerun; all 1,183 JVM tests passed with zero failures, errors, or skips.

Android artifacts are preserved under outputs/release-2.3.7-286. Verified APK package org.ncssar.rid2caltopo, version 2.3.7 (286); apksigner verification passed. AAB strict verification against the configured signing keystore passed (exit 0). Without that trust anchor, jarsigner reports the expected self-signed/untrusted-chain errors. It also reports archive entry-order/POSIX metadata warnings; these do not change the successful trusted-keystore signature result. Packaged release notes match the final canonical file in both APK and AAB; the embedded AAB R8 mapping matches the candidate mapping. Candidate mapping includes both AndroidX EdgeToEdgeApi29 and EdgeToEdgeApi35, consistent with the documented compatibility-call limitation.

SHA-256:
- APK: 7658befa5e41a97998581de609661fd0935997a2f7a2ca6106aa5b39d8cd2ad4
- AAB: be88f6b1cbd3774d84faf98919f8a2ec8a110399bd91f87ddfad175dc91a2e9a

Base source commit: e3e1cab504e7cc1048a0d51b7ba146006870516e plus the local candidate changes. Preserved tracked-source patch: outputs/release-2.3.7-286/candidate.patch, SHA-256 5294cc840aeb09e091bce163b12ad9e1a988a0cc80f15b708c89e6fbee49748a; new release-note files are in release-notes/2.3.7. This task did not commit, tag, push, install build 286, or upload the new release to either store. Device review of the new edge-to-edge changes and Play's next artifact analysis remain open.

## A5 Pro installation — 2026-09-27

At the user's request, installed 2.3.7 (286) in place on A5 Pro SM-X350, serial R52Y90C9XST. Installed build 285 used the Android debug certificate, so a copy of the verified release APK was re-signed with that same existing key. All 699 non-META-INF ZIP payload entries were hash-identical to the qualified release APK; the store-signed original remains unchanged. Compatible artifact: outputs/release-2.3.7-286/app-release-a5-compatible.apk; SHA-256 9fe9effa3cd4c4a6d714d71af6388867a3747e04a9fc9f849cf4ecab2cc88c6e. Certificate SHA-256 matched installed app: 7909030ec20262b1234d2cd342cbd34e720eb4dd5dff489d149c17cdff6c21aa. In-place installation succeeded without uninstalling or clearing app data. Physical UI review is pending with the user.

## Follow-up candidate — 2.3.7 (287)

Build 287 adds the user-authorized Android/Apple completed-flight confirmation guard and transition diagnostics. Both platforms and release-note resources have been advanced together. The 286 artifacts and full-gate results above are preserved historical evidence; they do not qualify the changed 287 source or notes. Current implementation and validation are recorded in docs/validation/2026-09-27-completed-flight-confirmation-fix.md. No device install or store action has been performed for 287.
