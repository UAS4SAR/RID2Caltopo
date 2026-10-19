# Multi-aircraft planning — 2.5.3 (397)

## Implemented scope

Both mission panels now offer separate Matrice 4TD, Matrice 4T, Matrice 30 and
Matrice 30T choices. Matrice 400 remains a placeholder with planning disabled.
M30 and M30T use a fixed wide-camera profile; variable-zoom M30 mapping is not
included. M4T offers wide, medium tele and tele. M30T and M4T also expose native
640 × 512 thermal planning, matching the existing thermal planning workflow;
this does not add a thermal reconstruction engine.

| Aircraft | Drone key | Payload key | Visible planning originals / DFOV |
| --- | --- | --- | --- |
| M4TD | 0-100-1 | 1-99-0 | Existing 4032 × 3024 / 82°, 4032 × 3024 / 35°, 4096 × 3072 / 15° |
| M4T | 0-99-1 | 1-89-0 | 12 MP: 4032 × 3024 / 82°, 4032 × 3024 / 35°, 4096 × 3072 / 15° |
| M30 | 0-67-0 | 1-52-0 | 4000 × 3000 / 84° |
| M30T | 0-67-1 | 1-53-0 | 4000 × 3000 / 84° |

M4T thermal planning uses 45° DFOV; M30T uses 61°. Super-resolution thermal
originals are not substituted for native sensor sampling. M30/T visible capture
selects `wide`, without a zoom-camera action; M4T uses VIS-only storage and
24/70/168 mm equivalent focal selection as appropriate. Aircraft and payload
IDs follow the chosen profile in both KMZ files, Pilot 2 mission-library entries,
model filtering and missing-model topology fallbacks. Explicitly reported
controller/aircraft topology values remain authoritative.

Wide cameras support open-ground and existing brush/tree patterns, including
clearance and canopy spacing. Capture-speed rounding and separate transit speed
are retained. M30/T uses a conservative 2 s planning minimum between photos;
M4T retains the conservative 1 s wide / 1.5 s telephoto policy. These are R2C
planning limits, not measured aircraft throughput guarantees.

Camera settings remain an operator check in Pilot 2. New plans describe nominal
12 MP geometry rather than claiming observed capture qualification. Existing
M4TD profile IDs, default choices and retained routes are preserved. Readers
infer omitted legacy aircraft names from the saved profile, but reject an
explicit aircraft, sensor or geometry mismatch.

Apple now selects the first available camera after an aircraft change, matching
Android. Original-photo matching includes the M4T telephoto profiles. The shared
manual-transfer page no longer directs every aircraft to M4TD.

## Sources

- [DJI aircraft/payload enumeration](https://developer.dji.com/doc/cloud-api-tutorial/en/overview/product-support.html): model/subtype and payload keys.
- [DJI WPML common elements](https://developer.dji.com/doc/cloud-api-tutorial/en/api-reference/dji-wpml/common-element.html): model identity and lens-selection fields.
- [Matrice 4 specifications](https://enterprise.dji.com/matrice-4-series/specs): camera fields of view, focal lengths, 12/48 MP modes and native thermal dimensions.
- [Matrice 30 specifications](https://enterprise.dji.com/matrice-30/specs): wide dimensions/DFOV and native thermal geometry.

## Verification

- Android mapping suite: 152 tests, 0 failures/errors, 1 existing optional skip;
  `:app:assembleDebug` passed. The host JDK 25 causes pre-existing JaCoCo proxy
  instrumentation warnings; the test runner and all enabled tests completed.
- Apple: broader mapping/native/cloud tests passed (27 XCTest cases, one optional
  real-flight fixture skipped). The first core run caught the missing legacy
  aircraft-name assumption; fixed, then final aircraft/mission/route tests (26)
  and Pilot Cloud tests (5) all passed.
- New tests inspect both exported XML files for model/subtype/payload identity,
  visible/IR lens choice, absence of a zoom action on M30 wide routes, M4T
  telephoto selection, camera geometry, capture cadence, independent transit
  speed and rejection of mismatched plans.
- HTTP/MQTT integration tests check each new aircraft's route discovery,
  wrong-model filtering, and aircraft/controller topology fallback on both
  platforms. Existing M4TD shared route coordinates/counts remain unchanged.
- Final Apple arm64 Simulator app compile/link passed, including SwiftUI panels.
  Interactive Simulator controls were unavailable through the desktop tools;
  no visual UI verification or physical-device installation is claimed.
- Canonical 2.5.3 notes, store mirror, metadata validation and copy checks pass.
  Android and both Apple configurations are 2.5.3/build 397.

No new disk, image, network or growing geometry work runs in view bodies or
selection callbacks. New catalog lookups are bounded in memory. Android still
runs mission forecasts through its serial background dispatcher; Apple still
uses its preview actor, cancellation and stale-result checks.

## Remaining hardware qualification

No M30/M30T/M4T controller or flight was available for this change. Before
claiming field qualification, check Pilot 2 import and lens selection, camera
resolution, capture cadence, heading/gimbal sequencing, photo-return metadata,
resume and reconstruction on each aircraft/firmware. In particular, confirm
M4T 12 MP timed originals and the conservative M30 capture policy on hardware.
The populated A5 Pro and physical iPad were not modified.

## In-place owner test installation

After the implementation checks above, the owner requested installation for
Pilot 2 testing. Both current development builds were rebuilt and installed in
place on October 19, 2026:

- A5 Pro `R52Y90C9XST`: `adb install -r` succeeded. Independent package query
  reports 2.5.3/build 397 and the original first-install timestamp is unchanged.
- Ken's iPad `00008103-0010596E1AD3001E`: development-signed app passed strict
  signature verification; `devicectl device install app` succeeded. Independent
  installed-app query reports 2.5.3/bundle version 397.
- Canonical release notes match both installed-build packages byte for byte.
- Android APK SHA-256: `5a341139b99d784dc88ba8774b488f9adfae8dec18e14c9479d267baf9044d65`.
- Signed Apple executable SHA-256: `f162357d6f84a616b090ec91092ae2ae082030b55e3058a5df3457658c89cebc`.

No uninstall, data clearing, test instrumentation, app launch, operational
session, controller connection or flight was initiated. Pilot 2 qualification
remains the owner's next verification step.
