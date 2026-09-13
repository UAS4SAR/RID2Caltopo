# AOL surface clearance: implementation handoff

## Objective and scope

Implement the discussed AOL functionality in RID2Caltopo on both Android and Apple: a third altitude field alongside ATO and AGL, backed by an offline-capable lidar-derived surface model that includes canopy and buildings, plus assignment/route high-point briefing information.

This is the missing work from the earlier discussion. The operating-profile handoff explicitly deferred AOL and surface-data processing, so operating profiles were subsequently implemented without AOL. Do not repeat that omission. Adding only headers or permanent placeholders is not completion of this task: deliver the data path, calculation, display, and briefing support, or clearly identify any genuinely blocked portion.

This document records the discussion and recommended implementation details; it does not claim AOL has been implemented. The user requested this handoff on September 11, 2026. The user should initiate the implementation task with this document.

## Workspace and current baseline

- App repository: `/Users/kjt/Projects/RID2Caltopo`.
- Tracker repository, if needed for organization dataset distribution or records: `/Users/kjt/Projects/r2c-tracker`.
- App HEAD when this handoff was written: `07c02548502594851cc2a25ad25210142dba6c68`. Inspect the current source/status before starting; another task has implemented operating profiles since the earlier snapshots.
- Existing source snapshots: `outputs/source-snapshots/20260911-pre-operating-profiles`. These predate operating-profile implementation and must not be restored over current work.
- Preserve existing aircraft readiness, operating profiles, mission-continuity behavior, short-flight recording choices, map/cache improvements, and unrelated working-tree changes.
- Maintain Android and Apple as peer platforms. Do not implement one platform and leave the other silently incomplete.
- No deployment, store publication, device installation, or team notification is requested by this handoff. Treat source implementation, automated validation, deployment, and field qualification as separate outcomes.

## Operator intent

The team operates SAR missions, often at night, mostly in remote forest. Pilots sometimes intentionally fly just above or through trees. Repeated collision/terrain alarms would become a nuisance. Offline operation and reusable preparation are important; no field launch-time processing or paperwork should be introduced.

The initial feature is a quiet situational-awareness measurement and a briefing aid. Predicted collision-course alerts were discussed first, but the user subsequently preferred a signed numeric field and expressed concern about telemetry tolerances and delays. Leave audible/predictive collision warnings out of this initial scope.

## Measurement contract

Use **AOL — Above Obstacle Level**. Do not use ASL, which can be confused with sea level. ATL was suggested by the user; AOL was proposed to avoid confusing this feature with the ground reference already used by AGL. Include an accessible explanation of the abbreviation and neighborhood radius.

Keep these distinct:

| Field | Meaning |
|---|---|
| ATO | Existing height above takeoff reference. |
| AGL | Existing height above bare ground at the aircraft position. |
| AOL | Aircraft elevation minus the highest mapped top-surface elevation within a 200-foot horizontal radius of the aircraft. |

The proposed 200-foot neighborhood is a horizontal disk of radius **60.96 metres**, centered on the aircraft's current horizontal position. It is not a 200-foot sphere, a 200-foot vertical offset, a distance from the tablet, or a search centered on the globally highest feature. Never subtract an additional 200 feet from the result.

Let `Z_aircraft` be the calibrated aircraft elevation and `Z_surface_max` the maximum valid surface elevation over that disk, expressed in the same vertical reference:

`AOL = Z_aircraft − Z_surface_max`

Example: aircraft elevation 1,000 ft and highest nearby mapped canopy elevation 1,025 ft produces AOL **−25 ft**. This may describe flight beside a taller tree, below canopy, or beside rising ground. It does not establish a collision. Ground belongs in the top-surface model too; a hillside may be the controlling feature.

Do not replace the DEM used by AGL with the surface model. Do not subtract a DSM height from a raw aircraft GNSS altitude without resolving datum/calibration compatibility. Trace the existing altitude correction and telemetry-source logic on both platforms. If a trustworthy common vertical reference cannot be established, AOL is unavailable rather than a plausible-looking number.

Treat the 200-foot radius as the agreed initial product setting, separate from the active operating profile's legal conditions. It must not silently disappear under Standard Part 107 or be advertised as proof of compliance with any waiver.

## Required UI

1. Add a visible AOL header/field alongside ATO and AGL in the principal aircraft/stream altitude displays on both platforms. Audit compact phone/tablet layouts, map aircraft detail cards, and any external-display altitude presentations that currently show ATO/AGL. Avoid a feature that exists only in a settings/detail page.
2. Display feet consistently with the existing altitude UI. Negative AOL has a minus sign and red digits. Include the sign/text for accessibility; color alone is insufficient. Positive values should be neutral, not a green declaration that flight is safe.
3. Explain the scope using a compact label or detail view such as `AOL · 200 ft radius` and `Height above the highest mapped surface nearby`.
4. Show `—` with an understandable reason when surface coverage or aircraft altitude is unavailable. Never substitute AGL or zero. Do not show the maximum from a partly uncovered neighborhood as though the entire disk was checked; initial conservative behavior should mark that calculation unavailable/incomplete.
5. Distinguish stale aircraft position/altitude, stale computation after movement, old survey date, and missing coverage. An old survey is not the same as a stale telemetry sample. Do not present a cached value from a previous position or aircraft as current.
6. Expose the controlling feature's map location, horizontal distance, surface elevation, source/survey date, resolution, and coverage status in details. Do not label an unclassified raster peak a tree, building, or tower unless the source establishes its type.
7. No audible alarms, recurring popups, mission interruption, forced acknowledgment, or launch/recording gate. A negative or unavailable AOL must not disable maps, video, flight confirmation, or recording.
8. A positive AOL does not establish absence of wires or other unmapped obstacles. Make that limitation available in the field explanation and briefing; where a known wire has unknown height, show that explicitly.

## Surface-data preparation and storage

Current ground sampling prefers USGS S1M where available and retains terrain fallbacks. S1M is bare earth; its one-metre grid is not a canopy/structure dataset or a guarantee of one-metre accuracy. Inspect the current offline/cache implementation before extending it.

Recommended architecture:

- Keep the ground DEM and top-surface DSM as separate, explicitly typed layers with separate cache keys/catalog identities. Share download, raster-reading, indexing, and storage-budget machinery where appropriate. Never let the existing ground sampler discover a DSM file and use it as ground.
- Obtain a suitable existing DSM when its provenance and quality are established, or derive a top-surface raster from public lidar point clouds. Raw lidar processing belongs in an advance-preparation tool/service, not on the tablet at mission launch.
- Start with a representative forested operating area in Nevada County, California, subject to actual source coverage. Inspect survey metadata and real sample points/rasters. Establish that the available data actually contains canopy and buildings; downloading another bare-earth DEM does not meet the requirement.
- Filter noise/withheld/invalid returns using documented rules. Preserve validated high features. Averaging or ordinary downsampling can erase narrow peaks; if coarser tiles or pyramids are used for maximum queries, make their extrema behavior explicit and verify that it does not lower relevant maxima. Conversely, isolated noise spikes must not become false controlling obstructions.
- Preserve explicit NoData masks and coverage footprints. Do not fill absent surface data with ground elevations and call it complete surface coverage.
- Store horizontal CRS, vertical datum/geoid/reference, units, survey acquisition date(s), dataset/product ID/version, processing version, pixel spacing, source quality information, footprint and checksums. Download time alone is not a survey date.
- Align ground and surface datums before deriving heights above local ground or comparing to calibrated aircraft altitude. Test overlap/seams between different surveys and datasets. Do not silently mix different vertical references.
- Deliver compressed, tiled offline packages for selected areas or route corridors, including the 200-foot neighborhood outside the requested boundary. Use resumable/atomic acquisition where supported, verify content before activation, and retain a usable previous version if an update fails.
- Integrate surface storage into the existing shared cache budget and eviction protections, avoiding two independent caches that each assume the full storage allowance. Do not evict data that a current query is reading. Confirm how users pin/preload assignment data and see incomplete preparation.
- Compute disk maxima using an appropriate spatial/block index or peak-preserving summaries. Do not rescan a large raw point cloud or all 1-metre cells on the main/UI thread for every RID update. Clip the query to the disk rather than using an unlabelled square approximation. Protect against out-of-order asynchronous results.

Measure actual compressed package size, download time, peak memory, disk usage and query latency on representative data before making storage claims or selecting a coarser default. A pair of layers is manageable in principle, but not evidence that every region will fit a particular tablet.

## Assignment and route briefing

For a selected search assignment polygon or route corridor:

- Identify the highest mapped top-surface elevation and its location.
- Distinguish highest absolute elevation from tallest object above local ground. A short tree on a ridge may be more relevant to the route's altitude planning than a taller tree in a valley.
- Show ground elevation at controlling points, height above local ground where both layers are compatible, source dates, coverage gaps, and relevant route/assignment extent.
- Include the selected corridor/neighborhood width in the briefing so the result is reproducible. Reuse the prepared analysis across flights in the assignment, invalidating it when geometry or dataset versions change.
- Make high points viewable on the map. Do not convert them automatically into an aircraft RTH command or a universal permitted altitude. RTH and waiver guidance belong to the selected operating profile and its actual conditions.
- Retain references to dataset versions and assignment/route geometry if briefing evidence is stored with a flight. New downloads must not retrospectively rewrite what the crew used.

## Wires and cables

The county has many rural powerlines and cables. Neither a general lidar surface nor the FAA obstacle database guarantees complete wire coverage. A one-metre raster may contain a return from a thin wire, but finer output pixels cannot recover a wire the survey missed. A single-elevation raster cannot represent the open space below a span or the full geometry of sagging cables.

The FAA has catenary entries, but its published FAQ describes a single coordinate without line direction. Do not automatically draw precise spans by joining nearest towers or treat inferred lines as surveyed geometry.

The discussed architecture includes a separate wire/cable layer, using available utility/county line geometry, FAA catenary/tower records, and reusable team observations. Source/observation date, geometry confidence and height uncertainty must be retained. Where only horizontal location is known, show `Wire crossing — height unknown`; do not fabricate numeric clearance or treat it as covered by a positive AOL.

For this AOL task, wire limitations and honest unknown-height presentation are required. Full wire-network acquisition, utility agreements, automatic wire extraction and 3D sag reconstruction should be reported as a separate integration stage unless the implementation task explicitly includes them. Do not claim the surface model solves the wire problem.

## Current implementation locations to inspect

Android (relative to the app repository):
- `app/src/main/java/org/ncssar/rid2caltopo/video/DroneAltitudeCoordinator.kt`
- `app/src/main/java/org/ncssar/rid2caltopo/video/DroneAltitudeModels.kt`
- `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`
- `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsScreen.kt`
- `app/src/main/java/org/ncssar/rid2caltopo/ui/MainScreen.kt`
- `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
- `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneOfflinePrepHelpers.kt`
- `app/src/main/java/org/ncssar/rid2caltopo/video/MapOfflinePrepCoordinator.kt`
- `app/src/main/java/org/ncssar/rid2caltopo/video/S1mPieceDownload.kt`
- `app/src/main/java/org/ncssar/rid2caltopo/video/mapcache/DemElevationService.kt`
- `app/src/main/java/org/ncssar/rid2caltopo/video/mapcache/GeoTiffDemSource.kt`
- `app/src/main/java/org/ncssar/rid2caltopo/video/mapcache/S1mCog.kt`
- `app/src/main/java/org/ncssar/rid2caltopo/video/mapcache/UnifiedMapCache.kt`

Apple:
- `apple/Sources/R2CCore/OperationalAltitudeCoordinator.swift`
- `apple/Sources/R2CCore/OperationalMainScreenPresentation.swift`
- `apple/Sources/R2CCore/GeoTiffElevationSource.swift`
- `apple/Sources/R2CCore/S1MCog.swift`
- `apple/Sources/R2CCore/OperationalOfflineMap.swift`
- `apple/App/AppleTerrainElevationService.swift`
- `apple/App/AppleMapOfflineManager.swift`
- `apple/App/RIDTrackViewModel.swift`
- `apple/App/RIDTrackMapView.swift`
- `apple/App/RIDAircraftDetailView.swift`
- `apple/App/ContentView.swift`

Search for all ATO/AGL presentation sites; these pointers are not an exhaustive UI inventory. Inspect current operating-profile and flight-readiness contracts before extending records. Only involve Tracker where a concrete distribution, organization-management, or evidence-storage need warrants it. Older apps and old Tracker archives must remain compatible; new surface/AOL fields must be optional.

## Implementation sequence and acceptance

1. Inspect current status and establish a recoverable current-source baseline if the new task requests changes. Do not rely on the pre-operating-profile snapshot as the current baseline.
2. Select and inspect real lidar/DSM coverage, establish the vertical-reference contract, and prepare a small reproducible surface package with metadata and checksums. Record any data or licensing blocker precisely.
3. Add typed surface storage and neighborhood-max sampling on both platforms, with shared cross-platform fixtures and offline coverage tests.
4. Wire AOL through altitude state to the actual aircraft/stream headers and details on both platforms. Validate sign, units, timing, stale/missing states, and unchanged ATO/AGL behavior.
5. Implement assignment/route high-point analysis and reusable briefing presentation.
6. Validate real source samples, then perform proportionate automated and UI/build checks. Clearly separate source correctness from device and field qualification. Do not state that the feature is complete with only synthetic fixtures or permanently unavailable headers.

Required test cases include:
- Positive, zero and negative AOL; the agreed example; exact 60.96-metre radius; a higher point just outside the disk; no extra vertical buffer.
- Rising terrain, canopy, roof peaks, multiple local maxima, tile boundaries, seams, NoData holes and incomplete neighborhood coverage.
- Units, datums, altitude calibration sources, uncertainty, telemetry age, aircraft movement and stale asynchronous results; no cross-aircraft state leakage.
- Peak preservation through resampling/overviews and removal of documented invalid returns.
- Offline startup, corrupt/incomplete downloads, interrupted updates, low storage and eviction while queries are active.
- Briefing maximum/location and height above ground; geometry/version changes invalidate saved analysis.
- AOL headers visible in supported compact/tablet layouts, negative sign/red styling accessible, missing data shown as unavailable, no audible alerts or mission gate.
- No regression in ATO/AGL, clue projection, operating profiles, historical records, or short-flight choice behavior.

Use current repository build/test guidance. Android and Apple builds/tests must be reported separately. Historical tests from the earlier task are not proof that the new data pipeline, UI or physical controllers work.

## Primary references from the discussion

Recheck current source documentation and dataset metadata during implementation:

- [USGS lidar and what is removed to make a bare-earth DEM](https://www.usgs.gov/faqs/what-lidar-data-and-where-can-i-download-it)
- [USGS lidar collection density and quality requirements](https://www.usgs.gov/ngp-standards-and-specifications/lidar-base-specification-collection-requirements)
- [USGS processing/handling requirements, including invalid returns and reference systems](https://www.usgs.gov/ngp-standards-and-specifications/lidar-base-specification-data-processing-and-handling-requirements)
- [USGS S1M product](https://www.usgs.gov/3d-elevation-program/new-product-3d-elevation-program-seamless-1-meter-digital-elevation-model-s1m)
- [FAA obstacle FAQ, including catenary coordinate limitations](https://www.faa.gov/air_traffic/flight_info/aeronav/obst_data/odtfaqs/)
- [FAA Daily Digital Obstacle File](https://www.faa.gov/air_traffic/flight_info/aeronav/digital_products/dailydof/)
- [FAA wire-hazard guidance](https://www.faa.gov/air_traffic/publications/atpubs/aip_html/chap7_section_6.html)
- Local waiver: `/Users/kjt/Projects/RID2Caltopo/BVLOS_Waiver/107W-2025-03957 Dustin Moe - CoW.pdf`. Its conditions are profile-specific; AOL is not itself a regulatory clearance or compliance determination.
