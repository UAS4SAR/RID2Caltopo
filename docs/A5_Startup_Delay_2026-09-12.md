# A5 Pro launch-screen delay — September 12, 2026

Fresh read-only diagnostics were collected without restarting the app:
- `/private/tmp/a5-startup-212-logcat.txt`
- `/private/tmp/a5-startup-main.txt`
- `/private/tmp/a5-startup-212-app.txt`

Build 212 process 11496 started at 12:34:55.019. The main thread waited 67.264 seconds for a monitor owned by `map-cache-maint` (thread 11558), then reported the first draw complete at 12:36:02.585: 67.566 seconds after process start. The screenshot at 12:35:39 falls inside that wait. The stall monitor separately recorded 66,365 ms of main-thread delay. This is a startup lock wait, not evidence of lidar preparation or a crash.

The build's R8 mapping resolves `v40.a` to `BlobCacheStoreFactory.create`. That factory synchronizes on `UnifiedMapCache.lock`, also held during startup maintenance and cache scanning. `DemElevationService` eagerly opened its cache while the Activity constructed StreamsViewModel/DroneAltitudeCoordinator, before the first interface could draw.

Fix: defer factory creation in DemElevationService, CaltopoIconCacheService, and TileDiskCacheWriter until cache use. Existing prewarming remains on background workers. This removes the maintenance-lock dependency from construction of these UI-owned services without weakening cache-budget locking or changing persisted data. Map prewarming and stats reads were checked to run off the UI thread. Apple uses a separate detached maintenance implementation; this Android factory fix does not apply to it.

Regression test holds the shared maintenance lock while constructing all three services and requires completion before releasing the lock. Full Android debug tests passed. No installation, restart, or physical startup timing of the fix was performed. Earlier install verification confirmed process launch but did not establish first-screen responsiveness.
