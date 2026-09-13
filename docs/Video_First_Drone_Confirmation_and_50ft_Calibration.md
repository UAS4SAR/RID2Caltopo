# Video-first confirmation and 50-foot launch calibration

## Confirmation

Android and Apple can open the Drone Confirmation Panel when a live incoming
stream designator exactly matches one configured RID mapping, ignoring case and
surrounding whitespace. Missing and ambiguous matches do not select an aircraft.
Local video playback is excluded. The stream name alone does not create an aircraft position, claim that RID was
received, or establish a launch reference. Complete live DJI position and height
telemetry now creates and updates a track through the configured stream binding,
without requiring a RID observation.

Video and RID candidates share the current confirmation lifecycle. Confirmed,
ignored, or already prompted aircraft are not prompted a second time merely
because the other input arrives. Video-only candidates remain eligible while
that stream is active, including when no RID tracks exist yet. RID-first cases
continue using the existing confirmation path. Android observes stream changes
directly, independently of the video-thumbnail refresh interval.

## Manual recovery

The aircraft detail panel offers “Calibrate at 50 ft over launch” on both
platforms. Android's previously disconnected calibration routine is exposed again;
Apple's existing control is updated to the same wording and behavior.

The adjacent instruction requires an independently established 50-foot hover
directly above the launch point. Fresh aircraft position and absolute altitude
are required, and the handler rechecks freshness. The operator action establishes
that location as the AOL launch reference without depending on the RID airborne
flag. ATO and AGL honor the manual altitude offset even when the reported RID
height differs. Terrain correction uses a sample belonging to the calibration
location; missing terrain cannot silently reuse an earlier-position sample.

AOL uses compatible prepared survey ground at the crew-specified launch location
plus the calibrated relative height, then subtracts the highest mapped surface
within the existing 200-foot radius. It therefore need not equal 50 feet. Missing
surface coverage still produces Unk. Neither DCP confirmation nor a stream alone
performs this calibration. The manual Android AOL reference is scoped to the
current flight identity and is cleared when that identity changes.

Automated coverage includes unique/ambiguous/missing stream matches, video-first
panel persistence until RID arrival, duplicate suppression, and manual calibration
with an unreliable airborne flag and a conflicting RID height. Physical RTMP/RID
ordering and 50-foot hover verification remain device/field checks.

## Inspection panel correction — September 12, 2026

The A5 Pro screenshots at 13:59:44 and 13:59:50 in `ScreenCaps/` showed
survey diagnostics occupying the main inspection panel. Available AOL results
included coordinates and internal survey identifiers; stale results replaced
that block with one line. This pushed calibration down the scroll area and
changed the layout abruptly. Android also colored the diagnostic block red
because its text began with “AOL,” just like the measurement row.

Both platforms now place the diagnostics behind an “AOL details” control.
Calibration remains with the compact aircraft measurements. Android colors only
the negative numeric AOL value, matching Apple. Diagnostics remain selectable
and scrollable in their own view. Freshness requirements and calibration math
are unchanged; the disabled control with POS? in the second screenshot is
expected. The captured application log contained no manual-calibration entry,
so the screenshots do not establish that calibration was applied.

Validation: Android debug compilation and all 1,053 unit tests passed; the signed
Apple device build (2.2.7, build 214) succeeded. `git diff --check` passed.
These changes have not yet been installed or physically retested on either tablet.
