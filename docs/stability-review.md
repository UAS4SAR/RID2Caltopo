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

## Open risks (deliberately left)

1. **Interrupted journal cold-start read under `LOCK`** — first `mutableRootLocked()` may still read disk once under the monitor. Subsequent ops are memory-only under the lock. Further work: hydrate cache on a worker before first use.
2. **`group.next()!` timeout helpers** (`AppleClueStore.withTimeout`, clue JPEG encode in `AppleVideoFrameSource`) — force-unwrap is safe while both tasks are added first; left as-is.
3. **`Timer` + `MainActor.assumeIsolated` on main RunLoop** (`AppleVideoFrameSource` background pull, `RIDTrackMapView` clearance timer) — fire on `RunLoop.main`; acceptable. Prefer `Task { @MainActor }` if these timers move off main.
4. **`@unchecked Sendable` types with `NSLock`** (GeoTIFF, credentials, video peers) — locks are short for memory; GeoTIFF already unlocks before file decode. No change.
5. **Mapping / surface `!!` and `parentFile!!`** — mostly processing paths with prior guards; not clue/track critical path.
6. **Bluetooth LE `onScanResult` on binder thread** — parse is bounded; toast on failure already hops to a worker. Left open.
7. **FFmpeg pointer lifetimes** — `NativeDisplayFeed.stopAndDestroy` serializes destroy with ticks (from `980e4a00`); no additional defect found this pass.
8. **Apple `OperationalWorkQueue` / Android dual-writer clue index** — two concurrent `persistIndexSnapshot` calls can race last-writer-wins; memory state remains correct and next mutation rewrites. Acceptable; serializing writers would be a larger change.

---

## Build

- Android: `./gradlew :app:assembleDebug`
- iOS: `xcodebuild -project apple/RID2CaltopoApple.xcodeproj -scheme RID2CaltopoApple -configuration Debug -destination 'id=00008103-0010596E1AD3001E' build`  
  (Ken’s iPad; no app launch; no `connectedAndroidTest`)
