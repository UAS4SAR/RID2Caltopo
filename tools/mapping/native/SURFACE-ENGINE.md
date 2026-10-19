# Local RTK surface engine

The installed Android app runs Ceres CPU bundle adjustment, OpenCV SIFT tracking
and multi-view stereo, bounded 3D-track interpolation, GLB export and orthographic
XYZ tile projection in its own process. There is no runtime process execution,
NodeODM service, network upload or downloaded engine. This first path requires
visible-wide, straight-down, fixed-RTK originals, exposure altitude/uncertainties
and enough valid exposure laser targets to anchor the focal/height ambiguity of
a level nadir flight. It retains the originals and previous layers.

The 3D artifact is a **visible-surface heightfield**, including roofs and canopy.
It cannot reconstruct unseen ground beneath canopy or complete vertical facades.
Unknown depth stays unknown. Sparse interpolation is bounded to triangles with
edges at most 6 m and height span at most 5 m; its contribution is recorded
separately from consistent stereo depth. The 2D deliverable samples original
images through the calibrated cameras onto that reconstructed surface.

## Development build

Build the CPU dependencies from the exact pinned OpenMVG v2.1 revision, then:

```sh
python3 tools/mapping/native/build_openmvg.py android \
  --source /private/tmp/r2c-openmvg-v2.1 --build /private/tmp/r2c-openmvg-android
python3 tools/mapping/native/build_surface_engine.py \
  --source /private/tmp/r2c-openmvg-v2.1 \
  --dependencies /private/tmp/r2c-openmvg-android \
  --build /private/tmp/r2c-surface-engine \
  --opencv /path/to/opencv-4.12.0/prefab/modules/opencv_java4
```

The stripped runtime is staged at the ignored
`tools/mapping/dist/native/android/arm64-v8a/libr2c_mapping_surface.so`, included
by Gradle alongside existing JNI libraries and retained through `gradlew clean`.
The build pins the source commit and rejects another revision. Android NDK 29
and CMake 3.22.1 are used; the shared library has a 16 KiB ELF alignment.
Developer-side compilation does not process flight imagery. Follow the existing
device-encrypted DJI provisioning procedure separately; plaintext credentials
are not inputs or assets of this engine.

## Outputs and qualification

Each completed layer stores `surface/surface.glb`, the little-endian
`surface/surface.f32` DSM, `surface/surface.json`, calibrated `surface/cameras.json`
and the adjusted sparse structure as `surface/sparse-points.ply`. The GLB is a
colored preview mesh; original-image 2D tiles retain substantially finer detail.
Model references, byte sizes and SHA-256 checksums accompany the 2D map package.
Only the fixed data artifact names can be imported; HTML and executable scripts
are never imported with a model. The Android viewer is an internal protected
activity, with an intercepted local HTTPS origin, an offline WebGL renderer,
orbit/pinch/pan/reset controls and a return-to-2D button.

Device tests are explicit opt-ins against a named local mission or layer. They
verify unchanged photo inventory, geometry limits, retained model data, durable
tile reads and package interchange. Pixel sampling density and adjustment
residuals do not establish independently surveyed positional or height accuracy.
Thermal, Apple runtime integration, closed-canopy bare-earth reconstruction and
full volumetric meshes require separate qualification.

## Controlled oblique preparation (2026-10-11)

The initial nadir-only restriction above describes the completed field
qualification. Full DJI exposure attitude, slant rectified depth bounds and
conservative oblique texture visibility are now implemented. Synthetic A5
controls and a retained-nadir adjustment regression pass; real oblique field
qualification is pending. The user-approved -60 controlled-trial export is
enabled; -45 remains gated. Pilot 2 retains clearance review/start authority. See
[the implementation and qualification report](../../../docs/validation/2026-10-11-oblique-capture.md)
for profiles, estimates, preserved limits and the required first -60 review.

## Common reconstruction implementation

Android JNI and the Apple C wrapper now invoke `native/mapping/Reconstruction.cpp`
for feature matching, track joining, camera adjustment, stereo pair ordering,
depth fusion and interpolation. `SurfaceSampling.hpp` supplies full-frame box
sampling and fixed-point grayscale conversion; platform adapters only decode
originals into sRGB RGBA. Native JPEG decoders can still differ slightly.
Android links the pinned OpenCV 4.12.0 AAR native library and its shared C++
runtime; do not mix a statically linked C++ runtime across the OpenCV boundary.
The AAR prefab module path is available in Gradle's transformed dependency cache.
Geometry/checkpoint versions change with the new sampling policy. Old completed
results remain readable, but new jobs cannot reuse old camera/depth checkpoints.

`R2CMappingPipelineTests` compares the complete Apple entry point against the
split alignment/dense entry points used by Android with identical inputs, including
NaN masks. It also checks cancellation, malformed buffers, and sampling at image
corners. These tests run on the host without either tablet attached.

## Overhead preference for 2D projection (2026-10-15)

The shared projection engine ranks each valid camera by its viewing-ray angle
from vertical at the actual surface location, with small image-radius and border
penalties. Gimbal pitch alone does not establish whether a photo is overhead.
Photometric seam cuts are restricted to candidates within a two-degree score
allowance of the original best view. That reference stays fixed across pair cuts
to prevent cumulative drift. The final renderer gives a seam label only that
bounded allowance and reevaluates visibility and projection at each output pixel.
It no longer unconditionally overrides a better overhead source. Oblique images
remain available to the shared depth reconstruction and as projection fallbacks.
The allowance is in angular-score units; border penalties can also affect rank.

Projection marker: `overhead-photo-seams-v3`; result engine:
`regions-v10-overhead-projection-mesh04`. Existing geometry checkpoints remain
valid, but completed region/tile checkpoints must be regenerated. No interpolation
between roof and ground heights or site-specific image rules are introduced.

## Ray-based obstruction for every source (2026-10-15)

`projectionVisible` now tests the surface-to-camera path regardless of optical-axis
pitch. The previous three-degree near-nadir exemption accepted offset views even
when their ray crossed a reconstructed roof. Seam preparation and final pixel
rendering use the same corrected rule. Existing half-cell ray sampling, the
0.65 m obstruction tolerance and 1,024-step work limit remain unchanged.
Missing intermediate heights now classify a ray as uncertain rather than blocked.
The full path is still checked: a later measured obstruction rejects it. A large
score penalty makes uncertain rays fallback sources only when no clear view is
available. The output point itself still requires supported height; this does
not fill missing depth or invent colors. Overhead source preference remains in place.

Native regressions check orientation invariance, an actually vertical clear ray,
a ground-side clear ray, unknown intervening heights, and the complete renderer
choosing an unobstructed source over a nearer roof-blocked nadir photo. Projection
marker: `ray-coverage-photo-seams-v5`; engine: `regions-v12-ray-coverage-projection-mesh04`.
Geometry checkpoints remain valid; colors and tiles must be regenerated.
