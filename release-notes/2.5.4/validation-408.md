# 2.5.4 build 408 — revised notes and privacy wording

October 24, 2026: both populated tablets were updated in place, independently
queried as **2.5.4 (408)**, and launched successfully. Their app processes remained
running. Packaged release notes on both platforms exactly match the current
canonical text. No store upload, uninstall, data clearing or device reset occurred.

## Changes since build 407

- Rewrote the release notes to identify the added link and explanatory-text
  changes, explicitly retaining existing recording/sharing/approval behavior.
- Replaced Android's vague "not intentionally" and Apple's broader "never"
  credential statements with the same scoped explanation: configuration
  diagnostic reports omit credential secrets; configuration exports and backups
  can contain credentials. The platform-specific storage descriptions remain.
- Clarified diagnostic-bundle file selection and removed the broad secret-free
  claim from the diagnostic manifest/help. The packagers select logs and optional
  tracks, not saved configuration. This is not a claim that a universal secret
  scrubber exists for arbitrary log messages.
- Added Android's direct policy link and clarified its existing Crashlytics use.
  Corrected the shared policy's incorrect no-analytics statement: Android already
  uses Firebase Analytics/Crashlytics; Apple does not bundle those SDKs. Existing
  SDK behavior and collection settings were not changed. Policy/catalog date is
  October 24, 2026.
- Advanced the shared build number and both Xcode configurations to 408.

All runtime edits in this follow-up are display/diagnostic-description text and
the Android external policy-link action. Recording, sharing, approval, credential
storage and logging/filtering implementation were not changed. No slow work was
added to either UI thread. Terms of use were not changed.

## Verification

- Android development APK build passed. Seventeen focused JUnit tests passed
  with no failures or skips: configuration/request credential diagnostics (3),
  location redaction (1), and diagnostic packaging/activity handling (13).
  The new test supplies synthetic CalTopo/FAA secrets and verifies that the
  actual saved-configuration report masks them. Existing request tests cover
  exclusion of payloads, response bodies and capability URLs. These tests do not
  constitute an audit of every possible log message.
- Apple Release arm64 archive rebuilt successfully. Unsigned-archive checks,
  native WebRTC, identity, privacy declarations (12 app categories/two packaged
  manifests), real bundled app-registration and signed-export checks passed.
- Separate Apple Development and Apple Distribution exports passed package
  identity/notes checks; the device export passed strict signature and iPad
  provisioning checks. The corrected credential sentence was found in both
  exported app binaries. Android's revised diagnostic sentence is in its DEX.
- Canonical notes and App Store metadata are synchronized; metadata/privacy/UI
  checks and `git diff --check` passed.
- Build 407's full native/shared/Swift/Simulator release gate is prior evidence.
  This follow-up used targeted checks and fresh packaging; it did not rerun that
  entire gate or establish fresh flight, background, audio or thermal acceptance.
  Existing compiler warnings and JDK 25/JaCoCo coverage-tool diagnostics remain;
  the focused JUnit test results passed, without a coverage claim.

## Artifacts

- [Distribution IPA](../../apple/Build/TestFlight-408/RID2CaltopoApple.ipa):
  38,649,133 bytes, SHA-256
  `970f6991fd27a19d182432c19976568e1f6daca3c64c22049164c0e3e97ea16b`.
- [Direct-install IPA](../../apple/Build/Local-iPad-408/RID2CaltopoApple.ipa):
  development-signed export of the same final Release archive.
- [Android development APK](../../apple/Build/ReviewCandidate-408/RID2CaltopoAndroid-408.apk).
- [Final source-change record](../../apple/Build/ReviewCandidate-408/source-inputs.json).
  Private registration stays outside Git and is checked separately against
  packaged bytes. No commit or tag was created.

Logs and machine-readable artifact/test/install evidence are under
`apple/Build/ReviewCandidate-408/`. The first Apple attempt predates the owner's
wording correction and was not installed; it remains in a separately named
`408-initial` archive. Only the final archive was exported for installation.

## Data preservation and installation

Fresh backups were completed before installation in the ignored, access-restricted
`.local-deployment/device-backups/2026-10-24-build408/` directory.

- iPad Pro 11-inch, third generation (`iPad13,4`): 16,623 backup files,
  7,638,882,713 bytes; every copied file was read and hashed. After installation,
  all 15,367 files in Documents, Application Support and Preferences were present
  with unchanged sizes before launch. Keychain is outside the container backup;
  the same app/team identity was retained without resetting protected storage.
- A5 Pro (`SM-X350`, `R52Y90C9XST`): private data and both app-owned external
  storage locations were archived and read back for verification. `adb install -r`
  succeeded; all 374 durable private paths and both removable-storage files
  remained. Normal activity launch returned `Status: ok`.

## Remaining release work

The [original qualification/input list](validation.md#input-needed-before-resubmission)
still applies, using **408** for exact-build acceptance and App Store selection.
Publish the corrected policy and save reviewed live store answers before
resubmission. Separately reconcile Google Play Data safety with Android's existing
Firebase integrations; no Firebase project/account retention settings or console
declarations were inspected or changed here. Installation and synthetic tests
do not replace operator hardware/Pilot/video qualification or review demonstration.
