# 2.3.0 (242) release verification — 2026-09-16

## Scope

Android and Apple version 2.3.0, build 242. Includes the unreleased 2.2.9 MA package, captured-video picker, and archive-setup/storage fixes, plus optional gimbal adjustment for clue submission on both platforms. Canonical release notes and Apple metadata are synchronized; Play and TestFlight notes are prepared separately for their audiences.

## Validation

- Android full unit suite: 1,117 tests, zero failures/errors/skips.
- Android releaseCheck, release APK and AAB packaging: passed, including configured Crashlytics symbol upload.
- Apple full ten-step release gate: passed, including rebuilt native XCFrameworks, 5,209 portable anomaly checks, color/person qualifications, 21 XCTest checks, 370 Swift Testing checks, clean simulator link, and clean unsigned arm64 device archive verification.
- App Store metadata validation: passed for 2.3.0.
- Exported Apple IPA: verified Apple Distribution identity/team, App Store provisioning profile, get-task-allow=false, build/version, and bundled canonical release notes. The intermediate automatically signed archive used development entitlements; the verified store export replaces them with distribution entitlements.
- APK signature and AAB JAR signature verification: passed. AAB verification reports the expected self-signed Android upload certificate and ZIP/JarInputStream ordering warnings; Play validates the uploaded bundle independently.
- APK, AAB, and IPA embedded release notes match the canonical 2.3.0 text. Source-file hashes remained unchanged throughout qualification and packaging.

## Artifact SHA-256

- APK: `e7572e2cd59d3202318e492c1fd0bf98f99e3604dc1fee71ff14dc62180cc9d3`
- AAB: `cf5748236a0239d51890e23ee73b1016b42af05090d26adaa459568a3dd1783d`
- IPA: `0e9f761cb334adf508fb7eb4a81593351fea6d1cc100e4ecba123cf542a4ec16`

Local build logs, signing verification, source manifest, and distribution evidence: `outputs/release-v2.3.0-build242/`.

## Device and distribution boundaries

No 2.3.0 device installation or physical field test was performed in this release preparation. Physical clue submission, MA transfers, camera/Local Network permission handling, and picker behavior remain separate checks from automated qualification.

Requested distribution: Google Play production; Apple internal TestFlight group R2C_AppleTests; Apple App Store review with automatic release after approval. Upload, processing, group assignment, review submission, and public availability must be verified independently in the store consoles.

Before this release, Apple 2.2.6 (196.1) was Waiting for Review with no reviewer message displayed. The Free Apps Agreement and Digital Services Act status were Active. App distribution was Public with 175 territories configured. The Paid Apps Agreement is not needed for the current free app without in-app purchases. Replacing the queued submission restarts App Review.
