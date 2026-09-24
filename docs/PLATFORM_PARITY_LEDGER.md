# Internal platform parity ledger

Project-only engineering record; do not package in the app, copy into store metadata, or publish with release notes.

## Working policy

- Android and Apple are peer platforms. Keep public release notes focused on operator-visible improvements without platform comparisons.
- Append new differences here for each release. Record the affected workflow, impact, evidence, and the next step to close the gap.
- Keep resolved entries and add the resolution version plus Android and Apple verification evidence. A historical release-note claim alone is not current parity proof.
- Distinguish actionable gaps from operating-system constraints and harmless native presentation differences.
- Use [the UI parity contract](../apple/ANDROID_UI_PARITY.md) for the detailed workflow backlog; verify older claims before treating them as current.

## Cumulative release history

Imported on 2026-09-23 from existing canonical release notes. Entries below preserve historical wording; their present status has not been re-audited. Prior shipped release sources are retained for provenance. From 2.3.5 onward, these sections belong only in this ledger.

### 2.0.1

Platform-specific changes:
- iOS: Added Android-aligned Main Screen, Live View, MapPane, aircraft details, stream management, offline-map, tracker, alert, and anomaly workflows.
- iOS: Added iCloud and Files integration, Keychain-protected backup passphrases, four-stream viewing, and app-managed external-display support.
- iOS: Wi-Fi Beacon and NAN aircraft use the DS110 wireless relay into the Bluetooth Remote ID receiver.
- Android: Network snapshots include available controller Wi-Fi RSSI and channel information.
- Android: The RID-2-Caltopo credentials selector is centered in the top bar to match the iPad layout.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.0.2

Platform-specific changes:
- iOS: Added Android-aligned Main Screen, Live View, MapPane, aircraft details, stream management, offline-map, tracker, alert, and anomaly workflows.
- iOS: Added iCloud and Files integration, Keychain-protected backup passphrases, four-stream viewing, and app-managed external-display support.
- iOS: Wi-Fi Beacon and NAN aircraft use the DS110 wireless relay into the Bluetooth Remote ID receiver.
- Android: Network snapshots include available controller Wi-Fi RSSI and channel information.
- Android: The RID-2-Caltopo credentials selector is centered in the top bar to match the iPad layout.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.0.3

Platform-specific changes:
- iOS matches Android's double-tap screen switch, blue incident-map emphasis, 44-point map target, and assignment zoom.
- iOS retries primary-window dismissal after cleanup and keeps two-way WebRTC audio active when microphone state changes.
- Android fixes Play video-export crashes.
- Android runs full cleanup when removed from Recents and exits Split view on a Stream Tile tap before changing focus.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.1.0

Platform-specific changes:
- iPad refreshes Wi-Fi identity after permission and lifecycle changes.
- iOS matches Android's double-tap screen switch, incident-map emphasis, map target, and assignment zoom.
- iOS retries primary-window dismissal after cleanup and keeps two-way WebRTC audio active when microphone state changes.
- Android fixes Play video-export crashes and captures prior ANR evidence.
- Android runs full cleanup when removed from Recents and exits Split view on a Stream Tile tap before changing focus.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.
- Fresh Tracker enrollment downloads published CalTopo credentials and drone mappings immediately; startup retries interrupted downloads.

### 2.1.1

Platform-specific changes:
- Android and iOS provide matching Tracker sign-in and DJI SEI diagnostic controls.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.1.2

Platform-specific changes:
- Android and iOS provide matching adjustable Live View split controls and accessibility descriptions.
- Android provides a Wi-Fi RID scanning switch for reception diagnostics; iOS monitors Bluetooth RID and does not need this control.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.1.3

Platform-specific changes:
- Android can disable Wi-Fi Beacon and Wi-Fi NAN Remote ID discovery without disabling Bluetooth RID, DS100 bridge reception, video, or normal Wi-Fi.
- Android keeps a collapsed split handle inside system-navigation gesture edges; iOS provides the equivalent directly draggable split control.
- Android and iOS provide the same centerpoint elevation and camera-field-of-view behavior when the required RID, camera, and terrain telemetry is available.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.1.4

Platform-specific changes:
- Android's first tap on a displayed live stream now establishes explicit focus and reveals the clue-snapshot control; only a later near-center tap toggles Centerpoint Elevation.
- Android and iOS provide the same persistent pilot-callsign prefill, MSL/reference elevation display, and team-drone mapping preservation behavior.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.1.5

Platform-specific changes:
- Android's expired archive-folder confirmation now clearly offers either continuing with the displayed Drone Trax directory or changing the archive directory.
- Android and iOS provide the same ownership-independent peer traffic exchange, map display, adaptive reporting, and bounded diagnostics.

Known platform differences:
- Peer positions are advisory map information in this release. Existing proximity alerts continue to use locally received aircraft telemetry while peer freshness and altitude behavior receive further field validation.
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.1.6

Platform-specific changes:
- Android prevents temporary render sessions from replacing operational telemetry, improves remote-video frame pacing, and confirms operator-requested exits.
- Apple keeps clue selection and details accessible when markers overlap and retains one reusable decoder and telemetry timeline per publisher.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.2.0

Platform-specific changes:
- Apple offers Face ID or Touch ID first with an explicit Use Device Passcode fallback.
- Android accepts biometrics, PIN, pattern, or password and links directly to system security settings when device security is not configured.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.2.1

Platform-specific changes:
- Apple Tracker sign-in now recovers cleanly when Safari does not return an app callback, without reopening an expired one-time link, and keeps protected navigation hidden until the access check completes.
- Apple map aircraft labels now remain stable during position updates and no longer display the aircraft-reported operator point.
- Apple waits for captured video files to finish finalizing before publishing their recording links.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.2.2

Platform-specific changes:
- Apple immediately reoffers the current confirmed drone for pairing if an imperfectly named stream starts after takeoff; long-press remains available as the manual fallback.
- Apple Tracker sign-in now recovers cleanly when Safari does not return an app callback, without reopening an expired one-time link, and keeps protected navigation hidden until the access check completes.
- Apple map aircraft labels now remain stable during position updates and no longer display the aircraft-reported operator point.
- Apple waits for captured video files to finish finalizing before publishing their recording links.
- Android Quit and automatic RID-idle closing now remove the app reliably even when initiated by a background timer or when diagnostic-file output is slow.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.2.3

Platform-specific changes:
- Apple replaces a stalled media connection when Tracker sends a new offer for the same authorized request, while safely ignoring duplicate deliveries and callbacks from the retired connection.
- Android behavior is unchanged in this hotfix because it already replaces changed media offers correctly.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records that value as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS.

### 2.2.4

Platform-specific changes:
- Android asks after re-authentication whether a matching Samsung tablet is new or the same physical device. Confirming the same tablet preserves its established name and recording history; this remains Android-only because that duplicate-installation problem has only been observed there.
- Android keeps Tracker awake during remote-video and recording-transfer requests, then begins a fresh 30-second standby interval.
- Android reinstall recovery verifies a renewed archive-folder grant, suppresses repeated prompts after cancellation or failure, and offers temporary storage while protected access remains fail-closed.
- Android Quit and RID-idle closing finish reliably even from a background timer or delayed diagnostic output.
- Apple improves WebRTC offer and ICE sequencing, Safari sign-in callback recovery, stream reoffering for a confirmed drone, stable map labels, and recording publication after file finalization.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records it as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS and remains an outstanding parity gap.

### 2.2.5

Platform-specific changes:
- Android keeps offline-map tile workers alive across screen lock and reauthentication, protects active downloads while the display sleeps, and recovers stalled network requests.
- Android accepts the secure OS unlock that immediately follows screen-off instead of asking for the device PIN a second time.
- Android and Apple both keep an active map download protected and begin a fresh idle interval after it finishes or is cancelled.
- Android and Apple use the same pre-download storage checks and editable cache-limit controls.
- Android removes its former local defaults that hid four built-in CalTopo map groups; server visibility and operator choices are still honored.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records it as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS and remains an outstanding parity gap.

### 2.2.6

Platform-specific changes:
- Android same-tablet reauthentication asks using the tablet's existing Tracker name and preserves that name when confirmed, without presenting a provisional replacement name.
- Apple selects the controller-facing Wi-Fi address from the active network path, with wired Ethernet as fallback, and omits the default RTMP port from the displayed endpoint.
- Apple correctly decodes multi-byte offline GeoTIFF elevation rows used for terrain-aware clue projection.

Known platform differences:
- Apple uses iCloud Drive and Files for backup and transfer; Google Drive remains selectable through Files when installed.
- iOS does not expose handset Wi-Fi RSSI, so the Apple app records it as unavailable.
- iOS may suspend video and anomaly processing while locked or backgrounded; Bluetooth restoration is best effort.
- Person Relevance filtering is not yet available on iOS and remains an outstanding parity gap.

### 2.2.7

Platform-specific changes:
- Android avoids a cache-maintenance lock wait before the first screen, stabilizes map status-label width, and corrects render-rate rounding while retaining adaptive video pacing.
- Both platforms use designator/time/date track titles, also in standalone mode. Standalone flights stay independent, with confirmation and archive uploads.
- Apple uses compact markers in narrow map panes, bounds enrollment waits, logs import timing, and cleans the cache automatically. Map cache size and age use labeled keyboard-entry panels.

Known platform differences:
- Apple uses iCloud Drive and Files for backup/transfer; Google Drive is selectable through Files when installed.
- iOS does not expose Wi-Fi RSSI and may suspend video/anomaly processing while locked or backgrounded. Bluetooth restoration is best effort.
- Person Relevance filtering remains unavailable on iOS.

### 2.2.8

Platform-specific changes:
- Android uses legacy Bluetooth Remote ID scanning to match iOS; Wi-Fi RID scanning is disabled by default. Keep map-tile reads responsive while indexing removable storage.
- Android uses one cyan crosshair label on a fitted dark background for readable MSL/AOL text. Improve recorded-video frame-rate fallback and source metadata extraction.
- Android starts microphone capture only after permission and explicit enable, reports capture failures accurately, and clears remote-viewing and microphone indicators when a session ends.
- iPad removes the duplicate Settings Import Config link and uses a dedicated stream touch layer for taps, double taps, long presses, pinch and pan.
- iPad reads completed-video properties asynchronously and caches them. New artifacts use RID2Caltopo/FlightStorage/local-date folders. Existing CapturedStreams, Tracks, Logs and Clues folders remain in place; in-place updates preserve them. No uninstall is required.

Known platform differences:
- Android retains the selected Archive Dir and document-provider support. Apple previews stored files with Quick Look; Android uses available viewers. Some Android providers cannot report free space.
- Map/terrain storage has separate limits; AOL packages and active terrain are protected from ordinary cache trimming. Surface data must cover the flight location, and valid altitude references are still required.
- iOS may suspend video/anomaly processing while locked or backgrounded. Wi-Fi RSSI and Person Relevance filtering remain unavailable on iOS.

### 2.2.9

Platform-specific changes:
- Android fixes false low-storage warnings before archive setup. Manage Storage distinguishes device free space from app allowances and shows archive usage as unavailable until access is granted. Selecting an archive folder rechecks recording availability.
- Android fixes Play Captured Video losing the selected file. Opening or cancelling the system file picker no longer requires reauthentication. Selections survive Live View layout changes; a real device lock still requires authentication before playback.
- Android uses a directly connected Wi-Fi/Ethernet route for MA transfers when available, keeps sharing and downloading on separate workers, and limits stalled incoming connections. Preparing another package no longer overwrites the active share's temporary file.
- Apple can receive Android MA transfer QRs through Import Config and camera links. Downloads verify the sender's certificate, package size and checksum before import. MA ZIP files now reach the package importer from the main Import Config screen.
- Apple discards a previously prepared package when its map-access option changes, preventing the old package from being shared with unintended credentials.

Known platform differences:
- MA transfer QRs work over a reachable local network, not cellular internet alone. Keep the sender's QR panel open and scan the current QR. Wi-Fi client isolation or denied Local Network access can prevent transfer.
- Android hosts MA QR transfers. Apple exports MA ZIP files through the share sheet; Apple QR hosting is not included.
- MA packages contain already-cached map and terrain data. Missing data must be prepared before export.

### 2.3.0

Platform-specific changes:
- Android fixes false low-storage warnings before archive setup. Manage Storage distinguishes device free space from app allowances and shows archive usage as unavailable until access is granted. Selecting an archive folder rechecks recording availability.
- Android fixes Play Captured Video losing the selected file. Opening or cancelling the system file picker no longer requires reauthentication. Selections survive Live View layout changes; a real device lock still requires authentication before playback.
- Android uses a directly connected Wi-Fi/Ethernet route for MA transfers when available, keeps sharing and downloading on separate workers, and limits stalled incoming connections. Preparing another package no longer overwrites the active share's temporary file.
- Apple can receive Android MA transfer QRs through Import Config and camera links. Downloads verify the sender's certificate, package size and checksum before import. MA ZIP files now reach the package importer from the main Import Config screen.
- Apple discards a previously prepared package when its map-access option changes, preventing the old package from being shared with unintended credentials.

Known platform differences:
- MA transfer QRs work over a reachable local network, not cellular internet alone. Keep the sender's QR panel open and scan the current QR. Wi-Fi client isolation or denied Local Network access can prevent transfer.
- Android hosts MA QR transfers. Apple exports MA ZIP files through the share sheet; Apple QR hosting is not included.
- MA packages contain already-cached map and terrain data. Missing data must be prepared before export.

### 2.3.1

Platform-specific changes:
- Android labels an unassociated aircraft action "Add to RID Map" and uses a matching edit-panel title.
- Apple labels an unassociated aircraft action "Add to RID Map" and uses a matching edit-panel title.
- Apple positions the map scale above the legal attribution link.

Known platform differences:
- Android and Apple retain their existing flight confirmation, organization, and pilot-readiness fields after the new entry point.
- Android and Apple use their native map rendering controls for scale placement and presentation.

### 2.3.2

Platform-specific changes:
- Android renders a visible scrollbar for the registered designator list.
- Apple presents the registered designators in a scrollable list sheet.
- Both platforms retitle the list to “droneDesig's for registered drones” and provide an Add aircraft action that opens the aircraft details editor.
- iOS keeps the first tap on the designator link in the split stream view from collapsing the map pane.

Known platform differences:
- Android and Apple retain their native Status and stream setup presentation.

### 2.3.3

Platform-specific changes:
- Android and Apple persist the Minimum Location Accuracy setting independently on each tablet.
- Android and Apple preserve existing app data when updating to this version.
- Android downsamples stored clue thumbnails before decoding them to reduce bitmap memory usage.
- Android retains protected access for 15 seconds during brief app switching, while still requiring authentication after a device lock.

Known platform differences:
- Android and Apple retain their native Bluetooth scanning and Settings presentation.

### 2.3.4

Platform-specific changes:
- Android removes fixed-orientation declarations from QR and barcode scanner windows.
- Apple improves the Live View back button and cleanup of stopped or manually closed video streams.
- Apple preserves recent aircraft heading when altitude telemetry ages out and retains long local tracks without the previous 5,000-point cap.

Known platform differences:
- Android and Apple retain native Bluetooth, video, Settings, and authentication interfaces.
- Android's packaged compatibility libraries still include deprecated system-bar color APIs. Android 15/16 scanner rotation and edge-to-edge layout require physical verification.

### 2.3.5

Platform-specific changes:
- Android resumes airspace, NOTAM, and land-rule monitoring when the app reopens and aligns NOTAM expiry with Apple.
- Apple identifies local video delay and frame inactivity.

Known platform differences:
- Android and Apple use native layouts. These data displays do not provide flight authorization.

#### 2026-09-23 standalone RID-map correction

Both platforms' local editors allowed standalone access but unconditionally
required an organization during validation. Local saves now allow a blank
organization; managed entries still use the organization requirement and
existing edit-authorization checks. This closes a shared standalone defect,
not a difference between platforms. Regression tests cover blank organization,
managed validation, invalid fields, duplicate RIDs, and duplicate callsign/model.
Physical clean-state saving on each tablet still requires operator verification.

#### 2026-09-23 pilot naming and form input

Both mapping validators now accept free-form pilot callsigns/names; the same
pilot must still use distinct model descriptions for multiple aircraft.
Android preserves explicit free-form ownerCallsign values when restoring saved
mappings rather than applying the legacy callsign-pattern heuristic to them.
Apple's RID form disables the native scroll touch delay locally and enlarges
its entry fields. Header swipe recognition no longer delays/cancels control
touches. Physical field response needs operator retesting; no improvement in
end-to-end video latency is claimed from these input changes.


### 2.3.5 — Tracker recovery and update detection (unreleased)

- Android now checks Google Play on foreground entry, at most once every six hours per process, independently of Tracker authentication. Available updates feed the existing optional store prompt. This does not enable Play automatic installation or force an update during a flight. iOS has no new independent App Store availability check; this remains an open parity gap.
- Both platforms retain uploads rejected with 401/403/426 (and cancelled uploads with 499) as pending and surface organization re-enrollment guidance after upload authorization rejection. Android additionally surfaces recovery from managed-configuration download rejection; that addition has not been made on iOS.
- Android resubmission totals now count only successful uploads, excluding duplicate or permanent rejections.
- Validation: 80 focused Android tests, three Apple upload-contract tests, and an unsigned Apple Debug device build passed. Physical-device recovery and Play-delivered update detection remain unverified. No store release or Josh flight-credit recovery has been confirmed.

## 2.3.5 candidate follow-up — 2026-09-23

- Both platforms now permit local RID entries without an organization and accept free-form pilot callsigns/names, enforcing pilot/model uniqueness. Both show required-field asterisks and the Main Screen Pilot Callsign/Name label.
- Both platforms support directional title-bar navigation. Native transitions differ; navigation remains outside map, video, and form content. Apple RID form fields have 44-point minimum touch height and no scroll touch delay.
- Android adds an independent Play update check; Apple continues to use its distribution update mechanisms. Saved-flight authorization failures remain pending on both platforms.
- Candidate installation and automated checks are recorded separately from the operator morning field test. Prior iPad demo acceptance does not qualify the newly built Android artifact.

### 2.3.5 build 250 follow-up

- Closed an Apple bootstrap gap: organization configuration now refreshes independently of a selected incident map after sign-in and on foreground/startup. Android already invokes its independent bootstrap after reauthentication.
- Pending Apple sign-in no longer reports configuration import success. Automated recovery tests pass; see docs/validation/2026-09-23-ipad-enrollment-recovery.md for device evidence and physical verification status.

### 2.3.5 build 252 follow-up

- Apple sign-in callback and foreground recovery handling now survive the privacy gate hiding protected content during a browser switch. Android already handles incoming URLs at Activity level.
- Server audit confirmed the affected iPad login succeeded; the repeated prompt was stale client state. Physical recovery evidence is tracked in docs/validation/2026-09-23-ipad-enrollment-recovery.md.

### 2.3.5 build 254 follow-up

- Android "Keep current folder" now validates the retained Android folder grant and reactivates/persists the archive selection after a settings reset. Previously it only dismissed the dialog, so startup prompted again. Missing authorization routes back to explicit folder reauthorization.
- Apple uses application-owned archive storage and has no corresponding Android tree-grant selection flow. Its build 253 clean reset/enrollment was confirmed by the operator; two Face ID scans were observed during initial recovery and remain a separate UX follow-up.
