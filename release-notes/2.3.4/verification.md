# 2.3.4 (246) candidate verification

## Google Play findings checked before release

Inspected the live Test and release page and expanded both detail panels on September 21, 2026. Findings apply to production release 2.3.3rc1, version code 245.

- Edge-to-edge advisory: R2CActivity already calls AndroidX enableEdgeToEdge; MainScreen uses WindowInsets.safeDrawing. This source evidence does not establish all-screen physical layout correctness.
- Deprecated calls: Play identifies Window.setStatusBarColor and Window.setNavigationBarColor starting at obfuscated wh1.a. AndroidX Activity 1.8.2 bytecode contains both calls. Exact attribution of wh1 requires the matching build-245 mapping; dependency attribution alone does not prove this warning harmless. Compatibility calls remain in this release.
- Fixed orientations: confirmed in the merged release manifest for JourneyApps CaptureActivity (sensorLandscape) and Google GmsBarcodeScanningDelegateActivity (portrait). Added manifest overrides to unspecified for both. JourneyApps ScanOptions already sets orientationLocked(false).

These are advisory findings, not demonstrated physical defects, and are not all false positives. The user authorized proceeding after being informed of the unresolved findings. Scanner rotation and layouts on Android 15/16 require physical verification. Verify Google's analysis after the new artifact is processed; source changes cannot establish that Play warnings have cleared.

## Qualification

Pending full Android and Apple release gates, signatures, artifact hashes, source tag and store status. No fresh physical device or field qualification is claimed.
