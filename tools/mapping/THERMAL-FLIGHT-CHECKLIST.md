# Small thermal mapping test — manual fallback only

This checklist exercises manual boundary export and the separate local processing station. It does not test automatic Pilot 2 integration or on-device R2C reconstruction. Use only under your normal flight authorization, airspace coordination, and aircraft safety procedures.

## Before the test

- [ ] Start the optional local mapping station and confirm its local NodeODM engine is ready.
- [ ] Put the station, mapping R2C device, and controller on the same trusted local network.
- [ ] Select a small, flat assignment with recognizable fixed features. Save the thermal mission settings in R2C.
- [ ] Export the boundary manually. Transfer/import it into Pilot 2 using a method supported by the controller. Review the aircraft, thermal camera, nadir orientation, route, height above takeoff, speed, overlap, and terrain clearance in Pilot 2. The imported boundary alone does not apply those settings.
- [ ] For a first comparison, use a conservative small area and retain the original radiometric JPEGs. Keep thermal and RGB imagery in separate missions. The resulting visual mosaic is experimental and is not a calibrated temperature map.

## After landing

- [ ] Transfer originals to the processor using an available local method and select them in the station's photo importer.
- [ ] Confirm the expected photos are present, then choose **Make 2D map**. Keep the device and engine awake; the station shows approximate progress and permits cancellation.
- [ ] Inspect alignment, coverage, and recognizable features. Record image count, processing duration, and visible registration errors.
- [ ] If useful, try the local layer link in another R2C instance. Organization sharing is optional and requires tracker configuration.

This is a manual prototype test path. It is not the intended one-device field workflow.
