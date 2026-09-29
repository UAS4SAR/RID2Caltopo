# iPad stale confirmation panel — log sanity check

Read-only check of Ken's iPad, installed 2.3.7 (302), on September 29 at approximately 08:47 PDT. No restart, installation, or device-state edits.

Log copied from `Documents/RID2Caltopo/FlightStorage/2026-09-29/Log_29Sep2026-081852-PDT-0700.txt` to `/tmp/r2c-ipad-confirmation-20260929/current-log.txt`.

All times PDT; aircraft `1581F6Z9C24BH0036EJL`:

- 08:21:50.810: confirmation queued with fresh aircraft receipt; .848: panel presented (lines 243–244).
- 08:28:17.905: flight ended and confirmation eligibility retired; session confirmation cleared (lines 534–536).
- 08:28:17.912: unanswered/unconfirmed flight explicitly skipped for archive/upload (line 538).
- 08:44:27.949: same panel presented again, 16 minutes 10 seconds after flight end (line 721).
- 08:44:42.708 and 08:45:14.075: presentation repeats (lines 760 and 811).
- No new confirmation queue or flight-reactivation entry accompanies these late presentations. Bluetooth location/observation counters remain at 213; ongoing events are bridge relay pings, not fresh aircraft positions.

Source explanation: ContentView's flight-end callback calls `droneConfirmations.endFlight`, clearing model eligibility, but does not clear the matching `pendingDroneConfirmation` sheet item. The original unresolved UI request can remain alive and reappear when the presentation hierarchy is rebuilt. This is separate from rearming eligibility based on stale stream listings and separate from the uninstalled Main Screen button-routing fix.

Required correction: retire the matching pending presentation at flight end and prevent an expired automatic request from being presented after navigation/foreground restoration, while preserving explicit operator confirmation for a currently active flight. Validate the matching/nonmatching flight cases and later genuinely new flight. No source fix was made during this requested log check.

## Implemented source correction

The pending sheet now uses `PendingFlightConfirmation`, which retires only the request matching an ended aircraft. ContentView invokes retirement after explicit telemetry flight end and from both active-flight reconciliation call sites, covering video-only disappearance. It logs `Dismissed expired confirmation` when clearing the request. Explicit requests still use the same state, and a later genuinely new flight can be presented normally.

Android parity check: `R2CViewModel.clearFinishedFlightConfirmationState` already invokes `clearInactivePromptOnly(..., trackFinished = true)`, which clears the matching `_pendingDroneConfirmation`.

Validation: all 434 Swift Testing tests and 34 XCTest tests passed (468 total), including three new regressions for unanswered-panel retirement/new flight, another aircraft ending, and video-only disappearance. Signed iOS Debug build succeeded; `git diff --check` passed. Logs: `/tmp/r2c-expired-confirmation-tests.log` and `/tmp/r2c-expired-confirmation-build.log`. Shared release notes and the Apple metadata mirror now mention dismissal of expired unanswered panels. No installation performed; iPad still has build 302 until the next authorized update. Physical verification remains pending.

## iPad installation

Installed and launched 2.3.7 (303) on Ken's iPad `694108CB-8CBE-593D-ABE1-D9EDD947B901`. Device metadata confirms build 303. Signed app verification passed; packaged release notes match the canonical 2.3.7 notes. In-place update retained app data and includes the pending-panel, Main Screen confirmation-routing, and Follow-control fixes. Build log: `/tmp/r2c-install-303-ipad.log`. Physical retest remains with the operator.
