# Drone Ownership local configuration — November 8, 2026

Version **2.5.10 (439)** stops the Settings screen from calling Tracker when Drone Ownership changes. The 2.5.9 build reported `Tracker could not save Drone Ownership (HTTP 404)` because it PUT `/api/v1/organization-config/drone-ownership`, an endpoint that is not in the deployed tracker.

The setting now follows RID Map Entries: an allowed user edits and saves it on this device, and it takes effect immediately. Organization distribution is only the existing System Admin request, review, and approve of the device's organization-configuration snapshot. A person enrolled in an organization who is not a current `config_admin` can see the value and cannot change it. A device that is not enrolled can change it directly. No network call is made for the edit.

A later snapshot that repeats the last applied organization value does not replace a staged local edit. A snapshot that publishes a different value replaces it, the same way a newer RID map snapshot replaces the saved aircraft list. The value is stored per organization scope, so leaving an organization or joining another one does not keep the previous organization's setting as the active value.

The apps were not launched. This record does not claim the Settings screen or a System Admin upload was exercised on a device.

## Tests

Passed:

```
./gradlew :app:testDebugUnitTest --offline \
  --tests org.ncssar.rid2caltopo.data.DroneOwnershipPolicyTest \
  --tests org.ncssar.rid2caltopo.data.OrgConfigBundleTest \
  --tests org.ncssar.rid2caltopo.data.FlightOwnershipContextTest
```

```
swift test --package-path apple --filter DroneOwnershipPolicyTests
```

Apple: 4 tests passed. Android: `BUILD SUCCESSFUL`.

Tracker, using `/Users/kjt/Projects/r2c-tracker/.venv` and not deploying anything:

```
.venv/bin/python -m unittest \
  tests.test_organization_routes.OrganizationRouteFlowTest.test_drone_ownership_is_optional_on_the_existing_configuration_snapshot \
  tests.test_organization_routes.OrganizationRouteFlowTest.test_managed_config_snapshot_excludes_organization_policy_and_device_secrets \
  tests.test_flight_readiness_presentation
```

Result: 5 tests, OK.

## Installations

In-place only. Neither app was uninstalled or launched. Android `firstInstallTime` stayed 2026-10-19 07:09:18.

| Device | Evidence | Artifact SHA-256 |
| --- | --- | --- |
| A5 Pro, R52Y90C9XST | `dumpsys package org.ncssar.rid2caltopo` reports versionName 2.5.10, versionCode 439, lastUpdateTime 2026-11-08 17:19:21. | APK `95cb436b9e2b7b41f6b229fa89c7c9b470759745b7d9e2e3756b162624516d1b` |
| Ken's iPad, 00008103-0010596E1AD3001E | `devicectl device info apps` reports RID2Caltopo 2.5.10 (439). The inventory did not expose an installation time. `codesign --verify --deep --strict` passed. | Signed app bundle ZIP `e958dd860e3c26e68983860706e7ff6cf2e3d16743269a44705dac49c1513303` |

iPad executable SHA-256 `780e64f3eb95b4248312f2f833af762575618c0a5c9bd29add59a6279a1a65dc`. Code-directory hash `4ba7818dfcd87471e18ee4e22d22d549ffb481bb`.

## Tracker release readiness

Not deployed. `gcloud` was not used.

Deployed tracker `HEAD` (`validated_organization_config_snapshot`) allows only `configSchemaVersion`, `sourcePlatform`, `sourceAppVersion`, `sourceAppBuild`, `organizationCaltopoEnc`, `mutualAidCaltopoEnc`, `droneSpecs`, and `aircraftSchemaVersion`. A snapshot that contains `droneOwnership` is rejected as an unsupported field. That is why organization distribution cannot be tested against production yet.

The local tracker checkout on `project/modularize-coordination` already has undeployed work that:

- accepts optional `droneOwnership` on the existing organization-configuration snapshot
- keeps the current value when an older app uploads a snapshot that omits the field
- rejects a value other than `all_detected`, `streaming_device`, or `streaming_shared_incident`

This session added the ownership change to `organization_config_diff` and to the System Admin proposal review, so an administrator can see it before approval. Those tracker edits are local only. They are not committed and were not deployed.

The same undeployed checkout also has `PUT /{designator}/api/v1/organization-config/drone-ownership` and a System Admin form that saves immediately. The 2.5.10 apps do not call that endpoint. Local edit plus the existing snapshot approval is the distribution path. The immediate PUT is not required for it, and it is not on production, which is the HTTP 404 from 2.5.9.

Before an organization upload can be tested end to end, the tracker snapshot allowlist and the proposal diff have to be released. Until then, a System Admin pull from 2.5.10 will be rejected by production because the snapshot now includes `droneOwnership`. Local edits on each tablet do not need that release.
