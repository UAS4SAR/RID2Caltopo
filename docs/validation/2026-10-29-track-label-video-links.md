# CalTopo archive titles and iPad recording links — October 29, 2026

## Android evidence and correction

Read-only checks identified the attached A5 Pro as Samsung SM-X350, serial
R52Y90C9XST, running 2.5.5 (432), last updated October 28 at 19:27:08 device
time. No app launch, installation, device tests, backup, or data modification
was performed.

Selected track-finish/title/link lines were read from the known October 28 log
directory, `/storage/4A21-0000/DroneTrax/tracks-28Oct2026`. The extracted events
are in `/private/tmp/r2c-track-label-20261028-events.txt` on the development Mac.
Examples:

- At 18:12:45.932 Main terminates `1sar7DjMn4Pr_180807Oct28`; the worker logs an
  empty title five milliseconds later and archives 102 points at 18:12:46.038.
  A recording link is added at 18:13:07.412.
- At 18:20:56.863 Main terminates `1sar7DjMtrc4td_181357Oct28`; the worker logs
  an empty title two milliseconds later and archives 343 points at 18:20:56.973.
  A recording link is added at 18:21:33.092.

The same pattern appears in several earlier October 28 flights. These log
events establish the title race; the two screenshot short codes have not been
individually resolved to those examples.

`CaltopoClient.terminateTrack` dispatches `finishTrack` from Main and immediately
resets `CtDroneSpec`. Archive/journal/rename work previously read the mutable
label after that reset. `CaltopoLiveTrack` now owns its segment title, captures
restart/rename titles before dispatch, and uses the retained title for live
creation, archive writes, recovery checkpoints, and recording candidates. An
old segment's cleanup does not reset a differently named subsequent flight.

## Apple evidence and correction

The inactivity path archives the track with `publishFlightEnd: true` before
finishing its CalTopo publication. Flight-end observers remove session and peer
identities. `finishPublication` previously queried identity afterward, so a
session-only designator could be missing from both immediate and deferred
recording matching. The recording catalog identifies files by aircraft
designator, whereas the remaining Remote ID alone does not match those files.

The previously saved October 27 14:28:12 iPad diagnostic log corroborates the
lifecycle: a session-only Mini 4 Pro confirmation is cleared at 14:31:29.820,
before CalTopo reports the track stopped at 14:31:30.975. No current iPad device
diagnostics or physical tests were obtained for this change.

The app now captures an immutable `CaltopoRecordingAssociation` before archive
and flight-end callbacks. Immediate and deferred video lookups share its
designators and original waypoint time range. The shutdown path uses the same
snapshot rule. Recording matching still requires identity and time overlap;
unrelated or stale recordings are not linked.

## Execution context and validation

Android retains a small volatile title; it adds no UI lock or storage work.
Existing `CaltopoRecoveryWork` dispatch, journal ordering, and publication
generation checks remain in place. The Apple snapshot copies at most two
identifiers and the endpoint times on MainActor. Catalog filesystem scans stay
in the existing detached utility task; CalTopo publication stays in
`AppleCaltopoPublisher` / `CaltopoLiveClient` actors.

- 98 Android unit tests passed: live tracks, interrupted journals, recovery work,
  drone specifications, waypoint tracks, replay ownership, and identity promotion.
  New regressions cover a reset and subsequent-flight label before delayed
  archive, durable journal title, and a renamed segment reset before archive.
- Six focused Swift tests passed, including the new completed-flight identity
  snapshot, Remote ID fallback, exact recording association, stale recording
  rejection, and bounded finalization retry behavior.
- Unsigned generic iOS Debug app build passed, including the actual
  `RIDTrackViewModel` call sites. Output:
  `/private/tmp/r2c-track-link-ios-build/Build/Products/Debug-iphoneos/RID2CaltopoApple.app`.
- `git diff --check` passed.

Build/test logs are `/private/tmp/r2c-track-label-android-tests.log`,
`/private/tmp/r2c-track-link-apple-tests.log`, and
`/private/tmp/r2c-track-link-ios-build.log`. The Android test runtime emitted
existing JaCoCo/JDK 25 instrumentation warnings; all 98 test-result XML cases
report zero failures or errors.

Version and build identities were not changed. At completion of the source
validation, the corrections had not been installed. Existing CalTopo objects
were not edited; installing the fixes does not retroactively rename their
blank titles or attach missing historical recording links.

## Installation follow-up requested by the owner

Both tablets were subsequently updated in place on October 29, 2026. OS checks
confirmed the expected application identifiers and version 2.5.5 (432).

| Device | Installation time (PDT) | Artifact SHA-256 |
| --- | --- | --- |
| A5 Pro, SM-X350 | 06:11:09, OS `lastUpdateTime` | APK `eb2931ae1e0bf97deb3e841519c82294017f6e58def27f64e2eafb371aa42118` |
| iPad Pro 11-inch, third generation | 06:12:02, successful install result completion | Signed bundle tree `4e4f1e8641c7456bd472b8920b828b4e96751f12c9fba4833d5be5b62376a01b` |

Android used `adb install -r`; its original October 19 07:09:18 first-install
timestamp remained unchanged. The iPad received a signed optimized development
Debug build (Swift `-O`) via `devicectl device install app`. Its existing team
and application identifier were preserved, and system signature verification
passed before installation.

Artifacts, hashes, install results and before/after OS identity/version checks
are retained under
[track-label-video-links](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/track-label-video-links),
including the
[A5 Pro record](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/track-label-video-links/a5pro-install-record.json)
and [iPad record](/Users/kjt/Projects/RID2Caltopo/build/releases/2.5.5-432/track-label-video-links/ipad-install-record.json).

These were installation-only operations. Neither app was launched or exercised;
no physical functional/instrumentation tests, backups, data/permission resets,
uninstalls or private-data inspections were performed. Flight verification is
still pending.
