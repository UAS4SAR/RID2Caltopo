# Operating profiles — implementation status

Implemented September 11, 2026. The operator verified iPad build 202 with public Tracker v1.4.90 after the confirmation, profile-preference, aircraft editing and upload fixes. Android automated validation is separate from physical device verification. See `../release-notes/2.2.7/verification.md` for current source-release evidence. The pre-operating-profile snapshots remain intact.

## Operator behavior

- Drone Confirmation on both platforms offers Standard Part 107, configured organization profiles, and Other / details pending. The last confirmed flight type is remembered separately per organization across app restarts, resolving against the current catalog. With no saved choice, the configured organization default is selected; absent configuration defaults to Standard Part 107. BVLOS / authority details pending is also available without implying a configured waiver. A missing configured default is flagged as unresolved.
- Profile selection is advisory. Continue remains available with expired, missing, stale, inapplicable, or incomplete information. Public-aircraft/COA and unfamiliar authority types require separate authority/qualification review and are not represented as Part 107 waivers.
- Each condition is a readable checklist item carrying its source provision, topic, units and reference. RPIC range, altitude, VO, equipment and RTH provisions can be entered without treating PDF extraction or a checkbox as proof of compliance. Unchecked conditions remain review flags.
- Active locally confirmed profiles appear in the shared operating overlay, including map/video screens. Reopening confirmation permits a change. Profile and checklist changes append time-stamped before/after snapshots; they do not replace the initial selection.
- “Use for this assignment” creates an explicit session assignment UUID for reuse across batteries. The scope combines organization endpoint and incident map ID, not display names. End assignment, an incident/organization change, an unchecked reuse choice on Continue, or app restart clears reuse. There is no existing selected-assignment model in these apps, so operators use End assignment when moving to another assignment on the same map.
- The existing 10-second short-flight choice, readiness/service behavior, maps, terrain/AGL/ATO information and video remain in place. A source audit found no existing NCSSAR-specific waiver condition engine in this baseline to disable. General safety advisories remain independent.

## Tracker administration and compatibility

Organization Configuration links to **Manage operating profiles and default authority** (`/{designator}/admin/operating-profiles`). Configuration administrators and organization owners can create profiles, revise them and select the organization default. The form stores a stable profile ID, server-assigned version, authority type, number, holder, effective dates, document reference, pilot/aircraft/location applicability and readable conditions. Pilot and aircraft applicability use named choices backed by stable IDs. Concurrent edits require a refresh instead of silently overwriting another administrator.

Profiles are kept in separate organization-scoped catalog/revision tables. The existing authenticated readiness response distributes the catalog to both apps' organization-scoped offline caches. Cached data older than 24 hours, missing synchronization time or a changed profile version produces an advisory. This freshness threshold is a synchronization policy, not a waiver provision.

Configuration schema 1 and legacy device configuration request/approval/restore paths remain unchanged. Because profiles are independent of those releases, an older device cannot delete the catalog by omitting fields. No operational waiver was inferred from organization membership or preloaded into a live organization; an administrator must configure the verified document and conditions.

Flights retain profile ID/version, complete readable conditions, selection time, assignment/incident/organization context, synchronization revision/time, checklist acknowledgments and review issues. Historical corrections use the existing records-admin form, reason, optimistic revision and attributed before/after history. A correction selects an exact saved organization profile version, including older versions. Original submissions and original in-flight timelines remain available; replay does not erase corrections. Legacy flights without profile fields still import and are flagged for unresolved authority review.

Apple preserves a local flight snapshot when older peer identity messages omit readiness metadata. Android also retains profile history when a later readiness update omits it. Tracker's container file list includes the new module and its existing readiness dependencies.

## Validation

| Check | Result |
| --- | --- |
| Android full unit suite | **1,019 passed**, zero failures/errors/skips; includes five operating-profile tests |
| Apple operating-profile tests | **6 passed**, including older-peer compatibility and later checklist/history preservation |
| Apple arm64 Simulator app build | **Passed**, signing disabled; no installation |
| Apple full core run during implementation | 309 tests; the single previously documented `appleDeviceLocationProactivelyPrefetchesTerrain` source-signature assertion at `R2CCoreTests.swift:676` remains. The terrain source and existing test match the supplied snapshot; neither was changed here. |
| Tracker full Python suite | **390 passed**, including five operating-profile tests and full browser form rendering, role/tenant/CSRF checks |
| Tracker disposable migration/localhost checks | **Passed**; no deployment |
| Diff whitespace check | Passed in both repositories |
| Snapshot comparison | No snapshot files missing. Android/Apple: 13 existing files changed plus 5 new implementation files. Tracker: 7 existing files changed plus 3 new implementation files. All other snapshot files retain their original hashes. |

The new tests exercise explicit defaults, missing selections, scope reset without resurrecting an earlier assignment, offline/stale and expired/inapplicable advisories, multiple profile changes, later checklist acknowledgement, immutable historical versions, replay preservation, administrator permissions and optimistic conflicts.

Builds and automated checks do not qualify physical controllers, real offline/background transitions, multi-device field behavior, or operator usability. Those remain device/field validation work. No automatic interpretation of numerical waiver provisions is claimed: conditions are source-referenced briefing items; existing situational-awareness displays remain available.

## Review artifacts

Logs, source patches relative to the supplied snapshot, and the file comparison are in `outputs/operating-profiles-20260911/`. The patches isolate this implementation from the much larger pre-existing uncommitted changes. No commits, branch changes or cleanup operations were performed.
