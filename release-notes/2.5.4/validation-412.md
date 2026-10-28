# 2.5.4 build 412 — credential transfer removal and clear privacy wording

October 24, 2026. Both tablets now report **2.5.4 (412)**, launched normally and
retained their checked operational files. This follows the 407 release gate and 408 wording correction.
Build 409 contained the credential-transfer and backup implementation; 410/411
refined the displayed explanation and terminology. Build 412 also fixes the
isolated Android CalTopo browser startup order. Final installation results and
artifact hashes are recorded in `apple/Build/ReviewCandidate-412/`.

## Resulting behavior

- Legacy organization/FAA credential exports and credential file/QR imports are
  removed on both clients. Map-only exports/imports and authenticated tracker
  enrollment remain available. Credential-bearing old map packages are rejected.
- Settings backups contain allowlisted preferences and aircraft mappings. Unknown
  fields, CalTopo/tracker/FAA secrets, enrollment tokens and access profiles are
  omitted. Older backup input is filtered through the same policy.
- Backup creation does not change the live configuration. Settings restore keeps
  credentials already stored on that device. CalTopo Teams and organization
  credentials persist across app launches and updates and are reused normally;
  there is no per-connection credential-entry requirement.
- Android system backup uses a sanitized staging file, rather than the live
  protobuf store. Old raw backup entities are ignored/tombstoned.
- Apple excludes private settings/browser/provisioning/export directories from
  container backup, preserving operational imagery/mission/map eligibility.
  Keychain items are device-bound while retaining their prior unlock constraints.
  These items can still be recovered to the original device by Apple system
  backup. This is not complete exclusion of Keychain secrets from all OS backups.
  No new device-passcode requirement was imposed. Using Apple's stricter
  passcode-bound class remains an owner choice; it would prevent same-device
  backup recovery and erase those items if the passcode is removed.
- Privacy describes current behavior; removal history is in release notes.
  About & Privacy says "map tile packages" and uses a camera symbol beside the
  camera explanation. Remaining user-facing profile labels say organization.
  Internal storage keys and tracker protocol fields retain compatibility names.
- The store description now describes tracker enrollment and credential-free map
  imports instead of advertising retired organization/FAA credential QR imports.
  Video recording, remote-viewing approval, sharing and microphone behavior have
  not changed in this follow-up.

## CalTopo isolated-browser startup correction

Final 411 checks exposed `WebView already initialized` in the isolated
`:caltopo_probe` process. A provider Binder call could initialize CookieManager
before Application.onCreate set its private data-directory suffix. The suffix
is now configured in attachBaseContext, before providers are published. It keeps
the same cookie-store name and does not clear or migrate saved sign-in data.
This bounded startup setter only checks/assigns process configuration; it does
not load the WebView provider or perform storage/network work. The existing
separate-process boundary remains.

This ordering follows Android's [WebView API requirement](https://developer.android.com/reference/android/webkit/WebView#setDataDirectorySuffix(java.lang.String)).
The [platform setter](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/webkit/WebViewFactory.java)
confirms its bounded in-memory operation. Retained before/after device crash
buffers provide physical startup evidence without clearing diagnostic records.

## Validation and execution context

- Final backend validation on 409: **1,614 Android host tests in 251 suites**, zero
  failures/errors/skips; **140 XCTest + 590 Swift Testing tests**, all passed.
  New tests cover backup allowlisting, preservation of existing credentials,
  rejection of credential-bearing packages and retired FAA imports.
- Build 411 passed **16 focused label/lifecycle tests**. Build 412 passed
  **25 focused personal-session/security tests** in six suites and fresh Android
  compilation. Apple has fresh Release compilation, signed device/distribution
  exports, package version/notes and metadata/privacy checks. The full 409 suite
  is earlier backend evidence; it was not rerun after the 412 startup-order fix.
- Every final package is checked against canonical release-note bytes. Apple
  packages contain the explicit saved-credential persistence and map-tile wording;
  the old privacy removal sentence and retired credential-export buttons are
  absent. Apple declarations cover twelve data categories and two bundled
  privacy manifests. No live store answers are verified by these checks.
- Apple backup encoding/decryption, mapping preparation and directory/Keychain
  protection run on detached workers/background actors. Prepared settings return
  to Main only after cancellation, edit-permission and stale-result checks.
  Mapping writes are ordered on a serial worker. Android parsing is outside the
  cache lock; cache commits and persistence scheduling retain order. OS backup
  restore uses its Binder worker and durable executor, never a Main-thread wait.
- A normal iPad 410 launch confirmed private-directory protection succeeded and
  Keychain migration reported `pending=0`, without reading credential values.
- The optional S3 SDK fixture was corrected to advertise each object's actual
  MD5 instead of reusing another object's fingerprint, removing an order-dependent
  test failure. DJI production behavior did not change for that test repair.
- Existing native conversion/deprecation/result-builder warnings remain. JDK 25
  emitted JaCoCo class-version warnings; passing JUnit results are not a claim of
  complete coverage instrumentation. Xcode's contradictory exit-code-zero task
  diagnostics were accompanied by successful fresh output/signature checks.
- Build 407 remains full native/shared/Simulator gate evidence. This work did not
  repeat that entire gate, exercise an actual OS backup/restore, or establish
  exact-build flight, sustained-media, background, thermal or hardware acceptance.

## Final packages

All three packages identify 2.5.4 (412), with exact canonical notes and signature
checks. SHA-256 values:

- Android development APK: `4e61f90a41b21ad68fa7879a4c5726daa808f810239080c0687d28e10a498364`.
- Apple device IPA: `d5df5ba975d826d41170f263f1d6f3e6fd6611d5fad7a72dbb709b88bb1a1e9c`.
- Apple distribution IPA: `182181201b73bc6956dd000f0b7ff7789e016dc898c4e11a5b92f75e00c04b3a`.

Authoritative 412 hashes are in [artifacts.json](../../apple/Build/ReviewCandidate-412/artifacts.json),
with [source inputs](../../apple/Build/ReviewCandidate-412/source-inputs.json).
The Apple distribution export is retained locally; no upload was performed.

## Android physical startup result

Build 412 installed in place and reported 2.5.4 (412). Normal activity launch
returned `Status: ok`. Both the main and isolated browser processes were running
(PIDs 4976 and 5071 at verification). Comparing retained crash-buffer process
records before/after startup found no new app crash. All 264 backed-up private
operational paths remained present. This checks the observed startup failure;
it is not end-to-end sign-in/provider-account qualification.

## Device-data protection

Operational-only safety backups were read back before in-place installation.
They exclude configuration/authentication/provisioning stores: Android has 264
private operational files plus two removable-map-cache files; iPad has 15,351
operational files totaling 7,401,165,077 bytes. The continuous 410–412
follow-ups reuse this safety baseline. Android updates use `adb install -r`; Apple
updates retain the existing app identity. No uninstall, data clearing, managed
Android instrumentation runner or device reset is used.

Final iPad checks found all 15,351 baseline operational files present; the
public-land cache refreshed its location/timestamp normally (one size change).
All other baseline sizes were unchanged. Both removable Android map-cache files
were also still present. Saved credential values were not read for these checks.

Older backups and credential-sharing copies already held locally, in cloud
storage or by recipients were not automatically deleted. Remove those separately
and revoke/replace credentials where appropriate.

## Before resubmission

The [original qualification and owner-input list](validation.md#input-needed-before-resubmission)
still applies, using **412** for the next candidate: exact-build hardware acceptance
and demonstration, isolated review organization/map/access, rights/seller/export
and privacy/age declarations, public policy publication, live console updates and
explicit upload/resubmission authorization. GitHub permission remains read-only;
no policy publication, store upload, resubmission, commit or tag is claimed here.
