# Map viewport and NOTAM action parity — 2026-09-29

All edits were made in `/Users/kjt/Projects/RID2Caltopo`; no new worktree.

## Behavior

- iPad map-tapped NOTAM point, line, and polygon details retain the actual notice and offer both **Show on map** and **Close**, matching Android. Show on map uses the existing notice geometry focus request.
- iPad viewport memory now belongs to the main screen lifetime, preserving map center, bounds, and manual intent when Live View is recreated.
- Android retains manual viewport intent in StreamsViewModel and no longer clears the saved viewport when the incident map changes.
- Pan, pinch zoom, and double-tap zoom suspend automatic focus on both platforms. New stream arrivals do not clear manual intent.
- First-location centering is tracked for the app session on both platforms. Later location updates do not repeat it. A manual view established before location arrives also takes precedence.
- Explicit focus actions (such as Show on map or enabling Follow) remain available.

## Validation

- Android video/map regression suite: 413 tests, zero failures/errors.
- Android debug APK: build succeeded.
- Apple core suite: 434 tests passed.
- Apple signed iOS Debug build: succeeded.
- Installed in place and launched version 2.3.7 (302) on A5 Pro `R52Y90C9XST` and Ken’s iPad `694108CB-8CBE-593D-ABE1-D9EDD947B901`. Queried installed metadata confirms build 302 on both. Android installed/new certificate SHA-256 matched; iPad signature verification passed. App data preserved by in-place updates.
- Build logs: `/tmp/r2c-install-302-android.log` and `/tmp/r2c-install-302-apple.log`. Physical UI checks below remain pending.

## Device checks pending

On both devices: tap KZOA and confirm Show on map and Close; pan and zoom (also test zoom without pan), return to Main Screen and reopen Live View, then background/resume and switch full/split/inset layouts. Confirm the chosen bounds remain. Repeat with a new stream arriving. Check explicit Show on map and Follow still work. Test startup with location delayed, including a manual viewport change before the first fix.
