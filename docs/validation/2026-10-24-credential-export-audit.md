# Credential export review — Android and Apple, build 408

Reviewed October 24, 2026 against the current source and the local build-408
packages installed earlier. This is a source/call-path and package-string review;
no real configuration was exported, no credentials were read from the devices,
and no device, runtime code, cloud file or account setting was changed. No new
test run is claimed.

The intended model described by the owner is tracker-hosted organization access
through enrollment QR codes and authorized users, with map-data export remaining
available. The implementation does not yet consistently enforce that model.

## Confirmed findings

### 1. Organization credential exports remain reachable on both clients

- Android Settings can open Developer Tools. Its **Export Org Config** action
  invokes `OrgConfigManager.uploadOrgConfig`, which calls
  `CaltopoClient.BuildOrgConfigBundle`. The bundle includes `team_id`,
  `credential_id`, and `credential_secret`. It uploads a reversibly obfuscated
  credential block to Google Drive, requests public-link reader access, and
  produces an R2C2 QR. Requiring a current enrollment locator does not remove
  the CalTopo secret from the exported bundle. It does omit the source tablet's
  issued tracker device key and carries an enrollment locator instead.
  Evidence: [UI](../../app/src/main/java/org/ncssar/rid2caltopo/ui/MainScreen.kt:2529),
  [upload](../../app/src/main/java/org/ncssar/rid2caltopo/data/OrgConfigManager.kt:399),
  [bundle builder](../../app/src/main/java/org/ncssar/rid2caltopo/data/CaltopoClient.java:2662),
  [public sharing](../../app/src/main/java/org/ncssar/rid2caltopo/data/GoogleDriveConfigSync.kt:483).
- Apple Settings → Advanced → Developer Tools exposes **Export Org Config** and
  **Share Prepared Org Config**. Its builder reads the CalTopo credential secret,
  wraps it as `ct_credentials_enc` using the same fixed-key obfuscation, and
  writes a shareable JSON file. This is separate from encrypted personal backup.
  Evidence: [settings navigation](../../apple/App/CaltopoSettingsView.swift:448),
  [builder](../../apple/App/CaltopoSettingsView.swift:597),
  [secret field](../../apple/App/CaltopoSettingsView.swift:629),
  [export action](../../apple/App/CaltopoSettingsView.swift:737).
- The literal **Export Org Config** is present in both the direct-install Apple
  Release binary and the frozen Android development APK for build 408. These
  are current capabilities, rather than merely historical comments.
- The R2C2 codec uses a fixed, source-visible XOR key and Base64. This is
  reversible obfuscation, not passphrase encryption. No actual prior Drive
  exports or public exposure were inspected in this review.

### 2. Current map-package exporters leave credential profiles empty

Both exporters explicitly write `profile_enc` as an empty string and do not call
the old MA credential-profile builder. Their package entries contain selected
cached map/terrain data, reconstructed mapping layers/surfaces and metadata.
Evidence: [Android manifest](../../app/src/main/java/org/ncssar/rid2caltopo/data/MutualAidPackageManager.kt:330)
and [Apple manifest](../../apple/App/AppleConfigurationTransfer.swift:179).

The standalone Android `MutualAidExportDialog`, `uploadMutualAidProfile`, and
`buildEncryptedProfilePayloadForCurrentIncident` remain defined, but source
search found no live application caller for those export entry points. Their
presence should not be confused with the still-reachable organization exporter.

### 3. Both map-package importers still accept legacy credential profiles

Android reads a nonempty `profile_enc` and calls
`MutualAidProfileManager.installEncryptedProfilePayload`. Apple decrypts that
field and applies the resulting profile to its organization/CalTopo settings.
Thus an older credential-bearing package can still install map-access credentials
even though current exporters no longer create such packages.
Evidence: [Android import](../../app/src/main/java/org/ncssar/rid2caltopo/data/MutualAidPackageManager.kt:96)
and [Apple import](../../apple/App/AppleConfigurationTransfer.swift:276).

### 4. Backups remain another route for credentials off the device

- Apple **Backup & Transfer → Encrypted local backup** creates a shareable,
  passphrase-encrypted file. The same snapshot is used for iCloud backup. It
  contains `caltopo.transferSnapshot()` and `organization.transferSnapshot()`,
  including the CalTopo secret and the tracker API key.
  Evidence: [snapshot](../../apple/App/AppleConfigurationTransfer.swift:188),
  [local share](../../apple/App/AppleConfigurationTransfer.swift:673),
  [CalTopo fields](../../apple/App/AppleCaltopoSettings.swift:278),
  [tracker field](../../apple/App/AppleOrgConfigImporter.swift:767).
- Android's manual Google Drive backup uploads `AppConfigStore.exportConfigBytes`.
  That returns the persisted configuration protobuf, which contains CalTopo
  credentials and tracker keys. The OS backup agent also selects the same
  configuration file. The manual Drive backup has no app-level passphrase
  encryption step in this path; this does not assert that Drive or OS transport
  and storage lack their own protection.
  Evidence: [Drive backup](../../app/src/main/java/org/ncssar/rid2caltopo/data/GoogleDriveConfigSync.kt:186),
  [serialized snapshot](../../app/src/main/java/org/ncssar/rid2caltopo/data/AppConfigStore.kt:117),
  [OS backup](../../app/src/main/java/org/ncssar/rid2caltopo/data/AppConfigBackupAgent.kt:6).

Backups therefore need an explicit design decision when implementing a rule that
no exported/restorable file may carry credentials. This is distinct from keeping
offline map export. Backend handling of restored/cloned tracker keys was not
evaluated here.

### 5. Android retains a legacy FAA credential publisher

Developer Tools still exposes **Publish FAA Config**. If legacy NOTAM client ID
and secret are present, it exports them to a Drive-hosted obfuscated configuration
and creates a QR token. It refuses to proceed if those legacy credentials are
absent. No equivalent active Apple publisher was found.
Evidence: [UI](../../app/src/main/java/org/ncssar/rid2caltopo/ui/MainScreen.kt:2545)
and [publisher](../../app/src/main/java/org/ncssar/rid2caltopo/data/FaaConfigManager.kt:208).

## What to preserve when removing the legacy paths

Both platforms already fetch tracker-managed organization configuration through
authenticated `organization-config/current` requests. These are separate from
the local/Drive export UI and should remain operational. Some shared bundle and
codec functions also serve the tracker snapshot/comparison protocol; deleting
them wholesale would break the newer model.

The removal should target organization/FAA credential export entry points,
unused MA credential-export helpers, legacy credential-bearing package imports,
and any credential-bearing backup/restore behavior the owner elects to retire.
Preserve map-data export/import, tracker enrollment, authenticated managed sync,
and existing local operational files. After implementation, update the privacy
text and release notes to describe the resulting behavior, and test that no
exported payload contains synthetic credential sentinels.
