# Aircraft readiness and pilot records

Implementation specification, September 11, 2026. The initial aircraft, service-status, pilot, and historical-record workflows now have a local implementation. See `Aircraft_Readiness_Implementation_Status.md` for validation and remaining qualification. The brief reusable checklists remain the subsequent step described below. No production deployment or app publication has occurred.

## Purpose and agreed scope

Extend existing RID Map Entries, organization configuration distribution, Tracker member administration, and Drone Confirmation. Preserve fast emergency deployment: enter stable information once, report equipment issues during preparation, and confirm only current flight details at launch. Implement the same behavior on Android and Apple.

The initial scope is aircraft configuration and service status, organization permissions, and pilot member records. Incident and per-flight checklists will extend Drone Confirmation in a subsequent step. No GDL90 integration, field weighing requirement, new stand-alone aircraft editing panel, or document-upload subsystem is included in the initial scope.

FAA registration number belongs to the aircraft. Part 107 certificate number, original certificate date, and latest recurrent-training date belong to the pilot member. The user confirmed this distinction.

## Aircraft identity and RID Map Entries

An aircraft is identified within an organization by its Serial Number/Remote ID, never by callsign, owner name, or model. Use an immutable internal record ID to preserve history when an authorized administrator corrects an identifier. Enforce organization-scoped identity uniqueness. If hardware serial and broadcast RID differ, store both explicitly; do not silently assume every broadcast identifier is the hardware serial.

Retain the existing Remote ID, owner, callsign, and model fields. Add FAA registration number, base weight, accessory definitions and weights, required-equipment information, and monitoring equipment. Carry these through the existing organization configuration upload/release and automatic download paths. Round trips through export/import and both apps must preserve the added fields.

Outside organization membership, retain existing local editing. Once an instance belongs to an organization, only an authenticated member with config_admin may add, edit, delete, replace, or import changes to these authoritative fields. All other members see them read-only. Organization owners normally already have config_admin; do not invent another permissive client-side bypass.

Enforce authorization in Tracker as well as the app. A read-only form is insufficient: bulk imports, QR/config imports, device snapshot uploads, restores, and old client payloads also require protection. Server checks use current active membership and role, not a role supplied in the request. Automatic downloads remain allowed. Legacy clients must not erase new fields by sending an older schema.

Follow the existing reviewed organization configuration publication workflow. Uploading a candidate is not the same as publishing it. Routine service-status changes use a separate immediate path and do not wait for configuration approval.

## Weight and flight configuration

Define base weight explicitly as the measured aircraft with its permanent equipment, excluding the selectable removable battery, accessories, and variable payload listed separately. The UI must state what is included to avoid counting equipment twice. Where a team's existing measurement includes an accessory, migrate it deliberately rather than guessing.

An administrator defines applicable accessories and their measured weights: PRS, strobe, loudspeaker, standard battery, extended battery, payload drop system, and other approved equipment. Store applicability and required-equipment flags separately from installed-for-this-flight selection. Built-in equipment can be recorded as included in base weight.

Drone Confirmation displays the approved options as compact selections. Standard and extended batteries are mutually exclusive for aircraft that take one battery. Support aircraft-specific paired battery sets instead of assuming all aircraft use one pack. Remember the previous selection, but show the selected configuration before confirmation.

Takeoff weight = base weight + selected accessory/battery weights + variable payload weight.

Payload at launch has a free-text description and an optional numeric weight with explicit units. A description-only entry such as "water bottle" is accepted for recording and flagged for records-admin completion; do not require a field scale or fabricate a weight. An unknown weight remains unknown, never zero, and the calculated total takeoff weight remains incomplete. Use known/premeasured payload weights when available. A pilot can select approved options and enter the sortie payload without receiving permission to edit base weights or accessory definitions. Post-flight completion does not retrospectively establish what was known or checked before takeoff.

Preserve the exact configuration and payload applied to each flight rather than recalculating old flights from a later aircraft edit. The weight shown is derived from the saved measurements and current selection; it is not a fresh measurement or proof that equipment is functioning.

## Service status and equipment reports

Any active organization member with r2c_device may mark an aircraft Out of service or return it In service. config_admin is not required for these actions. Aircraft definition editing and service reporting are separate permissions.

An Out of service report requires a problem description and an explicit answer to whether the reporting member intends to address the problem themselves. An In service report requires a remediation note. Preserve both reports; returning to service does not erase the original problem.

Record the aircraft, organization, previous/new status, note, self-remediation intent when applicable, server-verified originator member ID, username/email and display name snapshot, originating device, client event time, server receipt time, and event ID. The source of user identity must be authenticated; a caller cannot submit an arbitrary username.

Submit immediately when online, then update connected organization devices. Store locally and retry after connection returns when offline, with a visible Pending upload label. Do not claim Tracker or an equipment manager has received an offline report. Store status history separately from organization configuration so a config download or rollback cannot restore an obsolete In service value.

Use an idempotent event ID for retries and a status revision for concurrent changes. A delayed offline In service report must not clear a newer Out of service report without review. Save unresolved conflicts and explain them to the reporting member instead of silently discarding or overwriting reports.

An existing aircraft starts with service status Not yet reported during migration; do not invent a prior inspection or In service confirmation. Once a team member reports status, show In service or Out of service with its last report.

## Equipment manager notifications

Add equip_manager to the organization role catalog and Members and Delegated Roles controls. It receives equipment status notifications for its own organization; it does not automatically grant aircraft configuration editing or status-changing authority. Assign records_viewer for access to the fleet view, and r2c_device separately if the member should change service status.

Email active equip_manager members after a service report is accepted, including aircraft identification, new status, note, originator, self-remediation intent when applicable, report time, and an authenticated link to the aircraft history. Notify on return to service as well, so recipients know the issue was remediated. Do not notify on routine read-only viewing or configuration downloads.

Commit the status event and durable notification work together. Retry mail delivery independently; email failure must not lose the service report. Track delivery failures and prevent duplicate notifications from retried submissions. If no equipment manager is assigned, save the report and show an administrative notification-configuration issue rather than rejecting the report.

## Tracker fleet status

Add an Aircraft status link near the top of the default organization Tracker dashboard. Require active organization membership with records_viewer, including r2c_device members who already receive that role. Keep equipment notes and member identities out of an anonymously accessible flight dashboard.

List every registered aircraft, keyed by Serial Number/RID, with owner/callsign, model, service status, last update, reporter, and current problem/remediation summary. Place Out of service aircraft prominently; allow opening complete status history. Enforce the same organization and role checks on the backing endpoints, not just the navigation link.

## Pilot member information

Extend Members and Delegated Roles with pilot callsign, Part 107 certificate number, original certificate date, latest recurrent-training date, and pilot status. Keep pilot status distinct from the existing member account state and assigned application roles. An equipment manager or records viewer need not be a pilot or have a certificate.

Use existing member-management permissions for these authoritative pilot fields. Suggested initial pilot statuses are Not recorded, Active, and Inactive, subject to team terminology. Missing data must not automatically classify a member as qualified. Do not treat an original certificate date as a certificate expiration date or automatically change account access based on dates.

Do not distribute a full member administration record in aircraft configuration. Apps need only the authorized pilot-selection data and necessary qualification fields; endpoints must limit access to the applicable organization and intended use.

## Drone Confirmation and pilot attribution

Extend existing Drone Confirmation with aircraft service status, approved accessory/battery selection, variable payload, and a pilot member selection. Preserve current incident/map/operational-period context and established confirmation behavior.

The aircraft owner is not necessarily the pilot. The member who authorized the tablet is not necessarily the pilot either. Default to the current authenticated member when appropriate, but explicitly identify the selected pilot. Shared-tablet use must distinguish the authenticated reporting user from the pilot selected for a flight. Selecting a pilot does not permit impersonating that user for service reports.

Within an organization, label the pilot field RPIC and offer the eligible pilot roster. Per the September 11 mission-continuity refinement, also accept a typed or missing callsign without preventing continuation. An unmatched callsign is retained as reported, with unresolved qualification and a clear explanation of how member or records administrators can remedy it. Never substitute the aircraft owner as the RPIC. Store the stable member ID and callsign/qualification snapshot used for the flight. Member administration must prevent ambiguous pilot callsigns within an organization. A VO can enter the information, but remains the reporting operator, not the RPIC unless explicitly serving in that role.

Use the member's recorded certificate, qualification status, and applicable training information to determine roster eligibility; a non-empty certificate number alone is not verification of qualification. Cache the authorized roster for offline use and preserve its version. Unknown or ineligible attribution must be visibly unresolved, not automatically certified; retain received flight evidence for records-admin reconciliation rather than dropping a flight. Outside organization membership, the pilot callsign remains free-form with no required organization member or Part 107 record association.

The ordinary Continue action must remain available without network access, pilot qualification data, aircraft details, weight, service clearance, or checklist completion. These conditions may produce warnings and review flags, but must not disable mission operations.

Service reports require an attributable authenticated member. The current DeviceCredential.authorized_user_id is an enrollment attribution, not proof of the present operator on a shared tablet. Implement a supported current-member session or explicit reauthentication before attributing reports to a different user; do not solve this with an unrestricted username picker.

Future checklist completion records attach to flight records with aircraft ID, actual selected pilot member ID, member/callsign snapshot, aircraft configuration version, accessory/payload snapshot, service-status revision, and briefing/checklist version and confirmation time. Preserve unknown pilot attribution on legacy records rather than inferring it from aircraft ownership.

Out of service status remains clearly visible during confirmation and operation. It must not disable maps, video, warnings, or recording during an active flight. Any later launch-readiness rule needs explicit product behavior; the initial status feature records and displays team reports.

## Post-flight records administration

Extend the existing organization records-admin page at /{designator}/admin/flights, which already supports editing reported flight records, rather than creating a second independent editor. Add the new RPIC, flight configuration, payload description, payload weight/units, and checklist-related record fields. Provide an obvious Records administration link for authorized members and a filter for records needing completion, including missing payload weight or unresolved RPIC attribution. records_viewer remains read-only.

An active records_admin can correct reported records belonging to the same organization. Reuse current server-side organization authorization and CSRF protection. This permission edits the historical flight record; it does not grant permission to change the shared aircraft configuration, service status, member certificate data, or role assignments. Apply the same eligible RPIC identity rules to corrections, using evidence of who served as RPIC at flight time. A later inactive account must not invalidate an accurate historical pilot record.

Keep the originally submitted values and an append-only correction history recording old/new values, authenticated editor, time, and reason or measurement basis. The current record and normal exports show the corrected values with correction metadata; reviewers can inspect the original submission and revision history. Store corrections separately from the raw submitted track archive. Retried uploads, reimports, and later configuration downloads must not silently overwrite reviewed corrections.

For a payload-drop flight initially recorded as "water bottle," retain that description and let records_admin enter the weight of the actual carried item, including contents and packaging. Store units and the basis, such as a measured matching bottle or a documented estimate. There is no universal water-bottle weight. Recalculate that flight's takeoff weight from its historical configuration snapshot plus the corrected payload weight; do not change other flights or today's aircraft definition. Keep takeoff payload distinct from payload remaining after the drop.

An administrator can amend checklist information after the flight, but the amendment must remain visibly post-flight; it must not rewrite the original preflight confirmation timestamp or imply a preflight action occurred when it did not. Use record revisions to prevent simultaneous editors from silently overwriting one another.

## Existing implementation points

- Android aircraft editor: app/src/main/java/org/ncssar/rid2caltopo/ui/RidMappingAdminPanel.kt.
- Android aircraft/config serialization: data/CtDroneSpec.java and data/CaltopoClient.java.
- Apple aircraft administration/config download: apple/App/RidMappingAdminView.swift.
- Tracker identity, roles, configuration releases: control_plane.py, especially OrganizationUser, DeviceCredential, and OrganizationConfigRelease.
- Tracker member editing: templates/organization_admin.html and associated main.py organization member routes.
- Tracker configuration validator and distribution: validated_organization_config_snapshot and organization-config routes in main.py.
- Existing flight records do not yet store an explicit pilot member ID or readiness/checklist snapshot.
- Existing organization records editor and batch update: /{designator}/admin/flights and organization_batch_update_flights in Tracker main.py.

## Implementation order and acceptance criteria

1. Add backward-compatible server fields, role/capability contracts, and organization authorization. Test each permission separately, including direct requests, removed roles, disabled members, imports, and cross-organization requests.
2. Extend aircraft configuration and both RID Map Entries editors. Verify new fields survive upload, approved publication, automatic download, local export/import, and old-client interactions. Verify non-organization editing remains available.
3. Add durable service events, online synchronization, offline retry/conflict handling, notification delivery, and the fleet status page. Test duplicate requests, failed mail, no equipment manager, stale In service events, and username spoofing.
4. Extend member administration and Drone Confirmation for RPIC identity, accessory selection, payload and visible service status. Test organization-only eligible RPIC selection, free-form non-organization callsigns, ownership/pilot differences, shared devices, unknown legacy data, and weight calculations without double counting.
5. Extend records administration for post-flight corrections and missing-data review. Test records_admin edits, records_viewer rejection, cross-organization rejection, preservation of original values and qualification history, payload recalculation, conflicting edits, and replay protection for corrected records.
6. Add the brief reusable checklists to Drone Confirmation after the above records and identities are established.

Validate Android and Apple source/tests/builds separately, then exercise multi-device synchronization and email delivery in an isolated test organization. Local tests do not prove field usability, background delivery, offline recovery, or physical equipment readiness. Store publication and production deployment remain separate from implementation and test results.
