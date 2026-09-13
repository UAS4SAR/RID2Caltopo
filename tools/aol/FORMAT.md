# AOL offline surface package v1

`.aol` is a ZIP (deflate or stored), containing exactly `manifest.json`,
`surface.f32`, and `ground.f32`. The two rasters are little-endian float32,
row-major, south to north then west to east. NaN is explicit NoData. Infinity
is invalid. The manifest gives SHA-256 of both expanded rasters. Ground is a
separate calibration/briefing layer; these files NEVER enter the AGL DEM catalog.

Coordinates use a local WGS84 equirectangular grid: x = R cos(originLatitude)
(longitude-originLongitude), y = R (latitude-originLatitude), radians,
R=6371008.8 m. Packages are limited to 10 km and latitudes below 70 degrees.
`west`/`south` are outer grid edges in metres, `spacing` is metres. Pixel
values represent cell maxima, not averages. Disk queries intersect raster cell
footprints with the exact 60.96 m disk in this local metric; they do not use a
square. Thus peak localization has the explicitly reported pixel uncertainty.
Do not interpret this approximate horizontal projection as surveyed geometry.

Manifest fields: schema=1, layer="top-surface", id, version, processingVersion,
sourceURL, surveyDate (acquisition dates when known; otherwise explicitly unknown, with catalog publication date distinguished), verticalReference,
horizontalCRS="R2C_LOCAL_EQUIRECTANGULAR_WGS84", units="metres", quality,
originLatitude, originLongitude, west, south, spacing, width, height,
surfaceSHA256, groundSHA256. Quality describes classification and interpolation.
A dataset has one explicit vertical reference for BOTH layers. A package's
paired ground is used to anchor takeoff-relative aircraft height. Raw GNSS
altitude and heuristic AGL datum/units inference are not used for AOL.

Empty surface cells remain unavailable, even if ground is present. Surface
preparation excludes withheld and LAS classes 7 and 18; accepted maxima are
never averaged. Ground interpolation is bounded and separate. This format does
not certify survey completeness, detect unclassified noise, or map all wires.
Each package is an immutable analysis extent; include at least 60.96 m padding
around intended operations. Packages are imported and validated before activation.

Mobile readers accept NAVD88/GEOID12B and NAVD88/GEOID18 metre references, or a
same-survey paired NAVD88 reference with nonempty referenceGroup and an explicit
EPSG:5703 sourceCRS. Native preparation records sourceSHA256 and the complete source
compound WKT. No coordinate reference is inferred from a filename.

Native prepared sets contain an index.json and multiple independently validated
.aol files, atomically published under surface_v1/sets/<unique-id>. Optional
coreWest/coreSouth/coreWidth/coreHeight designate the non-overlapping query core
inside an edge-padded tile. All tiles in a set share origin and referenceGroup;
takeoff ground from another tile is accepted only with matching referenceGroup
and sourceCRS. Assignment aggregation checks each core once. Legacy imports
without these optional fields remain supported. The .aol ZIP contract is unchanged.
Optional `wireObservations` contains at most 1000 unknown-height point observations:
latitude, longitude, source, date, confidence, and null heightMeters. These are a
separate, nonnumeric observation layer. Records are displayed for the prepared
area, not claimed to intersect the aircraft disk. No span is inferred.
