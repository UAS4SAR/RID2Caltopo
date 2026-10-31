# Platform parity — `cursor/caltopo-auth-session-ea0e`

Audit of behavior changes since `ce0b9336` (exclusive). Status values:

- **ported** — present on both platforms (or Apple-only when Android is N/A)
- **present** — introduced on that platform in the commit
- **partial** — core behavior present; platform-specific pieces differ
- **missing** — required on that platform and not yet mirrored
- **N/A** — does not apply (e.g. Android SAF-only)

## Commit table

| Commit | Subject | Behavior | Android | Apple |
|--------|---------|----------|---------|-------|
| `f7224556` | Load DEM catalog at app start without waiting on Archive Dir hydration | Warm DEM catalog at startup without Archive Dir `requireReady`; return unavailable while cold; never hold catalog lock across I/O | present | ported |
| `0e44beb3` | Publish personal clues with the signed HMAC grant and stop retry spin | Skip cookie media probe for signed personal maps; per-owner backoff; clues-only delete lane before bulk hydrate | present | partial — HMAC already signed when `personalSessionID == nil`; backoff in `AppleClueStore`; Android SAF clues-only lane N/A |
| `2f468b1f` | Start LiveTrack whenever owner and map are ready, and ignore transient storage races | Idempotent LiveTrack start; archive queued points without LiveTrack id; quiet media GET 400; retry SAF projection races | present | partial — start/archive ported; SAF transient races N/A |
| `ce9052d9` | Remember the Map Browser credential profile, including personal | Persist last browse profile (incl. personal) and restore after personal credential load | present | ported |
| `cb69bcba` | Fix iOS device build for Map Browser profile preference | Drop unused `Sendable`; init legacy personal flag from defaults | N/A | ported (Apple-only) |
| `9bd32985` | Finish owned LiveTracks after offline end and ignore clues hydrate races | Persist interrupted journal when flight ends after disconnect; treat clue hydrate races as non-fatal | present | partial — offline-end journal ported; SAF hydrate races N/A |
| `7b95fe86` | Finish owned LiveTracks on disconnect/quit before closing the map | Bounded finish/archive of owned LiveTracks on disconnect/quit, then drop map | present | ported — finish path already present; **disconnect clue flush** added (`AppleClueStore.flushPendingBestEffort`) |
| `d01b5f0f` | Preserve clue files across hydrate retries and unblock LiveTrack/clue uploads | Union-merge clues hydrate (Android), per-id upload backoff, bind empty-map review intents | present | partial — review bind + per-id upload ported; SAF union hydrate N/A |
| `a35a4c9c` | Treat media GET 400/404 as absent so clue photos upload and attach | Media probe 400/404 means absent; attach to existing Marker after failed POST | present | ported |
| `9141fab3` | Re-run DEM clue projection after gimbal edits instead of keeping flat-earth | Debounced DEM ray walk on gimbal/heading; keep known DEM ground for flat previews | present | ported (+ height recovery / non-blocking Submit in follow-up) |
| `4539af9f` | Keep Map Browser open when restoring or reselecting its credential profile | Re-applying remembered profile must not session-reset/dismiss browser | present | ported |
| `8222efe9` | Prevent Disconnect ANR by keeping LiveTrack I/O off the track monitor | Do not hold LiveTrack lock / MainActor across finish/archive/network | present | ported |
| `06db1d69` | Keep LiveTrack start off archive work and journal waypoints for crash recovery | Split LiveTrack-create vs archive lanes; NDJSON waypoint journals | present | ported |
| `fa1a42e4` | Enforce zero-loss durability for tracks and clues on Android and Apple | Append-journal every track point; commit-then-delete recovery; per-item clue retries; integrity scan | present | ported (SAF union hydrate Android-only → Apple N/A) |
| `e04f3dec` | Defer CalTopo archives offline and flush immediately on connectivity restore | Skip/bound hung uploads offline; cancel in-flight on restore; flush journals + LiveTrack + clues | present | ported |

## Topic checklist

| Topic | Android | Apple | Notes |
|-------|---------|-------|-------|
| Clue durability / upload lanes | present | ported | `AppleClueStore`, `ClueTrackDurabilityIntegrity`, separate `clueLane` |
| Media GET 400 | present | ported | `CaltopoLiveClient` + personal photo tests |
| Per-clue backoff | present | ported | one `uploadTasks[id]` owner; exponential delay |
| Disconnect finish flow | present | ported | LiveTrack finish + **clue flush** before map drop |
| Profile memory | present | ported | `MapBrowserProfilePreference` |
| Map Browser reselect | present | ported | `shouldDisconnectMapForBrowseProfileSwitch` |
| Start / archive split | present | ported | `startLane` / `archiveLane` / `clueLane` |
| NDJSON journal | present | ported | `WaypointTrackAppendJournal` |
| Connectivity flush | present | ported | clues resume + interrupted LiveTrack recovery |
| DEM catalog warm (no Archive Dir wait) | present | ported | `GeoTiffElevationSource.warmCatalog` |
| DEM gimbal refresh | present | ported | debounced `.task(id: projectionInput)` |
| DEM height recovery for waypoint/RID captures | present | **ported (this change)** | `ClueDemAltitudeReference` + refine when AGL/ATO missing |
| Submit never blocked on missing AGL | present (after DEM refine) | **ported (this change)** | aircraft/waypoint approximate fallback |
| VT mid-stream IDR resync | N/A (software drop-and-continue) | **ported (this change)** | flush + wait for IDR after VT fault |
| HLS fallback only when path exists | prefer RTSP | **ported (this change)** | 404/no-stream → wait for publisher, not HLS |

## Follow-up gaps fixed in this change set

1. **iPad clue stuck busy / “no fresh AGL”** — Capture froze AGL=`--` / ATO=`0` with incomplete SEI (`origin=waypoint`). Apple required height before DEM ran, so Submit stayed disabled. Now: stash DEM datum at capture, recover height from waypoint altitude + DEM, keep flat/drone fallback, never spin an indefinite ProgressView, Submit always available.
2. **iPad video interruptions / HLS 404** — VideoToolbox died after MediaMTX discarded frames for a slow reader; recovery tore the session down and later opened HLS after RTSP 404 with no publisher. Now: flush + wait for IDR in-session; treat RTSP 404 / “no stream” as publisher absent (wait, do not HLS); do not treat `hlsStreamStarted` as publisher proof; deliver `streamStopped` before recording normalize.
3. **Disconnect clue flush** — Apple finished LiveTracks on disconnect but did not wake pending clue uploads the way Android’s `flushPendingCluesBestEffort` does.
