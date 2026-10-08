# Unified workspace integration — October 6, 2026

## Source

Local main was fast-forwarded from `eba9c9fec` to `a38459360`, the updated origin/main tagged `v2.4.3rc1` (2.4.3, build 338). The uncommitted single-screen UI changes were reapplied, with six overlapping files reconciled. Existing unrelated/untracked work was preserved. Recovery copies are `/tmp/r2c-ui-before-merge.patch`, `/tmp/r2c-ui-before-merge.tar.gz`, and Git autostash `445f76903`.

The newer alert center, speech logic, proximity fixes, flight IDs, archives, clue workflow, and DJI telemetry fixes are retained. The duplicate draft workspace audio center was removed. The upstream bell remains beside the bridge control. Its existing seven categories and 80% approach policy are preserved, including spoken-proximity gating; the earlier draft five-category/10%-bell description is superseded. The 10% visual map-marker approach band is separate from the bell policy.

About, resizable Bluetooth Stats, map account, stream server controls, and existing main-menu actions are hosted in the operational workspace. Unknown and ignored drones retain Inspect access without publishing. Alerts is also reachable through the menu before the session bell first appears. NOTAM/Airspace and Land Use remain accessible through Alerts. Persistent bottom status text is removed.

## Automated validation

- Android: all 1,385 unit tests passed; debug APK assembled. `/tmp/r2c-merged-android.log`.
- Apple: 77 XCTest tests and 562 Swift Testing tests passed. `/tmp/r2c-merged-swift.log`.
- A retained-network regression initially failed because its assertion expected network status in the former header. The stream placeholder now directly observes network diagnostics, and the regression verifies the new location. Full rerun passed.
- Shared native DJI telemetry C tests passed with warnings treated as errors.
- Apple FFmpeg bridge rebuilt for device and simulator against the merged native telemetry header. `/tmp/r2c-merged-native.log`.
- Signed iPad build passed; `/tmp/r2c-merged-apple-final.log`.
- `git diff --check` passed; no unresolved merge conflicts.

## Installation

- A5 Pro `SM-X350`, serial `R52Y90C9XST`: in-place APK install succeeded, launch succeeded, package metadata confirms 2.4.3 (338).
- Ken’s iPad `694108CB-8CBE-593D-ABE1-D9EDD947B901`: in-place installation succeeded for `org.ncssar.RID2CaltopoApple`. Signed artifact metadata is 2.4.3 (338). Launch initially required unlocking; after the user unlocked the iPad, launch succeeded. CoreDevice app inventory returned empty both before and after installation (including with default apps included), so version evidence is the successfully installed signed artifact, not a separate device-version query.

No uninstall, data reset, store change, commit, push, or external message was performed. In-place installs preserve existing app data; physical UI and field behavior are not established by installation or launch.

## Device checks still needed

Exercise phone/tablet large text, portrait/landscape, fullscreen/PiP, pane resize, sheet transitions, live RTMP input, confirmed server reset, Bluetooth disconnect/reconnect, unknown/ignored/publishing drone taps, and bearing preferences. Check first audible alert, approach/active/clear states, simultaneous categories, mute/unmute, and app restart. Verify map-account and recording-permission flows. Apple’s existing controller-RSSI telemetry gap remains; the merge does not invent a sensor feed.

## Portrait fullscreen follow-up

The reported A5 Pro screenshot showed fullscreen controls over the observation-only video warning. Android now places those controls in a measured row before the stream/map content instead of in its overlay. The row can scroll horizontally at large text sizes. Apple retains its measured safe-area header and now allows its fullscreen controls to scroll as well.

Restored the Proximity Alerts, NOTAM/Airspace, Land Use, and Incident Map status row on both platforms in normal and fullscreen modes. The separate session audio bell is unchanged. Android NOTAM/Land chips retain their existing service-visibility rules; Apple displays the current status labels.

Forty-one focused Android layout/workspace/alert tests passed and the APK built. The signed iPad build passed. Both updates were installed in place and launched. A5 Pro physical layout verification was initially blocked by its protected-access authentication prompt; no authentication was bypassed.

## Bridge meter, About chip, and link contrast follow-up

Restored the existing bridge RSSI label and colored signal bars on both platforms, retaining Bluetooth Stats on tap. Android's RID2Caltopo button now sizes to its label; a wrapping header keeps the action group from squeezing it. Apple uses an explicitly sized About chip in a measured, adaptive header instead of a compressible native toolbar item. At narrow widths the actions move beneath the chip and can scroll. Destination pages explicitly retain their navigation bar and Back control.

Apple About links now use body-sized, underlined primary text with plain link styling on the system background. Android retains the standard Material dialog/button colors. The Android build and 41 focused tests passed, and its in-place update launched. Apple final signed build passed; the in-place iPad installation and launch succeeded. Logs: `/tmp/r2c-header-android.log` and `/tmp/r2c-header-apple-final.log`. Physical large-text and link-contrast acceptance remains distinct from compilation.

## Alert playback history and stable RSSI follow-up

The single session bell now appears immediately before Proximity Alerts in the scrollable second row on both platforms (including fullscreen). Removed the duplicate Proximity Settings, NOTAM/Airspace, and Land Use links from the Alerts panel; their status chips remain. The main-menu Alerts entry remains available before the bell first appears.

Root cause of the premature iOS bell: the operational signal-loss evaluation calls speech eligibility even with an empty alert list, and eligibility used to latch visibility. Android similarly latched at request time. Eligibility/request paths now leave visibility unchanged. Actual TTS start callbacks record a typed alert category and latch the session bell only for non-muted, nonzero-volume playback. Queued or cancelled-before-start utterances, speech that fails before starting, and silent utterances do not increment history. Settings audio-test phrases do not enter operational alert history. Counts and last-played times reset with process restart and persist after alerts clear during the session; repeats increment their own category. Ambient severity and detector thresholds remain unchanged.

Each Alerts row displays a per-category Played count and Last played time (Never until first playback). Android preserves category metadata when proximity and other phrases are queued together. Apple passes explicit category metadata from the operational alert producers rather than inferring a category from spoken text.

Both RSSI labels reserve space for a three-digit negative value using stable digits; changing from a live value to the missing-signal dash no longer changes the chip's intrinsic width.

Validation: 57 focused Android tests passed, including new request/cancellation/history/category-queue regressions; 11 Apple XCTest and 7 Swift Testing checks passed, including startup eligibility, muted/silent playback, repeats and session reset. Both device builds passed. Logs: `/tmp/r2c-alert-history-android-final.log`, `/tmp/r2c-alert-history-swift-final.log`, `/tmp/r2c-alert-history-apple-final.log`. A5 Pro installed and launched in place. The iPad in-place installation and launch also succeeded. Physical speech audibility and rapidly changing live RSSI still require device acceptance; engine callbacks and automated tests do not prove the user's audio route is audible.

## Main-menu cleanup

Removed Alerts, Map Account, and Status menu entries on both platforms. Settings is first; Send diagnostics to developer is immediately above Terms of Use. Shared entries have matching relative order; existing platform-specific storage and external-display entries are retained. Moved Android uptime into About RID2Caltopo and added the same About field on Apple. Removed remaining `RID-2-Caltopo` UI strings and the unused standalone account panel.

Source checks verified first item, removed entries, diagnostics placement, shared relative order, and absence of the legacy name in app source. Android debug build passed. Apple signed build passed; both devices installed and launched successfully in place. Logs: `/tmp/r2c-menu-android.log`, `/tmp/r2c-menu-apple.log`. No functional alert or map-account connection policy changed.
