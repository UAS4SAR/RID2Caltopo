# Altitude and telemetry status labels

Android and Apple map labels, video headers, and altitude detail views use:

| Label | Meaning |
| --- | --- |
| Number with units | Available measurement |
| `Unk` | Required data or reference unavailable; details identify the missing input |
| `POS?` | Telemetry is no longer fresh (five seconds) |
| `--` | A calculation is pending |

Status words have no feet or degree suffix. Stale telemetry takes precedence over
pending work and missing-input status. Terrain data that is still loading is
pending; unavailable terrain is unknown. Neither is a position timeout. When
position telemetry expires, ATO, AGL, AOL, range and heading in the shared status
line show POS?. Android additionally marks ATO-based altitude fields POS? if the
height report has expired despite newer position updates. Numeric AOL below zero
remains red; an unavailable value is not rendered as negative clearance.

A displayed AGL value is not proof of an observed ground-launch coordinate.
Existing AGL can use an inferred absolute altitude offset, direct ground-relative
height (Apple), or a takeoff-height fallback. AOL requires the observed launch
location to sample compatible ground in its prepared survey and combine it with
fresh takeoff-relative height. Missing that reference is Unk, with a specific
reason in the AOL details. Do not silently substitute an airborne first position
as the launch point.

Android's AOL freshness now uses the accepted waypoint's receipt time rather
than time spent delivering the local-track callback. Its existing one-second
refresh updates the status even without new packets. Apple similarly uses the
accepted observation's receipt time and its periodic display refresh.

Regression tests cover fresh numerical fields, missing values, pending terrain,
pending AOL, stale telemetry overriding previously available numbers, and status
words without units. Calculation values remain available internally for existing
consumers; rendering must honor the status rather than interpreting nullable
numbers alone.

Validation on 2026-09-12: 1,044 Android unit tests, 326 Swift Testing tests and
10 XCTest tests passed; signed iPad device build passed. Source changes are not
yet installed on either device. Physical UI/flight verification remains pending.
