# iPad Live View incident-map state

Device evidence: Ken's iPad, iPad13,4, iPadOS 26.6.2, RID2Caltopo 2.3.7 (296).
Read-only log copy: `ipad-liveview-map-20260928/device.log`, captured September 28.

- 19:06:52 PDT: publisher and Tracker configured for map `4J0LF02`.
- 19:10:02.846: operator selected Live View Back.
- 19:10:03.005: publisher still configured for `4J0LF02`.
- 19:10:13.997: reopened Live View fetched 26 map features.
- 19:10:38.125–38.344: standalone state, device-marker removal, and publishing disabled. The log does not identify the initiating UI action; no automatic incident-map-disconnect message appears.

Source finding: RIDTrackMapView accepted a value snapshot of AppleCaltopoConfiguration from a navigation destination. Its chip and local map-options routing used that snapshot, but its fallback callback consulted ContentView's current AppleCaltopoSettings. A stale empty snapshot can therefore display Standalone and send the tap to the parent map-options panel even when the current map remains connected. This fits the reported symptom; it is not a physical reproduction.

Change: pass and observe the existing AppleCaltopoSettings object directly in RIDTrackMapView. Derive configuration from that object for the chip, action routing, artifacts, and export. Existing local map-options presentation remains in Live View when connected.

Android comparison: StreamsScreen reads StreamsViewModel.mapName, backed by Compose mutable state. No equivalent retained SwiftUI destination snapshot; no Android change needed for this finding.

Validation: full unsigned Debug build for generic iOS device succeeded using Xcode. `git diff --check` passed. Build log: `/tmp/r2c-liveview-map-build.log`. This is compilation evidence, not an installed or physically verified fix.

Physical qualification remains required: connect Taylor Site, open Live View, enter/exit FS, confirm site chip, tap it and verify options appear over Live View, cancel, then Back and verify no deferred panel. Also switch/disconnect and reconnect while Live View is open. No app install, restart, or device UI interaction was performed in this investigation.
