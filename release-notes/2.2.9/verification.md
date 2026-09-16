# 2.2.9 (241) verification

## Scope

- Android: marketing version 2.2.9, version code 241.
- Apple: marketing version 2.2.9, project build 241, bundled canonical release notes updated.
- Canonical notes: `whats_new.txt`; Apple App Store copy synchronized and verified. Google Play copy: `google-play.txt` (322 characters).
- Cache-only MA packages default to no shared map access on both platforms.
- Apple now recognizes the Android transfer token through pasted/scanned tokens, saved QR images and camera links, and routes ZIP files from Main Screen > Menu > Import Config to the package importer.
- Apple verifies the QR's SubjectPublicKeyInfo pin, certificate trust/host/validity, exact downloaded byte count and SHA-256 before importing. Redirects are not followed. Expired and oversized transfers produce actionable errors. Pasted/scanned package imports keep the token in the import screen for Retry after failure.
- Apple receives QR transfers but continues exporting ZIP files through the share sheet; Apple QR hosting is outside this update.

## Automated validation

- App Store metadata verification for marketing version 2.2.9: passed.
- Apple focused MA tests: 7 passed, including a shared Android-compatible token fixture; malformed/expired/oversized tokens; DER public-key extraction; real local pinned TLS download; rejection of wrong pins/checksums; and successful retry after failed attempts.
- The TLS test uses a temporary P-256 named-curve certificate, as Android does, and Python from PATH as the test server. This machine uses Python with OpenSSL 3.6.3. The macOS system Python's older LibreSSL server did not negotiate this test correctly; no production certificate checks were relaxed.
- Apple full suite: 21 XCTest checks and 370 Swift Testing checks passed (391 total).
- Final unsigned arm64 iOS app build: passed. Built Info.plist reports 2.2.9 (241), includes the r2cmapkg1 URL scheme, and bundles release notes identical to the canonical 2.2.9 file.
- Android full unit suite: 1,112 tests passed, with no skipped tests or failures.
- Android release verification and APK assembly passed. The built APK reports versionName 2.2.9 and versionCode 241, and assets/release_notes.txt matches the canonical notes. The complete releaseCheck passed, including configured symbol processing.
- APK SHA-256: 74d8ecc53f48c1b18ed4ff34d660576b9b2dd1dbf47fc0978a1a49f74a50f2f5.

## Physical and distribution boundary

The TLS transfer test runs on macOS using the shared Apple networking implementation. It does not prove iPhone/iPad camera recognition, Local Network permission handling, Wi-Fi/hotspot routing, or a physical Android-to-iPad transfer. No device installation, store submission, or publication was performed for this update.

## Follow-up: captured-video picker

The Android captured-video selection lifecycle was corrected after the release checks above. See `docs/Captured_Video_Picker_2026-09-15.md` for the fresh log evidence and retest procedure. All 1,114 Android unit tests passed; a debug build was prepared and metadata was revalidated (`/tmp/captured-picker-full.log`). The earlier release APK hash and Apple bundled-note comparison describe the preceding MA-package build; those artifacts do not include this later playback fix / release-note addition. Rebuild release artifacts before distribution. No installation or physical picker retest was performed.

## S11U installation after playback fix

Rebuilt `:app:assembleRelease` successfully, including its required checks (`/tmp/s11u-229-latest-build.log`). Current release APK SHA-256: `c9b78b43f088ca8f1443525d27ab98eaf92cc678413a2e5b6ef791918a845ac9`.

Installed with `adb install -r` on connected Samsung S11U SM-X930, serial R5GL430HQGL. Immediately before installation, Android reported no installed RID2Caltopo package for its sole user (0); no uninstall or data-clearing operation was performed. Installation returned Success. Package Manager confirmed 2.2.9 / versionCode 241. Explicit launch returned Status ok; `R2CActivity` was the top resumed activity. User will perform the actual file-picker/playback retest. Apple artifact remains the earlier build described above.

## A5 Pro installation — 2026-09-16

Installed the same release APK (SHA-256 `c9b78b43f088ca8f1443525d27ab98eaf92cc678413a2e5b6ef791918a845ac9`) on the attached Samsung A5 Pro SM-X350, serial R52Y90C9XST. The pre-install package queries returned no installed RID2Caltopo package. `adb install -r` returned Success; no uninstall or data-clearing operation was used. Package Manager confirmed version 2.2.9 / 241. Explicit launch returned Status ok and R2CActivity was the top resumed activity. Physical playback and MA transfer testing remain user-performed checks.

## A5 Pro storage-setup follow-up — 2026-09-16

Fixed missing archive setup incorrectly producing a low-device-storage warning, distinguished inaccessible archive usage from zero, exposed device free space, and rechecked recording availability after archive selection. All 1,117 Android tests and signed release packaging passed; Crashlytics upload tasks were excluded. Updated A5 Pro R52Y90C9XST in place and confirmed launch. Latest APK SHA-256: `db123a215fca67516a493f800c946676055a69b5aa7fa85e40c8297a2deb5a2c`, still v2.2.9 / 241. See `docs/Archive_Setup_Storage_Warning_2026-09-16.md`. The S11U remains on the preceding build; no data-clearing first-install test was performed.
