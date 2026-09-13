# DCP authorization and duplicate iPad archive upload

## Field evidence

A5 log `Log_12Sep2026-192313-PDT-0700.txt` records an explicit local DCP save at 19:24:19.721 PDT. Its archived Matrice flight spans 19:24:16.427–19:25:51.481 and uploaded successfully at 19:26:23.387 (HTTP 200), also recorded in `r2c_reported.txt`.

The rejected iPad file spans 19:31:52.716–19:33:18.382. These flights do not overlap. The iPad log records a successful upload of that exact file at 19:33:50.239, followed by duplicate requests returning 409 at 19:33:50.941 and 19:33:51.556. Server source confirms overlap rejection uses HTTP 409. No server policy change or removal of accepted flight records is required.

Evidence copies: `outputs/dcp-ignore-20260912/`; iPad full log: `outputs/aol-flash-224/ipad-prior-224.log`.

## Build 227

Both platforms require a current-flight confirmation before publishing or saving a flight archive. Saved aircraft mappings and telemetry reception are not consent. Unanswered or ignored DCPs do not become archived/uploaded flights. Telemetry can remain visible while a decision is pending. No automatic acceptance timer was added. The separate short-flight recording decision is only reached for confirmed flights.

Android captures authorization before queuing an archive, so end-of-flight cleanup cannot erase an accepted decision and a later confirmation cannot authorize an already finished flight. The aircraft's transient local confirmation survives peer lease changes but resets on flight reset. Apple captures eligibility before publishing flight end and clears its per-flight identities through the existing lifecycle.

Apple archive/replay requests for the same file now share one in-flight operation. The operation rechecks the reported ledger before sending because replay may have taken its directory snapshot before the original upload completed. A rejected duplicate is not reinterpreted as success, and transient failures remain retryable.

Existing files are preserved. No historical archive is deleted or automatically reclassified by this change.

## Validation

Android unit suite passed, including a mapped unanswered flight, immutable archive decision, and next-flight reset. Apple suite passed, including 20 concurrent same-file requests sharing one upload and a later retry remaining possible. Device build/install logs are saved under the evidence directory. Physical DCP-ignore and archive behavior still require field testing.

Build 227 installed in place on both devices. A5 launch succeeded and version query confirmed 2.2.7 (227), with original installation timestamp preserved. iPad version query confirmed 2.2.7 (227); launch was blocked by the locked screen. Android release verification passed with 1,066 unit tests; Apple passed 343 Swift Testing tests plus 11 XCTest tests and the signed device build.
