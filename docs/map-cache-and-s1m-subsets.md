# Shared map cache and S1M subsets

The Cache Size setting governs app-managed map imagery, terrain GeoTIFFs, persistent decoded terrain blocks, map icons, and cached elevation samples. Concurrent writes reserve capacity before publishing. File-system/database metadata and RAM caches are not part of the content-byte counter. This setting does not control flight recordings or clue photographs.

When no cache limit has been saved, initialize and persist the smaller of 10 decimal GB and 80% of currently available storage. Preserve existing saved limits. Cache Size continues to show the combined total, its categories, and available storage; the operator can increase the allowance using existing cache bytes plus 80% of currently free storage. Normal cache fullness does not display an alert. Cache writes evict older entries to make room. In-flight/recent terrain is protected briefly; a write that still cannot fit fails rather than exceeding the budget.

Android merges oldest-entry batches from its imagery/icon/elevation stores with terrain and decoded files. Apple uses the file inventory across its cache roots. Download reservations include the complete incoming piece, so simultaneous transfers cannot each spend the same remaining capacity. Publication and reservation release are synchronized. Terrain eviction invalidates catalog discovery.

## Smaller S1M transfers

S1M remains published as 10 km by 10 km source products. The app reads the GeoTIFF header and requests only the compressed full-resolution blocks intersecting the desired area. It writes standalone, lossless GeoTIFF subsets with adjusted raster coordinates and block offsets. Each piece has a 1,024 m core and a shared 512 m border on its east/south sides, normally producing a 1,536 m square file. Border overlap preserves bilinear interpolation without requiring both neighboring files. Edge pieces are smaller.

Both automatic operating-area preparation and manual S1M preparation use this path. Android stores a small header index so offline readiness can verify that all required pieces remain present. Unsupported layouts, truncated data, changed ETags, or servers ignoring byte ranges fail the subset request; they do not trigger a silent full-S1M download. The existing coarser terrain fallback remains available. Coarse 10 m/30 m products still use their existing whole-file path. Existing full-size cached products can age out through normal eviction.

The supported subset layout is the classic TIFF, 512-pixel tiled, one-metre EPSG:6350 S1M product already supported by the terrain samplers. Other S1M projections/layouts require additional reader support.

Offline planning estimates remain conservative source-product estimates; progress uses actual subset transfer totals. Thus advance offline-package capacity estimates can exceed the eventual subset bytes. This does not affect automatic operating-area preparation.

## Public verification (September 10, 2026)

No operator/device coordinates were used. The public USGS catalog was queried with dataset `Seamless 1-m DEM (S1M)`, `prodFormats=GeoTIFF`, `max=1`, and no bounding box.

Public sample: [S1M n0450e1510 20260821](https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/S1M/n04e15/n0450e1510/S1M_n0450e1510_20260821.tif).

- Actual HTTP object size: 447,032,917 bytes.
- Header request: HTTP 206, `Accept-Ranges: bytes`, `Content-Range: bytes 0-16383/447032917`.
- Raster: 10,000 by 10,000, 32-bit float, LZW with floating-point predictor, 512 by 512 internal blocks.
- A 5 by 5 block region (2,560 m square): 22,041,209 compressed bytes.
- A 3 by 3 block region (1,536 m square): 7,930,305 compressed bytes, plus a small local header.
- A real block request for bytes 255454748–256343553 returned the expected 888,806-byte payload.

`test-fixtures/s1m-public-header.bin` is the first 16,384 bytes of that public sample. Cross-platform tests check its exact subset byte ranges, adjusted coordinates, boundary pieces, and malformed headers. The Apple terrain-reader test replaces the compressed data with a known coordinate-dependent raster and verifies that the resulting standalone subset returns the original geographic elevation at one-metre resolution.

Reference: [USGS 3DEP products](https://www.usgs.gov/3d-elevation-program/about-3dep-products-services), [OGC Cloud Optimized GeoTIFF](https://www.ogc.org/standards/ogc-cloud-optimized-geotiff/).

Source/tests/build verification is separate from a physical S23U field test. Device fetch timing, sustained cache pressure, and clue projection during an actual operation still require device verification.
