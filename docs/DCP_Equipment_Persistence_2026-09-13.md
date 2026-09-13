# DCP equipment persistence and wording

The September 13 iPad screenshot showed Spotlight and Speaker selected, and a red message claiming pilot details had changed. Source inspection found that both confirmation screens initialized accessory selections to empty even though confirmation saved those choices. Apple also treated any nonempty pilot JSON as a prior verified selection, including the callsign-only object produced by `resolvingPilot`.

Both platforms now restore the last published equipment and payload from preferences scoped to the organization endpoint and aircraft Remote ID. Existing accessory-only preferences are read as a fallback. Saving no selected equipment replaces the previous selection. Aircraft-catalog reconciliation continues to remove unavailable accessory IDs. Equipment preferences contain no pilot, service, confirmation timestamp, or authority evidence; per-flight snapshots remain separate.

The Base/Custom menu is replaced by an Equipment & payload summary showing selected names and opening the equipment controls directly. The editor explains that publishing remembers choices and that operators should review them each flight. The Apple red changed-details claim is replaced by an advisory describing the saved roster's inability to verify current Part 107 qualifications. Android uses the same explanatory wording. This does not establish why this particular pilot's roster qualifications were unavailable; no live roster or flight logs were retrieved.

Validation is recorded in `/tmp/dcp-swift-test.log`, `/tmp/dcp-android-test.log`, and `/tmp/dcp-apple-build.log`. Regression tests cover equipment/payload restoration without reusing flight evidence and clearing a previous selection. The Swift regression also checks removal of an accessory absent from the current catalog. No device installation or physical flight test is part of this change.

Validation result: Swift equipment regression passed; all 5 Android AircraftReadiness tests passed and Android compilation succeeded; unsigned iOS device-target app build succeeded. App Store metadata verification passed (3,987 characters). Device behavior remains untested.

September 13 follow-up: at the user's request, certificate issue date is now sufficient for Tracker qualification calculations when no initial test/training date is recorded. Optional training dates are retained. The temporary missing-training-date warning has been removed from both mobile platforms. The current-roster and historical-flight calculations share the same fallback; original records are not rewritten.

Validation of this follow-up: 20 Tracker readiness/flight-history tests, 2 focused Swift tests, and all 6 Android AircraftReadiness tests passed. No server deployment or device installation was performed.
