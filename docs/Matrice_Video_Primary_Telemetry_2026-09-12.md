# Matrice video as the primary telemetry source

## Field evidence

The Samsung SM-X350 was running 2.2.7 build 214. The September 12 15:19 screenshot
showed POS? throughout the altitude status line while the video and camera view
continued. The captured device log at 15:19:19.226 reports paired SEI age 11 ms,
RID signal age 171124 ms, and track telemetry age 952 ms. The altitude coordinator
still used the RID waypoint timestamp. The stream indicator independently used
RID-derived track pairing to decide whether any telemetry existed.

Evidence is saved under `outputs/matrice-wired-telemetry-20260912/`: `logcat.txt`,
`flight-log.txt`, and `flight.json`. The observed DJI sample at 15:19:19.169 has
reference altitude 600.939 m and down displacement -605742 mm; the existing decoder
relation yields relative height 4.803 m (about 15.8 ft). These are embedded stream
fields, not evidence that the native reference altitude has an independently
verified MSL datum.

## Implemented behavior

- A complete live DJI position/relative-height sample can create and update the
  aircraft track without any RID observation. Identity comes from the existing
  configured or manual stream binding, not an assumed serial inside the payload.
- Android uses the normal waypoint, confirmation, local recording and publication
  path with DJI_STREAM source attribution. Apple ingests djiVideo observations
  through its track store and existing confirmation/publication path.
- Fresh accepted stream positions take priority over RID. Rejected stream positions
  do not suppress radio fallback. Freshness requires actual received samples;
  playing video alone is insufficient. Local playback is excluded.
- Hovering does not discard stream altitude updates merely because horizontal
  movement is small. Stream receipt time is retained on Android position and height.
- A previously established altitude frame is preserved when available; otherwise
  stream reference altitude plus relative height establishes the native frame.
  Apple publication uses its altitude coordinator's ground reference rather than
  assuming the first airborne observation was at ground level.
- Unpaired embedded telemetry is recognized as available rather than red/no
  telemetry. Mapped tracked aircraft retain the normal paired indication.
- Android's fresh camera bearing remains numeric independently of stale aircraft
  position. No fresh camera bearing falls back to the existing track-heading status.
- Neither stream presence nor a near-zero height fabricates a ground/launch event.
  AOL still needs an observed or manually established launch reference and prepared
  surface coverage. Video-only manual calibration uses the existing independently
  established 50-foot-over-launch procedure.

## Validation and limits

Android: all 1,058 unit tests passed. Coverage includes the captured Matrice sample
without RID, expiry/future-time rejection, source priority only after an accepted
sample, disconnect fallback, hovering height updates, source counts, no fabricated
ground status, and independent camera freshness.

Apple: 333 Swift Testing tests and 10 XCTest tests passed. New coverage starts a
track and computes height from video alone, checks expiry, preserves an unknown
launch reference, and accepts small hover movements with changing altitude. The
signed iOS app build also passes.

Release notes match on both platforms and pass the Apple metadata length checker.
`git diff --check` passes.

## Device installation

At the user's request, 2.2.7 build 215 was installed in place on Ken's iPad
and the Samsung SM-X350 (R52Y90C9XST). Both installation and launch commands
succeeded, and installed metadata confirmed build 215 on each device. The
Samsung retained its original first-install time (2026-09-12 07:27:53); neither
device was uninstalled or reset. Build and installation logs are in the
`install/` subdirectory of the evidence folder. Physical video-only startup,
recording, source fallback and altitude comparison remain user field checks.
