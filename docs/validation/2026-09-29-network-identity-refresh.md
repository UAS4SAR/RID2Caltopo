# Live View network identity refresh — 2026-09-29

Changes are in /Users/kjt/Projects/RID2Caltopo. No separate worktree.

Android previously read the SSID directly during composition, independently of the callback-observed endpoint list. Live View now observes one state containing both SSID and local controller endpoints. Available/lost, capability, and address events reread both; a three-second resumed-lifecycle check catches identity changes without a meaningful address event. The periodic callback is removed when paused or disposed. Wi-Fi disconnection clears the Wi-Fi endpoint and label; missing SSID permission yields an unavailable label. Ethernet endpoints remain separate.

iOS retains default and local-interface NWPathMonitor callbacks, with a three-second foreground identity refresh for same-path Wi-Fi switches and delayed identity/address settlement. SSID and BSSID come from one fetch; stale/cancelled async results are discarded. The refresh reads local interface addresses and updates the existing observable network snapshot, which drives both the SSID label and RTMP URL. Foreground refresh precedes managed-configuration network requests. Stop clears the cached SSID. No network probes or stream restarts are introduced.

Validation:
- Android ControllerNetworkTest: three tests passed, including same-IP SSID switch, changed-IP RTMP URL, disconnection, and unavailable SSID. Debug APK build passed.
- Apple: 437 Swift Testing plus 34 XCTest tests passed. Existing network refresh wiring regression test extended for foreground refresh, cancellation, and both published fields.
- Signed iOS Debug build and deep/strict signature verification passed.
- git diff --check passed.

Not installed in this task. Physical tests remain: switch between two Wi-Fi networks while Live View remains visible (including equal subnet/IP), switch through Settings and return, disable/reconnect Wi-Fi, and attach/remove Ethernet. Verify SSID and controller RTMP address settle within one refresh interval after OS network metadata updates, without interrupting streams unnecessarily. Automated tests do not establish real-device callback timing or SSID permission behavior.
