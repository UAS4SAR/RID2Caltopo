# Operating profiles: implementation handoff

## Next task

Implement organization-managed operating profiles across Android, Apple, and Tracker. The user accepted this design and authorized implementation after snapshotting. This long task stops at the verified snapshot and handoff; operating profiles have not been implemented here. Continue in the existing repositories, preserving all prior uncommitted changes. Do not deploy, publish, install on devices, or send team notifications as part of source implementation.

Repositories:
- `/Users/kjt/Projects/RID2Caltopo` — Android and Apple, maintained as peer platforms.
- `/Users/kjt/Projects/r2c-tracker` — organization control plane, records and website.

Snapshot: `/Users/kjt/Projects/RID2Caltopo/outputs/source-snapshots/20260911-pre-operating-profiles`. Read its README and manifests. Archives include current tracked files and selected untracked source; Git bundles preserve history. They are not production database or device backups. The earlier snapshot under `20260911-085233-pre-aircraft-readiness` predates the readiness work and is not the next-task baseline.

## Accepted operating-profile behavior

1. Add a compact Operating profile selector to existing Drone Confirmation. Choices include Standard Part 107 — no operational waiver; the organization's default waiver; other configured waivers; and Other / details pending.
2. `config_admin` manages profiles and the organization default. Store stable profile ID, version, display name, operating authority/type, waiver number and holder, effective dates, document, applicability to pilots/aircraft/locations, and relevant conditions. Organization members receive the applicable profiles automatically and can use cached profiles offline.
3. An organization with its waiver configured uses that default. An organization without one defaults to Standard Part 107. Do not infer that organization membership itself confers a waiver.
4. RPIC, or VO entering information for the RPIC, may select a different profile for the flight without changing the organization default. Offer Use for this assignment to reuse the selection across batteries. Clear that assignment preference on assignment or organization change; do not carry it indefinitely onto unrelated missions. Use stable incident/assignment identifiers, not display strings alone.
5. Keep the active profile visible while operating. In-flight changes record the time and old/new profile, rather than retroactively applying the new profile to the whole flight. Do not infer that changing a setting grants authority for any operation.
6. Missing, expired, stale, or apparently inapplicable profiles produce advisory warnings and unresolved review flags. Continue must remain available. No network, qualification, equipment, checklist, service-status, or profile gate may stop mission recording, maps, video, or continuation.
7. Persist the selected profile ID/version and the conditions used with each flight. Later edits/downloads must not rewrite historical flight records. `records_admin` may correct historical selection with a reason and attributed before/after history, using the existing correction model.
8. Profile conditions drive brief checklists and advisory guidance, including RPIC range, altitude reference/limits, VO requirements, equipment, and RTH. Store source-provision references and readable conditions. Conditions not representable reliably in code must remain readable briefing items, not silently disappear. Do not treat automated extraction from a PDF as a verified rule set.
9. Keep ATO, AGL, terrain/surface information and general safety information available regardless of profile. Remove assumptions specific to the NCSSAR waiver when a different profile is selected; do not remove general situational awareness.
10. Keep airspace authorization distinct from operational waiver selection. Use an extensible operating-authority model for future public-aircraft/COA support; do not mislabel a COA as a Part 107 waiver or assume identical pilot-qualification rules. Initial behavior must distinguish unresolved authority without inventing certification.

## Existing local work to preserve

- Aircraft readiness fields and accessories/weights in both RID Map Entries editors, managed configuration and backup paths.
- Organization configuration editing restricted in the new apps to authorized administrators; Tracker administrator-requested snapshots and separate approval remain compatible with old enrolled devices.
- In/out-of-service reporting with attributed notes, optimistic revisions, durable equipment-manager email outbox, browser retry drafts, tenant/role/CSRF checks.
- Member pilot certificate, original certificate date, actual initial knowledge date, recurrent-training date, status and unique callsign; organization roster and offline cache.
- Normal Continue works with incomplete details and unmatched pilot callsigns. Keep entered callsigns as reported, with unresolved qualification and a remedy message. Do not reintroduce pilot selection as a launch gate.
- Flight readiness snapshots and records-admin payload/configuration/RPIC corrections retain original submissions and revision history; replay does not erase reviewed corrections.
- Tracker accepts short flights and stationary hovers. Older archives need not contain readiness or profile metadata.
- New device-side short-flight choice: only when BOTH distance <0.1 statute mile and observed duration <60 seconds; nonmodal Yes/No panel; automatic Yes at 10 seconds and on backgrounding. No skips this device's pending flight log/upload, not already-reported records or video. Preserve this behavior.
- Many unrelated map, terrain, video, diagnostics and release files are dirty. Do not restore, clean, or overwrite them. Inspect current changes before editing.

## Useful implementation locations

Tracker:
- `main.py`: managed configuration validation, legacy snapshot response/merge, organization admin routes, imports, coordination refresh, lifespan.
- `aircraft_readiness.py`: pilot/service models, organization device access, compatible legacy aircraft-field preservation, roster API, service/profile routes.
- `flight_readiness_records.py`: original/effective snapshots, attributed corrections, records editor and exports.
- `control_plane.py`: organization roles, configuration proposal/release persistence, database metadata.
- `templates/organization_admin.html`, `templates/flight_readiness.html`, `templates/admin.html`.

Android:
- `data/OrgConfigManager.kt`, `AppConfigStore.kt`, `AircraftOrganizationAccess.kt`, `FlightReadiness.kt`, `CtDroneSpec.java`, `WaypointTrack.java`.
- `ui/R2CView.kt` DroneSpecConfirmationDialog, `R2CViewModel.kt` savePendingDroneConfirmation, `FlightReadinessFields.kt`.
- Audit current compliance/advisory code for hardcoded waiver conditions; `ui/ComplianceAlertHost.kt` may be present from adjacent work. Do not assume previously reported tests qualify unrelated newer changes.
- `data/ShortFlightRecording.kt`, `ui/ShortFlightRecordingPanel.kt`.

Apple:
- `App/AppleTrackerCoordinator.swift`, `AppleOrgConfigImporter.swift`, `AppleConfigurationTransfer.swift`, `CaltopoSettingsView.swift`.
- `App/RIDAircraftDetailView.swift` identity store and DroneConfirmationView, `RIDTrackViewModel.swift` archiving, `ContentView.swift` panels.
- `Sources/R2CCore/FlightReadiness.swift`, `OrgConfigInterop.swift`, `RidAircraftIdentity.swift`, `RidTrackGeoJSON.swift`, `ShortFlightRecording.swift`.
- New App files may require explicit Xcode project membership; new R2CCore package files are discovered by Swift Package Manager.

## Compatibility and tests

Retain configuration schema version 1 where compatible; do not force existing v2.2.6 users to upgrade to continue their existing missions. Preserve profile/readiness fields that old clients cannot represent during administrator-reviewed legacy configuration updates. New flight fields are optional to Tracker. Do not erase them on old app import/export or on a replay.

Test defaults and assignment resets, offline and stale data, expired profiles without blocking, cross-organization access, administrator vs pilot permissions, old client payloads, flight snapshots, in-flight changes and audited record corrections. Keep version and unit/vertical-reference handling explicit; the waiver has different AGL, obstacle-relative, horizontal-distance, and PRS/RTH conditions.

Previous local validation (rerun relevant checks for new edits):
- Android full suite: 1,013 passing tests before an additional explicit no-response short-flight test; five focused short-flight tests then passed.
- Tracker: 385 Python tests passed during the compatibility refinement; browser queue tests and disposable migration/localhost checks had also passed earlier. No Tracker changes were needed for the short-flight prompt.
- Apple arm64 Simulator app built after short-flight changes; five focused short-flight tests passed. Earlier full core suite had a pre-existing terrain-prefetch source-signature assertion failure at R2CCoreTests.swift:676; the affected terrain/test files matched the pre-readiness snapshot at verification time. Do not mask or fix unrelated work solely to make this assertion pass.
- Physical devices, controllers, email delivery, background behavior and field usability have not been qualified by these automated checks.

Typical commands:
- Android: `./gradlew :app:testDebugUnitTest`.
- Apple focused/core: `swift test --package-path apple` (observe the stated prior limitation).
- Apple build: `xcodebuild -quiet -project apple/RID2CaltopoApple.xcodeproj -scheme RID2CaltopoApple -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath apple/Build/readiness-check ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO -jobs 2 build`.
- Tracker: `.venv/bin/python -m unittest discover -s tests`; `./release_check.sh --skip-unit-tests` for disposable migrations/localhost smoke checks. These are not deployment commands.

## Separate discussed work

Lidar-derived canopy/building surface tiles, AOL within a proposed 200-foot horizontal neighborhood, assignment/route high-point briefings, and a separate wire/cable layer were discussed but are not implemented. These are separate from operating profiles. Keep ground AGL distinct from obstacle clearance; do not imply mapped positive clearance establishes absence of wires. The user prefers quiet numeric indications to nuisance audible obstacle alerts.

Supporting design documents: `docs/Aircraft_Readiness_and_Pilot_Records.md`, `docs/Aircraft_Readiness_Implementation_Status.md`, `docs/Short_Flight_Recording_Choice.md`. Original FAA waiver documents are in `BVLOS_Waiver/`. Consult exact documents for profile conditions; avoid assuming that every organization has the same waiver.
