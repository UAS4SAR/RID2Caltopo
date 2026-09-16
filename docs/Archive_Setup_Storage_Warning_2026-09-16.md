# Archive setup falsely reported as low storage

The attached A5 Pro SM-X350 (R52Y90C9XST) reports 223G total / 16G used / 207G available for both /data and /storage/emulated/0. The reported warning was not supported by device free capacity.

Android FlightStorage.scan previously combined a missing/unreadable archive, allowance pressure, and device pressure into one blocked flag. Its fallback message said "Device storage is low or the archive is unavailable." At first launch, the archive setup dialog and this warning could appear together. Manage Storage rendered the absent archive's empty scan as zero usage. A retained archive-selection hint only suggests the previous directory; it is not a storage permission grant.

Correction: classify archive-required, device-low and allowance states separately. Leave the existing archive setup dialog responsible for missing access, without emitting a capacity alert. Continue blocking recording until an archive is usable, including writable access. Explicitly recheck storage after persistent or temporary archive selection. Manage Storage now labels inaccessible archive usage, exposes actual device free space, distinguishes app allowances from device capacity, and identifies cache totals as local-only while archive access is absent.

Apple uses its own Documents/RID2Caltopo/FlightStorage directory, and its capacity predicate does not include Android's missing document-provider archive condition. No Apple code change was needed for this specific defect; physical Apple storage UI was not tested.

Regression cases cover missing archive without capacity notification, healthy setup completion, true device pressure, and allowance pressure. Existing retention and protected-day behavior remain unchanged. Physical first-install setup testing must not be simulated by clearing the user's app data.

## Validation and installation

All 1,117 Android unit tests passed; the debug APK and signed release APK built successfully. Release metadata validation passed. Release build log: `/tmp/storage-setup-release.log`. Crashlytics mapping/symbol upload tasks were explicitly excluded after automatic approval review flagged their external upload; local release validation and packaging completed successfully.

Updated the A5 Pro in place with `adb install -r` (Success) and launched R2CActivity (Status ok). Package Manager confirms 2.2.9 / 241, updated at 2026-09-16 05:03:28 device time. APK SHA-256: `db123a215fca67516a493f800c946676055a69b5aa7fa85e40c8297a2deb5a2c`. No app data was cleared. This verifies installation/launch, not a destructive recreation of first-install state. The S11U still has the preceding playback-fix build.
