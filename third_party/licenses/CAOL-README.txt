Local LAS/LAZ preparation shared by Android JNI and Swift Package Manager.
Vendored laz-perf 3.4.0, upstream tag commit b7bbe26 (archive root), Apache-2.0.
Source: https://github.com/hobuinc/laz-perf/releases/tag/3.4.0
The vendor's lazperf_base.hpp reports 3.2.0; retained verbatim from the release.
No lidar work is called from flight telemetry. Preparation advances in bounded
point batches so the owning offline job can cancel between batches.

GeographicLib 2.7 supplies accurate Transverse Mercator conversion (MIT).
Source: https://github.com/geographiclib/geographiclib/releases/tag/r2.7
Archive: https://api.github.com/repos/geographiclib/geographiclib/tarball/r2.7
SHA-256: 8be5dda0e2b4a4f40efbaf118a990ea039bb951fea67eed416f3a3ec49c164fb
Only Math, TransverseMercator, TransverseMercatorExact and EllipticFunction source
and required headers are retained, unmodified. Config.h is locally supplied for
static, little-endian, double-precision mobile builds without external datasets.
License texts are retained here. No source CRS or vertical transformation datasets
are downloaded at runtime.
