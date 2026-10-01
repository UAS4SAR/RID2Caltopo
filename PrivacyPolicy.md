# RID2Caltopo Privacy Policy

Last updated: September 30, 2026

RID2Caltopo is an incident-support application for Android, iPhone, and iPad.
It does not contain advertising or analytics SDKs, does not track people across
apps or websites, and does not sell personal data.

## Data used on the device

- Bluetooth and compatible external-network Remote ID observations are decoded
  to display nearby aircraft and create local track archives.
- Location permission is used to place the operator relative to aircraft on the
  map and support configured tracker coordination. Location is not collected
  for advertising or cross-app tracking.
- Local-network access is used for controller video, optional external Remote ID
  observations, and configured incident coordination services.
- Configuration QR codes may contain organization, incident, Remote ID mapping,
  CalTopo, tracker, FAA, and mutual-aid settings. Credential secrets are stored
  in platform-protected storage, including Apple Keychain on iPhone and iPad.
- Optional personal CalTopo sign-in uses an embedded browser. Its persistent,
  app-private browser profile retains CalTopo session cookies and identity-provider
  sign-in state until cleared in the app or expired by the provider. These browser
  records use operating-system storage protection; they are not a separate
  application-encrypted credential vault. Clear login removes this app's local
  browser state, but does not revoke sessions already copied elsewhere.
  Authorized native requests use the selected account's session to load maps
  and publish operator-requested tracks or photo clues to CalTopo.
- When tracker peer coordination is configured, the app uses an app-install
  zone identifier and device zone name to coordinate aircraft ownership with
  other RID2Caltopo instances.

## Network services

CalTopo publishing is disabled until it is configured and enabled by the
operator. When enabled, aircraft positions and associated telemetry are sent to
the operator-selected CalTopo map.

When enabled by imported organization configuration, tracker peer coordination
sends the app-install zone identifier, device zone name, operator position,
confirmed drone identity, and aircraft sightings to the configured tracker.
This is used to prevent field devices from independently publishing or warning
for the same aircraft.

When nearby NOTAM monitoring is enabled, the app sends the operator's current
latitude, longitude, and selected search radius to the configured tracker. The
tracker authenticates to the FAA NOTAM service, forwards the geographic query,
and returns the FAA response to the app. RID2Caltopo does not send FAA client
credentials from the app or request an FAA bearer token. The tracker may cache
the FAA response briefly for a small geographic area.

Apple MapKit or the configured Android map provider may contact its map service
to load map content. A configuration import may contact its specified public
file host to retrieve the operator-selected configuration bundle.

When protected-land checks are enabled, the app sends a small geographic search
area around the operator's current location to public National Park Service,
U.S. Fish and Wildlife Service, U.S. Forest Service, and Colorado Parks and
Wildlife boundary services. The returned nearby boundaries are cached locally
for offline field reference. RID2Caltopo does not send an identity or aircraft
track with these boundary requests.

## Files and diagnostic sharing

Track archives and diagnostic logs remain in the application's local storage
unless the operator explicitly exports or shares them. On iPhone and iPad,
these files are also available through the Files app when file sharing is used.

On iPhone and iPad, the operator may enable configuration backup to the app's
iCloud Drive container. That backup can contain operational configuration,
Remote ID mappings, and credentials, and is encrypted with the operator's
passphrase before it leaves the device. The passphrase used for automatic
backup is stored only in that device's Apple Keychain and is not included in
the iCloud file. The operator can disable automatic backup at any time; the
existing cloud file remains until the operator removes it from iCloud Drive.

The operator can select log days, create a diagnostic bundle, and choose a
recipient through the platform share sheet. Diagnostic log messages are
filtered to remove location details. JSON track files, which contain aircraft
positions, are excluded unless the operator explicitly chooses to include
them. A shared bundle may contain Remote IDs, the app-install coordination
identifier, app events, device and operating-system details, local-network
addresses, and operational status. Credential secrets and configuration tokens
are excluded. Nothing is transmitted by the log sharing feature until the
operator chooses a destination.

## Retention and deletion

Locally stored archives and logs can be deleted through the application's file
storage or by deleting the app and its data. The encrypted configuration backup
can be removed from iCloud Drive. Deleting the app removes its local app-install
zone identifier and local backup passphrase, but does not itself delete an
existing iCloud Drive backup. A diagnostic recipient controls retention after a
bundle is shared. CalTopo and the configured tracker operator control retention
of data deliberately sent to those services.

## Contact

Questions about this policy or deletion of a diagnostic bundle can be sent to
[help@uas4sar.com](mailto:help@uas4sar.com).
