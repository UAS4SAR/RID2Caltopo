# Initial Tracker outage repairs — local verification

27 September 2026. Isolated checkout based on e3e1cab504e7cc1048a0d51b7ba146006870516e.

The Android coordinator now distinguishes an in-flight handshake from a scheduled retry, preventing coordination activity during a handshake from suppressing the next retry. Pending drone confirmations remain queued until a matching server echo, with five-second retry throttling and replay after transport reopen. Flight end and authoritative peer confirmation cancel stale local pending confirmation.

Seven new outage regression tests cover handshake activity followed by failure, unacknowledged replay, matching acknowledgement, stale acknowledgement after a newer Save, flight end, throttling, and peer supersession. Two existing delayed-open assertions now require one connection instead of redundant reconnects. The watchdog test disables background timers before advancing its fake clock to avoid a concurrent timer racing its explicit liveness check.

Validation: 97 focused tests passed. Full Android suite: 1,190 tests, zero failures/errors/skips. The first full attempt exposed a missing ignored model asset and a watchdog test timer race; the existing local model assets were copied into this checkout and the test timing was made deterministic before the successful rerun. git diff --check passed. No device installation, live map writes, organization creation, service changes, or deployment occurred.

This addresses only the first two reproduced Android defects. The broader audit still identifies durable archive/clue recovery, publication lease enforcement, retention, idempotency, and Apple recovery gaps. Local tests do not establish field recovery or complete outage readiness.

Read-only test-scope verification: the live Tracker platform administration page lists only EDSAR and NCSSAR as provisioned organizations. MYSAR appears only under managed pilot requests with Pending state; EDSAR also retains a Pending intake request despite Ready provisioning. Therefore intake status does not establish organization availability, and mySAR cannot yet be used as the proposed live test tenant. No production organization was modified.

Test log: /Users/kjt/Projects/RID2Caltopo/outputs/a5-wan-review-20260927/isolated-repairs-full-tests.log
