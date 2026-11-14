# Google Play readiness — November 7, 2026

This is a source review and work list, not confirmation of Play Console status or Google approval. Verify the uploaded bundle, all active tracks, current Console screens, and published store listing.

## Precise permission and background RID reception

The app uses `WifiManager.startScan()` and `getScanResults()` to receive Wi-Fi beacon Remote ID. Android requires `ACCESS_FINE_LOCATION` for these APIs even on Android 13 and newer. `NEARBY_WIFI_DEVICES` covers Wi-Fi Aware but does not replace fine permission for beacon scan results. Aircraft coordinates in broadcast data are distinct from this tablet's location. Since November 13, 2026 the device location request is precise (`GRANULARITY_PERMISSION_LEVEL`, high accuracy when fine permission is granted) and runs only while an app activity is visible: a field test showed the coarse fix placing the tablet hundreds of meters off, and the map marks the tablet's position relative to aircraft. With approximate-only permission the app keeps working and the marker states the reported accuracy. No `ACCESS_BACKGROUND_LOCATION`; updates stop when no activity is visible (`ForegroundLocationGate`). The scanner's existing location foreground-service type is retained pending verification of background beacon reception and policy classification.

- Complete the precise-location declaration before January 27, 2027. Describe the beacon APIs and user-facing RID reception accurately; explain why coarse permission cannot supply beacon results. Also state the foreground-only precise tablet position used to mark the tablet on the map. Confirm Google accepts the justification.
- Data safety: declare precise location (not only approximate), collected for app functionality while the app is in use, and shared only through operator-configured features (the device marker on the selected CalTopo map, tracker coordination, advisory and protected-land search areas).
- Check the listing, privacy policy, Data safety form, and foreground-service declarations against the release behavior, including background RID scanning and data received from aircraft. Review whether the foreground-service location classification and its user-facing notification are appropriate with Google.
- Explicitly test Bluetooth, Wi-Fi beacon and Wi-Fi Aware reception with the app visible and backgrounded, on representative Android versions, after receiving device-testing authorization. Verify permission-denied behavior. Android may throttle scanning; do not infer reception from the service remaining alive.
- Foreground-only tablet position also means relocation-based automatic disconnection cannot obtain new location fixes while the app is backgrounded. Review this behavioral consequence before release.
- The current startup permission request groups location with nearby-device and notification permissions; review its explanations and timing against Play's in-context permission requirements.

References: [Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions), [Wi-Fi scanning](https://developer.android.com/develop/connectivity/wifi/wifi-scan), [foreground location declaration](https://support.google.com/googleplay/android-developer/answer/17033915), [foreground service requirements](https://support.google.com/googleplay/android-developer/answer/16965181).

## February 2027 quality thresholds

Release builds enable R8 minification and resource shrinking. Those flags do not prove Play's percentage thresholds or actual memory usage. The existing local `app-release.aab` contains about 14.3 MB of uncompressed DEX across two files, but its identity and correspondence to the published bundle have not been established; use the Console result for the actual release.

1. In Play Console, open the actual release in App Bundle Explorer and record its DEX size and optimization, obfuscation, and shrinking percentages. Apps over 10 MB of DEX must meet each 25% threshold. If a metric fails, use the R8 Configuration Analyzer to inspect keep rules and remeasure the actual uploaded build.
2. Open Android vitals > Memory and inspect 28-day P90 anonymous RSS plus swap and bitmap memory, by app state, RAM tier, Android version, and release. Compare them to Google's current tables; collect enough data before concluding compliance.
3. If a threshold fails, profile representative mapping, video, scanning, and background scenarios, then address measured causes and reassess. Do not change code or disable safety-critical features based solely on aggregate assumptions.

Reference: [Play technical quality requirements](https://support.google.com/googleplay/android-developer/answer/17492799).

## Local network — August 25, 2027

The project currently targets API 36. Before targeting API 37, inventory controller video and media transfers, map package transfers, mutual-aid exchange, tracker endpoints, and other local sockets. Evaluate whether Android's device pickers support each user-facing core workflow. If broad `ACCESS_LOCAL_NETWORK` is necessary, implement its runtime flow and submit Google's declaration explaining why pickers are insufficient. Verify device behavior and update the listing and privacy disclosures. The permission has not been added speculatively.

Reference: [policy deadlines](https://support.google.com/googleplay/android-developer/table/12921780), [local-network policy preview](https://support.google.com/googleplay/android-developer/answer/16909972).

## Zero-tap sign-in restoration — April 2027

The app has optional third-party CalTopo personal sign-in and device-bound/private credential storage; no Restore Credentials integration was found. Google's implementation uses a WebAuthn relying-party server to register and verify restore keys. Do not copy CalTopo secrets, browser cookies, tracker enrollment keys, or certificates into backups or a shared archive to simulate restoration.

- Confirm with Play whether third-party CalTopo account connections are in scope, and check for published exemptions or new guidance.
- If in scope, establish with CalTopo whether it provides a supported restore-key registration and authentication flow. An Android-only change cannot manufacture the server-side trust needed for Google's flow.
- Design the restore and sign-out/revocation flows with the identity provider, preserve existing credential security, and test device-to-device and supported encrypted cloud restoration on appropriate test devices.

References: [technical quality requirements](https://support.google.com/googleplay/android-developer/answer/17492799), [Restore Credentials implementation](https://developer.android.com/identity/sign-in/restore-credentials-implementation).

The developer has confirmed non-trader status; no account change was made in this review.
