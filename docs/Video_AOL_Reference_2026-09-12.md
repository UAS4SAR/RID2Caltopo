# Video-only AOL initialization

The iPad build-221 field log showed continuous `Unk` from 18:15:02 until manual calibration at 18:16:38, then a numeric value after 0.23 seconds. The user clarified the remaining issue was initialization, not flashing.

The shared DJI tag-4 decoder already exposes a geodetic reference latitude/longitude/altitude, local north/east displacement, and relative up height. Aircraft position is reconstructed from that reference plus displacement. `docs/DJI_Type_245_SEI_Payload.md` records the layout and relative-height relation. AOL previously ignored that reference coordinate and required explicit RID ground status or manual calibration.

Apple now carries the reference coordinate through the DJI observation and track-store normalization into the altitude coordinator. Fresh video observations with takeoff-relative height can initialize AOL at the supplied reference even if reception starts airborne. Android reads the same reference only from a fresh, bound, accepted stream sample matching the current height receipt time and flight identity. Neither platform substitutes current aircraft position when the reference is missing. Neither marks the aircraft grounded or creates RID ground-status evidence. Manual calibration retains precedence. Missing/invalid/stale reference inputs do not create an anchor. Existing terrain/surface preparation, source-reference validity, and ground-launch assumptions remain applicable.

Regression coverage includes airborne reception away from the reference, Apple track-store field preservation, manual override precedence, invalid/missing coordinates, missing height, and stale samples. Build 222 is the device candidate on both platforms. Source/unit/device deployment checks do not prove flight behavior; retest should confirm initialization without calibration and inspect the reference diagnostics if still unavailable.

Evidence and build logs: `outputs/video-aol-reference-20260912`. Android signing and release verification remain enabled; Crashlytics artifact uploads are excluded for the local test build.
