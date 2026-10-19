# Local mapping prototype

The target is one dedicated R2C instance that selects an assignment, configures a mapping mission, hands the route to Pilot 2, receives original photos, reconstructs locally, and immediately displays a durable 2D layer. Finished layers can later be shared with peer R2C instances and CalTopo. See [the intended workflow and implementation boundary](WORKFLOW.md).

## What works today

- R2C has an assignment-to-mission workspace on Android and Apple.
- Android R2C hosts a native Pilot 2 Cloud API prototype: a full M4TD wide/thermal waypoint-grid KMZ, route library, secure MQTT/WebSockets, and automatic-original upload settings with local S3-compatible storage. See [the native qualification checklist](NATIVE-CLOUD-TEST.md). Actual DJI firmware compatibility remains unqualified.
- The optional local mapping station can import selected JPEGs, call a local NodeODM engine, generate XYZ tiles, preview the result, and share the finished layer when configured.
- A manual boundary KML/KMZ export exists as a temporary Pilot 2 test aid.
- Android can stitch a verified visible-wide flight on the same tablet using bundled OpenCV, create durable XYZ tiles, view them offline, and share a map-package ZIP. The Active5 Pro processed the actual 66-photo MapTest flight in 197 seconds (3,799 tiles, zoom 14–23). See [local processing validation](../../docs/validation/2026-10-11-local-visible-stitch.md).

## What does not work yet

The Android/RC Plus 2 HTTP qualification flight loaded a route and returned 132 originals (66 visible, 66 thermal). Same-tablet visible 2D stitching is now tested on the Active5 Pro. It is a feature-aligned flat-ground mosaic; dense terrain-corrected photogrammetry, thermal stitching, 3D output, and Apple processing are not implemented in this engine. Automatic processing is wired to Pilot 2's group-completion callback for new visible flights; this controller omitted that callback on the interrupted test, so **Create 2D map from verified originals** provides an explicit handoff. Native HTTPS/offline-controller restart behavior and automatic peer/CalTopo publication remain qualification/work items. The station is optional. No controller helper app is needed.

The old Inbox delivered only a boundary file. It did not configure Pilot 2's camera or route, return imagery, or stitch photos. Its UI, endpoints, pairing data path, and prototype flight-state shim were removed so the app no longer implies those capabilities.

## Local station fallback

The station may be useful for development comparisons while the target device-local engine is evaluated. It requires a separately running local NodeODM engine and manually selected originals. The station's manual KML/KMZ export does not establish that Pilot 2 has applied the mission settings; the pilot must review the imported route and all flight parameters in Pilot 2.

For station setup, see [MBA-SETUP.md](MBA-SETUP.md). For a small manual test, see [THERMAL-FLIGHT-CHECKLIST.md](THERMAL-FLIGHT-CHECKLIST.md). These documents describe the fallback only.

For the tablet-owned, controller-browser LAN transfer design and its hardware test gates, see [CONTROLLER-LAN-TRANSFER.md](CONTROLLER-LAN-TRANSFER.md). This flow keeps R2C off the DJI controller.

For the Android HTTPS prototype's step-by-step test, see [BROWSER-TRANSFER-TEST.md](BROWSER-TRANSFER-TEST.md). Native Pilot 2 **Cloud Services → Open Platforms** is now confirmed present on the team's controller and should be qualified for route libraries and automatic original-media upload; it can connect to a private/LAN platform and does not require installing R2C on the controller.

## Export a local map to CalTopo

In the Android or Apple **Map Data Inspector**, choose **Prepare compressed
GeoTIFF (.tif)**, then **Share GeoTIFF (.tif)** and save the file. In CalTopo choose
**+ Add → Map Sheet → Upload New** (Pro or higher). The export is georeferenced
and preserves transparent gaps with lossless Deflate compression. The adjacent
**Limit to 40 MB for CalTopo** checkbox is unchecked by default, preserving full
saved detail. When checked, export tries the highest saved zoom first and only
reduces detail if the actual compressed file exceeds 40 MB. The result shows its
size, selected zoom, and whether detail was reduced. Changing the checkbox requires
a fresh export. No tracker hosting is required. See
[format and validation details](../../docs/validation/2026-10-19-geotiff-export.md).
