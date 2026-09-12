# Aircraft readiness: local implementation status

September 11, 2026. Tracker v1.4.90 is public, and the operator verified the updates on iPad build 202. Android and Apple source is being recorded in v2.2.7; see `../release-notes/2.2.7/verification.md`. Store publication and broader field qualification remain separate.

## Source recovery

Verified source archives and Git bundles are in `outputs/source-snapshots/20260911-085233-pre-aircraft-readiness`. Its README identifies the authoritative pre-edit archives and their manifests. The snapshots preserve the earlier working-tree changes; unrelated map, video, terrain, and environment work was retained.

## Implemented initial workflow

- Both RID Map Entries editors include aircraft serial number, FAA registration, defined base weight, accessories/batteries with weights and choose-one groups, required equipment, and air-traffic monitoring equipment. Android's settings/backup format and both platforms' imports, exports, and managed configuration paths carry these fields.
- Organization aircraft edits require fresh `config_admin` authorization. Tracker requires a configuration administrator to request and approve a configuration snapshot. Responses are bound to the requested organization and source device, including legacy enrollments without member attribution. Legacy snapshots retain existing readiness fields for matching RIDs; modern snapshots that unexpectedly omit them are rejected. An immutable aircraft record identifier retains service history when an administrator corrects a RID; duplicate serials and record identifiers are rejected.
- The Tracker Aircraft status page is linked from the organization dashboard and Drone Confirmation. `r2c_device` members can submit attributed problem/remediation reports; `records_viewer` can inspect status/history. Reporting uses the actual signed-in browser member, rather than assuming the tablet's enrollee is the current operator.
- Service events have idempotent IDs, revision checks, immutable notes, reporter identity, receipt time, and optional client report time. Stale reports remain conflicts. A durable email outbox notifies active `equip_manager` members and retries delivery independently. Missing equipment-manager assignments are visible.
- Once the status page is open, browser drafts survive a failed connection. Reopening the page as the same member retries them. Conflicts remain available for review; changing the signed-in operator cannot silently reattribute a draft. This does not promise background delivery after the browser is closed or first-time page access without connectivity.
- Members and Delegated Roles includes pilot callsign, certificate number/date, initial knowledge test/training date, recurrent-training date, and independent pilot status. Profile changes retain an attributed revision history. Callsigns are unique within an organization.
- Drone Confirmation offers the eligible organization RPIC roster, saved aircraft accessory choices, optional payload description/weight, calculated weight, and the last synchronized service status. Non-organization pilot callsigns retain free-form entry. Unknown weights remain unknown. Aircraft and pilot snapshots travel with the flight archive; unresolved legacy attribution remains unresolved. The normal Continue action remains available with incomplete information or no roster. Entered callsigns are retained; unmatched pilots receive a warning and unresolved attribution without blocking mission progress. Tracker uses the explicit flight pilot rather than inferring RPIC from a saved aircraft routing label.
- Service and pilot-profile changes notify connected organization devices to refresh. Apps retain a dated roster/status cache for offline use; rejected credentials clear access to the cached roster used for new selection. Saved aircraft configuration and status are separate so configuration changes do not reset service history.
- The existing records administration page links each flight to an RPIC/configuration/payload correction form. Corrections require `records_admin`, an explanation, and a matching revision. The original submission remains intact. Reimports preserve reviewed corrections, and CSV exports include current values and correction history. A completion filter identifies unresolved pilot or weight information.
- The earlier removal of short/hover-flight import thresholds remains included.

The initial knowledge date is separate because certificate issue dates are not the basis of knowledge recency. Currency checks use the recorded knowledge test/training dates and calendar-month boundaries; they do not query an FAA certification database. Reference: [FAA remote-pilot certification and recency guidance](https://www.faa.gov/uas/commercial_operators/become_a_drone_pilot).

## Local validation

- Android: compilation and 1,009 unit tests passed, including readiness/weight calculations and the settings backup round trip.
- Apple: the arm64 Simulator app compiled and linked. The shared suite ran 303 tests; the readiness tests passed. One existing terrain-prefetch source assertion failed at `apple/Tests/R2CCoreTests/R2CCoreTests.swift:676`. Both that test file and `AppleTerrainElevationService.swift` match the pre-change snapshot; the expected old function signature was already absent before this work.
- Tracker: 385 Python tests passed, including role/tenant/CSRF checks, status revisions and retries, aircraft identifier correction, pilot recency boundaries, failed email delivery, historical payload completion, and replay protection.
- Browser queue: four JavaScript tests passed for offline retention, acknowledged upload, stale-report conflicts, stable retry IDs, and storage-unavailable fallback. These tests use a simulated browser environment, not a device browser.
- Tracker's disposable-database migration checks and localhost HTTP/coordination checks passed. Source whitespace checks passed in both repositories.

## Remaining qualification and next step

The brief reusable incident and flight checklists are the subsequent implementation step in the agreed specification. This initial implementation does not record checklist completion; records administrators can append clearly post-flight checklist notes without changing the original confirmation time.

Before team rollout, exercise the workflow in an isolated organization with Android and Apple devices, distinct operator/admin accounts, real email delivery, reconnects, and short test flights. Verify that aircraft edits survive app restart and managed download, that shared-device reports carry the correct signed-in member, and that a payload correction survives an archive reimport. Mobile UI operation, physical controller behavior, and field readiness are not established by these source checks.

Deploy a compatible Tracker before distributing these app changes to an organization. The new RPIC/configuration flow depends on its readiness endpoint. No live service, team notification, store submission, or device installation was performed by this implementation run.

## September 11 compatibility and mission-continuity refinement

The Tracker update retains existing archive and coordination interfaces and configuration schema version 1. Readiness and pilot fields are optional for legacy submissions. Legacy source configuration requests remain available to administrators, and matching aircraft retain readiness fields that the source app cannot represent. This was checked against the saved pre-change Android and Apple parsing paths and legacy-payload regression tests; it is not a physical test of every historical app binary. Existing build requirements for older optional features are unchanged.

Both apps use a normal, enabled Continue action. Missing pilot, aircraft description, weights, service status, network access, or roster qualification does not prevent confirmation and recording. An entered unmatched callsign is retained without a member ID or an assertion of qualification. Blank organization uses the saved organization where available. Administrative configuration permissions remain separate from mission continuation.

The updated Apple app compiles for the arm64 Simulator, and all three focused readiness tests pass. The prior terrain-test limitation above remains unchanged. No deployment or app distribution has occurred.
