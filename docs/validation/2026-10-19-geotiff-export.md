# Local-map GeoTIFF export — Android and Apple

Both Map Data Inspectors offer **Prepare compressed GeoTIFF (.tif)**, followed by
**Share GeoTIFF (.tif)**. Save the shared file, then use CalTopo **+ Add → Map Sheet
→ Upload New** with a Pro or higher account. No tracker service is involved.

The export contains the full captured 2D extent, independently of the target-region
fade toggle. Transparent gaps remain transparent. It does not contain 3D data or
original photographs and does not change the saved reconstruction or its ZIP.

## Format and resolution

- Standard little-endian tiled TIFF: 256 × 256 tiles, lossless Deflate-compressed 8-bit straight
  RGBA, top-left orientation, ExtraSamples=2, chunky pixels.
- GeoTIFF PixelIsArea, EPSG:3857, with pixel scale and upper-left tiepoint derived
  directly from the XYZ tile coordinates. No screenshot alignment is involved.
- **Limit to 40 MB for CalTopo** is adjacent to the prepare button on both platforms
  and unchecked by default. Unchecked exports retain the highest available saved
  zoom and can exceed 40 MB; their actual size is shown with an upload advisory.
- Checked exports attempt the highest saved zoom using actual lossless compressed
  output. They stop a failed candidate as soon as it exceeds 40,000,000 bytes,
  remove its private partial file, and try the next lower available saved zoom.
  They never estimate fit from raw RGBA size or change the saved source tiles.
- The completed result shows its selected zoom, file size, and any reduction in
  detail. If no saved zoom fits, export fails with an instruction to uncheck the
  limit. Decode/IO errors and cancellation propagate rather than reducing zoom.
- The checkbox is disabled while exporting. Changing it invalidates prepared share
  state so the old export cannot be accidentally shared as the new choice.
- Standard zlib-wrapped Deflate per tile, TIFF Compression=8. Each tile offset is
  word aligned. The disk-backed offset/count tables are patched as tiles finish,
  so table size does not require proportional RAM allocation.
- A missing tile inside the selected rectangle becomes transparent. An existing
  unreadable or incorrectly sized tile fails the export instead of silently
  replacing it with a gap.
- Classic TIFF’s 32-bit index/offset format is checked before overflow. A result
  that cannot be represented reports a format-limit error; it never silently
  lowers the saved zoom. BigTIFF is not implemented.

## Execution and cancellation

Android enters Dispatchers.IO before scanning directories, decoding PNGs, or
writing. Apple uses Task.detached with immutable root/zoom inputs. Each worker
streams one decoded tile at a time; neither assembles a full mosaic bitmap.
No worker disk lock is acquired by UI rendering or status updates. There is one
export per inspector, with explicit cancel and cancellation on dismissal/change.
Workers check cancellation while scanning and between tiles. Partial output is
removed on errors/cancellation and published only after the completed file is
flushed. UI state only receives a completed URL and zoom.

Android serves the finished TIFF via the existing read-only, explicitly granted
mapping share provider with image/tiff MIME type. Apple uses a unique temporary
share directory; exports older than a day are cleaned on the next export worker.

## Validation

- Android build and `MappingGeoTiffTest`: 4 focused tests passed. Covers Deflate
  decompression, exact RGBA and georeference tags, transparent gaps, native zoom
  selection, failed-write cleanup, classic TIFF index overflow, and a successfully
  written compressed file **larger than 40 MB**, a corresponding checked export
  that fits, compression-only fit at native zoom, missing-zoom fallback, no-fit
  cleanup, and preserving previous completed output on failure.
- Apple `MappingGeoTiffTests`: 5 focused tests passed with corresponding coverage,
  PNG orientation and straight-alpha recovery, and a compressed file over 40 MB.
- Independent Pillow/libtiff decoding of the Apple sample verifies image size,
  colors, alpha, orientation, missing tiles, Compression=8, and EPSG:3857.
- Apple’s ImageIO recognizes these tiled Deflate TIFFs but does not decode them in
  the local macOS environment. A Pillow-generated strip-based Deflate control
  does decode there. Native Apple previews therefore cannot be relied on for
  these GIS exports; pixel compatibility is verified with libtiff.
- Android APK and signed arm64 iPad app compile successfully.
- Local Gradle tests emit existing JaCoCo warnings about Java class version 69;
  the focused test report records zero failures/errors.

A live CalTopo import of the new compressed output has not been exercised.
CalTopo instructions checked against:
https://training.caltopo.com/all_users/mapsheets/create-sheet

The initial implementation imposed a 40 MB uncompressed export budget. The user
correctly identified the unnecessary resolution loss; the current implementation
supersedes that policy with full-resolution lossless compression. The existing
Samsung SwampArea export was zoom 22 while the saved map reaches zoom 23.
A direct CalTopo web-viewer check requested zoom 22 at 0,0; the viewer clamped it
to zoom 21, and further scroll zooming stayed at 21. That viewer limit is separate
from the GeoTIFF’s resolution.

## User-requested installation

After the user requested installation, built and installed both development apps
in place. Samsung Active5 Pro: Android 2.5.2 (384), `adb install -r` succeeded and
Package Manager confirmed the updated installation timestamp. Ken’s iPad: signed
iOS 2.5.2 (392), `devicectl device install app` succeeded over existing build 391.
No uninstall, data clear, managed instrumentation, or test-data injection was used.

## Assignment-name correction

GeoTIFF sharing now follows the ZIP naming convention on both platforms:
`<assignment>_2D_map.tif`. Both formats use the same assignment lookup and safe
filename rules. Android exposes this filename via the share provider's display
name; its internal cache file remains `map.tif`. Apple writes the assignment-named
file directly into its unique share directory. The Apple inspector passes the
assignment resolved from result/mission metadata into the detached export worker.
Android APK and signed iPad build 393 compiled; the 7 focused Apple GeoTIFF and
original-access/sharing tests passed.

## Full-resolution compressed update installed

Installed the lossless Deflate/full-resolution change in place on the Samsung
Active5 Pro (Android 2.5.2 build 384) and Ken’s iPad (iOS 2.5.2 build 394).
Both install commands succeeded. Previously shared TIFFs retain their old reduced
resolution; prepare and share a new export to use the full saved zoom.

## Optional CalTopo compatibility limit

Added the unchecked-by-default size-limit checkbox after the user requested an
explicit compatibility choice. Actual compressed bytes determine whether a lower
saved zoom is needed. Export work, retries, decoding, and filesystem cleanup remain
inside Dispatchers.IO (Android) or the detached worker (Apple). No UI-side scans,
blocking locks, or parallel export jobs were introduced. Focused tests cover both
modes, the 40,000,000-byte boundary, no-fit behavior, errors/cancellation, and stale
prepared-output invalidation is wired directly to checkbox changes.
