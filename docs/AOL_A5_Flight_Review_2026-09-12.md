# A5 Pro AOL unavailable during 09:35 flight

Device readback: Samsung SM-X350, RID2Caltopo 2.2.7 build 207.
Flight: 12 September 2026, approximately 09:35–09:38 PDT, DJI Mini 4 Pro.
Fresh evidence copied from the device:
`/private/tmp/aol-flight-090712.txt` and `/private/tmp/aol-flight-093512.json`.

The log records zero height with `isAto=false` at 09:35:13.022. At
09:35:40.831 it records height 0.5 m with `isAto=true` and the transition to
airborne. Both platform coordinators previously required takeoff-typed height
while grounded to capture the AOL launch location. A drone that switches from
ground-relative zero to takeoff-relative height when becoming airborne can
therefore fly without ever establishing the required launch reference.

Fix: accept fresh near-zero height with either known RID height reference when
explicit ground status and a fresh position are present. Live calculation still
requires fresh takeoff-relative height. Do not infer a launch point from an
already-airborne first observation. Android and Apple now log changes in the
AOL result reason; Android also logs acquisition of the launch reference.

This is a likely cause, not conclusive proof of the entire flight's AOL state:
build 207 did not record its AOL unavailable reasons. Its private surface store
could not be inspected through `run-as` because this installed release is not
debuggable. The 09:17:42 failure was DEM catalog HTTP 504; the 09:32:51 map
completion in 427 ms does not establish successful AOL preparation. Do not
conclude that prepared AOL files were absent or damaged from these records.

The dash means no current AOL value. Possible causes include unavailable or
incomplete prepared surface coverage, missing launch ground reference, missing
ATO height, stale telemetry, and a pending calculation. It does not mean zero
clearance and does not by itself identify a missing tile.

Regression tests cover the observed DJI height-reference transition, a successful
calculation against the prepared fixture afterward, rejection of airborne-first
anchoring, stale observations, nonzero ground height and invalid height values.

Validation completed: 1,043 Android unit tests, 324 Swift Testing tests and 10
XCTest tests passed. The signed iPad device build passed. Changes have not been
installed on either device; the observed A5 Pro remains on build 207. These
checks do not establish successful AOL coverage during a physical flight.
