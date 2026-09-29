# Clue description parity — 2026-09-29

Implemented in /Users/kjt/Projects/RID2Caltopo. No separate worktree.

Apple now builds published clue descriptions using Android's field ordering, labels, units, azimuth precision/wrapping, and DEM wording. The report includes designator, captured drone coordinates and altitude, heading, AGL/ATO, available ground speed/track, and available DJI camera/position/reference telemetry. Original drone observation and snapshot camera metadata are retained separately from the projected clue position. Local-only marker behavior retains the capture summary without the publication telemetry block, matching Android.

Validation:
- Shared capture-summary golden fixture passed on Android and Apple.
- Android ClueCaptureSummaryTest: 11 tests, zero failures.
- Apple package suite: 437 Swift Testing tests plus 34 XCTest tests passed. Includes complete available RID/DJI report, coordinate formats, absent telemetry, and heading wrap.
- Signed generic iOS Debug build succeeded; deep/strict signature verification passed.
- git diff --check passed.

The Apple observation model does not currently expose Android's optional vertical rate or generic stream RID candidate fields. They are not fabricated. Other values naturally depend on the captured telemetry.

No device installation or physical published-clue validation performed in this change. Existing CalTopo clue descriptions are not rewritten.
