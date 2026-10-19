# App-wide DJI registration correction — 2.5.3 build 406

The owner clarified that DJI registration is app-based and must work for every
instance. The earlier per-device-envelope design and request to select an
operator provisioning policy were mistakes. This document supersedes the release
policy and recovery utility described in the build-403/404 validation record.

Both apps now prefer a shared registration asset, requiring neither enrollment
nor preexisting local credentials. They import it into the installation's secure
store in background work. The same FAA configuration transport encodes the app
registration, with a SHA-256 content-integrity check. It is client-side obfuscation;
it does not claim secrecy against app-package inspection. Actual registration
input is private, ignored by Git, and not printed in commands or logs.

The release verifier requires the shared format, rejects old per-tablet envelopes,
and validates the actual packaged bytes. Both builders default to the durable
private `.local-deployment/dji-app-registration.json`, with one common
R2C_DJI_APP_REGISTRATION override for CI. Release builds fail without a valid app
registration. The release runbook requires fresh and upgrade smoke testing.

The startup import records its receipt only after protected-store success. A
missing secure item is repaired even if the receipt remains; the same accepted
registration does not rewrite a later administrator override. Local Keychain,
Keystore, asset/receipt and cryptographic operations stay on background workers.
The setup views now describe manual fields as administrator overrides; both
platforms load and save this status away from the UI thread.

A shared synthetic fixture is tested on Android and Apple without enrollment or
stored credentials. Separate empty-store initialization and failed-write handling
are tested. No physical device is reset for fresh-install testing.

The corrected capture utility is prepared, but automatic approval review rejected
capture of the iPad's protected app registration for distribution with all app
instances. The owner has been asked explicitly to authorize that payload and
scope. No app-wide capture has occurred as of this record. Build 405 was installed
on iPad only to prepare the capture utility; A5 Pro retains the recovered build
404. Build 406 synthetic packages must not be installed on operational tablets.

## Verification completed with synthetic registration

- Android mapping: 157 tests, zero failures, one preexisting optional skip.
- Apple registration/legacy/Pilot connection: nine tests, zero failures.
- Shared release-gate suite: four tests pass, including actual APK/AAB/IPA/archive
  lookup, absence/corruption, exact-byte mismatch and Xcode phase behavior.
- Android and Apple development builds pass. Both contain the exact same shared
  synthetic registration fixture. Neither synthetic package was installed.
- Both platform release preflights reject an explicitly missing shared input.
  The previous A5 Pro device-bound envelope is rejected for both platforms.
- Release notes, metadata, shell/project syntax and diff checks pass.

The real-registration capture is still blocked by automatic approval review
pending explicit owner authorization for reading the iPad's DJI app ID, app key
and license and packaging them for distribution with every mobile installation.
No broad capture, new store artifact, upload or publication occurred.

## Owner-authorized bundled release and completed installation

The owner explicitly deferred a tracker release and requested the shared DJI
registration be bundled for this release, with installations on both devices.
The authorized capture succeeded after correcting the host deployment command
to place `--` before the app's capture arguments. This was an argument-passing
issue; the iPad's non-secret status already reported available credentials.

The real encoded registration is retained in the ignored durable private input
`.local-deployment/dji-app-registration.json`. Both build-406 packages were
rebuilt from it, then checked byte-for-byte against the real source and against
each other. The synthetic fixture is different and was not installed. Both
packages also contain identical updated Pilot page bytes. Android release-input
verification passes with the real default input; no tracker code or deployment
was changed.

Both apps were installed in place and launched normally. Independent installed
version queries report 2.5.3/build 406. Android's app-registration receipt matches
SHA-256 of the real source, and its protected credential file exists. Apple's
non-secret status reports configured=true, registration=app, and a registration
hash matching the same source. This establishes actual bundled registration
import on both physical devices without reading credentials back. Existing app
data was preserved; no uninstall, data clear, physical instrumentation, flight,
store upload or tracker release was performed.

Operator Pilot 2/controller qualification and final store-candidate smoke tests
remain separate from these successful installation/import checks. The owner can
now test diagnostic and phone formatting with build 406 on both tablets.

## Owner acceptance

The owner verified build 406 on both platforms and explicitly requested source
commit/tag `v2.5.3`, push, Google Play production, and TestFlight internal and
external distribution. This authorizes the coordinated 2.5.3 (406) release.
