# Tablet install — November 10, 2026

Version **2.5.14 (443)** from `41c9182d`. In-place only. Neither app was launched, uninstalled, or cleared.

The A5 Pro update completed on the first attempt. The first iPad attempt lost its connection. A later attempt, with the iPad `available (paired)`, installed the same bundle.

| Device | Evidence | Artifact SHA-256 |
| --- | --- | --- |
| A5 Pro, R52Y90C9XST | `adb install -r` returned Success. `dumpsys package org.ncssar.rid2caltopo` reports versionName 2.5.14, versionCode 443, lastUpdateTime 2026-11-10 05:27:11. `firstInstallTime` stayed 2026-10-19 07:09:18. | APK `a4e64b24b5d62118053587d11e2b98dc1b162d0a629f7afd62446c3af9b82bf5` |
| Ken's iPad, 00008103-0010596E1AD3001E | First `devicectl device install app` failed: CoreDeviceError 3002, IXRemoteErrorDomain 6, connection interrupted. Retry at 06:23, device state `available (paired)`, reported the bundle installed at `file:///private/var/containers/Bundle/Application/3969D572-ED81-45FA-892B-EC2D9E752713/RID2CaltopoApple.app/`. `devicectl device info apps` reports RID2Caltopo 2.5.14 (443). | Signed app bundle ZIP `d3882e6857f689100f65d2e0505b4085e85b4f9035c1b115c0b5a3bb8a9e22c9` |

The iPad bundle passed `codesign --verify --deep --strict` before the install attempt. Code-directory hash `77df8830513e74b7a41afc85b05a461ed0be4e1d`.
