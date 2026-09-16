# Captured video picker return failure — 2026-09-15

## Evidence

The supplied root `log.txt` identifies Android SM-S938U1 running 2.2.7 (13Sep2026:132313). Live View opens at 18:10:58; the archive directory is resolved at 18:11:00.931. At 18:11:01.466 the Streams UI consumer is removed. Device-owner authentication completes at 18:11:20.343 and Live View returns empty. There is no local-file playback start or decoder error. The old log does not record whether the picker returned a selection or cancellation.

Source inspection found a matching failure: `StreamsGrid` owned the Compose activity-result launcher. Opening Android's document picker stopped the Activity without a trusted-flow marker, invalidating organization access. The access gate removed the grid and unregistered its callback. The replacement grid registered a new launcher rather than receiving the old selection.

## Change

The captured-video launcher now stays mounted at the Activity's composition root, above the disclaimer/access gate and outside all stream layout branches. The document picker uses the existing trusted external-flow policy. Its result is retained with saved state, including a persistable read grant where supported. Playback is only dispatched below the authentication gate. Cancellation and launch failures end the trusted flow; actual screen locks still invalidate access. Logs identify picker opening, cancellation/selection and dispatch to playback without logging the selected URI.

The document contract, video file filtering, initial directory and playback engine are unchanged. Version remains 2.2.9 (241); canonical and store release notes include this Android fix.

Apple uses SwiftUI's in-app movie importer in `AppleCapturedVideoReviewView`, not Android's external Activity/result registry. No Apple runtime failure is established by this Android log, and no Apple code was changed for this report. Physical Apple picker behavior was not tested.

## Validation and remaining check

All 1,114 Android unit tests passed, including new cases for trusted picker completion/cancellation and a real screen lock during selection. The debug APK build and release metadata checks are recorded in `/tmp/captured-picker-full.log`. These policy tests do not simulate the complete system picker UI.

Physical retest: on an authenticated organization account, open Live View → empty tile settings → Play Captured Video; choose a movie and confirm the tile opens. Repeat cancellation, screen rotation, and screen lock/unlock while the picker is open. Ordinary backgrounding after picker completion must still require authentication. No device was installed or modified for this fix.
