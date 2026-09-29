# Completed-flight confirmation fix — 2.3.7 (287)

Implements the follow-up to the read-only A5 diagnosis in `2026-09-27-a5-post-flight-confirmation.md`. No device state was changed during this implementation.

## Behavior

Both Android and Apple retain a completed-flight barrier keyed by aircraft remote ID. The barrier records flight-end time and the last observed publisher connections, even when the stream registry removes its entry before the end callback. An old LIVE entry or unchanged aircraft snapshot cannot create another automatic confirmation. A receive timestamp later than flight end, or a different known publisher connection, releases the barrier. A stream with unknown identity cannot prove a new connection; resolving an old unknown identity is also insufficient. Normal first-flight confirmation remains available. Save is per flight; Ignore is retained; manual operator editing is not blocked.

The barrier does not end streams or change the existing track timeout. Existing combined RID/video lifecycle behavior still handles temporary signal gaps. Decoded/rendered frame diagnostics are observations, not evidence used to rearm the prompt. No extrapolated or source timestamp is substituted for a new receive time by the guard.

## Diagnostics

Both platforms log retirement, first suppression, and reactivation, including remote ID, publisher sessions, aircraft receive time, and reason. Repeated suppressed snapshots do not emit repeated guard logs. Android includes separate decoder progress and rendered-frame ages/counts; Apple includes decoded-frame age. The original lingering LIVE cause remains unproven and requires a matching field capture if it recurs.

## Regression coverage

- Completed confirmed flight with LIVE retained and repeated old aircraft snapshots remains silent.
- Fresh aircraft reception permits next-flight confirmation.
- A new identified video publisher permits video-only confirmation.
- Same publisher disappearing/reappearing and repeated end notifications remain blocked.
- Registry removal before the end callback retains old publisher identity.
- Unknown identity and late identity resolution cannot fabricate a new publisher.
- Other aircraft remain independent; Ignore and current-flight decisions retain existing coverage.
- Logs occur at guard transitions, not every repeated snapshot.

## Validation status

- Android: full JVM suite passed, 1,188 tests with zero failures/errors/skips. Final debug APK build passed, package org.ncssar.rid2caltopo, 2.3.7 (287). APK signature verification passed. A Kotlin compiler daemon connection failed during the final formatting rebuild; Gradle automatically used its fallback compiler and completed successfully.
- Apple core: 414 Swift Testing tests plus 28 XCTest tests passed, with zero failures. Device Release archive compilation and unsigned archive verification passed, including version 2.3.7 (287), privacy metadata, WebRTC, and byte-identical packaged release notes. Archive: apple/Build/RID2CaltopoApple-unsigned-287.xcarchive.
- Release notes synchronized; App Store metadata verification and git diff whitespace checks passed.
- Android artifact: outputs/confirmation-2.3.7-287/app-debug.apk, SHA-256 e889000bd092b6f20890a303360f8a830f77bc88bd749a40b058bf46d9756c70. Logs are preserved in the same directory.
- Build 286 full release-gate results remain historical and are not full qualification of build 287. This change was checked with platform tests and device builds; the complete store release gates were not repeated.
- No install, physical flight test, store upload, or release action was performed. A5 remains on the previously installed 286 build; Apple installation also remains pending.
