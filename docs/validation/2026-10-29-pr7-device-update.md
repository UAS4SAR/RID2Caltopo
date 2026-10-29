# PR #7 and release notes installed — October 29, 2026

Updated the shared 2.5.5 release notes and matching Apple App Store metadata for
personal credential login, Android track labels, iPad recording links, fresh-terrain
clue saves and background map preparation. Verified the updated text inside both
installed artifacts. Kept version **2.5.5 (432)** and Git history unchanged.

## In-place installations

| Device | Installation time (PDT) | Verification |
| --- | --- | --- |
| A5 Pro, Samsung SM-X350 | 2026-10-29T08:52:34-07:00 | OS package/version and lastUpdateTime; original firstInstallTime retained |
| iPad Pro 11-inch, third generation | 2026-10-29T08:52:38.220243-07:00 | Successful install result and OS bundle identity/version |

Android used `adb install -r`. The iPad used `devicectl device install app` with
the existing application identifier and signing team. Existing app data,
settings, credentials and permissions were preserved by the in-place updates.
Neither app was launched or exercised. No backup, uninstall, reset, storage scan,
private-data inspection or physical functional/instrumentation testing occurred.

## Packaging and checks

The Android PR integration had already passed 1,810 unit tests (one existing
skip). Gradle permission review timed out before a release-note-only rebuild
started. The previously validated APK was therefore repackaged with only
`assets/release_notes.txt` changed. Verified the Android application source hashes
against its build manifest and every other non-signature APK entry against that
validated artifact. Zip alignment for 16 KiB native pages and APK signature
verification passed. The original and replacement signer certificate SHA-256
matched: `7909030ec20262b1234d2cd342cbd34e720eb4dd5dff489d149c17cdff6c21aa`.

The optimized signed iOS Debug build succeeded. System `codesign --verify --deep
--strict` and entitlement checks confirmed the existing app identifier and team
before installation. Embedded version/build and release notes matched source.
The preceding Apple CalTopo selection passed all 76 tests. No additional tests
were needed for release-note text. `git diff --check` passed.

## Artifact hashes

- Android APK SHA-256: `2b20f517bb8676476eeef48c492f7c6fa760cb5f3c200c2955fe28c0124d2eca`.
- Signed iOS bundle tree SHA-256: `3c4ec25d790ee2476b4ee6a725ff0c7353d58b9f74fa94e44d57aa0462f8f6f0`.

Artifacts, signing/build logs, OS metadata, hashes and per-device installation
records are under `build/releases/2.5.5-432/pr7-device-update`.
The bundle-tree hash algorithm is recorded in `artifact-hashes.json`.

For personal-login verification, open CalTopo settings and select
**Personal Login Support > Credentials > Sign in**. Existing cookie logins keep
Session Cookies until explicitly changed. Live enrollment/publication and field
verification of the earlier fixes remain for the operator.
