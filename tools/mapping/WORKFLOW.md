# R2C mapping: intended workflow and implementation boundary

## Intended field experience

One R2C instance owns the mission from assignment selection through the finished map:

1. The operator selects a search assignment, chooses the aircraft/camera and a simple 2D or 3D profile, and reviews resolution, height, speed, overlap, and expected coverage.
2. R2C sends the complete route configuration to Pilot 2 over a supported local/device integration. The pilot checks the aircraft, clearance, and route, then starts the mission.
3. Original photos transfer back to that same R2C device over the local connection, preferably during flight and otherwise after landing. R2C verifies the expected set and retains originals.
4. The device reconstructs a 2D orthophoto (or later a 3D product), shows progress, estimated remaining time, and cancellation, then stores tiles locally.
5. The new layer is immediately available to that device and can optionally be shared to the incident's R2C peers and CalTopo.

The R2C device should be dedicated to the active mapping task while reconstruction runs. Normal flight safety and start/stop authority remain with the pilot in Pilot 2.

## Current implementation

This repository does **not** yet implement that complete workflow. It currently has:

- Assignment selection and a mission-settings workspace in Android and Apple R2C.
- An Android tablet-hosted native Cloud API prototype: protected credentials, a complete M4TD wide/thermal waypoint-grid KMZ, route library, secure MQTT/WebSockets, and local S3-compatible original-JPEG intake. Independent tests pass; controller/aircraft qualification remains pending. See [NATIVE-CLOUD-TEST.md](NATIVE-CLOUD-TEST.md).
- A local mapping-station prototype that can accept manually selected JPEGs, run NodeODM, make an orthophoto/tile set, preview it, and optionally share a finished layer.
- Manual KML/KMZ export as a temporary route handoff for testing.
- A limited Pilot 2 license-check prototype in prior development history; it did not deliver missions or return photos and has been removed from the current station workflow.

The separate **R2C Controller Inbox** app and its station pairing/send workflow have been removed. It was only a boundary file-drop helper. It did not configure a Pilot 2 route, receive aircraft photos, or reconstruct imagery. It must not be installed for the intended mapping workflow.

The current local station remains an optional development fallback. It is not the primary design and does not make the R2C tablet/iPad the reconstruction device. The same-device reconstruction engine, durable local tile storage, and local/peer map-layer integration still need implementation and qualification. The current manual fallback may require a separate processing computer and file selection.

## DJI integration gate

Developer registration and credentials alone do not provide route upload, Pilot 2 control, or media return. The manual KML export is only a boundary handoff. The native Android integration now generates an executable waypoint-grid KMZ; its route/sensor/photo actions still require Pilot 2 and aircraft qualification.

DJI Cloud API is designed for Pilot 2 to connect to a third-party platform, which can be privately hosted on the LAN. Route libraries and automatic/manual original-media uploads are supported platform functions; reconstruction is performed by the platform's own engine. Its product matrix lists Matrice 4TD with Dock 3, but release notes also mention M4D/4TD in the Pilot-to-cloud module. Therefore RC Plus 2 + M4TD must be qualified on the actual installed Pilot 2, rather than ruled out from that matrix alone. Inspect Cloud Service → Third-party Cloud, then test the license/JSBridge, route library, and media module using credentials stored outside the repository.

DJI's Mobile SDK can manage missions and media on a supported Android controller app, but R2C is intentionally not installed on the DJI controller. The implemented Android fallback is a short-lived authenticated HTTPS session hosted by the dedicated R2C tablet, opened in the controller's existing browser using QR or address/code. Pilot 2 imports and reviews the boundary; after flight, the operator selects original photos in the browser and uploads them to R2C. See [the transfer design and qualification gates](CONTROLLER-LAN-TRANSFER.md). This fallback does not automate Pilot 2 itself. Prefer the native local Cloud API integration if qualification succeeds.

## Next implementation slices

1. Define a transport-neutral mission package containing assignment geometry, camera/sensor, nadir/oblique attitude, target ground resolution, height reference, speed, overlap, route validation summary, and a logical `photoIntake` target keyed by mission ID. The target names the local `originals` collection and capture mode; it never embeds a LAN address or reusable credential.
2. Qualify the native tablet-hosted Cloud API on RC Plus 2/M4TD: HTTPS trust, license, MQTT/WebSockets, cloud route download, and generated WPML actions. Keep the implemented browser boundary handoff as a manual fallback.
3. Qualify automatic original-photo return and controller retry behavior. The native session provides a local storage destination; mission tags and original flags guard assignment. Validate counts, checksums, free-space handling, and status with real visible and thermal flights. The controller still reviews and starts the route.
4. Integrate a reconstruction engine per platform. Evaluate memory, storage, thermal throttling, runtime, and output quality on the actual Samsung and iPad models before promising tablet reconstruction. A Mac can be a development/reference processor.
5. Save the result as a durable R2C 2D layer on the same device, then add peer sharing and CalTopo tile publication as optional output paths.

## Temporary local-station fallback

The station is started separately and is not required by the target architecture. Its web page offers mission settings, manual boundary export, manual JPEG import, NodeODM reconstruction, local preview, and optional organization sharing. Do not describe this fallback as integrated Pilot 2 control or automatic image transfer.
