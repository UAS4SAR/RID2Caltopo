# AOL flashing during DJI reference refinement

## Evidence

The A5 Pro recording `Screen_Recording_20260912_192542_RID2Caltopo.mp4` shows AOL pending/numeric transitions during ascent, with ATO and AGL available. The operator calibrates at 50 ft during the recording. Earlier iPad build-224 logs show the same available/pending pattern on each automatically updated DJI reference coordinate.

## Cause and change

Android included the exact reference coordinate in the display-retention identity. Apple explicitly required the old and new reference coordinates to be equal. Each automatic update therefore bypassed the existing bounded refresh allowance.

Build 225 keeps the existing 1.5-second display allowance across automatic DJI reference refinements in the same flight. Calculation request identity still includes exact coordinates and height; superseded results are rejected. Repeated input changes cannot extend the allowance. Unknown, failed, or stale measurements remain immediate. Manual calibration clears the retained result; Android now explicitly clears it even on a repeated calibration. Flight identity and surface generation remain part of Android retention identity.

No coordinate quantization, frozen launch coordinate, or artificial altitude is introduced. This is display continuity while the current result is calculated, not a change to AOL's definition.

## Verification

Apple regression suite: 342 Swift Testing tests and 11 XCTest tests pass. Android unit tests pass, including streamed-reference updates, timeout, new flight, and calibration. Release notes match and metadata verification passes.

Build/install evidence: `outputs/aol-flash-224/`. The separate iPad missing-icon report is not fixed by this change. Physical retesting remains required.

The completed iPad log identifies build 224 through 19:34. Before manual calibration, AOL flips pending/available roughly each second. Calibration actions were recorded at 19:32:43.897, 44.846, 45.604, and 46.205; after the last result at 19:32:47.062 it remained numeric until telemetry became stale at 19:33:23.892. This field result predates build 225's launch at 19:35:10.

Separate follow-up: the completed flight remained saved locally, but Tracker archive requests returned HTTP 409 at 19:33:50-51. This is not resolved by the AOL display change.

Android release verification passed (1,065 unit tests); signed build 225 installed and launched on the A5 Pro, original installation date preserved. Apple build 226 passed its device build and installed in place; it adds the compact marker correction described in `iPad_Narrow_Map_Marker_2026-09-12.md`. Physical testing of these updates is pending.
