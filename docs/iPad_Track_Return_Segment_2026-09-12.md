# iPad track return-segment correction

The user's 16:17 screenshot of build 216 showed an artificial blue segment from
the current aircraft position back toward launch during a straight flight leg.
Fresh logs and the 16:15:22 flight archive were copied from the iPad into
`outputs/ipad-track-20260912/`.

`AircraftTrackRenderInput` concatenated the accepted track history and the entire
SEI history. After video telemetry became a primary track source, these histories
overlapped. Concatenation connected the latest accepted point to the oldest SEI
point, then traced the flight again. The archived 172-point flight has no such
large discontinuity: its largest adjacent step is about 7.54 m. Concatenating
copies of the first 130 points creates an artificial return of about 60 m,
matching the screenshot's geometry.

The renderer now merges by receipt time, deduplicates shared sample times, prefers
accepted track data at a shared timestamp, and excludes supplementary history
older than the current primary history. Android draws its accumulated history
without this whole-history concatenation; no Android change was required here.

A minimal coordinate/time fixture from the first 130 archived points is retained
at `test-fixtures/video-track/ipad-20260912-1617.json`. Regression tests verify that
the old concatenation produces a >50 m segment while the merged path exactly
matches the capture, with all steps <10 m. Other tests cover interleaved samples,
primary-data precedence, unordered input and earlier-flight exclusion.

## AOL startup

The first stream sample arrived at 16:15:22.699. AOL initially reported an unknown
launch reference. Manual 50-foot calibration occurred at 16:16:19.867, and AOL
became available at 16:16:20.090, 223 ms later. Another calibration was logged at
16:16:21.168. The initial delay was a missing launch reference, not a minute-long
surface calculation. No launch-reference or AOL math behavior was changed.

Build/install logs and final regression results are stored in the output folder.
A fresh physical flight is still needed to confirm the corrected map rendering.

Validation completed: 337 Swift Testing tests and 10 XCTest tests passed, as did
the signed iPad build and whitespace checks. Build 217 installed in place and
launched successfully at 16:44; the installed-app query confirmed 2.2.7 (217).
App data was not cleared. Physical track-rendering verification remains pending.
