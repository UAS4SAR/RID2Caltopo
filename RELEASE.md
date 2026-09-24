# RID2Caltopo mobile release runbook

This runbook covers coordinated Android and Apple releases. Android and Apple
are peer products: shared operator behavior, the marketing version, build
number, and release notes must remain aligned unless a reviewed exception is
recorded.

Automated tests, successful builds, device installation, Simulator evidence,
and physical field qualification are separate evidence levels. A store release
is not ready merely because a package builds or installs.

## Release roles and access

Name one **mobile release owner** for the version. Android and Apple builders or
testers may differ, but the owner reconciles their evidence and decides whether
the shared release is ready.

Use individual accounts and least-privilege roles. Builders need repository
access and the platform toolchain. Publishers additionally need their own
Google Play Console or Apple Developer/App Store Connect access. Signing keys,
API keys, profiles, and passwords must remain outside Git and must not be pasted
into issues, logs, or chat.

## Shared release preparation

### 1. Select one version and build number

`app/build.gradle` is the cross-platform version authority. Update:

- `versionMajor`, `versionMinor`, and `versionPatch` for the marketing version;
- `versionCode` to a new, never-before-published positive integer; and
- both Xcode build configurations so `MARKETING_VERSION` equals the Android
  version and `CURRENT_PROJECT_VERSION` equals the Android `versionCode`.

The build number cannot be reused after either store accepts it. A rebuild
after upload requires another higher build number on both platforms if the
release is to remain synchronized.

### 2. Prepare unified release notes

Create `release-notes/<version>/whats_new.txt` with a single `Latest changes:`
section. Keep platform names and comparisons out of public notes. Record
platform-specific changes, open gaps, and verified resolutions in the internal,
cumulative [platform parity ledger](docs/PLATFORM_PARITY_LEDGER.md), never in
app resources or store metadata. Preserve historical shipped notes as provenance.

Keep one operator-visible change per bullet. Update the Xcode
`whats_new.txt` resource reference to that canonical versioned file, then run:

```bash
tools/sync_release_notes.sh 2.0.4
apple/AppStore/verify-metadata.sh --marketing-version 2.0.4
```

Replace the example with the selected version. Review the rendered Android
in-app notes and the App Store metadata mirror, not only the source file.

### 3. Review and stabilize the candidate

Before either store upload:

- inspect Google Play Console's **Test and release** page and expand every
  finding for the latest release; record the affected version/build, exact
  APIs and activities, and the disposition in the candidate's verification
  notes. Check the merged release manifest and matching R8 mapping for
  dependency findings; the app's source manifest alone is not sufficient.
  Advisory severity does not establish that a finding is unfounded;
- merge only reviewed release changes;
- review `git status --short`, the complete diff, and all untracked files;
- confirm no signing material, credentials, operational logs, recordings, or
  unrelated artifacts are included;
- run focused tests while changes are being developed; and
- run both full platform gates on the exact candidate commit.

If either platform finds a shared-code defect, fix it and rerun both affected
gates. Do not qualify Android and then silently change shared source while
building Apple.

## Android release

### Prerequisites

- A supported JDK and the Android SDK/NDK/CMake versions resolved by the Gradle
  project.
- A private root `keystore.properties` referring to the authorized release
  keystore. The file and keystore must not be committed.
- Firebase/Crashlytics access if symbol and mapping upload is expected.
- Google Play Console release permission for the publisher.
- At least one representative physical Android device for release-build and
  field checks.

### Build and automated gate

From the repository root:

```bash
./gradlew :app:releaseCheck :app:bundleRelease --console=plain
```

The release gate runs the protected-land check, JVM and native verification,
anomaly qualifications, tracker coordination tests when the sibling tracker is
available, and a signed minified release APK. `bundleRelease` creates the Play
artifact and also enforces release verification. Release builds upload the R8
mapping and native symbols through the configured Crashlytics tasks.

Expected artifacts:

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`
- `app/build/outputs/mapping/release/mapping.txt`

Verify and fingerprint the exact artifacts:

```bash
jarsigner -verify -strict app/build/outputs/bundle/release/app-release.aab
shasum -a 256 app/build/outputs/apk/release/app-release.apk \
  app/build/outputs/bundle/release/app-release.aab
```

Record the hashes and gate result in the release issue. If a gate is flaky or
host-load sensitive, record the first failure and its diagnosis before a rerun.

### Physical Android qualification

Install the release APK, not a debug build:

```bash
adb devices -l
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell dumpsys package org.ncssar.rid2caltopo | \
  rg 'versionCode|versionName|firstInstallTime|lastUpdateTime'
```

On the exact release build, exercise every changed workflow plus the applicable
field paths: launch disclaimer, organization/config import, Bluetooth and
bridge Remote ID, location/map and track publication, tracker coordination,
NOTAM status, archive creation/export, and any changed video or anomaly mode.
Retain device identifiers, screenshots, diagnostic bundles, and test notes.

Installation and version output prove only the installed artifact. They do not
prove radio reception, background behavior, network interoperability, thermal
performance, or field correctness.

### Google Play staged release

1. Commit and tag the exact qualified source as described below.
2. Upload the recorded AAB to an internal test release first.
3. Confirm Play reports the intended package, version name, version code,
   signing status, and release notes.
4. Install the Play-distributed build and repeat a focused physical smoke test.
5. Promote that same artifact through the approved testing/production track.
   Use a staged production rollout when the account and operating plan support
   it, and monitor crashes, ANRs, reviews, and field reports before expanding.

Do not rebuild between internal qualification and production promotion.

### Keep installed clients informed of available updates

After verifying that the intended Android build is available to the target users
in Google Play, update production Tracker's `R2C_RECOMMENDED_APP_VERSION_CODE`
to that build number through the Tracker release process. Set `R2C_UPDATE_URL`
to the RID2Caltopo Play listing and verify both values on the revision serving
production traffic. Do not recommend an unpublished or inaccessible build.

Use an older, validly enrolled Play installation to verify that its Tracker
connection produces the update prompt and that **Check Play** opens the correct
listing with an available update. Record the installed and recommended build
numbers. TestFlight notifications do not maintain this Android server setting.
The independent Play availability check supplements this path in newer clients;
already-installed older versions still depend on the Tracker recommendation.

### Android rollback

An installed Android version cannot be replaced with a lower `versionCode`.
Pause or halt the store rollout when possible, then prepare a forward-fix build
with a higher code. Record whether already-installed users require operational
guidance; store rollback controls do not remove an installed app.

## Apple release

### Prerequisites

- A supported macOS/Xcode environment with required command-line tools.
- An individual Apple Developer/App Store Connect account for the release team.
- Signing access for the application identifier and a valid distribution
  identity/profile, or permission for Xcode-managed signing.
- App Store Connect metadata, privacy declarations, review information, and
  agreements in a releasable state.
- Representative physical iPhone/iPad hardware for the required matrix.

Store Apple credentials in the Keychain or the supported private
`~/.appstoreconnect/private_keys` location. Never commit them.

### Preflight, build, and automated gate

Set the values selected in `app/build.gradle` explicitly:

```bash
export R2C_DEVELOPMENT_TEAM=YOUR_TEAM_ID
export R2C_MARKETING_VERSION=2.0.4
export R2C_BUILD_NUMBER=126
apple/archive-for-testflight.sh --team "${R2C_DEVELOPMENT_TEAM}" --preflight
```

Then create and verify a distribution-signed IPA without uploading it:

```bash
apple/archive-for-testflight.sh \
  --team "${R2C_DEVELOPMENT_TEAM}" \
  --marketing-version "${R2C_MARKETING_VERSION}" \
  --build-number "${R2C_BUILD_NUMBER}"
```

Unless explicitly told to reuse an unchanged gate, the script runs the full
`apple/release-check.sh`: App Store metadata/privacy checks, protected-land
catalog checks, native XCFramework rebuild and symbol verification, portable
anomaly regression, color/person qualifications, Swift tests, a clean arm64
Simulator link, and a fresh unsigned device archive. It then exports and
verifies one signed TestFlight IPA.

The output path is printed by the command. Record the IPA SHA-256, version,
build, bundle ID, signing team, and gate result:

```bash
shasum -a 256 apple/Build/TestFlight-126/*.ipa
```

Do not use `--skip-release-check` unless the full gate just passed on exactly
the same source and the unsigned archive named by the command is the verified
one.

### TestFlight upload and physical qualification

Upload only after reviewing the signed IPA evidence:

```bash
apple/archive-for-testflight.sh \
  --team "${R2C_DEVELOPMENT_TEAM}" \
  --marketing-version "${R2C_MARKETING_VERSION}" \
  --build-number "${R2C_BUILD_NUMBER}" \
  --archive-path apple/Build/RID2CaltopoApple-unsigned-126.xcarchive \
  --export-path apple/Build/TestFlight-upload-126 \
  --skip-release-check \
  --upload
```

Replace the example build in paths with the selected build. The reuse command
must point to the unsigned archive produced by the immediately preceding full
gate. The separate export path prevents overwriting the locally verified IPA.
If that archive or source changed, run the full command again instead. Confirm
the exact source commit is tagged and pushed before adding `--upload`.

After App Store Connect processing, assign that exact TestFlight build to
internal testers first. Do not pass `--internal-only` for a build that may
proceed to external TestFlight or the App Store; that option marks the uploaded
build as permanently restricted to internal testing. Install the processed
build and complete [Apple release acceptance](apple/RELEASE_ACCEPTANCE.md),
including a fresh [device qualification
report](apple/DEVICE_QUALIFICATION_REPORT.md) for each required device.
Simulator or archive success is not physical proof.

Submit for App Review only after all required evidence is green. Release the
approved build publicly only after one final smoke test and review of the
current App Store Connect checklist.

### Apple rollback

Pause a phased release or remove the version from sale when appropriate. Apple
does not provide a true binary downgrade for users who already installed the
build; prepare a corrected build with a higher build number. Preserve the
failed artifact and evidence for incident analysis.

## Commit, tag, and publish the source

After both full gates pass and before any store upload, ensure the worktree is
understood and the release commit is pushed. Use the established synchronized
tag form:

```bash
R2C_MOBILE_VERSION=2.0.4
R2C_MOBILE_BUILD=126
git status --short
git tag -a "v${R2C_MOBILE_VERSION}(${R2C_MOBILE_BUILD})" \
  -m "RID2Caltopo ${R2C_MOBILE_VERSION} (${R2C_MOBILE_BUILD})"
git push origin main
git push origin "v${R2C_MOBILE_VERSION}(${R2C_MOBILE_BUILD})"
git describe --exact-match --tags HEAD
```

Choose the actual next version/build rather than copying these examples.
Store submissions must be built from that exact tag. If source changes after
tagging, create a new commit, higher build number, and replacement tag; do not
move a published tag.

## Coordinated release checklist

- [ ] Mobile release owner and platform publishers named
- [ ] One version/build selected in Android and both Xcode configurations
- [ ] Unified notes created, synchronized, rendered, and reviewed
- [ ] Google Play Test and release findings expanded, investigated, and recorded
- [ ] Complete diff/untracked-file and secret-hygiene review finished
- [ ] Android full gate, AAB signature, and artifact hashes recorded
- [ ] Apple full gate, IPA signature, and artifact hash recorded
- [ ] Android release APK physically qualified
- [ ] Exact TestFlight build physically qualified on required Apple devices
- [ ] Cross-platform tracker/CalTopo interoperability checked where affected
- [ ] Exact source commit reviewed, tagged, and pushed
- [ ] Android internal release checked before production promotion
- [ ] Apple internal TestFlight checked before App Review submission
- [ ] Store rollout monitoring owner and rollback/forward-fix plan assigned
- [ ] Production Tracker Android recommendation matches the available Play build; older-client prompt and Play link verified
- [ ] Final store versions, dates, links, and known issues recorded

Release-note details are in [the unified release-note guide](release-notes/README.md).
