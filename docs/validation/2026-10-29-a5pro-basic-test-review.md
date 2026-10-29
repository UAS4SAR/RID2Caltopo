# A5 Pro basic test review — October 29, 2026

Follow-up: [implemented fixes and A5 Pro installation](/Users/kjt/Projects/RID2Caltopo/docs/validation/2026-10-29-clue-terrain-map-worker-fixes.md).

The owner completed this test after the 06:11 in-place installation of 2.5.5
(432), reporting intermittent jerky video and an inaccurate `cows` clue center.
This review reads the existing evidence; it does not run or change the app.

## Clue location finding

The saved markers are **23.67 m (77.66 ft) apart**. Both saved clue records report
`uploadState=published` with HTTP 200. The displacement is already present in
the app's saved coordinates, rather than introduced by CalTopo publication.

| Capture | Frame/camera match | Projection used when saved |
| --- | --- | --- |
| cows, 06:35:24 app capture | Exact matching PTS 89951711 | Tilt −26.2°, camera bearing 95.2°, 19.9 m DJI relative-up; flat-ground estimate, no DEM; calculated horizontal range about 40.4 m |
| vert, 06:37:20 app capture | Exact matching PTS 205984711 | Tilt −90°; local USGS 1 m DEM refinement completed at 06:37:21.115; target directly beneath the captured aircraft position |

The first clue's saved description explicitly says `DEM used: none (flat-ground
estimate)`. Its 65 ft height is DJI home-relative altitude; treating that as
height above terrain at the viewed image center is unreliable on sloping ground.
At this tilt, each metre of height error changes the flat projected range by
approximately 2.03 m. The saved images support the owner's comparison of the
same cattle/ground area, but do not independently establish a surveyed true
center coordinate.

The flat estimate used target altitude 560.796 m. The vertical capture's DEM
target altitude is 534.112 m. These are different positions and the provisional
DJI reference datum is opaque, so their difference must not be treated as a
measured terrain-height error or used directly to relocate the first marker.

The code starts DEM projection on a background dispatcher after capture. No
completed DEM refinement is logged for cows before submission at 06:35:36.139,
11.4 seconds after capture. `startClueSave` freezes the provisional clue and
cancels `clueProjectionJob`. The source therefore permits submission of this
flat estimate while its terrain calculation remains unfinished. Vert's direct
downward lookup completed in about 0.3 seconds and avoided that path.

Relevant source:

- [Capture and initial projection](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt:2537).
- [Save cancels pending terrain work](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt:3000).
- [Terrain refinement worker and stale-result checks](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt:3196).

The correction should freeze the photograph's inputs and finish its terrain
projection asynchronously before publishing a terrain-resolved marker. If
terrain cannot be resolved, preserve an explicit approximate/pending state
rather than silently accepting relative-up as ground clearance. Preserve the
original capture clock, destination and consent; later live telemetry must not
replace this photograph's inputs. The precise corrected cows coordinate remains
unverified. Neither historical marker was edited during this review.

## Video finding

The live Matrice render session remained session 2 from 06:33:54 through
06:42:54; no decoder restart or bulk live-edge queue trim appears in that
session. Its render control normally stayed around 33–34 ms per frame.

There are two distinct sources of unevenness:

| PDT time | Decoded-frame gap | Corresponding arrival evidence |
| --- | --- | --- |
| 06:34:33 | 741 ms | Both the telemetry and render readers pause; RTMP records 725 ms socket wait, zero non-socket processing time on that read. |
| 06:38:52 | 1,282 ms | Both readers pause; RTMP records 1,272 ms socket wait, zero non-socket processing time. The render queue drains. |
| 06:41:53 | 743 ms | Both readers pause; the containing RTMP interval reports a maximum 752 ms arrival gap. |

The first two timing records directly locate the large pauses before decode,
while waiting for data from the controller connection. They do not distinguish
controller production pauses from Wi-Fi/TCP delivery delays. After the largest
gap, the player raises its buffer target to 1.8 seconds to absorb further gaps.

There is also display/UI jank. The available process logcat contains **30
Choreographer warnings**, from 06:41:18.743 through 06:42:53.655, each reporting
30–71 skipped display frames. SurfaceTexture callbacks, logged about every five
seconds, have median observed rate 24.5/s and low intervals around 14–16/s. These
are callback counts, not a precise display-FPS measurement; Main-thread delivery
can coalesce frames. They nevertheless corroborate uneven presentation beyond
the three arrival gaps.

Main-thread CPU load reaches 80% of one core, median 47.5% across 106 logged load
samples. The recorded thermal states are cool/light, and anomaly detection is
off. These observations do not support severe thermal throttling or anomaly
analysis as the principal explanation. Native packet-processing maxima are
43–116 ms in render windows, much shorter than the 0.7–1.3 second arrival gaps.

## Concrete UI-thread findings

- [Track reseeding](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt:2931)
  runs from Compose `LaunchedEffect` and mount `DisposableEffect` on Main. It
  copies/parses the full WaypointTrack JSON history, scans for the newest time,
  constructs point objects, groups/merges the growing history and sorts it.
  Work grows with flight size and violates the project's UI-thread requirement.
  The logs show repeated `track_seed_snapshot` events on Main. This is a
  concrete correction candidate, but these logs alone do not establish how much
  of the observed jank each map operation caused. Prefer versioned prepared
  track snapshots from their owning worker and bounded UI updates.
- **Eleven StrictMode disk-read reports** identify app calls from
  [configureOsmdroid](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt:586)
  into `context.filesDir`, which calls `ContextImpl.ensurePrivateDirExists`
  and `File.exists`, plus map construction. They occur at
  06:42:56 and 06:44:19/27, after live video stopped, so they do not explain the
  preceding in-flight pauses. They are independently actionable violations;
  prepare/configure filesystem-dependent map paths on a worker.
- A separate startup Main-thread delay of about 1.06 seconds appears at
  06:31:37, before video starts. The captured stack is in Compose/lifecycle
  collection and does not identify a specific app-owned blocking operation.

Four queue-enqueue/drop warnings belong to short auxiliary recording decoders
used around 06:36:14/20, not live session 2. They should not be counted as live
video decoder failures. Two HTTP 400 GET messages during photo publication are
consistent with the media-existence probe's supported create-if-missing path;
both selected clue records ultimately report published.

## Track-fix verification

At 06:43:24.475 the app archives
`1sar7DjMtrc4td_063354Oct29` with **482 points**. At 06:44:23.250 CalTopo
acknowledges the deferred recording link on that archived track. This test
contains the expected nonempty title and successful video-link update.

## Evidence and limits

Evidence is retained in
[the diagnostic folder](/Users/kjt/Projects/RID2Caltopo/build/diagnostics/2026-10-29-a5pro-basic-test),
with hashes and counts in
[review-summary.json](/Users/kjt/Projects/RID2Caltopo/build/diagnostics/2026-10-29-a5pro-basic-test/review-summary.json).
The inputs are one October 29 application log, process-scoped logcat for PID
16012, the two selected clue records, and their two saved JPEGs. The source
application log is
`/storage/4A21-0000/DroneTrax/tracks-29Oct2026/Log_29Oct2026-063135-PDT-0700.txt`.

The app log spans startup and the complete flight; process logcat's verbose
buffer is only partially retained, so an absence of earlier display warnings
is not proof of smooth earlier presentation. No crash or ANR was found in the
captured evidence. No app launch, device testing, install, settings change,
map edit or device-data modification was performed as part of this review.
