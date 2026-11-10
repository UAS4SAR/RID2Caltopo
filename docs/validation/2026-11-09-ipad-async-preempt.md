# iPad install — November 9, 2026

Version **2.5.12 (441)** is the in-place debug install of PR #10 at `3d003bb1`, plus this version bump. The iOS MediaMTX runtime calls `parsegodebug("asyncpreemptoff=1")` during init. The app was not launched, uninstalled, or cleared.

Before this install, `devicectl device info apps` reported RID2Caltopo **2.5.11 (440)** on Ken's iPad.

The repo shares one version identity, so the Android source version is also 2.5.12 (441). The A5 Pro was not updated and stays at the previously installed 2.5.11 (440).

| Device | Evidence | Artifact SHA-256 |
| --- | --- | --- |
| Ken's iPad, 00008103-0010596E1AD3001E | `devicectl device install app` reported the bundle installed. `devicectl device info apps` reports RID2Caltopo 2.5.12 (441). `codesign --verify --deep --strict` passed. | Signed app bundle ZIP `de0ee38001a06fcb93bb30ca0ba8d9ac1cb5e9174dd048dd75003177b3b2d717` |

iPad executable SHA-256 `0860eb8c0aed281ae55838a8865a9268a907b859caf7f19e64f9ecb45307ec4d`. Debug dylib SHA-256 `da17d5cf9b9f55fb8b141a08b5df983005e7580243787c477e4b95010c2739d1`. Code-directory hash `156fe7970de1ba19a1700900e640e21e410ddd7d`.

The linked debug dylib contains `Go runtime asyncpreemptoff=%s` and `runtime.parsegodebug`.
