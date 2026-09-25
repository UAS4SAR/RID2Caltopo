# RID2Caltopo for Apple platforms

This directory contains the Apple-platform implementation of RID2Caltopo. Open
`RID2CaltopoApple.xcodeproj` to run the universal SwiftUI shell on an iPhone or
iPad Simulator.

The initial architecture keeps platform radio and UI code thin around portable
contracts and native cores:

1. CoreBluetooth produces `RidObservation` values, including Wi-Fi Remote ID reports bridged by the DS110.
2. Shared tracking policy consumes observations and publishes accepted updates.
3. The verified MediaMTX Go core receives controller RTMP and exposes RTSP and HLS locally.
4. FFmpeg demuxes RTSP and VideoToolbox feeds an adaptive FIFO of `CVPixelBuffer` frames with matching camera telemetry. Live display uses Android's shared buffer-target and render-interval functions; AVPlayer HLS remains a fallback.
5. SwiftUI and Metal present operator state, video, and anomaly annotations.

`RidObservationProvider` is the radio boundary and `MediaServerController` is
the in-process Go server boundary. Both use `AsyncStream`, which keeps their
worker threads and goroutines out of SwiftUI.

Build both native dependencies before opening the app project on a fresh checkout:

```sh
apple/Native/MediaMTX/build-xcframework.sh
apple/Native/AnomalyCore/build-xcframework.sh
apple/Native/FFmpeg/build-xcframework.sh
```

The Swift package can be built and tested without the application target:

```sh
cd apple
swift test
```

The primary pre-release gate is:

```sh
apple/release-check.sh
```

It rebuilds the current MediaMTX, anomaly, and LGPL FFmpeg XCFramework device/Simulator
slices, checks their exported C ABI, runs the portable anomaly regression and
realtime qualification suites, runs all shared Swift tests, performs a clean
arm64 Simulator link, and verifies a fresh unsigned arm64 iPhone/iPad archive. Use
`--skip-native-rebuild` only for an intentional fast rerun after the native
artifacts have already been rebuilt from the unchanged source trees.

The gate also validates the shared protected-land source catalog. Network
verification is attempted at most once every seven days and its state is kept
under the ignored root `.release-state/` directory, so repeated release checks do not
contact upstream GIS services. Debug builds never run this check. Pass
`--force-land-catalog-refresh` only when an immediate recheck is intentional;
temporary upstream failure retains the checked-in last-known-good catalog and
prints a release warning.

Radio, background behavior, controller networking, and performance must be
qualified on a physical device even when equivalent Simulator tests pass.

Choose **Import Config** and scan the managed enrollment QR from
`r2c-tracker.com`. The app exchanges its signed, limited-use locator for a
revocable device credential and stores that credential in Keychain. Configure
organization and aircraft under Settings → Organization & RID mappings; the
drone designator is derived from owner callsign and model. `R2C2:`,
`R2CFAA1:`, `R2CMA1:`, and file imports remain available only for migration and
mutual-aid compatibility. Camera scanning and live credential redemption remain
physical-device field-test gates.

The app owns a `CLLocationManager`, requests When In Use authorization at
startup, publishes accuracy/error state on the Status screen, and renders the
operator using MapKit's user annotation. This is the Apple foundation for
operator-relative mapping and proximity behavior; physical-device accuracy and
background policy remain part of the hardware gate.

For a Simulator stream smoke test, launch the app with `--start-mediamtx`,
`--start-video`, and `--show-anomaly`, then publish a source to
`rtmp://127.0.0.1:1935/demo`. The live view renders the HLS player and overlays
normalized detector boxes on the same decoded raster. It also reports decoded
frames, analyzed frames, backpressure drops, and current anomaly boxes.
The detector control matches Android's high-level appearance choices: **Off**
keeps video connected without spending analysis CPU, **Color** runs the shared
fresh-RGBA Color Uniqueness detector, and **Infrared** runs the shared thermal
detector. Apple 1.2 also exposes **Target Colors** and persisted advanced
controls for motion/saliency, sensitivity, scan zone, cadence, registration,
thermal polarity/delta, color candidates, guide boxes, and detector diagnostic
overlays. The choice can be changed from the main Anomaly Detector section,
Live View, or Advanced Anomaly Settings and is included in the copyable Status
report.
On physical hardware, the Anomaly Detector section displays the device's
copyable `rtmp://<wifi-address>:1935/demo` controller target.

For a deterministic Remote ID map smoke test, launch with `--demo-rid` and
`--show-map`. Two synthetic aircraft exercise the same `RidTrackStore` actor
used by live Bluetooth observations, including track polylines and current
aircraft annotations on both iPhone and iPad. The operational map is backed by
`MKMapView` and supports OpenStreetMap or ArcGIS imagery, optional USGS contour
tiles, disk-backed visible-tile caching with an offline-only mode, signed
CalTopo marker/line/polygon snapshots with searchable nested folder and
individual-item visibility, including orphan grouping and parent-linked media
behavior. Visibility choices persist per map. Android's map-local gear exposes
Layer, Predictive Head, Download Map, Map Folders, and Map Management in the
same order. Map/video supports map-only,
video-only, split, and primary/inset layouts, and CalTopo snapshots are cached
per map for offline reuse. Apple 1.2 queries nearby NOTAM/TFR data using the
imported FAA configuration and renders status, age, details, and colored
point/line/polygon restrictions. It also queries the same one-mile FAA UAS
Facility Map operating area as Android and reports airport, class, published
ceiling, LAANC availability, and authorization status.
The map menu also provides Android-shaped region preparation for the current
viewport or a selected CalTopo line/polygon, including the Overview, Ops, and
Full Detail presets, optional contours and USGS 1-degree DEM downloads,
estimates, progress/cancellation, cache size/age maintenance, and bad-tile
quarantine/export. Long-pressing a cached bad tile opens Android's remove and
same-hash quarantine confirmation. Downloaded GeoTIFFs are sampled directly for terrain/AGL,
with the point-sample cache and USGS service retained as fallbacks.
Confirmed pilots also have Android-compatible local display preferences for
active and archive track colors and the optional viewport-edge bearing line;
tap a confirmed aircraft to edit or reset those settings.
MediaMTX publisher events admit up to four simultaneous controller streams.
Live Streams provides a focused, status-labeled grid, while the focused stream
feeds map/video presentation and clue capture. Stream tiles support tap focus,
long-press telemetry pairing, bounded 1x-4x pinch/pan, and double-tap clue
capture; snapshots preserve the current zoomed framing. Tile settings expose
close/restart actions and decoder/anomaly performance diagnostics. Stream
designators use Android's red/yellow/green telemetry-pairing state, and a
mismatched manual selection requires explicit confirmation. Settings can
use OS mirroring or
an app-managed attached display with streams, map, split, or observer content
and configurable alert routing.

Backup & Transfer creates a passphrase-encrypted local configuration file and
restores it through the document picker. It can also maintain an encrypted
latest backup in the app's iCloud Drive container; automatic updates are
debounced, and the passphrase is retained only in that device's Keychain.
Files import/export remains available for iCloud-only, local, removable, or
installed third-party document providers. The map menu's Export MA Package
workflow produces Android-compatible ZIP packages from cached viewport tiles
and DEM data; importing one installs its expiring incident profile and offline
map content after validating archive paths and package format.
Captured Video Review opens one movie at a time with replacement-safe local
staging, Back/Run/Pause/Step/scrub controls, optional pause-on-open, local
detector guide boxes, verdict summaries, and clearable paused-frame annotations.
Reviews preserve Android schema-v2 anomaly box/debug fields under Application
Support and can be exported through the share sheet.
Aircraft labels use Android's collision-aware name/status layout and expose a
combined telemetry and pilot-display sheet when tapped. Predictive Head and
Follow Focused Drone are available from the map menu. ATO, terrain-adjusted
AGL, and range from the first accepted takeoff fix use the same calibration
rules as Android. USGS terrain samples are cached for a year, stale fallback
values carry `?`, the detail sheet supports Android's manual 50-foot ATO/AGL
calibration, and aircraft turn yellow/red at 180/200 feet AGL.

The video pane's camera button captures the latest decoded frame and opens the
clue sheet. One-stream operation defaults to the focused aircraft (or the first
active aircraft), while the sheet allows a different active aircraft to be
selected. It shows the snapshot, drone and projected clue locations, heading,
AGL/ATO, and an Android-compatible -90° to 0° gimbal control. Operators can add
the clue only to the local R2C map or save locally and submit to CalTopo.

Clues are always persisted before any network request. Full JPEGs, thumbnails,
metadata, and upload state live in `Documents/RID2Caltopo/Clues`; local camera
markers remain visible after restart and support preview, sharing, deletion,
and manual retry. CalTopo photo submission uses stable marker/media IDs and
Android's signed Marker, backend-media, media-data, and MapMediaObject request
sequence. Failed uploads retry with bounded exponential delay and resume after
restart or credential reconfiguration. `--demo-rid --show-map
--demo-clue-sheet` and `--demo-local-clue` provide deterministic Simulator UI
qualification without a live camera stream.

Track completion writes Android-compatible GeoJSON into
`Documents/RID2Caltopo/Tracks/YYYY-MM-DD`. File Sharing and opening documents
in place are enabled, so operators can recover these archives through Finder
or the Files app. `--archive-demo` provides a deterministic Simulator smoke
test when used with `--demo-rid`.

Live CalTopo publishing is disabled by default. The in-app CalTopo screen stores
the credential secret in the Apple Keychain and non-secret map settings in
`UserDefaults`. When explicitly enabled, accepted track points create the same
signed `LiveTrack` object and use the same `position/report/DRONE` endpoint as
Android. A configured CalTopo Connect Key replaces `DRONE` in that endpoint;
an empty key preserves the legacy endpoint. When a local aircraft track ages
out, the app saves its GeoJSON and
issues the same signed `LiveTrack/{id}` DELETE used by Android, treating 400/404
as an already-completed stop. Real-server qualification still requires an
operator-supplied test map and credential.

Apple receives Remote ID through CoreBluetooth. A DS110 configured for wireless
relay bridges aircraft Wi-Fi Beacon/NAN reports into that Bluetooth intake; the
former Android-to-Apple UDP compatibility relay is intentionally not included.

The Apple app keeps daily diagnostic logs under
`Documents/RID2Caltopo/Logs`. **More > Send app log to Ken…** provides the same
operator flow as Android: select one or more days, package the logs, and choose
a destination using the system share sheet. The bundle includes useful app,
OS, hardware, network, decoder/scanner state, and Android-shaped `rid_rx`
evidence for accepted Remote ID waypoints, but never the CalTopo credential
secret. **More > About & Privacy** explains the behavior in-app, including that
shared diagnostics may contain Remote IDs and aircraft positions.

The primary screen follows Android's operator hierarchy as closely as practical
in SwiftUI: an incident/configuration summary first, followed by scanner and
Nearby Aircraft status, with Live View, log forwarding, Status, Import Config,
Settings, and About & Privacy in the More menu. Like Android, **Status** opens a
copyable report with build, scanner, DS110 bridge posture, location, loaded QR
configuration, tracker, CalTopo, MediaMTX, video, and persisted-drone-mapping
state; tokens and credential secrets are deliberately omitted. The primary
screen includes an Android-inspired Nearby Aircraft list. Each row
shows Remote ID, per-transport accepted counts, signal bars/RSSI, point count,
altitude, and range from the device when location is available. Tapping a row
opens the latest position, speed, heading, device-relative range and bearing,
reported operator position, accumulated distance, and transport breakdown.
The detail screen also provides **Confirm Drone**, using Android's organization,
pilot callsign, and drone-description fields and mapped-ID naming rule. Manual
confirmations are session-only; Remote ID mappings imported from an Android QR
configuration persist locally.

When tracker peer coordination is enabled, the app connects to the Android
tracker's `/<organization-designator>/ws/r2c` endpoint with `X-SAR-Token`
authentication. It sends the
same hello, heartbeat, first-sighting, sighting, lost, and confirmation payloads,
replays active state after reconnect, and displays link/peer status in the
incident header. CalTopo publication is allowed only after this Apple device
holds the current tracker lease *and* the operator has confirmed the drone
locally. Accepted points queue during handoff, then flush in order after the
same two-second stabilization delay used by Android. A non-owner relays its
sightings to the owner rather than publishing them independently.

With two or more active aircraft, Status also reports the closest pair's
horizontal, vertical, and three-dimensional separation. Apple now applies the
Android proximity threshold imported from the organization QR. An alert is
eligible only when the pair contains a confirmed team drone and at least one
aircraft has both the local tracker lease and a local Save confirmation. The
operator receives a visible warning card, speech and haptic warning, **Map** and
**Suspend** controls, and a visible **Resume Proximity Alert** control while the
pair remains inside the threshold. Alerts clear only after three seconds outside
the threshold. The Android-compatible **Predictive Head** setting is imported
from the organization QR and is also visible in Settings. When enabled, Apple
uses the last two samples to project horizontal and vertical motion forward by
up to two seconds before applying the same threshold; projected warnings are
identified in the alert readout and diagnostics.

See the root [mobile release runbook](../RELEASE.md) for coordinated Android and
Apple release control, and [TestFlight distribution](TESTFLIGHT.md) for tester
onboarding and Apple-specific field-test details.

The project is assigned to Apple Developer team `94UV79S6LR`. The TestFlight
script intentionally archives without signing and lets Xcode apply its managed
Apple Distribution certificate and App Store profile during export. This allows
device-free TestFlight packaging; a physical iPhone or iPad is still required
for development-install and field qualification.

Like Android's scanning service, the Apple app starts its Bluetooth Remote ID
scan automatically at UI startup; operators retain visible Stop controls. The
app declares only the `bluetooth-central` background
mode. iOS may wake it for Bluetooth central events, but background scanning is
slower and coalesces duplicate discoveries, so continuous Remote ID position
updates are not assumed until proven on hardware. MediaMTX, decoded video, and
anomaly processing remain foreground-only workflows.
