# Incident selection and iOS UI copy — October 7, 2026

## Incident behavior

Settings and operational incident controls use a shared selection panel on each platform. Operators can continue into the existing personal/organization credential and map-selection workflow, or save a nonempty incident name without a map. The latter explicitly states that it disconnects the selected map. Cancelling the selector does not change the selection. Existing archived flights are not edited by this flow.

Android no longer hard-codes Training when no map is connected. The standalone name is stored separately in incident_selection preferences, with the previous configured incident as the initial fallback. Apple likewise persists incident.standaloneName independently of credential profiles. Connected map titles take precedence only while connected; blank legacy names fall back to Training. No-map chips show the name with a No map indicator. Apple status/archive configuration uses the effective selected name and reacts to name changes. Android's general Settings save no longer overwrites the incident from an unrelated stale draft.

## Why the wording check missed Max Idle Time

The existing tools/store_notes/check_store_notes.py default_targets scans the selected version's release notes and apple/AppStore/metadata text files only. It does not scan Swift source. apple/AppStore/verify-metadata.sh invokes that metadata-only checker. Therefore the in-app CaltopoSettingsView sentence was outside its scope, rather than an ignored violation.

Added tools/ui_copy/check_apple_ui_copy.py and four regression tests. It scans Apple app Swift string literals, including indirect help/status strings and multiline/raw literals, excluding comments, code identifiers, and AppleLog diagnostic calls. The metadata verification script now invokes both its tests and scan, which also brings them into apple/release-check.sh. This is a static literal check, not proof about dynamically assembled or downloaded UI text. Nine visible direct/indirect platform references were removed, including Max Idle Time, aircraft Save help, video review help, QR status, package errors, and anomaly help.

## Validation

- Android full suite: 1,396 tests, zero failures; debug build passed.
- Apple full suite: 80 XCTest + 568 Swift Testing = 648 tests passed.
- UI copy checker: four tests passed; zero remaining violations. Existing store-note checker: seven tests passed.
- Final signed iOS device build passed, including status-label alignment.
- git diff --check and metadata shell syntax passed.
- No device installation in this turn. Physical checks remain: both selector entry points, no-credential naming, personal/org map selection, cancellation, map-to-name transitions, name retention after relaunch, and archive metadata on a newly recorded flight.
