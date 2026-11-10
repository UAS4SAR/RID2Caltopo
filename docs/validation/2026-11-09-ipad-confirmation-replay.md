# iPad install — November 9, 2026, confirmation replay

Version **2.5.13 (442)** is the in-place debug install of the Mine-confirmation replay fix. A Save that Tracker has already verified is not put back into the pending claim on the next RID fix. The app was not launched, uninstalled, or cleared. Flights already held for review were not released.

The repo shares one version identity, so the Android source version is also 2.5.13 (442). Android did not have this replay bug and was not changed. The A5 Pro was not updated and stays at the previously installed 2.5.11 (440).

| Device | Evidence | Artifact SHA-256 |
| --- | --- | --- |
| Ken's iPad, 00008103-0010596E1AD3001E | `devicectl device install app` reported the bundle installed. `devicectl device info apps` reports RID2Caltopo 2.5.13 (442). `codesign --verify --deep --strict` passed. | Signed app bundle ZIP `1a51b3d5605adb9e322a850737402c7ed1c4911f0549b3abe853bc3800a5a919` |

Code-directory hash `11f844976a98b98e075b4327a14fab450eed132d`.
