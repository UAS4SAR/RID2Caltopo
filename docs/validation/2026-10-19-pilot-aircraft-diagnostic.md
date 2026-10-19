# Pilot 2 aircraft-presence diagnostic — 2.5.3 (402)

The owner clarified that the controller was being tested without an M4T or a
linked aircraft. Pilot returned numeric download owner 1 even after the page
requested owner 0. That response alone does not establish a second-controller
conflict. Previous references to an actual M4T connection were assumptions;
M4T aircraft interoperability remains unverified on hardware.

The shared Android/Apple H5 asset now queries the documented
`platformGetAircraftSN` when download-owner readback still fails after the
existing bounded retry. An empty/whitespace serial produces:

> Pilot 2 has not reported a connected aircraft. Power on and link the aircraft to this controller, then reconnect to the R2C tablet. Photo-download ownership cannot be confirmed yet.

A nonempty serial retains the existing ownership diagnostic. An unavailable,
rejected or malformed aircraft query explicitly says Pilot could not report
whether an aircraft is connected and asks the operator to check power/link
before download-controller ownership. No serial is displayed or transmitted.
This uses the reported serial as a diagnostic hint, not proof of a live link;
a cached serial can therefore still produce the ownership diagnostic.

The change does not add an aircraft-presence readiness gate or accept owner 1
as ready. Mission loading and automatic-original flags still require verified
media settings; runtime drift still hides the mission and clears readiness.
All new work is one bounded bridge query on the existing error path, with no
filesystem, image, network or data-dependent scan added to either UI thread.

## Verification

The Node bridge regression passes with empty and whitespace serials, linked
serials, null/object replies, rejected query, and ownership drift after initial
success. Tests ensure no serial disclosure, mission loading or false ready
report on failed ownership. Existing scalar encoding, retry, stale callback,
license, token and no-flight-control checks also pass. Canonical release notes
and Apple store mirror are synchronized; metadata and whitespace checks pass.

Official sources:
- https://github.com/dji-sdk/Cloud-API-Doc/blob/master/docs/en/60.api-reference/10.pilot-to-cloud/30.jsbridge.md
- https://github.com/dji-sdk/Cloud-API-Demo-Web/blob/main/src/api/pilot-bridge.ts
- https://github.com/dji-sdk/Cloud-API-Demo-Web/blob/main/src/types/enums.ts

Physical-controller verification of the new text is pending.

Android debug and Apple development device builds pass. Both packages contain
byte-identical updated H5 assets; Apple package reports 2.5.3/build 402.
Packages are prepared; this change has not been installed on physical devices.

## Installation handoff

At the owner’s request, build 402 was installed in place on the iPad and A5 Pro.
The iPad device inventory independently reports version 2.5.3/build 402. Android
installation used `adb install -r` and completed successfully. No app data was
cleared, device reset, instrumentation run, or flight started.

## A5 Pro credential setup follow-up

The owner reports “Saved DJI credentials are unavailable” after installation.
Read-only `run-as` file metadata confirms that the current A5 Pro installation
has an enrollment PEM, but no `dji-cloud-credentials.enc` or provisioning receipt.
The build-402 Android APK also lacks `assets/dji-provisioning/sealed.json`: the
build command omitted `R2C_DJI_PROVISIONING_BUNDLE`. This explains why this build
cannot automatically provision an unconfigured installation, but does not
establish when or why the saved credentials became absent. No credential bytes
were displayed, overwritten or deleted during diagnosis. The in-place install
was successful; installation success alone did not establish operational setup.

The previously documented `/private/tmp/.dji-cloud-sealed.json` is absent. A
matching protected deployment input must be located or regenerated from an
authorized private source before claiming restored Pilot connection readiness.
The owner has been asked only for a private file location or another configured
Android device, never to paste credentials into chat.
