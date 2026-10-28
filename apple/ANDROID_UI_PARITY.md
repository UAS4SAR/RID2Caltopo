# Android / Apple Operator UI Parity

This is the working UI contract for the Apple port. Android remains the
reference unless an iOS platform restriction or a documented native convention
requires a different presentation. A native sheet or menu is acceptable; a
different label, default, placement, gesture, or state transition is not.

## Current parity

| Area | Android contract | Apple status |
| --- | --- | --- |
| Main Screen | Incident / operational period header, restriction status, aircraft table, confirmation flow with persistent field labels | Implemented. The Apple table includes BT4, BT5, WiFi, NaN, R2C, Total, duration, and RTT columns; drone confirmation fields remain visibly named while populated or edited. |
| Startup | Standalone, Training / Op 1; credentials remain available but no incident is restored | Implemented. |
| Live View navigation | Stream, map, split, and PiP without an unsolicited layout change when a stream arrives | Implemented. |
| Stream focus | One stream fills its pane; tap focuses/restores grid; long press pairs telemetry or resizes PiP | Implemented. |
| Stream grid | One column for two streams, 2 x 2 for three or four, no scrolling | Implemented. |
| Stream chrome | Designator, measured lag, telemetry, selectable Decimal/UTM/USNG coordinates, pairing state, focused-tile settings | Implemented. |
| AD lifecycle | Starts Off with canonical realtime defaults; mode changes select Android's fixed/adaptive defaults; adjustments last only for the process session | Implemented. |
| AD menu | Gear, `AD Mode`, `AD Help`, restart, close, troubleshooting diagnostics | Implemented; Apple also exposes native decoder performance diagnostics. |
| AD scan and ROI display | Scan-zone and small-target controls with 16:9 preview; optional cyan scan/target guides; detections retain native color, weight, rectangle/crosshair, and hottest-region ring | Implemented. Detection ROIs remain visible when guide boxes are disabled, matching Android. |
| Map controls | Layer, Predictive Head, Download Map, Map Folders, Map Management; nested Map Management and Bad Tiles items retain Android order | Implemented in the same order; Apple-only cache diagnostics follow the parity controls. |
| Map gestures | Operator pan stops automatic centering; follow focused drone is explicit; north-up remains reachable | Implemented. |
| Map items | Nested folder visibility, active Drone Tracks, one hidden same-day archive folder, and CalTopo artifacts with source opacity and CalTopo-rendered marker icons | Implemented. |
| Aircraft appearance | Active/archive track colors and bearing-line preference | Implemented. |
| Tablet marker | Unlabeled antenna, one-statute-mile ring, device-name and tracker-health callout on selection | Implemented. |
| Safety overlays | Airspace, NOTAM/TFR, protected-land state and map overlays | Implemented. |
| Video clue | Double tap captures the displayed viewport; empty focused title, Android report template/telemetry, `Local Marker Only`, and `Submit` | Implemented. |
| Quit / idle shutdown | Main Screen menu confirmation and automatic close after the configured maximum idle time | Implemented with UIKit scene destruction after orderly operational cleanup. |

## Functional UI deltas still to close

These are not presentation-only changes; Apple needs equivalent runtime state
or diagnostics before the corresponding controls can be honest.

- Android Settings exposes minimum movement distance, new-track delay, bridge
  check distance, audio alarm volume/test, editable proximity spacing, and
  standalone R2C coordination. Apple currently receives only part
  of this policy from imported organization configuration.
- Android's `Proximity Pairs` panel lists every evaluated aircraft pair and its
  horizontal, vertical, and 3D separation. Apple currently exposes the active
  warning but not the engine's complete debug-pair list.
- Android exposes Person Relevance controls backed by its bundled LiteRT model.
  Apple does not yet have an equivalent iOS inference asset/runtime, so it does
  not show a placeholder control that cannot affect detector behavior.
- Android supports captured-video playback and annotation. Apple records
  incoming streams and supports live clues, but captured playback remains a
  separate lower-priority workflow.
- Android exposes archive-folder cleanup from the Main Screen menu. Apple
  exposes app files through Files but does not yet provide the same in-app
  selective cleanup panel.

## Documented platform differences

- Cloud settings backup uses iCloud Drive on Apple and Google Drive on Android.
  Settings backups exclude access credentials. Both platforms retain map tile
  package imports/exports and tracker-based enrollment. The separate manual
  encrypted settings-file export/import workflow is removed.
- iOS does not expose the Wi-Fi RSSI Android uses for controller-strength
  percentage. Apple reports SSID, video health, and telemetry loss instead.
- iOS may suspend processing while locked or backgrounded. Bluetooth
  restoration is best effort under the operating-system policy.
- Apple closes the primary UIKit scene rather than calling `exit`; the system remains responsible for ending the process.
- Native share/file pickers follow the operating system. Mapping screens must
  retain Android’s labels, field/action order and navigation; platform widget
  styling is not permission to invent a separate workflow.

## Audit rule

For every future Apple UI change, compare the Android screen first and record
any intentional exception here. Release notes must summarize both newly closed
parity gaps and the remaining operator-visible differences.


## Mapping implementation checkpoint — 2026-10-13

Apple now has the standard brush/tree crossed nadir / −70° route, matching
Android route fixtures, storage estimates with the 80% warning threshold,
Files/SD-card original intake, paired LAN browser transfer from the controller,
and foreground on-device RTK reconstruction. Processing uses bounded regions,
disk checkpoints, 0.4 m meshes, a 2D tile pyramid and streaming ZIP64 result
packaging. The shared native adjustment/rendering core is used on both platforms.

Both platforms show elapsed time independently of worker callbacks, report real
file/byte progress during saving, and identify the currently selected 3D region.
Apple's single Local maps list owns plans, originals and results. Android's list
now explicitly distinguishes loading from empty and avoids restarting its disk
scan on every processing callback. Regional 3D viewing is one region at a time;
it is not a simultaneous whole-assignment 3D scene.

Release qualification must remain independent of physical devices. The Apple
gate runs Android mapping host tests, portable native reconstruction tests,
Swift mapping/intake/checkpoint/archive tests, a temporary iPad simulator that
renders two distinct regional models, and an unsigned device archive. Run
`apple/release-check.sh`; native dependencies must first be built when using
`--skip-native-rebuild`. Do not substitute A5 Pro or iPad interaction for a gate.

Remaining mapping parity work is explicit: native Pilot 2 HTTP intake is
implemented in local build 347 and awaits real-controller qualification.
Apple currently imports SD-card files into its application storage instead of
processing in a user-selected external workspace; Android's non-RTK preview
paths are not yet mirrored. General mutual-aid import retains its
existing in-memory size limit even though mapping export streams ZIP64. These
items prevent describing this checkpoint as complete mapping feature parity.


## Mapping UI correction — 2.5.1 (344)

Android is the reference: Map pane → Settings (gear) → Local maps is the single
library. It contains New flight, assignment selection, newest/oldest ordering,
mission summary cards and Open mission. The mission screen orders Flight plan,
Captured images, Map results, completed/all-original reconstruction choices,
and Back to local maps. Delete-originals confirmation is explicit.

Define Map Mission follows Android's map/assignment, storage, aircraft, camera,
resolution, capture pattern, canopy height, estimates, route details and Pilot 2
connection order. All five presets are present with the same −45° preview export
restriction. The result inspector has 2D, 3D, sharing and target-region fade
controls. Reconstruction uses the same heading/stage/elapsed/activity/cancel
layout. Files/SD intake and native sharing remain platform-specific pickers.

Build 344 used browser HTTP intake. Local build 347 replaces that connection
with Android's shared Open Platforms page and native Cloud/MQTT/photo services.
Actual DJI license verification and controller interoperability still require
local acceptance; the host protocol tests are not a substitute.


## Correction after local testing — 2.5.2 (345), not approved for distribution

Map Folders → area assignment → Map is a required planning shortcut. It opens
the same Define Map Mission screen used by Local maps → New flight. Consolidating
the mission/results list must not remove this shortcut. The earlier statement
that Local maps must be the sole creation entry point was incorrect.

Build locally, allow user testing, and obtain explicit approval of the tested
build before any TestFlight upload or other distribution. Automated checks do
not constitute acceptance.

The assignment Map shortcut is restored on Android too. Both platforms show it
for area assignments and open their existing Define Map Mission planner with
the chosen assignment. Local maps continues to own the single mission/results
list. Source audit and Android host tests passed; local operator testing remains
required before approving distribution.


## Local native Pilot 2 candidate — 2.5.2 (347)

Both planners use **Test Pilot 2 using HTTP** and **Pilot 2 connection settings**.
The connection instructions are **Pilot 2 → Cloud Services → Open Platforms**;
the exact same `dji-cloud` HTML/JavaScript assets ship on both platforms.
Apple now hosts the DJI workspace/wayline/media/storage APIs, WebSocket/MQTT
registration, and SigV4 MinIO single/multipart original uploads. Completed,
verified groups hand off to on-device reconstruction once per selection;
manual reconstruction remains available after a failed or cancelled attempt.
The iPad credentials live in a device-only Keychain item. Android's protected
credentials cannot be transplanted as ciphertext; iPad setup is required.

The 1 cm/px + 30 m canopy case is invalid (about 29 m flight height, below the
required 45 m). Both planners show the required height and minimum resolution.
Android's oversized action footer was removed so feedback stays in the scroll
content and the buttons remain usable. Clearance/qualification limits remain.

This is a **local test candidate**, not a released or field-qualified build.
HTTPS certificate provisioning on Apple remains unimplemented; the matching
HTTP diagnostic workflow is the scope of this candidate. Apple must remain
foreground for controller intake and reconstruction. Other outstanding mapping
parity gaps listed above still apply. No TestFlight upload is authorized.


## Automatic protected setup — local build 348

Apple now enrolls a device-only Keychain RSA key and automatically imports a
recipient-encrypted DJI deployment envelope from its private application
container. Administrator provisioning can reuse an already configured Android
tablet: its local deployment helper seals the existing credentials for the
pinned iPad public key. Only ciphertext transits the Mac. Manual credential
entry remains a fallback, not the normal operator workflow.

`tools/mapping/provision_pilot_ipad.py` performs the explicit local deployment
and verifies the iPad's accepted-envelope hash and configured status. The Android
helper is in the instrumentation APK and is skipped without an explicit admin
argument; it is not part of the release APK or automated release qualification.
Host tests verify recipient binding, authenticated encryption and tamper rejection.
Build 348 is installed on the local iPad. After reconnecting the A5 Pro, the
encrypted transfer completed and the iPad confirmed both the exact accepted
envelope hash and configured Keychain status. No manual credential entry was
needed. Nothing uploaded.

## Local final-verification review — 2.5.2 (396), October 19

Recent mapping changes were checked against the Android and Apple sources and
full host suites. Both expose the same GeoTIFF preparation/sharing labels,
assignment naming, full-resolution default and unchecked `Limit to 40 MB for
CalTopo` option, with matching compressed-size fallback, reduction reporting and
prepared-result invalidation. Both planners preserve capture/transit defaults,
units, camera profiles and clearance rules while preparing estimates off the UI
thread. Native share/file controls retain their OS presentation.

Full evidence and remaining operator checks are in
[the final verification record](../docs/validation/2026-10-19-final-verification/README.md).
This supersedes earlier mapping implementation checkpoints where they describe
features subsequently completed; unrelated historical parity gaps remain separate.
