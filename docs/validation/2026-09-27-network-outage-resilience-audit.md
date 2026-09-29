# CalTopo / r2c-tracker outage-resilience audit

27 September 2026. Android and Apple are peer platforms in this assessment.

## Conclusion

The current protocols and recovery paths are **not yet sufficient for unattended recovery through intermittent or extended WAN outages**. The recent LiveTrack work provides a sound foundation, but transport retries alone do not guarantee that confirmations, final flight geometry, clues/photos, and flight records reach their destination after an outage.

Two Android failure windows were reproduced against current production classes with an isolated fake transport: a stalled reconnect and loss of an unacknowledged confirmation. Other findings below are source-established gaps or explicitly identified qualification gaps, not claims of new field failures.

Scope: the main CalTopo read/write paths; Tracker coordination, ownership, confirmation, flight upload, configuration and authorization; managed-video signaling/recording transfers; storage and diagnostics supporting recovery. This is a source/protocol audit plus focused local tests, not certification of every backend route or of live external behavior. No app source fixes, device installation, network changes, forced submissions, or server deployment were performed. Audit probes live only under `outputs/` and are injected with a separate Gradle init file; normal test configuration is unchanged.

Source basis: RID2Caltopo HEAD `e3e1cab504e7cc1048a0d51b7ba146006870516e`, including the existing working-tree changes; Tracker HEAD `6363bd7f52264b5cd63e14c9c196acabe2701914`. This does not establish deployed server revision. The A5 flight was on 2.3.7 (286). Source line links describe the inspected working tree.

## Field evidence

The copied A5 log shows Internet validation lost at 12:37:52 PDT while Wi-Fi/LAN remained available. Its archive contains 109 positions, including 47 observed after validation was lost. The clue/photo survived locally. Tracker retries failed, with a duplicate-reconnect suppression at 12:38:46. CalTopo map reads and device-marker writes recovered at about 12:44:20; the refreshed log still had no Tracker reconnect or flight-upload retry through 12:46:32.

The A5 was never the direct CalTopo publisher in this flight: `localOwner=false`, `sent=0`, `confirmed=0` preceded the outage. Its assignment details were redacted. The audit does not equate this with missing data on the remote map, nor attribute that ownership result to the newly reproduced confirmation bug without server-side correlation. See the [flight review](/Users/kjt/Projects/RID2Caltopo/outputs/a5-wan-review-20260927/review.md).

## Priority findings

### 1. P1 — Android reconnect can become permanently pending without a timer

**Reproduced.** A forced reconnect clears `reconnectPending` when its handshake starts. If new coordination activity arrives during that handshake, `wakeForCoordinationActivity()` starts another connection, sets `reconnectPending=true`, and retains the previous scheduling timestamps. When this replacement attempt fails, `scheduleReconnect()` sees the old, already-due target and suppresses the retry as a duplicate. No new retry timer is scheduled. Later activity also returns immediately because reconnect is pending.

The audit probe reaches three connection starts, then records `cause=wake-drone_confirmed` and `Reconnect pending in 0 ms`. Its expectation of a further connection fails. Ordinary RID sightings also use this wake path. This reproduces a mechanism consistent with the field log; it is not proof of the exact interleaving on that flight.

Fix direction: separate scheduled, connecting, connected, and parked states; one serialized transition path; generation-bound callbacks; a bounded handshake deadline; a connectivity-restored wakeup; jittered retry that cannot be suppressed by an expired scheduling record. Apple uses a task-based reconnect path with generation checks and 2–10 second backoff; the same defect is not established there. Evidence: [TrackerPeerCoordinator.java:972](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/data/TrackerPeerCoordinator.java:972); [OutageAuditProbeTest.java:1](/Users/kjt/Projects/RID2Caltopo/outputs/a5-wan-review-20260927/probes/OutageAuditProbeTest.java:1).

### 2. P1 — Android treats socket acceptance as confirmation delivery

**Reproduced.** `flushPendingConfirmations()` removes a Save event when `sendJson()` returns true. That only means acceptance into the local WebSocket queue. Dropping the transport before a server echo leaves nothing to replay on the next connection. The audit probe's acknowledgement-loss expectation fails.

Apple retains pending confirmations until its own `drone_confirmed` echo, which is stronger. Both sides still need an explicit contract for restoring an *already acknowledged* current-flight confirmation after reconnect: the server removes that zone's confirmations and confirmation-based ownership on disconnect. Preserve consent for the current flight, never reuse consent for a later flight.

Fix direction: stable event ID plus flight/session epoch, authoritative acknowledgement, bounded resend, server deduplication, and an explicit reconnect snapshot of active-flight consent/state. Evidence: [TrackerPeerCoordinator.java:1365](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/data/TrackerPeerCoordinator.java:1365); [AppleTrackerCoordinator.swift:1147](/Users/kjt/Projects/RID2Caltopo/apple/App/AppleTrackerCoordinator.swift:1147); [main.py:2362](/Users/kjt/Projects/r2c-tracker/main.py:2362).

### 3. P1 — Ownership is not fenced by a locally enforced lease

**Source-established risk under partial outages.** Android records server lease expiry but does not use it to expire local publication authority. Apple checks lease sequence but does not retain/enforce assignment expiry for publication. Local ownership can therefore outlive the Tracker connection/lease while CalTopo remains reachable. The server can expire or revoke ownership and assign a different tablet. Local fallback can also self-assign while Tracker is unreachable.

The server's one-owner table is not an end-to-end guarantee that only one client writes CalTopo. This is especially important when only Tracker is unreachable, only one tablet is partitioned, or WAN returns at different times to different tablets.

Fix direction: define the partition policy explicitly. Recommended default for coordinated operation: continue all local capture, pause shared remote publication after a bounded lease, then reconcile with Tracker before resuming. Any deliberate autonomous publication mode needs distinguishable segments and merge rules; it cannot promise strict single-writer behavior during a partition. Use monotonic local deadlines, session/lease generations and confirmation epochs. A hard fence at CalTopo would require enforcement by a write gateway or equivalent; a client-only lease is weaker. Evidence: [TrackerPeerCoordinator.java:2044](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/data/TrackerPeerCoordinator.java:2044); [TrackerCoordinationProtocol.swift:569](/Users/kjt/Projects/RID2Caltopo/apple/Sources/R2CCore/TrackerCoordinationProtocol.swift:569); [main.py:2362](/Users/kjt/Projects/r2c-tracker/main.py:2362).

### 4. P1 — Saved flight uploads have no dedicated eventual-retry worker

**Source-established; recovery gap observed on A5.** Both clients preserve transiently failed uploads and make three immediate attempts. Subsequent replay is driven by startup/configuration/enrollment/manual paths, rather than a dedicated durable worker that resumes on restored service and periodically retries while work remains. Recovery must not depend on opening a screen, changing configuration, or restarting the app.

Android also scans directories after repeated failures: breaking its inner file loop does not stop the outer directory scan. Apple can spend three failures per pending file during an outage. A large backlog can waste network attempts and delay useful work.

Fix direction: durable per-item pending state with next-attempt time, single-flight deduplication, per-service outage backoff, foreground/service lifecycle support, connectivity and successful-service-response triggers, and bounded periodic fallback. A path becoming available is a hint, not proof that either service works. Evidence: [WaypointTrack.java:825](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/data/WaypointTrack.java:825); [RIDTrackViewModel.swift:683](/Users/kjt/Projects/RID2Caltopo/apple/App/RIDTrackViewModel.swift:683).

### 5. P1 — Clue recovery differs materially across platforms

**Android:** images/metadata are saved first, but the CalTopo marker/media transaction has no persistent upload lifecycle or automatic replay. The UI explicitly tells the operator to upload manually after failure. The upload generates fresh IDs separately from the durable local clue ID. Retrying by recreating the clue can duplicate a marker after an ambiguous outcome. In the photo workflow, failure of an inner operation can also return without completing the original outer operation/callback, leaving the timeout to communicate failure.

**Apple:** a persistent clue state, stable marker/media IDs and retries capped at a 60-second interval exist. However, the record does not store its original CalTopo map/team target. Configuration reenqueues pending/failed/uploading clues against the current client. A clue saved for map A can be sent to map B after a configuration change. It also retries all caught errors, including permanent authorization/validation failures, and has no global concurrency cap over clue tasks.

Fix direction: durable, destination-bound clue transaction on both platforms, stable object IDs, stage acknowledgements, correct terminal/retryable classification, and bounded upload concurrency. Pending clues must remain associated with their original incident/map/team. Evidence: [CaltopoSession.java:1179](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/data/CaltopoSession.java:1179); [AppleClueStore.swift:170](/Users/kjt/Projects/RID2Caltopo/apple/App/AppleClueStore.swift:170); [OperationalClueModels.swift:11](/Users/kjt/Projects/RID2Caltopo/apple/Sources/R2CCore/OperationalClueModels.swift:11).

### 6. P1 — Apple can permanently skip recoverable archives after a scope change

**Source-established.** `processOnce()` marks every ineligible file reported, including organization mismatch, missing organization, and currently unknown aircraft. Since replay scans all day directories with the current configuration, switching organizations can suppress an earlier organization's pending work. Android distinguishes temporary identity/scope mismatch (`RETRY_LATER`) from permanently ineligible local-only/unapproved work.

Fix direction: bind the destination scope when the item is created; defer scope/configuration mismatches; quarantine malformed data separately; retain explicit local-only/consent restrictions. Never use a single “reported” flag for successful delivery, a recoverable configuration mismatch, and permanent rejection. Evidence: [AppleTrackArchiveStore.swift:268](/Users/kjt/Projects/RID2Caltopo/apple/App/AppleTrackArchiveStore.swift:268); [WaypointTrack.java:825](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/data/WaypointTrack.java:825).

### 7. P1 — Extended-outage retention does not protect pending work

**Source-established.** Both storage managers protect today's directory and explicit active-operation holds. Automatic age/space cleanup does not consult upload acknowledgements or pending clue/archive state. A multi-day outage plus storage pressure can delete an older unreported track or Apple clue image before eventual replay. An upload's temporary protection does not protect it between retries or across restart.

Fix direction: retention must consult persistent pending obligations. Separate small durable flight/clue records from large video retention decisions; expose capacity and pending bytes; stop or degrade recording deliberately if necessary rather than silently deleting the only unuploaded copy. Define export/retention policy for permanently rejected items. Evidence: [FlightStorage.kt:153](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/app/FlightStorage.kt:153); [AppleFlightStorage.swift:111](/Users/kjt/Projects/RID2Caltopo/apple/Sources/R2CCore/AppleFlightStorage.swift:111).

### 8. P1 — Interrupted CalTopo finalization survives, but retry scheduling is incomplete

**Source-established gap.** Both platforms persist client-assigned LiveTrack identity and full geometry, write a Shape before deleting the LiveTrack, and retain failed recovery work. Android recovery is called from initial/full map parsing and folder setup; successful routine incremental refresh does not itself drain the journal. Apple invokes recovery during configuration and device-marker publication, coupling recovery to a marker path that can be disabled or lack a location.

Fix direction: an independent durable finalization worker, scoped to the original map, with a single in-flight transaction per identity and safe handling of active versus finalized segments. Shape-write success followed by lost/delete response must be replayable without duplicate geometry or reverting a newer state. Verify client-assigned UUID behavior with live CalTopo on a disposable map before treating it as proven idempotence. Evidence: [CaltopoMap.java:1657](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/data/CaltopoMap.java:1657); [AppleCaltopoPublisher.swift:178](/Users/kjt/Projects/RID2Caltopo/apple/App/AppleCaltopoPublisher.swift:178); the earlier [LiveTrack validation note](/Users/kjt/Projects/RID2Caltopo/docs/validation/2026-09-25-livetrack-start-retry.md).

### 9. P2 — Tracker upload deduplication is overlap rejection, not an exact receipt

**Source-established.** Tracker serializes flight submissions and rejects overlapping records with HTTP 409. This limits duplicate flight rows, but cannot tell a client whether its exact content was already committed or a different/incomplete overlapping record exists. Both clients treat most nontransient statuses, including 409, as terminal in the reported-file ledger. A server commit followed by a lost response can therefore appear as rejection on retry; a distinct overlapping record can suppress delivery of more complete data.

Fix direction: immutable flight/upload ID, content hash/version and organization scope; idempotent receipt returning the canonical record ID and accepted content version; conflict handling distinct from “already accepted this exact upload.” Check the post-commit usage-metering failure window too: the flight can exist even if generating the successful response fails. Evidence: [main.py:6066](/Users/kjt/Projects/r2c-tracker/main.py:6066); [main.py:5383](/Users/kjt/Projects/r2c-tracker/main.py:5383); [WaypointTrack.java:482](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/data/WaypointTrack.java:482); [TrackerArchiveUpload.swift:87](/Users/kjt/Projects/RID2Caltopo/apple/Sources/R2CCore/TrackerArchiveUpload.swift:87).

### 10. P2 — Retry budgets and work prioritization are inconsistent

Android CalTopo uses connect/read timeouts, three-attempt retries for non-position operations and jitter, but no explicit whole-call deadline; a single main request worker also sleeps during backoff. Map/control requests can delay fresh position work even though positions themselves are coalesced. Tracker archive retries do not honor `Retry-After`. Apple generic CalTopo requests are single-attempt and depend on caller-specific retry behavior; clue retries can continue indefinitely on nonrecoverable responses. WebSocket backoff lacks jitter on both clients.

Fix direction: service-specific deadlines, bounded concurrency and backoff; honor 429/503 retry guidance; separate live coordination/latest position from durable uploads and bulky media. Preserve local capture while remote workers are blocked. Evidence: [CaltopoSession.java:185](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/data/CaltopoSession.java:185); [AppleClueStore.swift:170](/Users/kjt/Projects/RID2Caltopo/apple/App/AppleClueStore.swift:170); [runtime_io.py:50](/Users/kjt/Projects/r2c-tracker/runtime_io.py:50).

### 11. P2 — Recording transfer is chunked, but not a durable resumable job

Both clients upload 8 MiB recording chunks. On failure their transfer loop exits; Apple removes the approved upload from its active dictionary. Neither inspected client loop persists an acknowledged byte offset and autonomously resumes it. The server accepts ranged chunks, but the clients need an explicit status/receipt protocol. Apple's per-request timeout is one hour, far different from Android's shared client limits.

Fix direction: retain an approved transfer job and source file, query/validate committed ranges, retry bounded chunks and finalize using size/hash verification. Keep live-video loss separate from local recording survival; never replay obsolete ICE/SDP or an expired request as a new session. Evidence: [TrackerPeerCoordinator.java:1887](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/data/TrackerPeerCoordinator.java:1887); [AppleTrackerCoordinator.swift:1332](/Users/kjt/Projects/RID2Caltopo/apple/App/AppleTrackerCoordinator.swift:1332).

### 12. P2 — Diagnostics do not fully distinguish capture, send, receipt and publication

The field log's redacted ownership details prevented resolving why the A5 was not publisher. Android's synthesized 503 was logged downstream as “server returned 503.” A cached map status of `up` persisted during failed reads. A socket send is not a server receipt, and a healthy Tracker socket is not evidence that the completed flight or CalTopo clue arrived.

Fix direction: compact, separate CalTopo and Tracker health; last successful operation; durable pending count/oldest age; reason and next retry; assignment/lease generation without coordinates or secrets. Rotate logs and preserve an operation/flight ID across client/server traces. Do not let diagnostics cover or move operator video.

## Interaction inventory and intended recovery contract

| Interaction | Current foundation | Required outage behavior / gap |
|---|---|---|
| CalTopo credential/team/map/folder reads | Android transient retries; Apple caller-driven requests | Keep last usable configuration; explicit stale status; do not interpret DNS failure as rejected credentials; retry bootstrap independently. |
| Map artifact polling | Android incremental cursor, one refresh in flight, next poll after failure; Apple artifact sync policy | Keep cached artifacts visible with freshness; cursor advances only on valid success; exercise reconnect and malformed/partial response. |
| LiveTrack create | Stable preassigned UUID and durable journal on both | Lost-response retry must resolve the same object; live-server qualification remains open. |
| Live position reports | Latest-only slots, 5-second post-completion cooldown on both | Send fresh current position after recovery, never drain historical positions as live movement. |
| Completed Shape plus LiveTrack delete | Full local geometry, journal, ordered write/delete | Independent durable retry; same identity; never delete the only remote geometry before Shape success. |
| Track rename, recording description, thumbnail metadata | Primarily immediate/bounded in-memory follow-ups | Reconcile latest desired metadata after reconnect; do not block geometry delivery; retain required final metadata. |
| Device marker create/update/delete | Stable device ID, periodic updates; observed A5 recovery | Latest state wins; stale callback must not recreate an old-map marker; cleanup eventually converges. |
| Clue marker/photo/media attachment | Both local-first; Apple durable retry | Same original target and object IDs, staged retry, receipt and user-visible pending state. |
| Tracker socket hello/heartbeat/reconnect | Watchdogs; generation checks in Apple; server deadlines | Reconnect progresses under new activity, repeated failures and transport replacement; service-specific liveness. |
| Ownership/confirmation/first sighting/drone lost | Leases, sequence ordering, server owner selection | Partition policy, bounded authority, acknowledgement/replay by flight epoch; expiry reconciles on reconnect. |
| Peer traffic / proximity / video presence | Latest advisory data; Tracker traffic cache limited to 30 seconds | Discard stale/future/old-epoch traffic; no stale safety replay; new positions replace cached positions. |
| Final flight GeoJSON upload | Local archive, transient retries, server overlap checks | Eventual retry, exact idempotent receipt, destination binding and protected retention. |
| Enrollment/authorization/reauthentication | Scoped credentials and explicit auth-error handling | Preserve offline local work; retry transient reads; never convert an outage into revocation; one-use enrollment redemption needs explicit ambiguous-result handling. |
| Organization configuration/readiness sync | Persisted state, version notices on hello, HTTP fetch | Keep last known state/freshness, retry failed advertised version independently, isolate old-scope callbacks; per-handler fault qualification still required. |
| Managed-video requests/decisions/ICE/SDP | Request IDs, pending-request replay server-side, client session handling | Distinguish reliable control state from expiring negotiation; fresh connection after outage; no silent reapproval or duplicate session. |
| Recording requests/uploads/download availability | Approved requests and chunked upload | Durable offset/receipt/finalization; preserve local file; retry bulk transfer at lower priority. |
| Backgrounding/restart/map switch/midnight | Some journals and local archives; in-memory timers/state | Restore pending durable work scoped to original destination; do not require UI activity; never revive old consent or live samples. |
| Tracker server restart / slow clients | SQL-backed coordination state, bounded socket writer (2-second deadline, backlog 16), silence timeout | Verify restart/reassignment with old-client writes and replay; single-process assumptions must match deployment. |

The correct split is **fresh replaceable state** (live positions, presence, current device marker) versus **durable acknowledged work** (flight record, final geometry, clue/photo, accepted control decisions). Trying to send every missed live sample is harmful; silently abandoning durable work is also harmful.

## Proposed resilience contract

1. Continue local RID/telemetry, track, clue and recording capture while either remote service is unavailable.
2. Persist accepted durable work before networking, with operation ID, original organization/map, flight epoch, payload version/hash, status and retry deadline. Store credentials separately.
3. Classify outcomes as acknowledged, retryable, authorization-blocked, validation-conflict, or explicitly local-only. Only a validated receipt or explicit user resolution completes an obligation.
4. Drain pending work automatically after a usable service response, a network change, app resume/restart, and periodic retry. Coalesce those triggers. Bound workers and backlog memory.
5. Independently supervise CalTopo and Tracker. One recovering service must not make the other appear healthy, and failing bulky uploads must not stall coordination.
6. Reconcile flight-scoped confirmation/ownership with the authoritative server before resuming coordinated writes after a partition. Do not carry local Save across flights.
7. Preserve pending work across app death, midnight, profile changes, and storage cleanup; surface low capacity and unresolved conflicts.

## Qualification matrix

Run every scenario on Android and Apple with the same scripted input; use two tablets for owner handoff. Use a proxy/fault harness and a disposable CalTopo map/Tracker test organization. Preserve local data and do not run these fault injections against an active search.

| Scenario | Required assertions |
|---|---|
| WAN blackhole for 2, 10 and 60 seconds; 10 and 60 minutes | Local capture continues; retry traffic stays bounded; latest live state resumes; all durable work eventually receives receipts. |
| Repeated 5 seconds up / 20 seconds down | No stuck reconnect or growing stale-position queue; progress during short usable windows; no duplicate outputs. |
| DNS failure, TCP blackhole, connection reset, high latency/jitter/loss | Whole-operation deadline; no frozen UI or blocked local capture; no credential clearing. |
| Only CalTopo unavailable / only Tracker unavailable | Independent health; correct partition/lease behavior; available service continues useful work. |
| Server accepts request, response dropped | Same LiveTrack/clue/upload identity; one canonical artifact; receipt recovered. |
| Outage at each stage of clue/photo and Shape/delete transaction | Pending stage survives; no orphaned permanent state; original map preserved. |
| Confirmation socket-send accepted, server never receives it | Same current-flight Save replays until acknowledgement. |
| Confirmation acknowledged, then connection/server restart | Consent restored only for the active flight; authoritative ownership converges. |
| New RID activity during a reconnect handshake | Exactly one connect attempt at a time; failed handshake always schedules another attempt. |
| Two tablets, owner partitioned, lease expires, new owner chosen | No old-owner writes after authority expires under the chosen policy; deterministic reconciliation. |
| 401/403/426 versus 408/429/5xx, including Retry-After | Correct blocked/transient status; no hot retry; pending payload retained. |
| Flight ends entirely offline | Complete final geometry and Tracker record upload without app restart or opening settings. |
| Restart/force termination with pending create/upload/finalization | Journal survives; bounded possible capture tail loss measured; no fresh UUID for an already accepted object. |
| Switch organization/map before connectivity returns | No cross-map clue delivery or permanent suppression of another organization's pending archive. |
| Cross midnight and impose storage pressure | Unacknowledged records/photos protected or an explicit operator-visible capacity action occurs. |
| Recording chunk accepted, reply lost; then restart | Resume verified ranges; one complete file; size/hash correct. |
| Suspend/resume and screen lock while outage persists | Platform lifecycle constraints documented; pending work resumes when execution is allowed. |
| Tracker restart and slow/stalled client among healthy clients | Healthy clients remain responsive; lease/session epoch reset is safe; stale messages rejected. |

Measure detection/recovery latency, attempt counts, queue depth/bytes, oldest pending age, local-versus-remote point counts, object IDs, receipt IDs and content hashes. For a lab acceptance target, require an automatic retry within 15 seconds of a usable-service signal while foregrounded, successful small pending work within 60 seconds on a healthy link, and continued bounded retries during long outages. These are proposed targets, not claims about current performance; background OS restrictions and bulk recording transfer need separate targets.

## Validation performed

- Android existing focused suite: **99 passed**, zero failures/errors: TrackerPeerCoordinator 62, LatestPositionReports 4, CaltopoLiveTrack 12, interrupted journal 2, WaypointTrack 18, DelayedExec 1.
- Apple focused Swift package suite: **72 passed**, covering protocol, archive contracts, LiveTrack identity/geometry, latest-position behavior and upload coalescing. These do not execute UIKit app adapters or physical background behavior.
- Tracker coordination/protocol/scenario suite: **74 passed**. Flight submission/identity/readiness/presentation group: **16 passed**. These are local tests, not live production probes.
- Additional Android fault probes: **2 failed as expected against the resilience requirements**—stuck retry after handshake activity; missing replay of an unacknowledged confirmation. Their source and captured XML are preserved under `outputs/a5-wan-review-20260927/`. No production code was changed to force either result.
- No live CalTopo idempotence experiment, two-tablet partition experiment, server restart, long-duration soak, physical Apple test, or release qualification occurred.

Logs: [Android baseline](/Users/kjt/Projects/RID2Caltopo/outputs/a5-wan-review-20260927/android-audit-tests.log), [Apple](/Users/kjt/Projects/RID2Caltopo/outputs/a5-wan-review-20260927/apple-audit-tests.log), [Tracker protocol](/Users/kjt/Projects/RID2Caltopo/outputs/a5-wan-review-20260927/tracker-audit-tests.log), [Tracker flight tests](/Users/kjt/Projects/RID2Caltopo/outputs/a5-wan-review-20260927/tracker-flight-audit-tests.log), [failing fault probes](/Users/kjt/Projects/RID2Caltopo/outputs/a5-wan-review-20260927/android-outage-probes.log), [probe XML](/Users/kjt/Projects/RID2Caltopo/outputs/a5-wan-review-20260927/probe-results/TEST-org.ncssar.rid2caltopo.data.OutageAuditProbeTest.xml).

## Recommended implementation order

1. Fix the reproduced Android reconnect and confirmation acknowledgement defects; add permanent regressions. Add the same behavioral tests to Apple even where its current design is stronger.
2. Establish durable replay scheduling, destination binding and pending-work retention for final tracks and clues on both platforms. Correct Apple's temporary eligibility handling.
3. Define and implement the shared lease/partition/reconnect-confirmation contract with Tracker. Add exact idempotent upload receipts and distinct conflicts.
4. Bound/prioritize retries, improve service/pending-work diagnostics, and make recording transfer resumable.
5. Run the matrix above and the disposable-map lost-response test before a field release. Qualify Android and Apple independently; code/tests/builds do not establish physical field resilience.
