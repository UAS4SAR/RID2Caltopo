# Shared thumbnail / LiveTrack interval — 2026-09-29

Settings > Video Streams now labels the existing preference “Thumbnail & LiveTrack update interval” on both platforms. Existing keys and values are preserved; normalization remains 0.5–60 seconds with a 5-second default.

Both latest-position report queues read the thumbnail preference as their cooldown interval. Cooldown remains measured from completion of the preceding send, including failed sends. One replaceable pending report per aircraft preserves latest-only coalescing, finalization cancellation, and no stale catch-up burst. A pending cooldown rechecks the setting at most every 500 ms so decreases and increases take effect without a restart; Android also rechecks when a blocked worker resumes. No changes to archive geometry recording, network retry delays, or interrupted-journal persistence intervals. The interval is a rate limit, not a promise of incoming telemetry or server response frequency.

Validation:
- Android: five LatestPositionReports tests and two thumbnail policy tests passed. Includes minimum interval, changing a pending 60-second wait to 0.5 seconds, increasing the interval while the worker is blocked, coalescing, failed-send cooldown, cancellation, independent aircraft.
- Apple: 438 Swift Testing plus 34 XCTest tests passed. New test changes the shared stored preference during a pending wait; existing coalescing and cancellation tests pass.
- Android Debug APK and signed iOS Debug build succeeded. iOS deep/strict signature verification passed.
- git diff --check passed.

Not installed. Device verification remains: adjust the interval, check thumbnail and LiveTrack timing on both tablets, and confirm updated settings layout fits.
