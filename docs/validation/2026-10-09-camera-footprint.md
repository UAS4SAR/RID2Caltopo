# Local camera footprint — iPad and A5 Pro

Implemented and installed development builds of 2.5.0 (340), then reinstalled
with the corner-first performance correction, on the connected
iPad Pro and Samsung SM-X350 A5 Pro on 2026-10-09. No store release was published.

## Behavior

- Enable **Camera footprint** in the Drone Inspector. The setting is saved per
  aircraft Remote ID and defaults off. Disabling it removes the overlay and
  stops its frame-pose lookups/terrain work; when no aircraft is enabled, the
  map footprint worker is stopped entirely.
- An enabled footprint requires fresh, bound DJI SEI camera telemetry with a
  valid launch reference and available calibrated ATO.
- A pinhole frustum uses horizontal/vertical FOV, calibrated tilt, and calibrated
  true-north azimuth. The immediate display projects four corner stubs onto a
  flat plane at launch elevation, without terrain reads or connecting lines.
  Camera roll is assumed to be zero.
- Following review of the latest A5 Pro screen recording, the background pass now
  projects only four terrain rays and connects those intersections. The earlier
  32-ray edge refinement is no longer run by the live maps. Ray marching uses 5 m
  slant-range intervals and refines the first ground/coverage crossing to about
  5 mm of ray distance. This tolerance does not imply millimetre geographic
  accuracy. Narrow terrain features between samples can be missed; straight edges
  between the four terrain corners approximate the footprint. Maximum slant
  range is 3 km.
- Each map has one worker that samples the newest pose, then waits 500 ms before
  the next pass. Each aircraft calculation has a 100 ms cooperative budget;
  an individual cold raster read/decode can exceed it, but executes off the UI
  thread. New telemetry does not cancel and restart the worker. A newer pose
  immediately updates the four corner stubs and prevents an old boundary from being
  drawn. Missing, slow, or cancelled terrain never removes the corner stubs.
- Android batches reuse one file descriptor per sampled tile, filter coarse DEMs
  before decoding, and suppress per-point warning logs during footprint work.
- Terrain height is downloaded S1M elevation at the launch reference plus
  calibrated ATO. Both launch and aircraft must have usable local S1M coverage;
  the completed terrain pass omits the connecting lines otherwise. The immediate
  flat-ground corner stubs and existing short camera FOV rays remain available.
  No network DEM lookup or download is initiated
  by the footprint.
- Ray endpoints stop at the first missing S1M sample or maximum range. Segments
  adjacent to these endpoints are dashed. Confirmed intersections are solid.
- Borders are cyan, 1.25 dp/pt, with a 3 dp/pt dark halo and no fill. Each corner
  has two short arms pointing toward its adjacent corners. Arm
  length is bounded by 2% of the map's smaller dimension and 12 dp/pt, and by 40%
  of its adjacent edge length so tiny footprints do not become closed boxes.
- Native-video pose uses frame-associated telemetry, with fresh live telemetry
  for sessions without native frame timestamps. Calibrated ATO is adjusted by
  the SEI relative-height difference between the live sample and frame sample.
  Android accepts a preceding SEI pose within 250 ms of the rendered frame. When
  frame metadata is absent, both platforms retain fresh live SEI for the corner
  preview. Exact clue-capture frame matching is unchanged. Stale/incomplete live
  snapshots are suppressed; terrain results are drawn only for the matching
  current pose.

## Automated verification

- Swift `CameraFootprintTests`: 12 passed.
- Android `CameraFootprintTest`: 12 passed.
- Android `StreamCameraTelemetryRegistryTest`: 18 passed.
- Existing Android `ClueProjectionTest`: 12 passed.
- Android Debug APK and signed iPad Debug app built successfully.
- Both final builds installed successfully. The A5 Pro launched and remained
  running with no AndroidRuntime startup errors. Remote iPad launch was denied
  because the device was locked; unlock and launch manually. Device launch checks
  are separate from camera-footprint field qualification.
- `git diff --check` passed. Android tests emitted the existing JaCoCo/JDK 25
  instrumentation warnings; JUnit XML reports no skipped or failed tests.

## Field checks still required

1. Enable **Camera footprint** in that drone's inspector. Download S1M covering
   the launch point, aircraft, and expected view. Establish or apply the normal
   50 ft ATO calibration. Toggle the feature off/on and verify the saved state
   survives reopening the inspector and restarting the app.
2. Look straight down: verify the rectangle's location, orientation, and size
   against identifiable ground features. Repeat at a different altitude/FOV.
3. Tilt forward and pan: verify the footprint follows the video without leading
   it and that terrain refinement agrees with slopes and ridges.
4. Aim across a downloaded tile boundary: verify the clipped endpoints and dashed
   closing segments. Aim near the horizon to check range clipping.
5. Zoom/pan/rotate the map, switch full/PiP layouts, and stop the stream: verify
   stable thin strokes/corner stubs and removal of stale footprints.

The boundary is an estimate of image-edge ground intersections. It does not
represent buildings, vegetation, or interior terrain occlusion.

## Recording review

Reviewed `Screen_Recording_20261009_134250_RID2Caltopo.mp4` from the A5 Pro
(79.84 seconds). Sampled frames show extended periods with the existing drone
and heading overlays but no visible footprint. Code inspection found repeated
32-ray job cancellation/restart, terrain results replacing the corner preview,
per-sample descriptor opens/warning logs, and exact-frame-only pose gating. These
are corrected above. This review does not provide a measured on-device speedup;
repeat the live-flight recording to qualify perceived latency and visibility.
