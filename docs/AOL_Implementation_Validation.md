# AOL implementation and validation — September 11, 2026

**Current behavior (build 206):** the main AOL switch has been removed. Prepared
tiles are used automatically. Only explicit Map Download preparation fetches or
assembles raw lidar. Earlier opt-in sections below record implementation history.

## Delivered source workflow

Android and Apple now import a typed, checksummed `.aol` surface package; calculate
signed AOL over a 60.96 m horizontal disk; expose the field beside ATO/AGL in
map and stream presentations; and show provenance, resolution, coverage and
unavailability reasons in aircraft details. Negative values are red and retain
the minus sign. This introduces no alarm, recording gate, readiness gate or
operating-profile condition.

The preparation tool derives surface maxima from classified LAS/LAZ and a separate
compatible ground reference. It supports same-survey tile seams, bounded memory
processing, explicit NoData, peak-preserving coarse grids and dated unknown-height
wire point observations. The apps atomically publish validated multi-tile prepared regions, retain earlier
regions and the legacy imported package, pin them within the shared cache allowance,
and query immutable decoded arrays off the UI thread. Disk queries visit the
bounded grid window, not the whole package or raw cloud. Out-of-order aircraft
results are discarded; five-second telemetry expiry is independent of survey age.

**Vertical reference:** AOL requires explicit RID Ground status with near-zero
Takeoff height to establish its own ground launch reference. A first fix in flight
is not treated as takeoff. The compatible package ground plus current takeoff
height supplies aircraft elevation. Raw GNSS heights, guessed DEM units, AGL
fallbacks and manual ATO offsets do not silently enter AOL. Elevated launches are
unsupported. Unknown/unsupported vertical references remain unavailable.

Map Folders offers an **AOL** briefing for a single polygon or route. The briefing
includes a 200 ft boundary buffer/corridor radius, geometry bounds, maximum mapped
absolute elevation and location, compatible ground/height at that cell, dataset
identity and dates, coverage gaps, wire observations and map navigation. Reuse is
keyed by geometry, complete dataset metadata and width. It does not set RTH or a
permitted altitude. Existing flight archives and operating profiles are unchanged.

Usage and format: [preparation guide](../tools/aol/README.md),
[package format](../tools/aol/FORMAT.md).

## Opt-in and processing priority follow-up

The device-local **Use prepared obstacle elevations (AOL)** setting defaults off
on Android and Apple. Package import does not enable it. Disabled live tracking
skips surface loading and calculation; disabling clears displayed numeric results
and releases stored decoded arrays while preserving the package on disk. An
already-running bounded calculation may finish, but cannot publish while disabled.
Explicit assignment briefings also require the setting.

Enabled live AOL reads prepared data only. There is one admitted live calculation
at a time, a one-second per-aircraft start interval, and no telemetry work queue.
Android uses a dedicated background-priority worker; Apple uses utility priority.
Late results remain guarded by current request identity. This reduces competing
work; it does not prove zero impact on video decoding or anomaly detection.

## In-app map download preparation

**MapPane → Settings → Map Download → Prepare 1 m AOL tiles** is implemented on
both platforms and defaults off. The selected visible map or artifact bounds are
looked up in the USGS National Map LPC catalog with a 65 m margin. The preview
shows full source-file count, advertised input bytes, output tile count and working
space. Download completes ordinary map and ground work first, then explicitly
fetches and processes the selected LAZ sources. Preparation does not enable live AOL.

The newest published intersecting work unit is selected; overlapping older surveys
are not blended. This does not guarantee full coverage. Files are downloaded whole,
sequentially, to temporary storage. These are not S1M-sized raster pieces or EPT
spatial range queries. The catalog lists bounds, work-unit paths, publication dates
and estimated sizes; the embedded file WKT supplies the actual coordinate reference.
Unknown acquisition dates are explicitly distinguished from publication dates.

One native streaming processor is shared between Kotlin/JNI and Swift. It uses
laz-perf for decompression, accurate GeographicLib UTM conversion, filtered surface
cell maxima and nearest class-2 ground within 3 m. Ground and surface retain their
absolute same-survey elevations. Sources must have identical compound WKT with
explicit NAVD88 metre heights and a supported horizontal projection. No implicit
vertical transform is performed. Both per-file hashes and source WKT are retained.

A region is divided into 1,000 m cores with 62 m overlap at internal edges. The
selected rectangle already includes outer padding. Queries use the current core
plus its overlap; takeoff ground can come from another compatible tile. Briefings
aggregate core cells without counting overlapping edges twice. More than one region
can be prepared and used offline. Previous regions remain stored; individual region
removal is not yet exposed. The initial maximum grid is 4,000 cells across including
padding, accommodating a one-mile-radius bounding square.

Preparation uses one worker, 10,000-point cancellation batches, sequential source
transfers and a shared-cache reservation that cannot evict existing maps/DEMs.
Publication is an atomic directory move after package validation. Cancellation
removes temporary work; abandoned preparation directories are removed at the next
preparation. Raw source files are deleted before publication. Limits include 64
source files, 1 GB and 150 million points per file, and supported LAS point formats.
Coverage gaps remain NoData and are included in the final preparation report.
No raw download or assembly is initiated by drone movement.

### Catalog sizing measured near Nevada City

At 39.2422, -121.0384, with the catalog margin and newest work unit:

| Selected bounds | Original source files | Advertised download | Output tiles |
|---|---:|---:|---:|
| 40-acre square | 1 | 126.3 MB | 1 |
| 60-acre square | 2 | 265.9 MB | 1 |
| Bounding square of a one-mile radius | 22 | 2.98 GB | 16 |

Decimal MB/GB, sampled catalog, not a universal allowance or download-time promise.
A boundary crossing can add another whole source file even for a small parcel.
Working allowance is 1.5 times advertised source bytes plus 16 MB per output tile,
and a further 64 MB physical free space must remain available. Actual source size
can differ from catalog estimates; transfer limits reject an overrun.

The newer 126,272,548-byte file contains 25,727,605 points in EPSG:6339 + NAVD88 /
GEOID18. A 527 × 527 output grid (roughly a 40-acre square with padding) decoded in
11.244 seconds with 21,479,424 bytes peak process RSS on the development Mac.
The older 48,193,650-byte file produced a 600 × 600 grid in 4.208 seconds with
22,724,608 bytes peak process RSS. These optimized standalone native measurements
include decoding and raster assembly, not network, mobile UI, package compression,
whole-app memory, or simultaneous video/anomaly processing. Output arrays alone
use 8 bytes per cell: about 2.2 MB for the 527-square example, before ZIP compression.

Both real-source surface arrays and NoData masks match the independent Python/
PROJ preparer exactly. The newer ground differences occur only within 3 m of the
outer grid edge because native preparation also uses nearby ground outside the
grid. In the older source, all 37 additional interior ground differences are equal-
distance nearest-return ties; every native choice is an equally near source point
(maximum elevation difference 0.14 m). No unexplained interior mismatch remains.
A truncated initial UTM inverse was replaced after it moved a few returns across
cell boundaries; final comparisons use GeographicLib.

Primary catalog/tool reference: [USGS LidarExplorer](https://www.usgs.gov/tools/lidarexplorer).
Real newer source metadata: [USGS survey record](https://www.sciencebase.gov/catalog/item/655e8f33d34e3aa43a43ac26).
Reproducible processor: tools/aol/native_benchmark.cpp; fixtures include synthetic
Albers/UTM source seams, noise/withheld points and unsupported-reference input.
Logs and measured reports: outputs/aol/validation/aol-prep-* and native-*-report.json.

## Real source inspected

The USGS National Map API returned tile
`USGS_LPC_CA_NoCAL_3DEP_Supp_Funding_2018_D18_w2117n2082.laz` near Nevada City.
The actual 48,193,650-byte LAZ contains 8,508,738 points, including 5,537,942 class-1
and 2,735,414 class-2 returns. Its embedded compound CRS is EPSG:6350 with
NAVD88 metre heights. The project metadata identifies GEOID12B and acquisition
from 2018-07-07 through 2019-09-05; this is a survey range, not a download date.

Primary references: [USGS tile record](https://www.sciencebase.gov/catalog/item/5e21b5dde4b014c853023843),
[NOAA project metadata](https://www.fisheries.noaa.gov/inport/item/78405),
[USGS processing requirements](https://www.usgs.gov/ngp-standards-and-specifications/lidar-base-specification-data-processing-and-handling-requirements).

The actual point cloud was processed, not its bare-earth DEM. The surface-minus-
ground inspection shows crown-shaped elevated returns and lower roof-shaped
features. Object classes are not inferred in the product: the source uses
unclassified returns for these features. Classification-derived noise/withheld
filtering excluded 235,382 returns. The prepared area has 39,850 one-metre cells
more than 10 m above paired ground, with a maximum difference of approximately
53.03 m. This verifies above-ground surface content; it does not validate every
object or establish current obstacle completeness.

The reproducible real-data package is
[nevada-city-1m.aol](../test-fixtures/aol/nevada-city-1m.aol). It covers a 372 m square,
including padding, around 39.2422, -121.0384. It retains 270 NoData cells. Neither
missing cells nor a partially checked disk produce numeric aircraft clearance.

At 39.24144906749628, -121.03819679099078, both native implementations check 11,921
cells and return the same complete-disk peak: 856.1300048828125 m at
39.241547992736294, -121.03753491021789, approximately 58.052 m horizontally away.
Ground at that cell is 827.5800170898438 m. These are raster cell locations, with
pixel-footprint uncertainty, not surveyed tree coordinates.

## Measurements

- Compressed package: 488729 bytes; expanded raster payload: 1107072 bytes.
- Desktop streamed preparation: 2.04 seconds; measured process peak memory:
  164839424 bytes. This includes the Python runtime and source decoding.
- Host debug query measurements (100 repetitions): approximately 0.7–1.2 ms for
  Kotlin/JVM and 15–27 ms for Swift in test runs. These are desktop measurements
  under varying test/build load, not controller or tablet latency guarantees.
- The public LAZ download required a resumed transfer after a timeout. End-to-end
  transfer timing was not reliably instrumented; no regional download/storage
  claim or tablet memory claim is made from this small fixture.

## Automated checks

- Android: all 1,032 unit tests and the debug app build passed after final native changes.
- Apple: 320 Swift Testing tests plus 10 XCTest tests passed; unsigned arm64
  Simulator app build/link passed after final native changes.
- Preparation tests cover parcel/radius sizing, catalog survey selection, native
  Albers/UTM input seams, noise/withheld filtering, unsupported vertical reference,
  cancellation, output tile boundary disks, compatible cross-tile takeoff ground,
  and complete route aggregation over more than 100,000 core cells.
- Existing signed AOL, missing coverage, input rejection, offline real package,
  briefings and live-work-gate tests remain passing.
- Python: same-survey seams, filtering and strict peak-preserving coarse reduction.
- Real older/newer sources were independently compared as documented above.
- `git diff --check` passed.

One Android peer-coordinator test transiently observed `heartbeat` where it
expected `hello` during an earlier concurrent build. Its isolated retry and later
full suites passed; its source was not changed.

## Qualification and explicit limits

No device installation, live controller test, field qualification, deployment,
store publication or notification was performed. Phone/tablet layout behavior,
accessibility on devices, real telemetry tolerances, actual storage-pressure and
process-kill recovery, and on-device query/memory performance still require
qualification. Build success is not proof of these behaviors.

This is a bounded local preparation workflow with supported NAVD88 references,
single polygon/route briefings and same-survey preprocessing. Cross-survey datum/
geoid transformations, unrestricted large-region processing, multipart analysis, and
persisting a briefing snapshot into flight records are not implemented. Briefings
are reused in memory and are not represented as immutable historical flight
records. Full utility/FAA wire acquisition and 3D cable reconstruction remain the
separate integration stage specified in the handoff.

Corrupt imports and missing coverage fail conservatively. Package pinning prevents
cache eviction during reads, but forced low-storage/interrupted-update tests on
both operating systems have not been performed. The full handoff acceptance list
is therefore not claimed as field-qualified or exhaustively validated.

A pre-edit snapshot of the tracked working source and its diff was saved at
`/private/tmp/aol-preimplementation-source.tgz` and
`/private/tmp/aol-preimplementation.patch`; the original HEAD was
`07c02548502594851cc2a25ad25210142dba6c68`. Existing unrelated workspace changes
were preserved.

## iPad build 204 follow-up — September 12

Device report: repeated taps on Map Settings → Download Map caused flicker before
opening; AOL catalog lookup eventually displayed a generic request failure.
Fresh logs were retrieved from Ken's iPad's September 12 07:23 session. They did
not include catalog HTTP status or menu presentation events, so the exact original
HTTP failure and the full tap sequence cannot be reconstructed from those logs.
A contemporary desktop USGS LPC request returned HTTP 200; this does not prove
network availability from the iPad at the time of failure.

The split map's expansion gesture was attached outside its settings controls,
allowing control taps to also replace the layout and its menu presenter. The
expansion gesture is now attached only to map content. Menu selection and download
sheet presentation are logged. Physical confirmation of the reported interaction
remains necessary, particularly if it occurred outside split layout.

Apple AOL catalog requests now use the terrain retry policy for transient HTTP
and network failures, a 25-second per-request timeout, explicit application headers,
and bypass cached error responses. Exhaustion reports the HTTP status or connection
failure, logs it, and exposes a Retry USGS catalog button. Retry is bounded and
cancellable. The previous single request and generic failure text did not provide
these behaviors. No lidar download or assembly starts from these metadata retries.

All 321 Swift Testing tests and 10 XCTest tests passed, including new cases for
503/429 recovery, 403 without retry, bounded 503 exhaustion and cancellation during
backoff. Build and installation status are recorded separately in device logs;
source fixes and automated checks alone do not establish physical resolution.

## Android build 205 follow-up — September 12

The operator confirmed iPad catalog/download progress, then reported Android
`USGS lidar catalog HTTP 504`. Android now matches the bounded Apple catalog retry
behavior: up to three retries for 408, 429, 5xx and transient connection failures;
25-second call limit; application headers; network-only metadata requests; bounded
response size; explicit final status and a Retry USGS catalog action. Cancelling a
pending response cancels its network call; cancelling backoff prevents another
request. An empty later catalog page now terminates instead of looping at the same
offset. No raw source download or preparation is triggered by a metadata retry.

New Android tests use a local HTTP server to verify 504/429 recovery, a permanent
403 without retry, four-attempt 504 exhaustion, request headers and cancellation
in backoff. Targeted surface tests passed. Build 205's local release command
excludes Crashlytics symbol/mapping upload tasks; all release verification tasks
remain enabled. Device installation and physical behavior are verified separately.


## Build 206: catalog modal, automatic prepared data, and timing diagnostics

Both platforms now show a modal Checking USGS lidar catalog panel with Cancel.
Cancel stops the catalog task and clears the optional AOL preparation checkbox;
normal map download remains available. The main Settings switch and its stored
preference reads have been removed. Prepared tiles now serve aircraft calculations
and assignment briefings automatically. The single-worker, per-aircraft cadence,
reference checks, and strict coverage requirements remain. This does not add any
movement-triggered lidar fetching or preparation.

Sparse monotonic stage logs now measure catalog, source transfer, checksum,
assembly and publication. Apple additionally records map/terrain stage boundaries
and each catalog HTTP attempt with elapsed seconds. Android hashes during transfer,
so its corresponding source stage is explicitly combined. These stage logs record
no coordinates or source URLs. Physical confirmation of modal presentation and
cancellation remains separate from compilation/tests.

See [iPad timing review](AOL_iPad_Timing_Review_2026-09-12.md) for the September 12
operator download and the limits of its original instrumentation.

Build 206 validation/deployment: 1,035 Android unit tests and all local release
checks passed (Crashlytics uploads excluded); 321 Swift Testing tests plus 10 XCTest
tests and the signed Apple device build passed. Version 2.2.7 build 206 was installed
in place on both devices. iPad launch was verified; A5 Pro initially exceeded the
launch-wait timeout, then reached a visible, drawn app window. Physical modal and
cancellation retests and new download timing measurements are still outstanding.
Android timing markers follow the device's diagnostic logging/filter configuration.

## Build 207: interrupted source transfers and retry recovery

The A5 Pro screenshot (08:17, prior to build 206) reports `stream was reset:
CANCEL`, with 14/14 map tiles and 2/2 DEM pieces all cache hits. Its 85% is an
overall weighted estimate, not a measured fraction of completed AOL processing.
The archived Android 08:12 session records a download-failure toast at 08:16:15.915.
It lacks a request-level record proving which endpoint cancelled the stream.
The connected device was verified as build 206 during diagnosis.

A concrete Android defect was found: AOL did not report activity to the offline
service's 45-second stalled-call watchdog, unlike ordinary map/DEM transfers.
Build 207 reports activity at source-request start, every received chunk, and every
bounded native point batch. This prevents active AOL work from being mistaken for
an idle request, while truly inactive calls remain recoverable. Interrupted source
transfers get at most two retries; each uses a fresh request, truncated output and
fresh checksum, and coroutine cancellation is never retried. Successfully completed
map/terrain data and validated prepared regions remain intact.

The Android catalog effect now reacts to job completion and preserves the plan
while a job is active. A matching plan can be reused after failure; changed or
missing plans can be checked again. Starting a new run clears stale completion and
failure state. A run with failures no longer marks the selection complete.

Android shows a prominent failure popup and red heading, plus Retry on the main
download panel. The popup returns to review/retry controls; closing a dialog does
not discard completed cached data. Apple also gains a prominent failure notice and
Retry label. These notices apply to explicit offline preparation, not aircraft
clearance alarms.

Regression coverage includes stream-reset retry success/exhaustion, operator
cancellation without retry, real activity versus idle watchdog timing, and catalog
state across running/stopped/changed-region cases. Physical download recovery still
requires an operator retest; source reasoning does not prove the old reset origin.

Build 207 automated validation: 1,039 Android unit tests and all release checks
passed; 321 Swift Testing tests plus 10 XCTest tests and the signed Apple device
build passed. Crashlytics upload group and both upload tasks were explicitly
excluded. Version 2.2.7 build 207 was installed in place on both devices; prepared
and cached data were preserved. Physical retry after a USGS reset remains an
operator retest, distinct from the deterministic recovery tests.

## 2026-09-12 — MA package transfer and preparation reuse

Android and Apple MA exports now include complete prepared AOL sets intersecting
 the selected bounds, regardless of the separate DEM checkbox. The optional
`aol_entries` manifest array lists `aol/<set-id>/index.json` and the set's `.aol`
files. Import restores the set to the local surface store and invalidates its
lookup cache. Packages without AOL remain compatible. Export/import status shows
AOL tile counts; Android's import preview also shows the count.

The index carries `preparedAtEpochMs`, preserved on transfer. For sets prepared
by builds through 207, export and reuse recover the original preparation time
from the timestamp-prefixed set identifier. Import does not reset that time.
Import validates checksums, complete core membership, geometry, and survey
reference before publishing each set. Existing identical set identifiers are
not installed twice. The shared cache budget applies to newly installed sets.

Before contacting USGS, explicit preparation now looks for a valid whole set
covering the requested bounds plus the 62 m assembly margin. The same check is
repeated at preparation start. Sets younger than Maximum Tile Age are reused;
zero/unknown dates, future dates, missing/corrupt tiles, and expired sets do not
qualify. UI reports “AOL already prepared — using cached tiles”; this path has no
lidar download, conversion, or temporary-space reservation. Prepared surface
holes remain unknown and are not mistaken for corrupt packages.

Reuse currently requires coverage by one complete prepared set. Same-area and
contained-area requests qualify. A larger or partially overlapping area can
still require preparation; this change does not retile or stitch different
survey grids to calculate only the uncovered fragments. Export keeps complete
sets and their internal overlap rather than clipping rasters to the MA boundary.

Validation: Android 1,042 unit tests passed; Apple 323 Swift Testing tests and
10 XCTest tests passed. Shared prepared-set fixture validates both platforms'
readers, checksums, grid membership and date handling. Regression checks cover
expiry boundary, unknown/future dates, required coverage margin, corruption and
misplaced cores. Apple also exercises ZIP encode/decode of the prepared set.
Physical device-to-device MA transfer and repeat preparation remain field checks.
