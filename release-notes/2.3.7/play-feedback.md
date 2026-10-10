# Google Play Test and release — 2026-09-27

Live Console review: production release 2.3.6, build 285 (linked vitals version), rollout 100%. Crash and ANR data unavailable. Expanded all three "For your next release" items.

## Edge-to-edge may not display for all users

Play requests edge-to-edge/inset handling for Android 15+. R2CActivity already calls enableEdgeToEdge before super.onCreate, but the resolved Activity dependency was 1.8.2. Updated the explicit activity-compose dependency to stable 1.12.4, which includes API-35-specific edge-to-edge protection and an invisible-view inset correction documented in AndroidX release notes.

App content previously consumed only status-bar insets at the operational page root. It now consumes safeDrawing insets (system bars, cutouts, and keyboard), allowing descendant app bars/scaffolds to consume only remaining insets. This covers Main, Settings, Scanner, and Live View. The full-window startup acknowledgement also applies safeDrawing padding before its scroll container. Immersive Live View still owns system-bar hide/show; hidden bars no longer reserve their visible size, while cutouts remain protected. The opaque system-unlock cover remains deliberately full-window; standard dialogs keep their separate window inset handling.

Merged release manifest inspected: main R2CActivity; JourneyApps CaptureActivity; ML Kit GmsBarcodeScanningDelegateActivity; AppAuth redirect/authorization activities; Firebase GenericIdpActivity/RecaptchaActivity; credentials HiddenActivity; Google SignInHubActivity/GoogleApiActivity; and PlayCoreDialogWrapperActivity. Both scanner activities retain unspecified orientation; no activity has a fixed resizeableActivity restriction. Dependency-owned scanner/auth windows were not rewritten. The generic Play edge-to-edge finding does not name an individual activity.

## Deprecated edge-to-edge APIs

Exact Play details for 2.3.6:
- android.view.Window.setStatusBarColor
- android.view.Window.setNavigationBarColor
- Starting location: ci1.a

Matched against the preserved build-285 outputs/release-2.3.6-285/packaged-mapping.txt, not a different candidate mapping: ci1 is androidx.activity.EdgeToEdgeApi29, and a is setUp. Thus these calls belong to AndroidX's compatibility helper, not custom R2C bar-color code.

Official Activity 1.12.4 source was inspected: its newer API-35 path uses ProtectionLayout, but still calls the color setters with transparent values. The official Core 1.17.0 WindowCompat.enableEdgeToEdge replacement also retains those setters internally, so replacing one helper with the other would not establish their removal. The candidate adopts the maintained Activity implementation; it does not claim that every deprecated reference has vanished or that Google's static advisory is cleared. Confirmation of Play's disposition requires its analysis of a subsequently authorized upload.

Sources:
- https://developer.android.com/jetpack/androidx/releases/activity
- https://developer.android.com/about/versions/15/behavior-changes-15#edge-to-edge
- https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.12.4/activity-1.12.4-sources.jar
- https://dl.google.com/dl/android/maven2/androidx/core/core/1.17.0/core-1.17.0-sources.jar

## Outdated closed testing track

Finding names v1.6.9(93), track 4701289340456375729, superseded by production more than 90 days ago. Track detail already showed paused when opened; Publishing overview contained exactly one pending change, that track's Pause track status. Submitted that single existing change. Console now shows Changes in review, with quick checks still running. No app bundle was uploaded and no production release was submitted by this task. Final publication of the pause is separate from this confirmed submission.

## Remaining physical verification

The operator approved the earlier header correction on A5 Pro build 285. New safe-area handling and the AndroidX dependency need portrait/landscape, gesture/three-button navigation, keyboard, Settings, Scanner, disclaimer, protected unlock, Live View/fullscreen and split-screen checks on Android 15/16. The reported SM-S931U and large-font matrix remain unverified. No claim of exhaustive physical qualification or Play-warning clearance is made.

## Release recheck — 2026-09-29, candidate 304

Live Test and release page still shows production 2.3.6 (285) at 100%, with two findings: edge-to-edge display coverage and deprecated Window.setStatusBarColor / Window.setNavigationBarColor at ci1.a. Both findings were read, and the API details expanded. The obsolete closed-track finding is absent. The preserved build-285 packaged mapping again resolves ci1 to androidx.activity.EdgeToEdgeApi29. Current source still calls enableEdgeToEdge and uses safeDrawing insets with Activity 1.12.4. The merged candidate release manifest was inspected; no fixed resizeableActivity restriction or opt-out was found. Compatibility API references may remain; no assertion that Play cleared the advisory is made. Operator acceptance of latest tablet builds is recorded separately from exhaustive device coverage.

## Live Console recheck — 2026-10-09, development 2.5.1 (342)

Read Monitor and improve and Test and release in the live Play Console. Neither page currently presents the earlier edge-to-edge display coverage or deprecated system-bar API advisory. Monitor and improve instead presents a bitmap image optimization recommendation attributed to release 337 (2.4.2). Test and release still shows 337 in its production overview, while Latest releases and bundles shows v2.5.0rc3 (341) as Production, Available on Google Play, Full rollout, and bundle 341 as Active. This discrepancy means the overview is not evidence of a completed build-341-specific advisory analysis.

The historical advisory is no longer displayed. No custom system-window workaround or dependency change was made solely to silence it. This closes the previously unverified live-Console recheck; it does not claim that AndroidX has removed every deprecated compatibility reference or that all physical Android 15/16 display checks have been completed. If the advisory returns, use its named version and exact packaged mapping to trace the calls before making changes.
