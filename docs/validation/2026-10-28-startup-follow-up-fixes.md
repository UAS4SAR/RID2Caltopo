# Startup follow-up fixes — Android and iOS 2.5.5 (432)

This follows the [remaining startup review](/Users/kjt/Projects/RID2Caltopo/docs/validation/2026-10-28-remaining-startup-review.md).
Both platforms retain marketing version 2.5.5 and build 432 for these intermediate
local replacements. Installation hashes and times distinguish the replacements.

## Repairs and execution context

| Path | Repair and ordering guarantees |
| --- | --- |
| Apple saved aircraft mappings | A worker loads, validates and sorts the saved records and prepares bounded warning text before ContentView construction. Identical duplicates collapse for operational display. Conflicting IDs are excluded from operational identity lookup; original valid records, including conflicting entries, remain available for explicit editing and backup. Startup does not rewrite the original mapping array. The authorized UI displays a dismissible recovery banner. |
| Apple mapping import, edit and restore | Worker preparation rejects conflicting duplicates before accepting changes. Revision, cancellation, scope and edit-authority checks reject obsolete results. Accepted preference writes stay on their existing serial writer. Closing a pairing view cannot grant flight/publication consent from a late save completion. |
| Apple private credentials | A serial worker reads the existing private Keychain accounts before protected UI is enabled. MainActor getters use already-decoded strings in a small memory snapshot; its lock is never held across Keychain or disk work. Missing items and unavailable storage are distinct. Unavailable reads leave the protected UI blocked with Retry and preserve the stored credentials. Device-lock notifications obscure access and trigger a fresh worker load after unlock. |
| Apple credential/profile writes | Keychain writes, UTF-8 decoding and profile JSON encoding/decoding run on workers. Existing service/account names and `WhenUnlockedThisDeviceOnly` protection are retained. Profile activation commits the Tracker and CalTopo secrets as a batch before either model changes. Expired Teams publication is suspended even if Keychain is locked. Related secret pairs publish together; a failed batch attempts rollback and retains the previous cached snapshot. Credentials remain private and are excluded from operational-storage migration. |
| Apple asynchronous credential interactions | A shared MainActor transaction gate serializes complete edits across worker suspensions without blocking Main. Nested operations share a unique active lease. Queued cancellation skips that edit and releases the next waiter; an accepted durable edit finishes if its presenting view closes. Revision checks prevent older profile/save completions from overwriting newer state. Configuration observers defer connection/publication updates until the transaction finishes. |
| Apple organization readiness cache | Full saved/network roster JSON decoding and collection bridging run on a serial worker. The UI getter returns an immutable memory snapshot. Startup prepares it before constructing the UI; role changes coalesce loads and reject old scope/token/reset/revision results. Invalid saved data is preserved. Old cache storage is released on the worker. |
| Apple organization snapshots | Credential encryption, aircraft/source matching and full JSON preparation run on a detached worker. Prepared output carries a currentness check. Mapping revision, credential transaction revision and connection generation are checked before sending. One preparation runs at a time; concurrent request IDs use a bounded queue and obsolete scopes are discarded. |
| Android pending-clue upload completion | The launched callback owns its storage-error boundary. Disk failures return a recoverable result; cancellation propagates. A failed index update restores the prior in-memory upload state. The local photo and previous durable index remain available for retry. The outer network callback is not relied upon to catch asynchronous child failures. |
| Android clue startup/index handling | Unreadable indexes block writes and are preserved. Temporarily unavailable images no longer cause metadata to be purged during an unrelated index save. Hydration runs on IO, cancels obsolete jobs, checks cancellation between thumbnail loads and rejects results from an older map/generation. |
| Android Bluetooth lifecycle | One serial worker owns platform start/stop/restart calls. SCAN and CONNECT are checked before adapter access; expected security/state exceptions become a recoverable condition. Cancelled delayed tasks are removed, closed scanners ignore late callbacks, and stopping a scanner never waits on Main. Activity status queries use one IO worker, cancellation and generation checks. |

The cache/snapshot reads above do not wait for a background worker holding a disk
lock. No Main-thread future, semaphore, blocking coroutine or worker join was
introduced. Network publication keeps the existing consent and destination
requirements; obsolete prepared results cannot switch their destination.

## Verification details

Focused tests cover conflicting and identical duplicate mappings, original-record
retention, 20,000-entry worker preparation, delayed Keychain reads with responsive
MainActor/cache lookup, unavailable credentials, failed related-secret writes and
rollback/retry, transaction nesting, FIFO completion, cancelled queued work,
accepted-write completion, failed clue-index writes, corrupt-index preservation,
missing-image metadata, cancellation without stale UI/error publication, and
Bluetooth permission/state races.

The full Swift host run initially exposed a separate macOS portability issue:
the host rejected `.completeFileProtection` even in writable disposable folders.
The registration receipt contains a hash, not credentials. It now uses atomic
writes on macOS while retaining the existing protection option on iOS. The full
Swift suite passed after that correction.

The Simulator qualification now signs its Release app and verifies its generated
simulated application identity. The earlier unsigned fixture could not access
Keychain; the new fail-closed UI correctly reported that failure. Location
permission is set only on the newly created disposable fixture, representing a
returning installation and preventing an OS permission dialog from obscuring the
recovery assertions. Physical-device permissions are never changed by this check.

The release UI fixture combines duplicate saved aircraft IDs and an unreadable
flight journal. It checks visible recovery, retry, repeated cold launch and
foreground return, and verifies both saved inputs remain unchanged. The
qualification driver records runtime warnings and fails for detected blocking
Main-thread work.

## Final results

The completed source passed the following checks. The older candidate results in
the preceding review documents describe earlier artifacts; these results and the
installation records below supersede them for the local device replacements.

| Check | Result |
| --- | --- |
| Android host suite | 1,788 tests; zero failures/errors; one existing skip. |
| Android isolated runtime qualification | Twelve recovery tests and three Macrobenchmark tests passed. StrictMode calibration passed; zero direct app-owned Main-thread I/O violations. The report separately retains 119 framework/library violations, so this is not a claim of zero platform/library I/O. |
| Android emulator timings | Cold launch p95 438.02 ms; foreground return p95 163.55 ms; initial-frame CPU p95 255.87 ms; scrolling-frame CPU p95 60.97 ms. These are emulator observations, not physical-device performance guarantees. |
| Swift host suite | 872 tests: 31 mapping XCTest, 199 core XCTest and 642 Swift Testing; zero failures; one existing XCTest skip. |
| iOS Release Simulator qualification | One combined recovery test passed, including repeated cold launches, foreground return and retry. Zero runtime warnings. Duplicate saved mappings and damaged journal preserved. |
| iPad build and signing | Optimized development build succeeded; strict signature verification, application identifier and signing team verified before installation. |
| Version parity | Android and Apple both report 2.5.5 (432). |

Final logs, test counts, reports and source hashes are indexed by
[verification-summary.json](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/startup-fixes/verification-summary.json).
The source-hash snapshot was checked again after the final tests and installations.
These checks cover the identified failure classes and their asynchronous
interactions; they cannot establish that no other crash or vendor-specific failure
remains. Physical functional and field validation remain outstanding.

## Evidence and installation records

Final results, artifacts and installation records are retained under
[startup-fixes](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/startup-fixes).
The device verification is OS installation/identity/version only. Devices are
updated in place and left unopened; no physical functional/instrumentation tests,
backup, data/permission reset, uninstall or private-data inspection is performed.

| Device | Final installation, October 28, 2026 (PDT) | Installed artifact SHA-256 |
| --- | --- | --- |
| A5 Pro, SM-X350 | 19:27:08; Debug 2.5.5 (432), `adb install -r`; original first-install time unchanged | APK: `d08b6d923b33ed4fb95706c054a8d93f9f487c5d0b564ad21a2576425428dd74` |
| iPad Pro 11-inch, third generation | 19:50:34; optimized development 2.5.5 (432), in-place `devicectl` installation | Signed bundle tree: `1265fa30e9634a856179569f0b15265e1b129f52d65626f61ef5d14d8e6988be` |

Exact timestamps, OS before/after version checks and the iPad tree-hash algorithm
are retained in the
[A5 Pro record](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/startup-fixes/a5pro-final-install-record.json)
and [iPad record](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/startup-fixes/ipad-final-install-record.json).
All earlier replacement records remain historical evidence.

These are local development installs. Distribution APK/AAB/archive artifacts from
the earlier pass predate these repairs and must be rebuilt before upload. No
Play/App Store/TestFlight delivery is part of this request.
