# Stability review — crashers, deadlocks, and durability

Branch: `cursor/caltopo-auth-session-ea0e` (started at `980e4a00`).  
Scope: iOS (`apple/`) and Android (`app/`). Goal: no field crash/hang; tracks and clues must not be lost.  
`docs/validation/` was left untouched. No version bump.

Reference patterns already fixed on this branch:
- `980e4a00` — MainActor isolation inherited by a DispatchSource handler (live video display feed).
- `8222efe9` — LiveTrack monitor held across catalog/journal/network I/O (Disconnect ANR).

---

## Issues found and fixed

### 1. iOS — NWPathMonitor handler MainActor isolation trap

| | |
|---|---|
| **Where** | `apple/App/AppleNetworkAddress.swift` — `AppleNetworkDiagnosticCenter.start()` (~L118–149) |
| **Severity** | Crash (runtime trap) |
| **Why** | `pathUpdateHandler` closures were formed inside a `@MainActor` method, so they inherited MainActor isolation. `NWPathMonitor` delivers on `monitorQueue`. Same class of failure as `980e4a00` (`swift_task_isCurrentExecutor`). Connectivity restore (clue flush / interrupted LiveTrack recovery) rides this path. |
| **Change** | Added `nonisolated static makePathUpdateHandler` so the delivered closure is formed off MainActor and only hops via `Task { @MainActor in … }`. |

### 2. iOS — `MainActor.assumeIsolated` on Combine sinks

| | |
|---|---|
| **Where** | `apple/App/AppleAlertCoordinator.swift` (~L62–91) |
| **Severity** | Crash (trap) |
| **Why** | Sinks used `MainActor.assumeIsolated` without `receive(on:)`. `@Published` usually emits on MainActor today, but `NotificationCenter.publisher` follows the posting thread. A non-main delivery traps and stops proximity / MediaMTX health while the display is locked. |
| **Change** | `.receive(on: DispatchQueue.main)` on all coordinator subscriptions; call hooks directly (no `assumeIsolated`). |

### 3. iOS — AppDelegate observer on arbitrary queue

| | |
|---|---|
| **Where** | `apple/App/AppleStreamRegistry.swift` — `RID2CaltopoAppDelegate` (~L1385) |
| **Severity** | Crash (latent) |
| **Why** | `addObserver(…, queue: nil)` delivers on the posting thread. A MainActor-formed closure can trap if isolation is inherited. |
| **Change** | Use `queue: .main` (work itself remains `Task.detached`). |

### 4. iOS — Captured-video analysis `Timer.scheduledTimer` from `@MainActor`

| | |
|---|---|
| **Where** | `apple/App/AppleCapturedVideoReview.swift` (~L390) |
| **Severity** | Crash (latent) |
| **Why** | `Timer.scheduledTimer` from a MainActor method can form an isolated handler; matches the display-feed pattern. |
| **Change** | Create `Timer(timeInterval:repeats:)` and add it to `RunLoop.main` for `.common`, with `Task { @MainActor in … }` (same pattern as live-video background pull). |

### 5. iOS — Force unwraps on runtime state

| | |
|---|---|
| **Where** | `apple/App/AppleStreamRegistry.swift` `focusedSession` (~L74); `apple/App/RIDTrackViewModel.swift` `scheduleTerrain` (~L948) |
| **Severity** | Crash |
| **Why** | `sessions.first!` if the list was cleared during teardown; `terrainRequestKey(…)!` despite the helper returning `Optional`. |
| **Change** | Recreate the demo placeholder if sessions is empty; `guard let requestKey = … else { return }`. |

### 6. Android — Clue store monitor held across index fsync / file probes

| | |
|---|---|
| **Where** | `app/.../video/SnapshotStore.kt` (`AndroidClueStore`) — `persistIndex`, `saveEncoded`, `delete`, `update`, `recordUploadResult`, `markUploading`, `allRecords`, `recordsForMap`, `save` |
| **Severity** | Hang / ANR; publication stall (can delay clue upload / connectivity flush) |
| **Why** | Same class as `8222efe9`: the store monitor was held across atomic index writes (`PlainFileSafeWriter` + fsync) and image existence checks. Upload flush and save share that monitor. JPEG encode also ran under `@Synchronized save`. Apple already keeps clue disk I/O on `OperationalWorkQueue`. |
| **Change** | Snapshot under the monitor, `persistIndexSnapshot` outside it; `ensureLoaded` reads disk outside the monitor; file probes outside; remove `@Synchronized` from `save` so encode is unlocked. |

### 7. Android — Interrupted LiveTrack journal I/O under `LOCK`

| | |
|---|---|
| **Where** | `app/.../data/CaltopoInterruptedTrackJournal.java` — `save`, `remove`, `finishArchiveCleanup`, `pendingCount` |
| **Severity** | Hang / publication stall |
| **Why** | `writeRoot` / `readRoot` ran while holding `LOCK`. Connectivity-restore flush and LiveTrack archive completion contend on that lock. |
| **Change** | In-memory `cachedRoot`; mutate under `LOCK`; `writeRoot` outside; invalidate cache on write failure. Cold-start disk read under `LOCK` remains (see open risks). |

### 8. Android — Config import ContentResolver / LoadConfigFile on Main

| | |
|---|---|
| **Where** | `app/.../ui/MainScreen.kt` JSON import launcher; `app/.../ui/R2CView.kt` `MapConnectionOverlayHost` |
| **Severity** | Hang / ANR |
| **Why** | Activity-result callbacks run on Main; `takePersistableUriPermission` + `LoadConfigFile` → `ReadTextFile` (SAF query + full file read). |
| **Change** | Run import on `Dispatchers.IO`, then publish UI events on Main. |

### 9. Android — `ReadTextFile` null / cursor leaks

| | |
|---|---|
| **Where** | `app/.../data/CaltopoClient.java` `ReadTextFile` (~L2361) |
| **Severity** | Crash / resource leak |
| **Why** | Null `Context` NPE; null `openInputStream` NPE; `Cursor` never closed. |
| **Change** | Null checks; try-with-resources for stream/reader; always close cursor. |

### 10. Android — Controller network + Wi‑Fi SSID on Main

| | |
|---|---|
| **Where** | `app/.../video/ControllerNetwork.kt` `rememberControllerNetwork` |
| **Severity** | Hang / jank (every 3s + every link callback) |
| **Why** | Callbacks and `WiFiScanner.WiFiSSID` ran on `Looper.getMainLooper()`. |
| **Change** | Dedicated `HandlerThread`; register callback on worker; hop to Main only to assign Compose state. |

---

## Follow-up pass (audit agents)

Additional P0/P1 items from the [Android crash/deadlock audit](d7ce8ee2-d32e-486d-b2c1-73d87ef68764) and [iOS crash/deadlock audit](a481eaa6-2bd7-4ea3-ad2e-fa55ff90d3d9):

### 11. iOS — MediaMTX log callback use-after-free on stop
| | |
|---|---|
| **Where** | `MediaMTXMobileController.swift` log callback; Go `forwardLogLines` |
| **Severity** | Crash |
| **Change** | Retain/release around the actor Task hop so `stop()` cannot free the controller mid-invoke. |

### 12. iOS — Anomaly processor destroy races queue work
| | |
|---|---|
| **Where** | `AppleAnomalyProcessor.deinit` |
| **Severity** | Crash |
| **Change** | Drain the anomaly queue before `R2CAnomalyDestroy`. See §21 — the first form used bare `queue.sync` and crashed when deinit ran on that queue. |

### 13. iOS — Background live video FFmpeg pops on MainActor
| | |
|---|---|
| **Where** | `AppleVideoFrameSource` background path |
| **Severity** | Hang |
| **Change** | Keep `NativeDisplayFeed` running while backgrounded for live native; main timer only for file/AVPlayer. |

### 14. iOS — DeviceMarkerJournal / PilotCloud timer / quit journals
| | |
|---|---|
| **Where** | `DeviceMarkerJournal.swift`, `PilotCloudServer.start`, `RIDTrackViewModel.shutdown` |
| **Severity** | Hang / data-loss |
| **Change** | Separate write gate (reads never wait on fsync); form keepalive timer off MainActor; await `awaitingFlightWrites` before CalTopo teardown. |

### 15. Android — LiveTrack AwaitingMap under track monitor + journalSnapshot races
| | |
|---|---|
| **Where** | `CaltopoLiveTrack.ensureLiveTrackStarted`, `persistInterruptedPublication` |
| **Severity** | Hang / crash (`ConcurrentModificationException`) |
| **Change** | Resolve AwaitingMap publication ids outside the track monitor; always snapshot `linePoints` under the monitor. |

### 16. Android — Disconnect completion while finish in flight
| | |
|---|---|
| **Where** | `CaltopoMap.DisconnectAfterFinishingOwned` |
| **Severity** | Data-loss / race |
| **Change** | Chain subsequent completions via `PendingDisconnectCompletion` after the in-flight finish. |

### 17. Android — Clue save waited on Archive Dir hydrate; AwaitingMap held lock across ClueStore
| | |
|---|---|
| **Where** | `FlightStorage.prepareAppPrivateWrite`, `SnapshotStore.saveEncoded`, `AwaitingMapFlights.decide` |
| **Severity** | Hang |
| **Change** | Metadata-only allowance for app-private writes; bind clues / `queueCompleted` outside the AwaitingMap monitor. |

### 18. Android — Recording catalog `MediaMetadataRetriever` under catalog lock
| | |
|---|---|
| **Where** | `ManagedVideoSessionRecordingCatalog.snapshot` / `readRecording`; `TrackerPeerCoordinator.uploadRecordingDownloadNow` |
| **Severity** | Hang (retain / presence / deferred video link / archive association) |
| **Why** | `@Synchronized snapshot` walked every mp4 and called `MediaMetadataRetriever` / `MediaExtractor` under the catalog monitor. Incomplete MediaMTX files can stall the retriever; `retain` and other callers blocked on the same monitor. |
| **Change** | Snapshot file list + cache hits under the lock; parse metadata unlocked with a 2s per-file timeout on a small daemon pool; apply cache/publish under the lock. `retain` copies off the lock. Scan asserts off Main; recording upload `find` moved onto `recordingUploadExecutor`. |

### 19. iOS — `AppleFlightStorage.maintain` directory walks blocked saves
| | |
|---|---|
| **Where** | `AppleFlightStorage.maintain` / `prepareWrite`; `AppleClueStore` save |
| **Severity** | Hang (clue save / recording finalize / Manage Storage / quit) |
| **Why** | `maintain` held `NSRecursiveLock` across directory enumeration, `clues.json` reads, deletes, and ledger rebuild. `prepareWrite` ran that inline on the caller’s path (including clue disk work). |
| **Change** | Walks/purge run only on the `flight-storage-demand` queue via nonisolated trampolines (no MainActor isolation inheritance). Locks cover in-memory state only. Maintain is incremental and cancellable via `cancelPendingMaintenance`. Clue saves use `prepareAppPrivateWrite` (Android parity — never waits on walks). After cancel/quit, `prepareWrite` also refuses to block. |

### 20. iOS — Quit MediaMTX recording drain unbounded
| | |
|---|---|
| **Where** | `MediaMTXViewModel.shutdown`; `ContentView` fullCleanup |
| **Severity** | Hang / abandoned normalize on quit |
| **Why** | Quit cancelled the event loop immediately, abandoning in-flight localize/normalize, or could wait without a bound while durable work still contended with Flight Storage walks. |
| **Change** | Full cleanup cancels Flight Storage maintenance first, finishes durable track/clue shutdown, then MediaMTX: stop native server → drain finalizers with a 5s hard timeout → cancel event loop → discard remaining tokens. Unfinished recordings stay on disk for next-launch recovery. |

---

### 21. iOS — `AppleAnomalyProcessor.deinit` `dispatch_sync` onto its own queue
| | |
|---|---|
| **Where** | `apple/App/AppleVideoFrameSource.swift` `AppleAnomalyProcessor.deinit` (introduced in `aca29bfc`) |
| **Severity** | Crash (`BUG IN CLIENT OF LIBDISPATCH: dispatch_sync called on queue already owned by current thread`) |
| **Why** | `submit` / `configure` / `reset` capture strong `[self]` on `org.ncssar.rid2caltopo.anomaly`. When the last external reference drops, the final release runs during that queue block’s teardown, so `deinit` itself runs on the anomaly queue and then called `queue.sync` — libdispatch aborts. |
| **Change** | Mark the queue with `DispatchSpecificKey`. In `deinit`, destroy the C runtime inline when already on the queue; otherwise `queue.sync`. Same reentrancy pattern as `AppleFlightStorage.runOnWorkQueue`. |
| **Audit** | Only other production Apple `DispatchQueue.sync` is Flight Storage (already key-guarded). Other deinits/closes from `aca29bfc`/`62445740` do not sync onto their own queues. Android: `ManagedVideoSessionRecordingCatalog.readRecordingWithTimeout` now refuses `submit().get()` re-entry on a MetadataScanExecutor thread (`ThreadLocal`); no equivalent anomaly-queue sync on Kotlin. |

---

## Open risks (deliberately left)

1. **Interrupted journal cold-start read under `LOCK`** — first `mutableRootLocked()` may still read disk once under the monitor.
2. **`group.next()!` timeout helpers** — safe while both tasks are added first.
3. **`RIDTrackMapView` clearance `Timer` + `assumeIsolated`** — fires on `RunLoop.main`.
4. **Mapping / surface `!!`** — not clue/track critical path.
5. **Bluetooth LE `onScanResult` on binder** — bounded parse.
6. **Clue index dual-writer last-writer-wins** — memory state remains correct.
7. **File/AVPlayer background pull still on main** — live native is off-main; file review is secondary.
8. **Timed-out catalog metadata workers** — native `MediaMetadataRetriever` may ignore interrupt; pool is small and daemon-backed so one bad file cannot stall retain/track work.

---

## Build

- Android: `./gradlew :app:assembleDebug`
- iOS: `xcodebuild -project apple/RID2CaltopoApple.xcodeproj -scheme RID2CaltopoApple -configuration Debug -destination 'id=00008103-0010596E1AD3001E' build`  
  (Ken’s iPad; no app launch; no `connectedAndroidTest`)
