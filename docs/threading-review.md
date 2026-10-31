# Threading and data-access review

Reviewed October 29, 2026, on the personal-account branch. The audit looked for locks held across slow work, main-thread blocking, unsynchronized shared state, lock-order deadlocks, `runBlocking` and dispatcher misuse, and single workers that can starve clue publish, live-track upload, or CalTopo POSTs.

Rules going forward are in `AGENTS.md` under "Locks and urgent publication" and "UI-thread performance."

Severity: **high** can stall a publish or the UI for seconds or longer. **medium** is real contention or a race that is less direct. **low** is a narrow or already-bounded case.

## Fixed on this branch

| Severity | Where | Finding | Status |
| --- | --- | --- | --- |
| high | `OperationalStorage.kt` `prepare` | `gate` was held for the whole Archive Dir hydration, including SAF `DocumentFile` scans. Clue destination lookup and the live-track journal called `requireReady()` and waited on that monitor (about three minutes in the field; `layers` alone was 34 s). | fixed earlier: hydration runs outside `gate` |
| high | `OperationalStorage.kt` `stateFile` | Every queue file except marker cleanup called `requireReady()`, so flight journals waited on bulk areas. | fixed earlier: queue files use the queue lane only |
| high | `OperationalStorage.kt` `prepareQueue`, `prepareRecorder` | `queueGate` and `recorderGate` were held across SAF migrate/hydrate. `retain` for journals and recordings needs those gates, so flight recovery, the marker journal, and recording sync waited on hydration. | fixed: hydration runs outside those gates; only the ready-pointer update takes them |
| high | `OperationalStorage.kt` `retain`, `onRecordingStore` | The archive `commit` (SAF write) ran inside `queueGate`, `gate`, or `recorderGate`. | fixed: identity check stays on the lane gate; the copy runs under `archiveCommit` |
| high | `CaltopoInterruptedTrackJournal.writeRoot`, `DeviceMarkerJournal.write`, `AwaitingMapFlights.save` | After the app-private file was durable, `retain` still ran on the caller. The recovery worker is one thread, so a SAF copy stalled the next live-track POST. | fixed: `retainLater` queues the copy on the archive setup executor |
| medium | `SnapshotStore.kt` `saveEncoded` | `@Synchronized` covered image writes and `FlightStorage.prepareWrite`, so `pendingForMap` waited on JPEG I/O. | fixed: files are written first; the monitor covers the in-memory record and index update |
| medium | `SnapshotStore.kt` `delete` | `OperationalStorage.remove` ran inside the store monitor. | fixed: index update is under the monitor; removal is after it |
| medium | `SnapshotStore.kt` `pendingForMap` | Reloaded the clue index from disk on every call while holding the monitor. | fixed: load once until the store writes again |
| medium | `AwaitingMapFlights.reconcile`, `awaitingFlightClues` | Flight recovery held the journal lock and then the clue-store lock across image existence checks. | fixed earlier |

## Deferred

| Severity | Where | Finding | Status |
| --- | --- | --- | --- |
| high | `CaltopoRecoveryWork` | One thread runs interrupted-track journal saves, flight recovery, and `forwardNextWaypoints`. A slow journal save still delays live-track POSTs even after `retainLater`, because the local journal write stays on that thread. | deferred: split publish from recovery before changing crash ordering |
| medium | `OperationalStorage.kt` `retainTree` | `gate` or `queueGate` is still held while walking a directory and calling `writer.put` for map products. | deferred: generation publish is ordered; moving it needs a dedicated writer |
| medium | `OperationalStorage.kt` `commit` inside `archiveCommit` | One SAF copy still blocks the next copy. Callers no longer block on unrelated hydration. | deferred: acceptable serialization of the archive writer |
| medium | `AwaitingMapFlights.record` and the other `@Synchronized` methods | `load`/`save` still read and write the small JSON file while holding the flight monitor. `retain` is no longer in that section. | deferred: the file is small; moving the write out needs a generation so two saves cannot reorder |
| medium | `SafBlobCacheStore.recoverRowFromFileLocked` | Called with `dbLock` held and can stat or migrate a SAF file. `get` itself reads blob bytes outside `dbLock`. | deferred |
| medium | `ManagedVideoSessionRecordingCatalog.snapshot` and `retain` | `@Synchronized` covers a directory walk, MP4 reads, and a file copy. `initialize` is only `mkdirs` under that lock. | deferred |
| medium | `GeoTiffDemSource.refreshCatalogLocked` | Holds the DEM catalog lock across SAF `listFiles`. `TerrainReadWorker` then holds its mutex for the whole sample, so live AGL, prefetch, and the clue preview share one decode. A missing `.f32raw` block falls through to LZW decode of a strip that can be an entire 1 m tile. That matches the ~73 s sample. | deferred: do not change the sampler in this pass |
| medium | `AndroidClueStore.persistIndex` and `loadIndex` | Still run under the store monitor. They are one small JSON file, not the JPEG. | deferred |
| medium | `CaltopoPersonalAccountConnection` `stored` / `disconnect` | `storageLock` is held across Keychain load and `clear`. UI status uses a different lock and does not enter Keychain. | deferred |
| low | `AppConfigStore.readConfigBlockingFromDataStore` | `runBlocking` on DataStore. Restore already checks it is off the main looper; other callers need the same check before anyone calls them from Main. | deferred |
| low | `CaltopoFullMapReads.probeCalls` | One thread performs the cookie-probe lease call. A stuck binder occupies that thread; callers time out and use the in-process gate. | deferred: already fails open for the map open |
| low | iOS `CaltopoLiveClient` actor | One actor runs map reads and marker/track posts in order. A slow read delays the next post. There is no Archive Dir hydration lock on this path; the iPad clue submit stayed at 2–3 s. | deferred |

## Checked and not a defect

- `SafBlobCacheStore.get` reads the blob after releasing `dbLock`.
- Personal-account UI reads use the in-memory cache (`cachedStatus` / `cachedLookup`) and do not take the Keychain lock.
- Clue Submit no longer calls the DEM worker. The bound log records `wait=context|waypoint-bind|destination` and the milliseconds for each step.
- iOS clue and live-track writes go to the app sandbox. They do not take a process-wide lock around a multi-minute folder scan.
