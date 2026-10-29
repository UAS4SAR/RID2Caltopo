# Clue terrain and map worker fixes — October 29, 2026

Addresses the cows/vert clue discrepancy and the app-side presentation work
identified in the [A5 Pro test review](/Users/kjt/Projects/RID2Caltopo/docs/validation/2026-10-29-a5pro-basic-test-review.md).

## Clue saves

Submission freezes the capture inputs, binds against the capture's flight, and
captures its destination before waiting for terrain. A worker completes terrain
intersection before durable image/index storage and CalTopo publication. An
already completed fresh projection is reused; a changed waypoint origin
invalidates the old terrain result. Image/frame timestamps, camera bearing,
gimbal angle, and SEI home/relative-up inputs remain those of the photograph.

Preview calculations may still be cancelled when the user edits the form. Save
replaces that preview work with required preparation of the accepted snapshot;
cancelling a preview no longer silently commits its flat-ground approximation.
All required DEM samples must be fresh. A 30-second lookup budget prevents
continuously sampling an unavailable view. Unavailable/stale terrain or timeout
retains the form and image, reports a retryable error, and performs no publication.
The UI stays responsive and displays its existing saving state during preparation.

This change does not adjust empirical camera calibration or infer a corrected
historical cows coordinate from the vertical photograph. The two original clue
records and CalTopo markers were not rewritten.

## Map presentation

- Waypoint render snapshots use a volatile append-only point history. Readers
  take a stable head and traverse on a worker, without locking the ingest/archive
  monitor or parsing mutable JSON. Durable archive coordinates and write ordering
  remain unchanged. The active-track registry supports concurrent worker reads.
- Main reads a small revision token to avoid reseeding unchanged history. One
  conflated coroutine per map prepares full-history merges, sorting, and new
  Compose state lists on Default. Applying the result swaps prepared state;
  identity, segment and map/flight epoch checks reject obsolete results.
- A separate single conflated preparation creates osmdroid line geometry and
  filters recent points on Default. A worker never changes a line already
  displayed. Main attaches the prepared lines and applies UI styling/info windows.
- Saved configuration and filesystem-dependent osmdroid setup run during the
  settings startup worker, after saved app configuration is ready. The published
  configuration's path getters return prepared File values. This also bypasses
  osmdroid's default getter, which called mkdirs during MapView construction.

The controller/socket arrival gaps in the review are a separate upstream
condition. These changes address the identified app-side work, not the Wi-Fi or
controller's production of video. Physical playback improvement still requires
field verification.

## Validation

- Final full Android unit suite: **1,800 cases, zero failures/errors, one existing
  skip** (1,799 passed).
- New regression cases cover delayed terrain blocking publication, frozen capture
  inputs despite later telemetry, missing/stale terrain, timeout, completed
  projection reuse, 30,000-point chronological history preparation on a worker,
  stale-flight/live-point rejection, immutable render-object reuse, path getters
  that cannot touch disk, and render snapshots returning while the ingest/archive
  monitor is deliberately held.
- Existing clue projection, map seeding, waypoint archive/rename, consent and
  CalTopo track tests passed in the complete run.
- Debug Kotlin/Java/native compilation and APK assembly passed.
- `git diff --check` passed.

An earlier full run exposed an independent intermittent planner preference test
timeout. It also reproduced under the standard test configuration. Temporary
diagnostic tracing was removed; the original test and production planner source
were unchanged in the final passing full run. Final unit tests used a temporary
Gradle init script to disable the JaCoCo agent that cannot instrument JDK 25
classes; no project coverage configuration was changed.

## A5 Pro installation

Installed in place with `adb install -r` on SM-X350 serial R52Y90C9XST at
**07:16:48 PDT on October 29, 2026**, as reported by Android's lastUpdateTime.
Verified package `org.ncssar.rid2caltopo`, version **2.5.5 (432)**. The original
October 19 07:09:18 first-install timestamp was preserved.

APK SHA-256:
`ccccb6a0fada3ec1befad4d724368b87b66585f5bf3af3324d981e318af4f4de`.

Artifact, test XML/logs, source hashes and installation record are retained in
[clue-terrain-map-workers](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/clue-terrain-map-workers),
including the
[installation record](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/clue-terrain-map-workers/a5pro-install-record.json).
Verification was limited to installation and OS identity/version checks. No
app launch, functional/instrumentation test, backup, uninstall, permission reset,
or device-data comparison was performed.
