# R2C tablet ↔ RC Plus 2 transfer design

## Ownership

R2C remains on the dedicated Samsung/iPad and owns assignment selection, mission parameters, photo intake, reconstruction, local tiles, and sharing. Pilot 2 remains the only app installed/used for aircraft operations on the RC Plus 2. Do not install R2C or a separate R2C Controller Inbox on the controller.

## Recommended local workflow

The native Android Cloud API prototype is now the preferred path to qualify: **Pilot 2 → Cloud Services → Open Platforms** connects directly to the R2C tablet, loads its complete route, and returns originals through the native media module. Follow [NATIVE-CLOUD-TEST.md](NATIVE-CLOUD-TEST.md). The browser workflow below remains a manual fallback.

1. R2C starts a short-lived authenticated transfer session over the incident Wi-Fi or a hotspot. It displays a large QR code and a clear session status. The controller scans it if its built-in camera/QR flow supports opening links; otherwise the same page offers a short LAN URL to type in. QR-to-browser behavior must be qualified on the RC Plus 2.
2. R2C creates a mission handoff. A boundary KML communicates the polygon only. The R2C mission manifest also records the logical photo destination (`missionId` + `originals` collection), expected capture mode, and byte-preservation requirement. The LAN session resolves that logical destination to the current tablet and provides the temporary upload capability. No station URL, LAN address, or bearer secret belongs in the reusable mission file.
3. The controller browser downloads the DJI route file into local shared storage. The intended no-retyping route file is a DJI WPML KMZ with the flight plan and photo actions; the operator imports it into Pilot 2 and reviews/starts it there. Pilot 2 also supports importing KML/KMZ from controller storage. The exact generated WPML mapping route must be tested on the team's RC Plus 2/M4TD before flight use.
4. After flight, Pilot 2 downloads the aircraft's full-resolution originals to a controller-visible location. In the same browser session, the operator selects the mission photos and uploads them directly to the R2C tablet over the LAN. The transfer page is already scoped to that mission's `originals` collection, so the operator does not choose a second destination.
5. R2C validates file count, sizes, names, timestamps, camera/mode, and checksums; retains the originals in the mission's local collection; then starts local reconstruction with progress, remaining-time estimate, and cancel/resume controls.

The mission manifest's photo destination is R2C workflow metadata. Pilot 2 and the aircraft will not interpret it as an upload command. The controller browser/session performs the return transfer, and a short-lived session secret remains separate from both the DJI route and reusable manifest.

The transfer is browser-based, so the controller needs no R2C package and no R2C account. The route and images stay on the local network. Pilot 2 still requires a human import/review/start action unless DJI exposes a supported integration for this aircraft/controller combination.

## Build and test in small steps

1. Prototype the tablet-hosted web page with a QR-opened session, small KML download, and upload on the same Wi-Fi. Use a single-use session token, short expiration, TLS or a pinned local-session secret, and explicit close/cancel. Do not enable open LAN uploads.
2. Verify the RC Plus 2 browser can download and open the KML in Pilot 2. Then test a DJI WPML KMZ route with safe ground/simulator conditions. Confirm Pilot 2 accepts the route and preserves the mission settings. DJI's guide says KML carries an area boundary only; a full route KMZ contains `wpmz/template.kml` plus `wpmz/waylines.wpml`. It also notes that third-party batch import is restricted, so test one route at a time.
3. Verify Pilot 2 can make original visible and radiometric thermal photos available to the browser's Android file picker. Upload a small mixed sample and compare each transferred file byte-for-byte/checksum. If Pilot 2 keeps originals in app-private storage or the file picker cannot see them, stop and test Pilot 2's supported export path; do not assume browser access to private app files.
4. Add resumable/chunked upload, duplicate detection, disk-space preflight, and per-file progress. Test interruption and retry with hundreds of images before a field flight.
5. Only after route import and original-photo transfer are repeatable, run a controlled small flight. Never start the aircraft from the transfer page.

## DJI API boundary

DJI's Mobile SDK can manage waypoint KMZ missions and camera media, and its supported-products list includes M4D/M4TD with RC Plus 2. But those APIs run inside an Android app connected to the controller/aircraft; they do not let an R2C app on a separate tablet directly call Pilot 2. Since R2C must remain off the controller, keep MSDK out of the main tablet workflow unless DJI provides a documented controller bridge that does not require installing an app there.

DJI Cloud API is the native integration to qualify before promoting browser transfer to the main field workflow. Its [architecture](https://developer.dji.com/doc/cloud-api-tutorial/en/overview/product-architecture.html) explicitly allows a privately deployed, reachable platform; it does not require WAN hosting or a new controller app. Pilot 2's built-in H5/JSBridge verifies the developer license and loads route and media modules. The platform supplies HTTPS APIs, MQTT/WebSocket services, and OSS/S3-compatible upload storage. [Route management](https://developer.dji.com/doc/cloud-api-tutorial/en/feature-set/pilot-feature-set/pilot-wayline-management.html) and [automatic/manual media upload](https://developer.dji.com/doc/cloud-api-tutorial/en/feature-set/pilot-feature-set/pilot-media-management.html) belong to this platform; reconstruction remains an R2C engine responsibility.

As checked on 2026-10-11, DJI's [product matrix](https://developer.dji.com/doc/cloud-api-tutorial/en/overview/product-support.html) lists M4TD with Dock 3, while [DJI's Cloud API v1.14 release notes](https://developer.dji.com/doc/cloud-api-tutorial/cn/) describe an M4D/4TD feature in the Pilot-to-cloud module. This is conflicting evidence, not proof that RC Plus 2 cannot connect. The user supplied a controller photograph confirming **Cloud Services → Open Platforms** is present on the team's RC Plus 2. DJI's [connection instructions](https://developer.dji.com/doc/cloud-api-tutorial/en/feature-set/pilot-feature-set/pilot-access-to-cloud.html) identify that exact entry for a custom platform URL. Qualify license, route library, and original-media upload on the installed firmware. The browser fallback page is not a Cloud API platform and must not be presented as one.

## Android prototype status (2026-10-11)

The A5 Pro R2C build now hosts an HTTPS browser transfer session, serves the boundary KML and R2C mission ZIP, and receives JPEG originals in resumable 1 MiB chunks. Checksums identify duplicates and verify retained bytes. Sessions expire after two hours or explicit close. Originals, partial uploads, and their inventory persist under `files/mapping-missions/<missionId>`; reopening a saved flight creates a fresh access session. TLS keys live in Android's no-backup private directory, and session credentials are excluded from exports.

The QR contains the authenticated browser URL. It is for a browser-compatible QR scanner, not an implemented DJI aircraft-camera scanner. A short HTTPS address plus a rate-limited six-digit code provides the alternative when the controller cannot scan it. This test certificate is generated locally, so browsers may require the human operator to confirm the certificate warning. A generally trusted certificate needs separate provisioning before this can become a frictionless field experience.

Automated tests and a live HTTPS transfer to the A5 Pro passed. RC Plus 2 browser downloads, original-photo picker access, and its certificate behavior remain qualification gates. Complete WPML route generation and automatic aircraft-media return are not implemented by this browser prototype. On-tablet reconstruction is separate work.

The separate native Android prototype now generates complete M4TD wide/thermal waypoint-grid KMZs and hosts the Cloud API route, MQTT/WebSocket, and S3-compatible media services on the tablet. It sets automatic-original upload in Pilot 2 and preserves received JPEG bytes. Independent clients pass; actual Pilot 2 license/HTTPS/WSS/route/media behavior remains unqualified. Developer credentials and imported certificate keys use encrypted no-backup storage. No controller app or Mac station is required by this native transport.
