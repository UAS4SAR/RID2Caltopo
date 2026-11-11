# Ownership review and recovery — November 8, 2026

Version **2.5.9 (438)** completes the local hold, operator review, and crash-recovery path for an unverified Mine claim. Both tablets were updated in place. The apps were not launched, so this record does not claim the flight workflow was exercised on a device.

## Policy that was recovered

`CurrentFlightPublicationGate` on both platforms already says a flight publishes only after an explicit Mine confirmation. Not Mine / Ignore stays local. With a map and network but no Mine or Not Mine selection, the flight stays local and is not treated as verified ownership.

The earlier note that Ken preferred "option 1" for that case did not include the option text, and the repository does not contain that wording. This build follows the explicit gate above rather than guessing a more permissive publication rule.

An explicit clue Submit is unchanged: it still publishes that clue from the waypoint nearest the snapshot time, a new snapshot stays disabled while a clue is pending, and Yes on "Current flight ignored, publish?" still reopens the Drone Confirmation Panel. Submitting a clue does not release a held flight, and a held flight does not block a different flight.

## What the build does

- Mine selected, tracker claim still pending, rejected, conflicting, or expired: the ended flight stays in local review. CalTopo publication and tracker upload stay held until the operator chooses Confirm and publish. The recorded pilot is kept. No pilot is invented.
- Mine selected and the claim is verified, or tracker was never asked (offline / no coordination): the existing publication path remains.
- A waypoint journal with no pilot or claim line is recovered as a held local flight. Its review copy has no RPIC. Confirm and publish does not fill one in.
- A journal that records Not Mine, Ignore, or no selection is kept and is not offered for publication.
- Releasing one flight does not change another flight's hold.

## Tests

These passed. They are unit tests, not device flights.

```
./gradlew :app:testDebugUnitTest --offline \
  --tests org.ncssar.rid2caltopo.data.FlightOwnershipContextTest \
  --tests org.ncssar.rid2caltopo.data.WaypointTrackJournalTest \
  --tests org.ncssar.rid2caltopo.data.AwaitingMapFlightsTest \
  --tests org.ncssar.rid2caltopo.data.WaypointTrackTest \
  --tests org.ncssar.rid2caltopo.data.TrackerPeerCoordinatorTest \
  --tests org.ncssar.rid2caltopo.data.DefaultPeerCoordinatorTest
```

```
swift test --package-path apple --filter FlightOwnershipContextTests
```

Apple result: 5 tests in `FlightOwnershipContextTests` passed. Android: `BUILD SUCCESSFUL` for the test tasks above.

## Installations

In-place only. Neither app was uninstalled or launched. Android `firstInstallTime` stayed 2026-10-19 07:09:18.

| Device | Evidence | Artifact SHA-256 |
| --- | --- | --- |
| A5 Pro, R52Y90C9XST | `dumpsys package org.ncssar.rid2caltopo` reports versionName 2.5.9, versionCode 438, lastUpdateTime 2026-11-08 16:35:12. | APK `e37faafc9638d39aed6ffcf81140a3d454ba556b8186208b746dcc1d39c0ced9` |
| Ken's iPad, 00008103-0010596E1AD3001E | `devicectl device info apps` reports RID2Caltopo 2.5.9 (438). The inventory did not expose an installation time. `codesign --verify --deep --strict` passed. | Signed app bundle ZIP `66556b4eb5673142c78a05cd2b7b72f231f30a437e3cebe427a9112b23d5fb43` |

iPad executable SHA-256 `28927e9a7bfc69e730da73b12a6193a6d41fd80aa2e0d320737cb34f8055e89e`. Code-directory hash `bfa2c8e5cc9909e6c9a42bbbe3d2d1a7e25cb8c5`.

An earlier 2.5.9 APK that still contained unrelated uncommitted location-monitor edits was installed at 16:33:13 and then replaced by the APK above.

## Tracker

Not deployed. The r2c-tracker checkout `project/modularize-coordination` still has uncommitted claim-persistence and drone-ownership admin changes. Production was not updated and those changes were not merged.

The flight hold works against the tracker that is already deployed: a Mine confirmation stays pending until this device's `drone_confirmed` echo. `drone_claim_unavailable` is recognized if a future tracker sends it. The organization Drone Ownership save calls `PUT /{designator}/api/v1/organization-config/drone-ownership`, which is only in the undeployed tracker diff. Until that is deployed, the org-wide setting cannot be saved; devices keep the default `all_detected` when a snapshot omits the field.
