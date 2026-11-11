# Field install — November 9, 2026

Version **2.5.11 (440)** is the in-place debug install of PR #10 at `1020b137`, plus this version bump. It removes the extra "Drone already claimed" prompt and reads incoming RTMP on a dedicated thread. Neither app was launched, uninstalled, or cleared.

Android `firstInstallTime` stayed 2026-10-19 07:09:18.

| Device | Evidence | Artifact SHA-256 |
| --- | --- | --- |
| A5 Pro, R52Y90C9XST | `adb install -r` returned Success. `dumpsys package org.ncssar.rid2caltopo` reports versionName 2.5.11, versionCode 440, lastUpdateTime 2026-11-09 08:25:58. | APK `87af979007f8d503570b3c46736e446823a452f99702b0ae091ab77c76884f72` |
| Ken's iPad, 00008103-0010596E1AD3001E | `devicectl device install app` reported the bundle installed. `devicectl device info apps` reports RID2Caltopo 2.5.11 (440). `codesign --verify --deep --strict` passed. | Signed app bundle ZIP `e67cede5976b975445172b7ffcc9f6b294e5e8c40b9420cbd139412f868f7ef7` |

iPad executable SHA-256 `cb0a169368ae5c96cfc7af8d2f02744f6a24535f1500a229c7217fffd286144e`. Code-directory hash `e8f61fdb1fa869ba77b857738f75987553507538`.

The iPad build used a new MediaMTX xcframework compiled from patch `0013-dedicated-rtmp-reader`. The Android debug APK used a rebuilt `app/src/main/assets/mediamtx` with stripped SHA-256 `59751e25c50bb48a7d87bf3c5a8b1c040e2bdc7e8b0e0113ef16d12512439fde`. That asset is not in Git. The matching unstripped hash is recorded in `third_party/mediamtx/manifest.env`.
