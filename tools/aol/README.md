# Offline AOL preparation

Prepared obstacle tiles are used automatically for aircraft AOL and assignment
briefings, including offline and during flight. There is no main Settings switch.
Missing coverage remains unavailable. Downloading and assembling lidar still
requires explicitly selecting **Prepare 1 m AOL tiles** in Map Download.

Live queries use one background worker at a time, with at most one start per
second per aircraft; busy requests are dropped and retried from current telemetry.
No raw lidar download, conversion, or automatic surface preparation is triggered
by aircraft movement. Missing coverage remains unavailable.

## Prepare on the device

In **MapPane → Settings → Map Download**, select the visible map or an assignment
polygon/route and check **Prepare 1 m AOL tiles** (off by default). A modal catalog check with Cancel appears first. Cancel stops the lookup and
clears the AOL preparation checkbox; ordinary map downloads remain available. The preview lists source-file count, advertised download size, output tile count and
temporary space allowance. Start downloads the selected maps and ground data first,
then downloads original USGS LAZ files and assembles the surface tiles. Cancellation
stops the network transfer or the next bounded point batch and removes temporary
work. Successfully completed map/ground downloads remain usable.

The USGS National Map catalog indexes lidar files by geographic bounds and survey
work unit. This implementation downloads complete intersecting source files; it
does not assume they have the size or layout of S1M DEM pieces. The newest published
work unit among the intersecting candidates is selected, without mixing surveys.
Coverage holes remain visible; publication date is not presented as acquisition date.
The preview performs a small catalog lookup; conversion only starts with Download.

Selections may be up to about 3.87 km across (4,000 grid cells including padding),
which accommodates the bounding square of a one-mile radius. Polygon/route selection
prepares its bounding rectangle with about 200 ft of padding. Output tiles have
1,000 m cores and overlapping edges for uninterrupted 200 ft disk queries. Multiple
prepared regions are retained, including compatible takeoff ground in another tile.
The final report identifies missing surface cells. Check coverage before relying on
an offline area; a finished download does not imply complete obstacle coverage.

Preparation runs on one background worker. Sources are streamed, processed one
file at a time, and removed after validated output is installed. A shared-cache
reservation must fit without evicting existing map or terrain data. The estimate
allows 1.5 times advertised input bytes plus 16 MB per output tile; an additional
64 MB of physical free space is required. Individual sources are limited to 1 GB,
150 million points, and supported LAS formats; at most 64 files from one survey
are accepted. Oversized or unsupported selections fail with an explanation.

Prepared output is pinned within the shared map-cache budget. Repeated preparation
retains previous regions; individual region removal is not yet exposed in the UI.
Prepared data becomes available to live AOL automatically. Share prepared regions
through the existing **MA package export/import** workflow on Android and Apple.
MA export includes complete prepared AOL sets intersecting the selected area,
regardless of the DEM checkbox; MA import restores those sets for local use.
The export/import report shows the AOL tile count. Prepare the source area before
exporting; an MA package does not create missing surface coverage.
Standalone `.aol` files remain supported by the legacy file importer, but there is
no separate surface-package menu entry.

Native preparation supports explicit NAVD88 metre heights with CONUS Albers
(EPSG:6350), NAD83/NAD83(2011) northern UTM zones and WGS84 northern UTM zones.
All input files must have the identical compound reference. Other projections,
implicit vertical references and cross-survey transformations are not supported.
Both surface maxima and nearby class-2 ground retain their source absolute heights;
no surface-minus-DEM obstacle-height conversion is required.

Aircraft AOL requires recent position and takeoff-relative height plus an
observed **Ground** status with near-zero takeoff height to establish the ground
launch reference. First detection in mid-flight is insufficient. The paired
ground raster supplies the vertical reference; the existing AGL DEM, GNSS datum
heuristics and manual ATO offsets are not silently reused. Ground launches are
assumed; elevated launch surfaces are unsupported. AOL is not collision detection
or a waiver-compliance measurement. Surface and telemetry uncertainty remain.

In **Map Folders**, select **AOL** beside a single polygon/route for its high-point
briefing. The briefing includes a 200-foot polygon buffer or route corridor radius,
geometry bounds, coverage, source/version/date, and a **Show high point** action.
Partial results are explicitly marked incomplete. Results are reused in memory
by geometry, complete metadata and width; this initial version does not attach
briefings to flight archives or claim a historical flight evidence snapshot.

## Optional desktop preparation

Use an isolated Python environment with `laspy[lazrs]`, `numpy`, `pyproj`, `scipy`.
Acquire original classified LAS/LAZ in advance. A download can be resumed with
`curl -C -`; keep the original source URL and survey metadata. The preparer streams
250,000-point chunks and excludes withheld, low-noise (7), high-noise (18), and
non-finite returns. It retains cell maxima, including unclassified above-ground
returns; an unclassified peak is not labelled a tree or building.

```sh
python tools/aol/prepare_surface.py survey.laz area.aol \
  --center 39.2422 -121.0384 --extent 250 --spacing 1 \
  --dataset USGS_NoCAL_B1_w2117n2082 --version 2018-2019-r2c1 \
  --survey-date 2018-07-07/2019-09-05 --geoid GEOID12B \
  --source-url https://www.sciencebase.gov/catalog/item/5e21b5dde4b014c853023843
```

`--extent` is the requested square width before 60.96 m padding on every side.
Initial preparation is limited to 1800 m requested extents. Adjacent tiles from
the **same survey** can be mosaicked with repeated `--additional-input path.laz`;
CRS mismatches are rejected. Do not mix surveys/geoid realizations merely because
both say NAVD88. Cross-survey alignment needs independent source verification
and explicit transformation before this preparer. The desktop preparer labels GEOID12B or GEOID18 explicitly. Native output can also
preserve a same-survey NAVD88 reference with full source CRS and file hashes.

One metre is the default. Two- and four-metre outputs use maxima and retain NoData
if ANY one-metre child is missing. Coarse ground uses only complete child support.
There is no surface interpolation across empty cells and no AGL substitution.
Every intersecting raster cell must be valid for an aircraft disk to be complete.
The grid is a local metric approximation with pixel-footprint localization
uncertainty; a raster cell value is not a surveyed obstacle coordinate.

Optional `--wire-observations file.json` accepts a reusable list such as:

```json
[{"latitude":39.24,"longitude":-121.04,"heightMeters":null,
  "source":"Team field observation","date":"2026-09-11",
  "confidence":"Observed crossing location; span geometry not surveyed"}]
```

These observations are shown as **Wire crossing — height unknown** with provenance.
They describe the prepared area, do not change numeric AOL, and never cause spans
to be inferred. Complete utility/FAA wire acquisition and 3D geometry remain a
separate integration stage, as scoped in the handoff.

## Validation and provenance

- `test-fixtures/aol/complete.aol` and `hole.aol` are synthetic, shared by Kotlin/Swift.
- `test-fixtures/aol/nevada-city-1m.aol` is the real USGS-derived offline regression
  package; it is not a claim of operational obstacle completeness.
- `python -m unittest discover -s tools/aol -p 'test_*.py'` checks adjacent input
  seams, withheld/noise filtering, peak preservation and strict NoData coarsening.
- Native `SurfaceClearanceTest` / `SurfaceClearanceTests` cover signed values,
  disk clipping, missing coverage, a route briefing and the real package.
- See `docs/AOL_Implementation_Validation.md` for results and qualification gaps.
