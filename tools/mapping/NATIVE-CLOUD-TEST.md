# Tablet-hosted Pilot 2 Cloud API qualification

Android R2C now provides a native Cloud API prototype: a Pilot 2 connection page, mission library, MQTT/WebSockets, and mission-scoped S3-compatible storage for original JPEGs. Services and returned photos stay on the tablet and LAN. No R2C/helper app is installed on the controller. The Mac is used only to build/install this development APK.

## One-time setup

1. Put the R2C tablet and RC Plus 2 on the same Wi-Fi, with client isolation disabled.
2. In R2C, connect the incident map, select a small assignment, and open **Map → Define Map Mission**.
3. Every complete app build includes the app-wide **Cloud API** app ID, app key, and basic license. A fresh installation imports it automatically into protected local storage. Ordinary operators do not enroll a tablet or enter developer credentials. **Pilot 2 connection settings** is for administrator setup/rotation; manual entry remains an administrator fallback. Mobile SDK credentials do not work here.
4. If available, import a dedicated HTTPS certificate chain and its PKCS8 private key. Enter its hostname; LAN DNS must resolve it to the tablet. R2C validates the hostname, dates, and matching private key, then encrypts the bundle. Importing a chain does not prove the controller trusts it.

The default certificate is locally generated for testing. Pilot 2's web page and native API, WebSocket, MQTT, and storage clients may make different trust decisions. If the page opens but native connections fail, qualify HTTPS trust before flight. Do not disable certificate checks. A dedicated hostname and publicly trusted certificate with LAN DNS is the intended field setup; route/photo transfer needs no WAN after provisioning.

Offline qualification must include WAN disconnected while the tablet and controller remain on the LAN: open the connection, verify license/modules, download a route, and return a small original photo set. Repeat after restarting Pilot 2 and R2C. A trusted certificate does not itself require internet for each transfer, but hostname resolution must remain local and DJI's installed firmware/license behavior needs this test. DNS-01 issuance and certificate renewal need WAN during administration, not during the mapping transfer. No public tablet exposure is needed.

DJI credentials and TLS private keys are encrypted with Android Keystore in private no-backup storage. They are absent from mission exports and connection diagnostics. Enter them locally; do not add them to the repository, screenshots, fixtures, or chat.

### Administrator: app registration build input

DJI registration is app-based, shared by all installations and both platforms.
Use the private `.local-deployment/dji-app-registration.json`, or set
`R2C_DJI_APP_REGISTRATION` to the same FAA-style encoded registration file on a
build host. Both builders package and verify `dji-registration/registration.json`.
Release gates reject missing, corrupted, plaintext or old per-device registration
input. The default private folder is ignored by Git and must be backed up in
durable administrator storage rather than temporary directories.

`capture_dji_app_registration.py --ipad UDID --output PRIVATE_PATH` captures the
registration from a configured development iPad using the explicit debug-only
capture flag. Obtain explicit owner authorization for capture and distribution
with all app instances first. No plaintext registration file is written by the
tool. The app package uses the same obfuscated transport as FAA configuration;
local credentials use Keychain/Keystore. The transport is not a secret from an
owner of the distributed app.

Legacy per-device provisioning code remains only for compatibility with earlier
administrator deployments. A sealed bundle for one tablet is not a substitute
for app-wide registration and cannot pass the release gate. See [the release
runbook](../../RELEASE.md) for fresh-install and exact-artifact checks.

## Ground connection check — aircraft can remain off

For the authorized controlled-LAN diagnostic, use **Test Pilot 2 using HTTP · local LAN only** instead of the HTTPS button. It starts HTTP APIs/storage and documented `ws://` MQTT on the same tablet. Enter the displayed `http://…:8443/cloud` URL in Pilot 2; changing only the controller URL while the tablet is still serving HTTPS does not work. This mode sends developer/session credentials and originals without transport encryption and is labeled a diagnostic. It tests native firmware support independently of certificate trust. HTTPS remains the default.

1. Select **Matrice 4TD**, then **Wide / visible** or **Thermal**, and set target ground resolution. Tele-camera routes are not qualified.
2. Tap **Send mission through Pilot 2 Open Platforms** (or the explicitly selected HTTP diagnostic). R2C shows the URL, six-digit access code, flight and return-home heights in feet/metres above takeoff, target ground detail, and approximate photo count/flight time. Keep R2C open.
3. On RC Plus 2, open **Pilot 2 → Cloud Services → Open Platforms**. Enter the R2C URL and tap **Connect**. A general browser does not expose DJI's JSBridge.
4. Enter the access code in the R2C page and tap **Connect Pilot 2**. Confirm the tablet reports Ready for **DJI license**, **Controller connection**, **Mission connection**, **Route library**, and **Automatic original photos**.
5. Open Pilot 2's cloud flight-route library, find the assignment, and load its route. Record firmware versions and any rejection message.

Stop qualification if license, HTTPS trust, MQTT transport, route library, or KMZ import fails. The prototype uses secure MQTT over WSS. DJI's JSBridge guide explicitly documents `tcp://` and `ws://`, so WSS acceptance on this firmware remains a hardware gate despite passing independent Paho tests. A loaded module does not prove the aircraft accepted the route.

## First small flight

1. Review the full route in Pilot 2: aircraft/sensor, nadir gimbal, height relative to takeoff, grid, speed, photo interval, return-home height, lost-link behavior, and terrain/obstacle clearance. The prototype assumes level ground. The overview may show distance/time/photos without height; inspect route settings or a waypoint and compare with the explicit R2C connection-page height before starting. Time estimates exclude travel to/from the grid, takeoff, landing, and operational delays.
2. Check estimated photos/time against battery and operational limits. Use a small, simple assignment first.
3. The pilot clears and starts the aircraft **in Pilot 2**. R2C does not arm, take off, or start flights.
4. Keep both devices on the LAN. R2C configures Pilot 2 for automatic **original** photos, this controller as download owner, and no automatic video upload. During-flight versus after-landing download/upload behavior remains unqualified.
5. After landing, compare Pilot 2's original-photo count with **mission originals verified** in R2C. Resolve any upload error in Pilot 2. Reopening a saved native flight creates a fresh access session and retains stored originals.
6. Confirm **All N returned originals stored; M selected-camera mission originals verified locally**. Compare a returned visible JPEG and, for thermal, a radiometric JPEG with the controller source by SHA-256 and original metadata. R2C retains bytes without decoding/re-encoding. If multiple sensors were saved, only the selected sensor enters the reconstruction inventory; known camera mismatches remain separate.

The KMZ sets a unique mission filename/folder tag. Files join `originals` only when Pilot 2 reports `is_original=true` and the filename/path contains that tag. Old/unmatched files are retained separately and cannot silently complete the mission. Verify the tag survives this controller's capture/upload path.

Sessions end after two hours or **Close connection**. Originals and incomplete S3 multipart uploads remain in private `files/mapping-missions/<missionId>`. Reopen the assignment and use **Resume native connection for this flight** after restarting R2C; for the authorized LAN diagnostic, choose **Resume this flight · HTTP LAN test**. Saved native flights appear even with zero verified originals, so an interrupted completion report does not require a new mission or flight. Pilot 2 must reconnect to the fresh session. Completed files are deduplicated. Native retry/resume under a real controller disconnect remains a test gate.

Mapping work suspends R2C's automatic idle shutdown and inactivity-driven incident disconnect, and keeps its foreground screen awake. **Back to mission** leaves a native connection running; **Close connection**, expiry or explicit app Quit ends it. After the last mapping resource closes, the normal idle countdown restarts. A phone/tablet lock still protects access; package installation and force-stop can still terminate the process. Avoid app updates during an active transfer.

The saved flight remembers its chosen transport. Its resume button explicitly shows **HTTPS** or **HTTP LAN test**. Use the exact matching URL shown by the tablet: requesting `http://` from an HTTPS listener can produce `net::ERR_EMPTY_RESPONSE`. Changing the controller address alone does not switch the tablet's listener. Unknown/legacy transport defaults to HTTPS; the explicit HTTP diagnostic button remains available. For qualification, leave the connection open without touching R2C longer than its configured idle interval, confirm it stays connected, then close it and verify normal idle behavior resumes.

After a tablet restart, Pilot 2 can retain native connections from the earlier session. Reloading its page alone may leave **Waiting for local device connection** or unauthorized photo-upload credential requests. Leave the tablet's resumed flight open; use Pilot 2's **LogOut**, reconnect to the displayed address, and enter the current access code. The reconnect fix refreshes previously loaded modules and checks the new API token; its device test is still pending. Keep the existing flight rather than generating a new mission, so the queued originals keep their matching mission tag.

## Evidence and remaining scope

### Same-tablet visible 2D map

After originals are verified, use **Create 2D map from N verified originals** in the native connection panel or saved flight. R2C snapshots/checks only the selected visible-wide originals and processes them locally. This explicit handoff also works when the final Pilot 2 group callback is missing; it does not fabricate a controller completion report. New visible flights request automatic local processing when the authoritative group callback arrives.

The **Making 2D map** modal shows stage, approximate remaining time and cancellation. Mapping work/foreground notification/wake lock protect the processing lifetime. Cancelling keeps originals and removes partial output; after process death, a new job clears orphaned working files before retrying. Results are published only after all photos are feature-connected, alignment checks pass and tiles/share package are written.

Use **Map settings → Local mapping layers → View this map** without reconnecting an incident or WAN. **Inspect / share layer** shows capture time, visible/IR mode, estimated ground detail, photo count and quality limitations; **Share this layer (.zip)** uses R2C's map-package format with no original photos or credentials. The regular Map share also includes displayed mapping layers. Generated/imported mapping tiles live in durable app storage, separate from evictable basemap cache.

The Active5 Pro processed the actual 66 visible originals in 197 seconds, generating 3,799 PNG tiles at zoom 14–23, approximately 2 cm/px estimated native ground sampling, and a 360 MiB share package. This engine assumes a locally flat ground plane and uses feature registration plus drone GPS placement. Trees/buildings can retain parallax; it does not generate a dense terrain model or claim survey accuracy. Current bounds are 3–200 photos, at most 16,000 tiles at the finest zoom, and sufficient local working storage. The 66-photo job briefly used about 3 GiB of temporary decoded pixels, removed before publication. [Validation](../../docs/validation/2026-10-11-local-visible-stitch.md).

Tests passed for route generation, H5 with a synthetic DJI bridge, secure MQTT with Paho, and signed single/multipart S3 uploads with the official AWS SDK. On the A5 Pro and RC Plus 2, the user confirmed actual license verification, Ready connection indicators, and a loaded cloud route using the HTTP/WS diagnostic. Pilot 2 reported 2.50 kft, 3m 49s, and 69 photos. The tablet's saved MapTest visible-route KMZ specifies 57.9786 m (190.2 ft) at all 14 waypoints, relative to takeoff, and the same return-home height. The desired ground resolution is 1 cm/px.

The user completed the flight and confirmed height/obstacle clearance. After Pilot 2 LogOut/reconnection, all 132 previously queued originals reached tablet storage: 66 visible-wide and 66 thermal, 513.9 MiB total. All 132 upload callbacks report original files; checksums recomputed on the tablet match every stored upload record. The 66 visible originals matched the selected camera and entered the mission inventory. A group-completion callback has not yet arrived, so automatic batch handoff remains unqualified. Unattended reconnect, native HTTPS trust, independent comparison with controller-source checksums, and operation with WAN disconnected also remain gates.

The returned visible sample is 4032 × 3024, while the current planner assumed 8064 × 6048. At the flown height, the planner's level-ground/FOV model therefore estimates about 2 cm/px rather than the requested 1 cm/px. Capture-size control and planning must agree before claiming the requested ground resolution; the stored originals are retained unchanged.

Native hosting/processing on iPad/Mac and terrain-corrected/thermal reconstruction remain separate work. NodeODM station and manual browser/QR transfer remain optional fallbacks.

Official references: [platform connection](https://developer.dji.com/doc/cloud-api-tutorial/en/feature-set/pilot-feature-set/pilot-access-to-cloud.html), [JSBridge](https://developer.dji.com/doc/cloud-api-tutorial/en/api-reference/pilot-to-cloud/jsbridge.html), [routes](https://developer.dji.com/doc/cloud-api-tutorial/en/feature-set/pilot-feature-set/pilot-wayline-management.html), [original-media upload](https://developer.dji.com/doc/cloud-api-tutorial/en/feature-set/pilot-feature-set/pilot-media-management.html), [WPML actions](https://developer.dji.com/doc/cloud-api-tutorial/en/api-reference/dji-wpml/common-element.html).
