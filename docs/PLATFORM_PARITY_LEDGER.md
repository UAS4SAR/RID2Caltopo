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

## 2026-09-24 — Accuracy-aware proximity alerts (unreleased)

Android and Apple now enforce a 100-ft default/minimum base spacing and include per-aircraft position uncertainty. Proximity altitude uses separately tagged RID absolute references and declared accuracy; SEI, unknown accuracy, incompatible references, and stale altitude retain horizontal-only alerts. Both platforms display unknown vertical separation, account for sample age and forecast uncertainty, and preserve current-position conflicts when prediction points away.

See [policy and validation](validation/2026-09-24-proximity-accuracy.md). Automated verification and local app builds are separate from physical two-aircraft, different-takeoff-elevation qualification, which remains outstanding. The 50-ft unknown/SEI allowance is provisional and has not been established by measured SEI accuracy. No store release or device deployment was performed.

### 2026-09-24 — Local proximity opt-in, build 255

Both platforms now default proximity warnings off and require the same telemetry-caveat acknowledgment on every off-to-on transition. The toggle sits beside the 100-ft spacing setting; acknowledgment is local and cannot be supplied by organization imports. Both show Off/On/Suspended status, clear pending and suspended warnings on disable, and support resuming suspension after the pair clears. Apple binds persisted consent to the device; Android excludes its separate consent preferences from backup/transfer.

Validation: 87 selected Android tests, 400 Apple core tests, Android debug build, and signed Apple device build. Deployment and physical-proof limits are recorded in [the opt-in validation report](validation/2026-09-24-proximity-opt-in.md).

### 2026-09-24 — Standalone proximity correction, 2.3.6 build 256

Fresh iPad flight evidence exposed an Apple-only Tracker-lease gate suppressing every standalone proximity alert. Apple now permits locally confirmed standalone flights without a lease, while coordinated incidents retain ownership rules. Android already allowed standalone coordinator eligibility and now explicitly gates proximity on current-flight confirmation. Both permit nearby unconfirmed traffic as the other member of the alert pair. Apple also consumes the newly published track snapshot and logs eligibility-state changes.

90 selected Android tests and all 402 Apple core tests passed; both apps built, installed in place, launched, and reported 2.3.6 (256) on the A5Pro and Ken’s iPad. Physical retest remains outstanding. See [fresh evidence and validation](validation/2026-09-24-proximity-standalone.md).

### 2026-09-24 — Proximity scope, 2.3.6 build 257

Both platforms now default to Published only and offer All aircraft in Settings. Published only retains locally claimed-flight eligibility; All aircraft includes any received aircraft pair without changing confirmation, recording, publishing, or proximity consent. Scope changes immediately reevaluate and clear obsolete warnings while preserving suspension. Regression validation and device deployment are tracked in [the scope report](validation/2026-09-24-proximity-scope.md).

### 2026-09-24 — Apple Live View proximity presentation, build 258

Fresh build-257 logs confirm alerts were detected while Live View was visible, but the banner was attached behind the pushed destination. Apple now presents proximity above navigation and offers Resume in Live View. Android already hosts its proximity dialog above screen selection and remains build 257. Apple speech diagnostics now distinguish requests, start/finish/cancellation, volume, and output route; the reported silence remains unproven pending device retest. See [Live View evidence](validation/2026-09-24-proximity-live-view.md).

### 2026-09-24 — Proximity freshness and timed clearing, build 259

Both platforms separate five-second proximity position validity from longer track retention, exclude stale/future positions, show a telemetry-unavailable notice, and evaluate once per second so the three-second clear delay completes without new packets. Fresh separation to 650 ft and no-packet expiry have regression coverage. Apple adds periodic distance/age/accuracy and clear diagnostics. See [expiry investigation and validation](validation/2026-09-24-proximity-expiry.md).

### 2026-09-24 — Compact Apple proximity presentation, build 260

Apple replaces the automatic large navigation-level banner with a five-second compact notice and persistent top-right alarm bell. Details and Suspend/Resume are available on demand; notice dismissal has no effect on detection or speech. Stale telemetry status moves into the bell’s details. The detection and uncertainty policy remains unchanged; fresh logs explain the wide effective threshold. Android remains build 259. See [presentation and threshold evidence](validation/2026-09-24-proximity-compact-ui.md).

### 2026-09-24 — 50-ft configurable minimum, build 261

Both platforms retain the 100-ft default while accepting configured values down to 50 ft across settings, imports, restores, and evaluation. Accuracy decoding remains a radial bound; it is not halved. The separate sample-age/prediction policy is unchanged. See [minimum and accuracy validation](validation/2026-09-24-proximity-minimum.md).

### 2026-09-24 — Retire predictive head and proximity motion padding, build 262

Both platforms remove predictive map controls/extrapolation and ignore legacy enable flags. Proximity uses accepted reported positions and accuracy bounds without assumed-motion padding or two-point forecasts. Existing telemetry outlier rejection is unchanged. See [reported-position behavior and validation](validation/2026-09-24-reported-position-proximity.md).

## 2026-09-24 — Versioned terms acceptance

- Android and Apple now persist acceptance of the current version and exact terms text, require a checkbox only when acceptance is absent or stale, and retain the accepted text and timestamp locally.
- Both platforms log new acceptance separately from restored acceptance and provide Main menu > Terms of Use for read-only access. Existing legal wording is retained pending the content decision.
- Validation and remaining physical-device checks: [versioned terms acceptance](validation/2026-09-24-versioned-terms-acceptance.md).

### Individual User Terms selected for build 263

Both platforms now display the user-selected Individual User Terms, including the identical individual-capacity checkbox statement. Terms version 2026-09-24.2 supersedes the old content; prior acceptance cannot bypass this revision. Opening notice, numbered headings, and video warning remain conspicuous. Android screen-text inspection confirmed the new title, version, and individual-capacity paragraph after installation. Focused Android (4) and Apple (3) tests passed; signed Apple device build passed. This supersedes the earlier entry retaining old legal wording.

### General operational-risk note — terms 2026-09-24.3

Both platforms include the approved broad operational-risk note verbatim within Section 2, with a bold heading and renewed versioned acceptance. Exact text parity, 7 focused tests, Android compilation, and Apple device build passed. This revision has not been installed on devices.

### 2026-09-24 — Tracker heading and Android system-unlock handoff

Android's main status heading and both Apple main-screen status layouts now say Tracker. Android still implements MQTT coordination and Tracker hard-failure fallback; this label change does not remove that support. Apple uses Tracker coordination without an equivalent MQTT transport.

Android preserves a pending system-unlock handoff and its original lock timestamp when onStop and ACTION_SCREEN_OFF both report the lock. Previously the second report erased the handoff and caused another app credential prompt. Regression coverage includes both notification orders, repeated notifications, stale authentication, still-locked devices, and explicit invalidation. The focused OrganizationAccessPolicyTest, R2CViewCoordinatorStatusTest, and DefaultPeerCoordinatorTest suites passed, including Android compilation. Apple lock handling was audited; its protected-data notification path does not share this defect and remains unchanged. Apple label changes were source-reviewed only. Connected A5 R52Y90C9XST logs show screen-off at 17:53:48 and app authentication at 17:54:11, consistent with the symptom but insufficient to prove callback ordering. Changes are not installed; physical lock/unlock validation remains pending.

Requested installation completed as 2.3.6 (264): Android debug build and signed Apple device build succeeded; Apple strict signature verification passed with system trust-service access. In-place updates preserved app data. A5 R52Y90C9XST installation and cold launch succeeded; package query confirmed build 264 and update time 2026-09-24 18:15:04. Ken’s iPad 694108CB-8CBE-593D-ABE1-D9EDD947B901 installation and launch succeeded; device app query confirmed build 264. These builds include the current terms revision 2026-09-24.3. Build logs are `/tmp/r2c-tracker264-android-build.log` and `/tmp/r2c-tracker264-apple-build.log`. Physical lock/unlock retest remains pending; no store submission was made.

### 2026-09-24 — A5 system unlock notification delivery, build 265

The user's build-264 retest still left Protected access locked after OS authentication. Fresh A5 logs show authentication at 18:18:53.996, screen-off at 18:19:38.653, and resume waiting for system unlock at 18:19:45.690. The broadcast dump records USER_PRESENT at 18:19:45.717 from com.android.systemui UID 10058; the app's receiver was registered but absent from its delivery targets. RECEIVER_NOT_EXPORTED excludes privileged senders outside the system UID. The previous fix preserved session state but did not repair this delivery restriction.

The screen-lock receiver now uses RECEIVER_EXPORTED and remains restricted to SCREEN_OFF and USER_PRESENT, both protected OS broadcasts. Added a receipt diagnostic to distinguish delivery from session acceptance. Android documents this requirement at https://developer.android.com/develop/background-work/background-tasks/broadcasts; protected actions are declared in https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/res/AndroidManifest.xml. Apple does not use this receiver path and remains build 264.

All 20 OrganizationAccessPolicyTest cases passed; debug build succeeded. Build 2.3.6 (265) installed in place on A5 R52Y90C9XST, package update time 18:23:20. Evidence: `/tmp/r2c-a5-unlock265.log`, `/tmp/r2c-a5-broadcast265.txt`, `/tmp/r2c-a5-unlock265-build.log`. Another physical OS lock/unlock test is required to confirm delivery and disappearance of the panel.

### 2026-09-24 — Quiet Android unlock transition, build 266

The user confirmed build 265 resumes after OS authentication but briefly flashes the locked dialog. Fresh A5 logs show resume waiting at 18:30:07.400, USER_PRESENT receipt at 18:30:07.496, and accepted system unlock at 18:30:07.497. This physically confirms the receiver fix while identifying a 97 ms presentation gap.

Android now uses a distinct waiting presentation with an opaque theme surface and no dialog while the existing authenticated session awaits system unlock. Protected content remains hidden until authentication is accepted. A resume-scoped 750 ms fallback restores manual unlock controls if the notification is absent; it does not grant access or discard the handoff. Stopping the activity or accepting the unlock cancels that fallback. All 20 authentication policy tests and the Android debug build passed; log: `/tmp/r2c-a5-unlock266-build.log`. Apple has no Android broadcast handoff and remains build 264. Physical visual retest is still required.

Build 266 was installed in place on A5 R52Y90C9XST; package query confirmed 2.3.6 / 266 and update time 18:32:36. Cold launch succeeded. App data was preserved; visual lock/unlock confirmation remains with the user.

### 2026-09-24 — Retain Android page composition during system unlock, build 267

The user confirmed build 266 suppresses the locked panel, but the Main Screen still refreshes. Source inspection shows the access gate's early return disposes the entire protected page even for WAITING_FOR_SYSTEM_UNLOCK. Build 267 keeps the existing page composition mounted during that state and presents an opaque, secure, full-screen modal cover last. Back navigation and pending captured-video opening are deferred; operational dialogs are withheld until unlock acceptance. Cold launch, explicit lock, and timeout recovery still use the ordinary locked gate. Existing authentication policy is unchanged. Page mount/dispose diagnostics now allow the physical retest to distinguish recomposition from actual teardown.

Android build and 20 authentication policy tests passed. Build log: `/tmp/r2c-a5-unlock267-build.log`. These unit tests validate authentication policy, not Compose retention or visual smoothness; a physical lock/unlock retest remains required. Apple uses a separate obscuring path and remains build 264.

A5 R52Y90C9XST in-place installation and cold launch succeeded. Package query confirmed 2.3.6 / 267, updated at 19:08:23; app data was preserved. Visual continuity and mount/dispose evidence across a user-performed lock/unlock remain pending.

### 2026-09-25 — Apple adaptive live-video buffering

Apple's native live path now queues decoded frames and matching camera telemetry, using Android's shared native adaptive timing functions and buffer-target tuning. Android behavior is unchanged. Apple adds bounded retained-surface memory and uses display callbacks for presentation; local recordings and AVFoundation HLS fallback keep their existing pacing. Buffer/dequeue/display-submission diagnostics distinguish source timing from display backpressure. Portable/native/Swift tests and application builds are tracked in `docs/validation/2026-09-25-apple-buffered-live-video.md`; physical iPad smoothness and cross-platform field equivalence remain unverified.

### 2026-09-25 — Apple display callback throughput correction

Build 268 field diagnostics exposed sustained overflow despite a 700 ms adaptive target. Apple now coalesces overdue presentations against the same adaptive timeline, relocks substantial source-rate changes promptly, and reduces redundant SwiftUI publications. Presentation skips are explicitly separate from memory-overflow drops. This differs deliberately from Android's independently timed rendering worker; shared target and rate-adjustment functions remain unchanged. Callback-jitter regressions and limits are documented in `docs/validation/2026-09-25-apple-render-callback-correction.md`.

Build 269 was installed in place and launched on Ken’s iPad. The user tested it and reported “That was fantastic.” This is operator confirmation of the tested playback improvement; broader cross-platform field equivalence remains unverified.

### 2026-09-25 — Bounded video telemetry research capture

Android and Apple share first-20-per-layout SEI discovery capture with 64-layout and 2 MiB text caps, complete chunked payloads, summary counts and off/on reset. Both preserve original MediaMTX recordings when research capture has been enabled; Apple retains a sibling pre-normalization MP4 and Android retains application-private fragments. Capture covers H.264 SEI, not every RTMP control/data message, and does not decode AirSense reports. Validation and field instructions: `docs/validation/2026-09-25-sei-discovery-capture.md`.

### 2026-09-25 — Android video frame notifications

Android stops publishing every displayed frame into Compose state; only explicit pending clue captures request a frame-driven retry. Exact frame counts and native frame/camera association are retained. Apple already separates exact counts from throttled UI updates. Focused tests and build passed; physical A5 playback improvement requires retest. See `docs/validation/2026-09-25-a5-playback-ui-correction.md`.

### 2026-09-25 — Android field builds without coverage probes

A5 live profiling found extensive main-thread work in JaCoCo probes. Device coverage is now opt-in; unit-test coverage and release settings are retained. Build 273 was verified free of probe strings in all DEX files and installed in place on the A5 Pro. Apple is unaffected by this Android build setting. Playback improvement remains pending retest; see `docs/validation/2026-09-25-android-field-build-coverage.md`.

## 2026-09-25 — App acknowledgement and software license separation

Android and Apple now show a 247-word operational acknowledgement, version 2026-09-25.1, with individual acknowledgement and no bespoke software release or fee-based cap. Both expose the complete unchanged Apache license and notices offline and the existing platform-specific privacy information. The main menu's read-only terms view uses the same screen. Hosted-service contracting is separate; a review draft was prepared without changing or deploying the tracker intake flow.

## 2026-09-26 — 2.3.6 (285) release preparation

Both platforms retain complete admitted track geometry with a shared one-second recording gate and latest-only CalTopo LiveTrack updates spaced at least five seconds after completion. Routine position metadata logs and RTMP acknowledgment chatter were removed; shared relay timing now summarizes every 60 seconds, with slow reads reported at most every five seconds and a final failure summary. Android no longer logs each native relay line twice, drops per-point UI messages, and uses the same 60-second/five-second limits for its FFmpeg processing diagnostics. Apple retains its existing native diagnostics; Android-specific FFmpeg stage counters remain intentionally platform-specific. Apple build 284 field testing confirmed post-flight DCP cleanup, uninterrupted local video during WAN failure, final 104-point track, and successful delayed clue publication; the operator removed that clue after testing and subsequently reported another successful outage clue test and five-second-or-longer LiveTrack spacing. Build 285 full gates and final-candidate physical checks are tracked in outputs/release-2.3.6-285.
