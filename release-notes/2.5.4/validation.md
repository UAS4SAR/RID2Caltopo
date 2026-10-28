# Latest local candidate

Build 428 fixes settings help dismissal, removes redundant Developer Tools
shortcuts, and fixes an Android startup scan on the UI thread. It is installed
and running on both tablets. See [validation-428.md](validation-428.md) for the
automated UI tests, recording tests, launch checks and data-preservation checks.
The earlier private-storage exemption and storage migration are recorded in
[validation-426.md](validation-426.md) and [validation-425.md](validation-425.md).

# Apple candidate 2.5.4 build 407

The local distribution-signed candidate passed the complete Apple release gate
and package verification on October 24, 2026. It is ready for controlled
TestFlight/hardware qualification, not yet for an App Review readiness claim.
No upload, submission, policy publication, physical-device installation, data
clearing or device reset was performed during the initial gate. The subsequent
owner-requested device installations are recorded below.

## Artifacts

- [Signed IPA](../../apple/Build/TestFlight-407/RID2CaltopoApple.ipa), 38,648,622 bytes.
- SHA-256: `242b1b4385fadf10f6e34c59bc7c218aad1b62a8732c80d4cadcb26f9b6b1ed6`.
- Fresh unsigned archive: `apple/Build/RID2CaltopoApple-unsigned-407.xcarchive`.
- Bundle: `org.ncssar.RID2CaltopoApple`; version **2.5.4**, build **407**;
  universal iPhone/iPad arm64; Apple Distribution team `94UV79S6LR`.
- [Packaged evidence](../../apple/Build/ReviewCandidate-407/packaged-evidence.json)
  retains identity, hash, purpose strings, background modes and privacy categories.
- [Source change record](../../apple/Build/ReviewCandidate-407/source-inputs.json)
  records the uncommitted changes over base `4dfbd780`. No source tag was created.
  This is a record of this work's source changes, not a complete list of build
  inputs. The private DJI app-registration file is omitted from this record and
  from version control; its exact bytes are included and verified in the Apple
  and Android packages. Existing edits to `release-notes/2.5.1/whats_new.txt`
  predate this work and remain untouched; they are omitted only from this change
  record, not removed from the workspace or from app functionality.

Version settings are synchronized in the shared Android authority and both Xcode
configurations. Android distribution artifacts were not built or published.
The existing `release-notes/2.5.3/whats_new.txt` was left unchanged. Older build
342 (2.5.1) and build 406 (2.5.3) archives/packages were kept locally without being
overwritten or deleted. Build 407 was built into separate output paths.

## Build and automated results

| Check | Result |
| --- | --- |
| Full Apple gate | Passed, including fresh native dependency rebuilds, clean Simulator link and Release device archive. |
| Metadata/privacy | Twelve app data categories and two packaged manifests verified; note lengths, direct policy link, required reasons and policy/catalog date passed. Seven privacy-validator, four UI-copy and eight store-copy tests passed. |
| Protected-land sources | Seven verifier tests and all five live boundary-service checks passed. |
| Portable anomaly suite | 5,209 passed, zero failures. |
| Color/person relevance | Both qualification tasks passed. |
| Native mapping/FFmpeg/media | Native geometry, surface/pipeline, JNI, shared viewer, queue/telemetry and callback checks passed. |
| Shared mapping JVM tests | 157 cases, zero failures/errors, one optional SDK case skipped in the initial gate; that case subsequently passed independently. |
| Swift package | 140 core XCTest cases and 587 Swift Testing cases passed; mapping XCTest ran 31 cases with one optional local-flight-copy skip and zero failures. |
| Fresh iPad Simulator | Five image/fixture tests passed. Actual offline viewer rendered two regions with 1,922 and 1,000 triangles; switching regions loaded distinct geometry. The gate used and removed its own Simulator. |
| Independent official SDK | Focused boto3 test passed after correcting the test client's missing page-revision header. Covers byte-preserving single/multipart transfer, callbacks, camera/flight quarantine and storage-scope protections. |
| Signed export | Code signature, provisioning profile, team/bundle/version, no debug entitlement, app registration and app/SDK privacy verification passed. No upload flag used. |
| Package consistency | At packaging, bundled release notes matched the then-current canonical 2.5.4 bytes. The later wording revision below is not yet in these packages. Updated About & Privacy, microphone/settings and visible background-summary text confirmed in the binary; updated location/microphone purpose strings retained in the packaged plist. |

The optional Swift skip requires an explicitly supplied local flight copy; the
synthetic end-to-end reconstruction tests ran. This is not evidence of a fresh
real-flight recovery or hardware test.

Evidence logs are under `apple/Build/ReviewCandidate-407`: `preflight.log`,
`release-gate.log`, `export.log`, `jvm-mapping-summary.json`,
`independent-sdk-test.log`, `independent-sdk-test-rerun.log` and
`sdk-requirements.txt`.

## Diagnostic qualifications

- Xcode emitted contradictory `failed with exit code 0` compiler-task diagnostics.
  The build commands returned zero, produced fresh linked Simulator/device apps,
  and completed runtime/archive/signing verification. The original diagnostics
  remain in the gate log; no source-code failure was inferred from those messages.
- An additional binary-string probe initially expected text from an unused
  `ContentView.operationalNotesSection` helper. Release optimization omits that
  helper; the final probe verifies the actual visible About & Privacy background
  summary instead, as well as settings and purpose strings.
- The first independent SDK attempt failed with HTTP 409 because the test client
  omitted `X-R2C-Page-Revision: 2`. The server's existing guard behaved as intended.
  Only `tools/mapping/tests/test_native_cloud_s3.py` changed for this repair;
  product sources and the signed IPA were unchanged. The rerun passed with
  boto3/botocore 1.42.97 in a disposable local environment, using synthetic
  credentials and a localhost test server.
- JDK 25 triggered existing JaCoCo class-version instrumentation diagnostics.
  JUnit executed and passed the focused test; this is not a code-coverage claim.
- Existing deprecation, vendored conversion and result-builder warnings remain.
  No physical-device or sustained thermal qualification is implied by this gate.

## Input needed before resubmission

1. **Hardware acceptance and demonstration.** Use the exact processed TestFlight
   candidate on representative iPhone/iPad hardware. Record models/OS versions
   and demonstrate Remote ID/DS110, controller video, local mapping/Pilot 2,
   permission denial/recovery, screen lock/background operation, and sustained
   video. Check per-request approval versus enabled Remote Video Control and
   microphone input/OS indicators while outgoing voice is muted. Qualify fresh
   DJI registration on a separate test installation, preserving populated devices.
   Follow the project's backup and in-place update rules before physical tests.
2. **Review access.** Designate an isolated test organization, disposable CalTopo
   map and authorized remote requester. Supply durable review-only access through
   private App Review fields/attachments, not tracked notes or operational secrets.
3. **Owner declarations.** Confirm the intended seller identity versus UAS4SAR LLC
   branding, actual CalTopo/DJI/service rights, and encryption classification using
   the inventory in the [submission audit](../../apple/AppStore/SUBMISSION_AUDIT_2026-10-23.md).
   The existing non-exempt-encryption flag is retained; signing is not an export
   determination. Review the twelve linked/App Functionality privacy categories,
   voice-chat and unrestricted-web age answers, public-content classification,
   and Content Rights wording in the [worksheet](../../apple/AppStore/APP_STORE_CONNECT_CHECKLIST.md).
4. **Publication and account access.** Approve the selected policy/release source
   for publication and authorize TestFlight upload when ready. Read-only GitHub
   permission does not authorize pushing the policy. The saved browser-origin
   restriction left public policy URL verification pending. App Store Connect
   access is needed to save the current policy/privacy/age/review information,
   select 2.5.4 (407), and verify live agreements, availability and review state.

Before App Review, refresh store screenshots, attach the physical demonstration
and review access, complete exact-build acceptance reports, and finish the normal
reviewed-source commit/tag/release process. The [342 supplemental notes](../../apple/AppStore/review-submissions/2.5.1-342/review_notes.txt)
remain specific to Apple's earlier request; use current metadata for this candidate.

## Owner-requested physical-device installation — October 24

Both populated tablets were updated in place to **2.5.4 (407)**, launched normally,
and independently queried for installed version and a surviving app process.
No uninstall, data clearing, reset, physical instrumentation or store upload was
used. Installation is not the hardware acceptance/demonstration listed above.

- **iPad Pro 11-inch, third generation (`iPad13,4`):** the tested unsigned Release
  archive was exported with the installed Apple Development profile for direct
  installation. This is a separate signing export from the retained App Store
  IPA. CoreDevice installation and launch passed. Before installation, the app
  was stopped and its container copied privately: 16,629 files, 7,639,462,998 bytes,
  all backup files read and hashed. After installation and before launch, all
  15,366 files under Documents, Application Support and Preferences remained at
  the same paths and sizes. Keychain is outside this container backup; the
  installation preserved the app identity and did not reset protected storage.
- **A5 Pro (`SM-X350`, `R52Y90C9XST`):** a matching development APK built
  successfully, passed packaged app-registration and signature checks, and was
  installed using `adb install -r`. Private data plus primary/removable app-owned
  storage were archived and fully read for backup verification before updating.
  All 372 durable private file paths and both removable-storage files remained;
  activity launch returned `Status: ok` and the process remained running.

Backups and inventories are in the ignored, access-restricted
`.local-deployment/device-backups/2026-10-24-build407/`; they are not publication
artifacts. Non-secret build/install/launch evidence is in
`apple/Build/ReviewCandidate-407/`, including `ipad-device-install.json`,
`android-device-install.json`, both local-artifact JSON records, the Android
build log and the CoreDevice installation/launch logs. The original distribution
IPA SHA-256 above is unchanged. No DJI/Pilot flight or live media qualification
was performed by these installation checks.

## Release-note wording revised after installation

At the owner's request, the canonical 2.5.4 notes and App Store metadata copy were
rewritten in plain language. They explicitly state that recording, sharing and
approval behavior is unchanged, and identify the added link and explanatory-text
updates. The existing 407 packages and installed apps still contain the earlier
release-note wording. Rebuild the packages to include the revised in-app notes;
the earlier package hashes and validation evidence remain historical facts.

The [build 408 follow-up](validation-408.md) supersedes the rebuild-pending status
above. Build 407 artifacts and their original evidence remain retained separately.
