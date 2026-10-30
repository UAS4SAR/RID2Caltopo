# Track and clue durability

SAR life-safety data: once a track point or clue (including its photo) is captured, it must never be lost or silently dropped, and must upload automatically when connectivity returns.

This document is the contract for Android and Apple. Implementation lives in the operational stores, append journals, upload owners, and startup integrity scans described below.

## Invariants

1. **Write-ahead.** Persist locally (atomic temp + rename, `fsync` / `FileHandle.synchronize` / `OperationalSafeFileWriter`) **before** any UI success signal or network attempt.
2. **Delete only by consent or queue ack.** Only an explicit user delete removes local clue/track data. A confirmed successful upload may remove a **queue / journal entry**, never the local photo, index row, or archived GeoJSON/KMZ as a silent side effect of upload success alone (published clues stay on disk until user cleanup / retention policy).
3. **Hydrate / merge / reconcile only ever union.** Never replace a working clue projection with an archive tree. Keep both sides of a hash mismatch; log it.
4. **Persistent pending-upload state.** Each publishable clue has a durable `uploadState` (`pending` / `uploading` / `failed` / `published` / `local`) that survives process death, ANR kill, reboot, and `adb install -r`.
5. **Per-item retry with capped backoff.** Retries are per clue/track item (not one global HOL queue). Backoff is capped (2s → 60s). Wake immediately on Internet restored (`ConnectivityManager` / `NWPathMonitor`), map reconnect, and app launch.
6. **Idempotent uploads.** Stable local ids; reuse an existing CalTopo marker or Shape when the server already has it (including media GET 400/404 = absent → create/attach).
7. **Permanent 4xx keeps the item.** Auth / client errors stop automatic retry, keep the local record, and surface the error. They never delete the clue or track.
8. **Airborne append journal.** Every accepted track point is appended to an NDJSON journal with sync during flight. Recovery after an unclean exit covers every unfinished flight.
9. **Visible undelivered work.** Pending / failed clue counts are shown in the UI (map badge / status / Manage Storage).

## Lifecycle stages and guarantees

| Stage | Tracks | Clues |
| --- | --- | --- |
| **Capture** | Point accepted into in-memory queue only after duplicate filter; journal append is queued immediately. | Photo + metadata encoded; no upload until local save succeeds. |
| **Local persist** | `WaypointTrackJournal` / `WaypointTrackAppendJournal`: append line + `fsync`. Interrupted LiveTrack JSON and awaiting-map intents use atomic replace with sync (`PlainFileSafeWriter` / `OperationalSafeFileWriter`). | Image + thumb via atomic replace + sync; then index write. Index failure rolls back the in-memory row and deletes the just-written images. |
| **Index** | Awaiting-map flight journal + interrupted LiveTrack journal. | `clues.json` (Android flat root; Apple per-day). Union-merge on hydrate. |
| **Upload queue** | LiveTrack start/forward on a **start** worker/lane; archive on a separate **archive** lane. | Per-item pending state; Android owner allows up to 3 concurrent uploads; Apple one Task per clue id. Clue HTTP uses a dedicated lane (Apple `clueLane`) so photo 502s do not block LiveTrack create/points. |
| **Upload / partial / retry** | Points forward incrementally; interrupted journal checkpoints Shape conversion. Idempotent Shape edit by stable LiveTrack id. | Marker create/attach idempotent; media GET 400/404 treated as absent. Transient 5xx/timeout → backoff + retry. Permanent 4xx → `failed`, kept, surfaced. |
| **Archive** | Local GeoJSON/KMZ + CalTopo Shape on archive lane. Journal marked finished only after durable archive/awaiting-map commit. | KMZ rewrite may embed clues; never deletes source clue photos as part of rewrite. |
| **Hydrate / prepare / reconcile** | N/A for LiveTrack RAM; journals recovered at startup. | Android: `prepareCluesProjection` + `ClueProjectionMerge` always union. Apple: no Archive Dir hydrate; working store is app sandbox. |
| **App kill / crash / ANR / LMK** | Append journal retains every accepted point; recover → awaiting-map (commit-then-delete). | Index + images already durable before UI success; `uploading` → `pending` on reload. |
| **App update / `install -r`** | App-private journals and indexes preserved. Integrity scan re-runs. | Same. |
| **Reboot** | Same as kill recovery at next launch. | Same. |
| **SD / Archive Dir unavailable** | App-private journal still works; archive copy deferred. | Working projection kept; hydrate retry merges when archive returns — never replaces. |
| **Disconnect / reconnect / profile / map switch** | Interrupted journal + connectivity wake start/forward/recover. | Pending state retained; flush on map-up, Internet restore, launch. |
| **User ignores a flight** | Flight stays local / awaiting-map review; not published until decide. Journal still durable. | Clues bound to ignored flight stay local until publish decision. |
| **Quit** | Finish/archive path journals then cleans; unclean quit leaves journals for recovery. | Pending uploads resume next launch. |

## Destructive paths (allowed)

| Action | When allowed |
| --- | --- |
| User deletes a local map marker / clue | Explicit confirm; unpublished warns. |
| User discards an awaiting-map flight | Explicit discard; deletes bound clues + archives after decision. |
| Journal `.ndjson` delete | Only after awaiting-map open+finish (or Apple `acknowledgeRecovered`) succeeded, or the journal is empty/finished. |
| Interrupted LiveTrack journal remove | After Shape archive uploaded (and LiveTrack delete ack / already-gone). |
| Retention / Manage Storage day delete | User-driven or policy; blocked while unuploaded clues protect the folder. |

Any hydrate, merge, prune-missing-photo, or upload-failure path that **deletes** index rows or photos is a bug.

## Startup integrity scan

Runs off the UI thread at launch (`ClueTrackDurabilityIntegrity`):

- Orphan clue photos without an index entry → re-indexed as pending stubs (operator may need to fix coords/title before publish).
- Index entries without a photo → **kept** and flagged `failed` / missing-photo (never dropped).
- Unfinished waypoint journals → recovered into awaiting-map; journal file removed only after durable commit.
- Summary logged (`orphansReindexed`, `missingPhotos`, `journalsRecovered`, …).

## Connectivity wake

| Platform | Trigger | Action (off Main / MainActor) |
| --- | --- | --- |
| Android | `NetworkCheckRecovery` validated Internet | Flush pending clues; recover interrupted LiveTracks; wake LiveTrack start/forward |
| Apple | `NWPathMonitor` → `checkRecoveryGeneration` | `resumePendingUploads`; `retryInterruptedPublications` |

Also: map reconnect, 30s owner poll (Android), publisher recovery task (Apple), app launch.

## Failure modes this work addresses

| Id | Failure | Mitigation |
| --- | --- | --- |
| (a) | Clue hydrate replaced working projection / deleted photos | Union merge only; never `removeTree` on working clues |
| (b) | Single upload owner HOL-blocked by 502 | Bounded parallel clue uploads (Android ≤3); per-id Tasks + `clueLane` (Apple) |
| (c) | Media GET 400 treated as failure | 400/404 = absent → create/attach |
| (d) | Archive stalled shared worker → LiveTrack never started | Separate start vs archive workers/lanes |
| (e) | ANR kill airborne lost track; no journal | Per-point append journal from first accepted point (before LiveTrack id); commit-then-delete recover |

## Remaining known risks

- **Dual journal ids.** Android RID `WaypointTrack` and CalTopo `CaltopoLiveTrack` may journal the same flight under different flight ids until both finish cleanly → possible duplicate awaiting-map recoveries (union / review, not silent drop).
- **Orphan reindex stubs** use placeholder coordinates / `unassigned` map until the operator edits or binding assigns them.
- **Apple personal-session clue HTTP** may still share session machinery outside `clueLane`; team-credential clue POSTs are separated from LiveTrack start.
- **Publisher actor coupling (Apple).** Archive recovery no longer gates LiveTrack start on awaiting-map health, but heavy archive work on the publisher actor can still delay concurrent publishes under load; network lanes are split.
- **Android day-folder archive layout.** Working `AndroidClueStore` is a flat `filesDir/clues` root; integrity scan matches that. Nested day `clues.json` trees from archive merge are union-kept on disk but are not the primary runtime index.
- **Interrupted LiveTrack 5s rewrite** remains a secondary checkpoint; the NDJSON append journal is the ANR/kill primary path.
- **Permanent 4xx** stops auto-retry by design; the operator must fix credentials/payload and use Retry (Apple) or re-submit/re-bind flows.

## Tests

Fault-injection coverage (non-exhaustive):

- Kill between recover steps → journal retained until awaiting-map ack.
- Network timeout / 5xx / permanent 4xx → item kept pending or failed.
- Hydrate/merge race → union, no deletes into the working tree.
- Restart with pending queue → pending ids resume.
- Missing photo prune → metadata kept and flagged.
- Media GET 400 → upload then attach.

Android: `./gradlew :app:testDebugUnitTest --tests '*Durability*' --tests '*WaypointTrackJournal*' --tests '*ClueProjectionMerge*' --tests '*ClueUploadBackoff*' --tests '*ClueHydration*'`

Apple: `swift test --filter R2CCoreTests` (includes `WaypointTrackAppendJournalTests`, `ClueTrackDurabilityIntegrityTests`, media GET 400 tests).
