# Remaining startup and resume review — 2.5.5 (432)

**Follow-up complete:** the findings below were repaired, retested and installed
on both the A5 Pro and iPad. See the current
[fix and installation record](/Users/kjt/Projects/RID2Caltopo/docs/validation/2026-10-28-startup-follow-up-fixes.md).
This document retains the original findings and reproduction evidence.

Reviewed October 28, 2026, after the requested in-place installations. This is a
review of the current candidate, not a claim that every startup failure has been
eliminated. No product sources were changed during this installation/review pass;
the devices retain a stable build for the owner's manual checks.

## Installation result

| Device | Installed identity | Completion (PDT) | Evidence |
| --- | --- | --- | --- |
| A5 Pro, SM-X350 | Android development Debug 2.5.5 (432) | October 28, 18:04 | [Install record](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/a5pro-requested-reinstall-record.json) |
| Ken's iPad Pro 11-inch, iPad13,4 | Optimized development Debug 2.5.5 (432), Swift `-O` | October 28, 18:06 | [Install record](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/ipad-install-record.json) |

Android used `adb install -r`; Apple used `devicectl device install app`. OS
metadata verified both versions. Existing data, settings, credentials and
permissions were preserved. Neither physical app was launched or exercised. No
backup, private-data inspection, uninstall or reset was performed. Artifact
hashes and exact UTC installation times are in the records above.

## Findings, ordered by priority

### 1. P1 — duplicate saved aircraft IDs terminate iOS during launch

**Confirmed in the current Release Simulator app.**

[AppleDroneConfirmationStore.init](/Users/kjt/Projects/RID2Caltopo/apple/App/RIDAircraftDetailView.swift:255)
passes saved `org.ridMappings` entries directly to
`Dictionary(uniqueKeysWithValues:)`. Two entries with the same nonempty
`remoteID` trigger a Swift fatal error. The store is constructed by
[ContentView](/Users/kjt/Projects/RID2Caltopo/apple/App/ContentView.swift:124), so an
affected saved profile cannot reach usable application UI.

The same unchecked assumption occurs in
[applyImportedMappings](/Users/kjt/Projects/RID2Caltopo/apple/App/RIDAircraftDetailView.swift:449)
and [replacePersistedMappings](/Users/kjt/Projects/RID2Caltopo/apple/App/RIDAircraftDetailView.swift:480).
The organization-config decoder
[accepts repeated remote IDs](/Users/kjt/Projects/RID2Caltopo/apple/Sources/R2CCore/OrgConfigInterop.swift:420).
Consequently a duplicate import can also terminate the process. This review does
not establish that duplicates exist on either owner's device.

Reproduction created a new disposable Simulator, installed the existing 2.5.5
Release Simulator app, seeded only synthetic terms acceptance and two aircraft
mappings with `remoteID=REVIEW-DUPLICATE`, then launched it. Output included:

```text
Fatal error: Duplicate values for key: 'REVIEW-DUPLICATE'
```

The Simulator was shut down and deleted afterward. Physical devices were never
selected. `simctl launch` returned zero despite the application's fatal error;
the captured application output, rather than that command status, establishes
the failure. See the
[result](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/startup-review/ios-duplicate-mapping-reproduction.json),
[launch log](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/startup-review/ios-duplicate-mapping-launch.log)
and [reproducer](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/startup-review/reproduce-ios-duplicate-mapping.py).

Recommended repair: validate and prepare the entire mapping snapshot on a worker;
use an explicit duplicate/conflict policy at all three entry points. Preserve
the original saved data and report conflicts rather than silently discarding
records. Test both identical duplicates and conflicting duplicates in saved
profiles and imported configurations, including relaunch after recovery. Moving
this code off Main alone will not prevent the fatal error.

### 2. P1 — Android pending-photo completion can let a disk failure escape

**Static finding; no failure injected on a physical device.**

The startup-created
[retry loop](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt:4396)
resumes pending clues once an incident map is active. Its upload callback starts a
new [viewModelScope.launch](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt:2889)
whose disk update has a `finally` cleanup but no exception handler. The enclosing
callback's `try/catch` cannot catch a later failure inside that launched coroutine.
The disk operation runs on IO, but an exception resumes the suspended coroutine
on Main and can reach uncaught coroutine handling. This follows Kotlin's
[launch exception semantics](https://kotlinlang.org/docs/exception-handling.html).

[recordUploadResult](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/video/SnapshotStore.kt:219)
mutates its in-memory record and persists the index.
[PlainFileSafeWriter](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/data/FlightArchiveWriter.kt:38)
can throw on unavailable storage, a full filesystem or failed durable write.
Therefore this is a concrete unhandled error path after pending upload completion,
not a claim that every cold launch uploads or crashes.

Recommended repair: handle failure inside the launched coroutine; propagate
cancellation, retain the queued photo and last durable state, log/show a retryable
error, and avoid marking an upload durably recorded before the save succeeds.
Review sibling hydration launches for the same boundary. An isolated fault test
should fail the index write after simulated upload success, assert process
survival and retained data, then verify successful retry and relaunch consistency.

### 3. P2 — iOS startup and authentication still query Keychain on MainActor

**Execution context confirmed by source; a watchdog termination was not reproduced.**

`AppleCaltopoSettings` is MainActor-isolated. Its
[initializer](/Users/kjt/Projects/RID2Caltopo/apple/App/AppleCaltopoSettings.swift:117)
synchronously calls
[SecItemCopyMatching](/Users/kjt/Projects/RID2Caltopo/apple/App/AppleCaltopoSettings.swift:425)
while ContentView constructs it. `AppleOrgConfigSettings` is also MainActor-isolated;
its [trackerAPIKey getter](/Users/kjt/Projects/RID2Caltopo/apple/App/AppleOrgConfigImporter.swift:432)
performs another synchronous Keychain read on every access. The
[authentication requirement getter](/Users/kjt/Projects/RID2Caltopo/apple/App/ContentView.swift:1502)
calls it from view/authentication/resume paths.

Apple documents that
[SecItemCopyMatching blocks its calling thread and can hang the UI](https://developer.apple.com/documentation/security/secitemcopymatching(_:_:)).
MainActor isolation does not bound security-service latency. Contention could
stall startup or resume; a sufficiently prolonged stall creates watchdog risk.
This violates the project's requirement to move potentially slow work to a
worker before it starts, even when normal reads appear fast.

Recommended repair: serialize Keychain operations on a dedicated worker and
publish a small credential-status snapshot to MainActor. Represent loading,
unavailable and missing separately; preserve fail-closed authentication and
existing private Keychain storage. Invalidate appropriately for device lock and
account/configuration changes. Test delayed/unavailable Keychain responses,
rapid lock/resume and stale completion rejection while a UI heartbeat continues.

### 4. P2 — Android BLE start/stop lacks a synchronous platform-error boundary

**Static finding; permission/radio race not reproduced.**

[ScanningService.startScanning](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/app/ScanningService.java:125)
sets its running flag and constructs/starts the Bluetooth scanner from the service
startup callback without a recovery boundary. The scanner checks radio state and
SCAN permission, then calls
[startScan](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/opendroneid/android/bluetooth/BluetoothScanner.java:243)
without catching synchronous platform errors. The
[stop path](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/opendroneid/android/bluetooth/BluetoothScanner.java:264)
has the same pattern. Preflight checks cannot guarantee unchanged OS state at the
subsequent call; asynchronous `onScanFailed` does not handle a synchronous throw.

Android documents the
[runtime SCAN permission requirement](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner).
The supported Android 11 implementation calls a
[state check](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-mainline-release/core/java/android/bluetooth/le/BluetoothLeScanner.java)
that [throws when the adapter is off](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-wear-wear-input-release/android/bluetooth/le/BluetoothLeUtils.java).
The inferred failure scenario is a radio transition or permission change between
the app's checks and platform interaction. It is not an explanation of the
previous Samsung archive-provider crash.

Recommended repair: check required SCAN/CONNECT permissions before adapter calls,
handle expected security/state failures at the platform boundary, unwind partial
scanner state and expose a retryable radio/permission condition. Test permission
denial/revocation and adapter shutdown during start, restart and stop using an
isolated test fixture. Keep service state consistent after a failed start.

## Scope, confidence and next release checks

Traced Android settings/application initialization, disclaimer persistence,
stream construction, pending-flight recovery, local clue hydration/upload retry,
scanner startup, map overlay refresh and recording setup. Traced Apple app-root
and StateObject construction, organization/authentication gates, flight/clue
loading, location startup, browser protection and MediaMTX startup.

The prior worker/error boundaries for settings, flight-journal recovery, prepared
geometry and browser storage-policy work remain useful. The duplicate-mapping
fixture demonstrates that passing the previous startup qualification does not
cover every saved-data shape. Main-thread disk detection also does not establish
that synchronous Keychain IPC is safe. Different failure classes need separate
fixtures and explicit assertions.

Before distribution, address the findings and extend the release gate with the
specific saved-data, callback-write, credential-latency and radio-transition cases
above. Retain the existing provider-fault and journal-preservation checks. Tests
should assert recoverable visible state, durable data preservation, cancellation,
stale-result rejection and a responsive Main thread, rather than only a successful
launch command. Run those cases on owned emulators/Simulators; physical-device
functional testing remains the owner's current activity.

Only the isolated duplicate-mapping reproduction was added/executed in this
review. Existing broad suites were not rerun because product sources were not
changed. The newly identified repairs are **not yet in the installed builds**.
Source/artifact hashes for this review are retained in
[review provenance](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/startup-review/review-provenance.json).
