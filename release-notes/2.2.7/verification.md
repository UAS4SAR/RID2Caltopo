# RID2Caltopo v2.2.7 — build 236

## Release scope

Android production and Apple internal TestFlight use marketing version 2.2.7 and build 236. This source includes the accumulated app work since 2.2.6: offline surface preparation and AOL, telemetry/reference continuity, initial single-stream map follow, map reconciliation, equipment persistence, tablet incident briefings, flight readiness, configuration import, cache improvements, and flight/archive lifecycle fixes.

Ignore persists for the app session on both platforms. Publishing remains explicitly confirmed per flight; saved aircraft configuration does not authorize a new flight. No default acceptance timer was introduced.

The previous unpublished local v2.2.7 source snapshot is preserved as v2.2.7-build202-snapshot. The final v2.2.7 tag is intended for this release. Local recordings, diagnostic logs, generated builds, credentials, and unrelated editor files are excluded. The video timeline fixture retains relative motion and timing with translated coordinates and relative timestamps.

## Validation

- Android production bundle, required release gates, and Crashlytics mapping/native symbol uploads passed. All 1,080 Android unit tests passed with no failures, errors, or skips.
- Apple shared validation: 352 Swift Testing tests and 14 XCTest tests passed. Rebuilt native frameworks; 5,209 portable anomaly tests and color/person-relevance qualifications passed.
- Desktop surface preparation regression passed.
- Release notes and App Store metadata are synchronized and within limits.
- Upstream laz-perf source retains its original whitespace; project-authored staged files pass whitespace checks.

Clean Apple simulator and arm64 device archive passed the complete release-check gate. A separate signed Release archive also built successfully. Distribution export is verified separately before upload.

## Field and server boundaries

The operator tested preceding development builds on iPad and A5 Pro, including Matrice and Mini 4 Pro streams. Build 236 itself has automated build/validation evidence; do not infer a physical flight test of every final change. Last direct tablet installs were build 235.

This is the mobile release. Separate r2c-tracker source changes are not deployed by this release task. Store upload, processing, tester assignment, review, and public availability are tracked separately in the local release evidence.
