# 2.5.4 build 411 — credential transfer removal and clear privacy wording

October 24, 2026. This follows the 407 release gate and 408 wording correction.
Build 409 contained the credential-transfer and backup implementation; 410/411
refine the displayed explanation and terminology. Final installation results and
artifact hashes are recorded in `apple/Build/ReviewCandidate-411/`.

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

## Validation and execution context

- Final backend validation on 409: **1,614 Android host tests in 251 suites**, zero
  failures/errors/skips; **140 XCTest + 590 Swift Testing tests**, all passed.
  New tests cover backup allowlisting, preservation of existing credentials,
  rejection of credential-bearing packages and retired FAA imports.
- Build 411 adds fresh Android compilation and **16 focused label/lifecycle
  tests**, all passed; fresh Apple Release compilation, signed device/distribution
  exports, package version/notes checks and metadata/privacy checks. The 409
  backend test results remain applicable to the copy-only 410/411 follow-ups.
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

## Device-data protection

Operational-only safety backups were read back before in-place installation.
They exclude configuration/authentication/provisioning stores: Android has 264
private operational files plus two removable-map-cache files; iPad has 15,351
operational files totaling 7,401,165,077 bytes. The continuous 410/411 display-text
follow-ups reuse this safety baseline. Android updates use `adb install -r`; Apple
updates retain the existing app identity. No uninstall, data clearing, managed
Android instrumentation runner or device reset is used.

Older backups and credential-sharing copies already held locally, in cloud
storage or by recipients were not automatically deleted. Remove those separately
and revoke/replace credentials where appropriate.

## Before resubmission

The [original qualification and owner-input list](validation.md#input-needed-before-resubmission)
still applies, using **411** for the next candidate: exact-build hardware acceptance
and demonstration, isolated review organization/map/access, rights/seller/export
and privacy/age declarations, public policy publication, live console updates and
explicit upload/resubmission authorization. GitHub permission remains read-only;
no policy publication, store upload, resubmission, commit or tag is claimed here.

## Final checks and follow-up

Both devices reported 411 and launched normally. All 264 Android operational
files and both removable-cache files remained. All 15,351 iPad operational files
remained; one public-land cache changed size as its location/timestamp refreshed.
The main processes remained running. Crash-buffer inspection additionally exposed
an existing isolated CalTopo browser process startup failure: its cookie-store
suffix was configured after a provider could already initialize CookieManager.
This finding is addressed in [build 412](validation-412.md); use that follow-up
for final release review instead of this intermediate candidate.
