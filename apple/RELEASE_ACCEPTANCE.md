# Apple release acceptance

Use this matrix with the procedural [mobile release runbook](../RELEASE.md).
The runbook controls versioning, build/export/upload, tagging, and evidence;
this document defines the Apple physical acceptance threshold.

Every release is a **no-go** until every required row below is green for the
exact candidate build. Evidence summarized in the table is a baseline and must
be refreshed when affected behavior or dependencies change. Simulator coverage
is evidence for shared logic and layout, but it is not a substitute for radio,
camera, networking, performance, or background testing on Apple hardware.

## Automated and Simulator gate

| Capability | Android-aligned operator contract | Current evidence | Status |
| --- | --- | --- | --- |
| Configuration import | Import Android `R2C2`, `R2CFAA1`, and `R2CMA1` QR/config payloads; R2C2 applies team settings and redeems a unique tracker device credential. | Cross-language vectors, bundle tests, paste/custom-URL flow, and Simulator UI are green. | Green except physical camera/Drive proof |
| Remote ID core | Decode ASTM Basic ID, Location, System, and Message Packs and apply Android filtering, canonical IDs, aging, and archive policy. | Shared tests and live synthetic Simulator observations are green. | Green |
| Bluetooth scanning | Start automatically, expose scanner state and aircraft details, and produce Android-shaped diagnostics. | A real iPad flight produced 48 accepted Bluetooth waypoints to approximately 333 m and a completed local archive; background/lock continuity remains unqualified. | Foreground green; background physical gate |
| Wi-Fi Remote ID | Receive Wi-Fi Beacon/NAN aircraft through the DS110 Bluetooth bridge. | The app uses the normal Bluetooth parser and intentionally has no UDP compatibility listener. | Physical DS110 flight gate |
| Map and tracks | Show aircraft/operator positions, heading, trails, range/bearing, closest-pair separation, and Android-compatible GeoJSON archives. | Physical iPad GPS reached 2 m reported accuracy and a real Bluetooth flight archived successfully; the new initial operator-follow behavior still needs visual field confirmation. | Green except final visual follow check |
| NOTAM/TFR | Authenticate with imported FAA configuration, retain last results across transient failure, show freshness/restriction status and details, and render operational geometry. | Parser/policy tests and deterministic Simulator overlays are green. | Credentialed physical-location gate |
| FAA facility map | Query Android's one-mile operating area and report airport, airspace class, ceiling, LAANC availability, and authorization status. | Query/parser/policy tests, Simulator UI, and full app compilation are green. | Physical-location gate |
| Offline terrain | Download Android-compatible USGS one-degree DEM tiles and sample GeoTIFF elevation directly before online/cache fallback. | Direct GeoTIFF parser/sampling test and full app compilation are green. | Physical downloaded-DEM spot-check gate |
| Tracker/CalTopo | Automatically request Save/Ignore once per active flight, match organization-scoped `/<designator>/ws/r2c` ownership/relay behavior, publish/stop Android-shaped CalTopo tracks, and upload/replay eligible completed archives. | Automatic current-flight confirmation compiles in the signed iPad build; two-zone tracker Simulator run, signed-request tests, and Android-compatible archive-upload policy/request tests are green. | Credentialed physical gate |
| Proximity alerts | Match threshold, ownership/team eligibility, predictive head, speech/haptic, Map, Suspend, Resume, and delayed clear behavior. | Shared tests and tracker-backed Simulator exercises are green. | Physical controlled-spacing gate |
| Video/MediaMTX | Run MediaMTX in-process, accept controller RTMP, and render local HLS video with reconnect behavior. | Native ABI, Simulator RTMP/HLS loopback, and signed arm64 packaging are green. | Physical network/thermal gate |
| Multi-stream/external display | Admit up to four controller streams, expose focus/grid/capacity state, and route app-managed content and alerts to an attached display. | Registry integration and Simulator layouts are green. | Physical multi-publisher/display gate |
| Backup/mutual aid | Encrypt and restore local or iCloud configuration; automatically debounce iCloud updates; exchange Android-compatible incident profiles, cached tiles, and DEM data safely. | Archive/profile tests, entitlements, and Simulator UI are green. | Physical iCloud and Android-to-Apple round-trip gate |
| Captured-video review | Replace the active local movie safely; provide Back/Run/Pause/Step/scrub, pause-on-open, detector boxes, annotation summaries/clear, and durable Android schema-v2 annotations. | Full app compilation and deterministic review UI are green. | Physical Files/video/sidecar round-trip gate |
| Anomaly detector | Offer persisted Off, Color, Target Colors, and Infrared modes plus Android-shaped advanced controls; keep video alive when Off; align overlays and bound analysis drops. | Native regression/realtime suites and sustained Simulator runs are green. | Physical quality/performance gate |
| Logs and status | Provide Android-like Status, copyable diagnostics, daily log selection/package/share, and exclude credential secrets. | Swift tests and visually reviewed iPhone/iPad Simulator UI are green. | Green except physical share-sheet proof |
| Signing/package | Universal iPhone/iPad arm64 app, privacy manifest, release entitlements, and App Store profile. | Verified distribution-signed IPA and nine-stage release gate are green. | Green |

## Required physical-device run

Use a supported iPhone or iPad with an Android comparison device and retain the
resulting diagnostic bundle, screenshots, and test notes. Complete a copy of
`DEVICE_QUALIFICATION_REPORT.md` for each Apple device and exact TestFlight
build; an untested or unevidenced required row remains a no-go.

1. Install a Release/TestFlight build and accept Bluetooth, Local Network,
   Location, and Camera permissions.
2. Scan Android-generated organization, FAA, and mutual-aid QR payloads as
   applicable; include one real public Drive-hosted bundle.
3. Compare a known ASTM Bluetooth transmitter side-by-side with Android,
   including foreground updates, lock/background behavior, and recovery.
4. Exercise a Wi-Fi Beacon/NAN aircraft through the DS110 wireless relay and
   compare the bridged Bluetooth ID and position with Android.
5. Verify physical GPS position, range/bearing, MapKit layout, track completion,
   Files export, and the system log-sharing sheet.
6. Publish a controller stream to the displayed RTMP address. Exercise Off,
   Color, Target Colors, and Infrared plus the advanced settings against representative visible and thermal fixtures and
   record frame counts, drops, CPU, memory, battery, and thermal state.
7. Back up to iCloud, change a harmless setting, confirm the automatic update,
   and restore on a second signed-in device using the passphrase. Review a local
   movie, replace it with another selection, save annotations, and round-trip
   the exported sidecar.
8. Join the real tracker and a disposable CalTopo test map with Android and
   Apple devices. Exercise ownership handoff, non-owner relay, reconnect, Save,
   ordered publication, and track stop.
9. Run a controlled two-aircraft spacing exercise and verify speech, haptic,
   Map, Suspend, Resume, predictive head, delayed clear, and non-owner silence.
10. Inspect every primary screen in portrait and landscape at the supported text
   sizes. Confirm Android-equivalent control names and workflow order remain
   recognizable while native iPhone/iPad navigation remains usable.

## Store release gate

Before upload or release:

1. Create and review `release-notes/<version>/whats_new.txt`, covering shared
   changes, platform-specific changes, and known platform differences. Run
   `tools/sync_release_notes.sh <version>` to update store metadata.
2. Run `apple/release-check.sh` with that marketing version from a cleanly
   understood worktree; the gate verifies the versioned release notes and the
   shared protected-land source catalog. Catalog network verification is
   limited to one attempt per seven days unless explicitly forced.
3. Publish the reviewed `PrivacyPolicyApple.md` and verify the public URL.
4. Complete category, age rating, App Privacy, review contact/instructions,
   screenshots, territories, price, export compliance, and DSA status in App
   Store Connect.
5. Upload a newly numbered build and let App Store Connect finish processing.
6. Run the physical-device matrix above using that exact build through
   TestFlight.
7. Submit for App Review only after all evidence is green. Release publicly
   only after approval and one final smoke test of the approved build.
