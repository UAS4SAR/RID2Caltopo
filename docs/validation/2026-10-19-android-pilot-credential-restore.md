# A5 Pro protected DJI setup restore — build 403

The owner confirmed that the iPad has working DJI credentials and that the A5 Pro
should use the same registration. The A5 Pro is enrolled but has no protected
credential file or import receipt. Android build 402 omitted the protected
provisioning asset; the previous temporary bundle no longer exists. The absence
of saved credentials predates this diagnostic check; its original cause is not
established.

Added an explicit Debug-only iPad administration operation and host deployment
script to seal the iPad's working registration for the A5 Pro enrollment. No
production export endpoint or normal-user export flow was added. The host copies
only the public recipient enrollment and encrypted response. A pinned recipient
fingerprint and unique request receipt prevent selecting another destination or
reusing stale output. The existing Android v1 import format is preserved. All
Keychain, crypto and filesystem work runs on the detached startup worker, never
MainActor. Neither device is reset and no physical instrumentation is used.

The synthetic test verifies actual OpenSSL OAEP SHA-256/MGF1 SHA-1 decryption,
AES-GCM authentication, all three credential values, randomized envelopes,
recipient mismatch, invalid enrollment and trailing DER rejection. Existing
Apple recipient/import tampering tests pass. The Apple development device build
passes. Build 403 was installed in place on the iPad; A5 Pro remains at 402.

Automatic approval review rejected execution of the protected transfer tool:
the exact sensitive payload and destination require explicit owner authorization.
The tool did not run and no credential transfer occurred. The review is not
being bypassed. Awaiting explicit authorization to copy the iPad's DJI app ID,
app key and license as ciphertext for the A5 Pro via the Mac. The proposed
persistent output is the ignored `.local-deployment/dji-a5pro-sealed.json`.

## Authorized recovery completed — build 404

The owner explicitly authorized transfer of the iPad's DJI app ID, app key and
license as ciphertext for the A5 Pro via the Mac. The same protected-transfer
command was rerun with that authorization and succeeded. The Mac received only
the recipient-encrypted Android v1 envelope. It is retained in the ignored,
private `.local-deployment/dji-a5pro-sealed.json` rather than temporary storage.
The APK's asset was checked byte-for-byte against that envelope and its recipient
was independently checked against the connected Android enrollment.

Build 404 starts Android's existing synchronized provisioner from a dedicated
background thread, matching Apple's background startup preparation. Locked-device
failures can retry from mapping settings. Credential and certificate status reads
in the Android setup dialog were also moved into Dispatchers.IO; rendering only
uses prepared state, with inputs disabled until loading finishes. Existing receipt
semantics preserve manual changes after the same bundle has been accepted.

Both tablets were updated in place. Launching the A5 Pro completed import: its
protected credential file exists and the acceptance receipt equals SHA-256 of the
exact authorized envelope. No credential contents were read back. A new Pilot 2
license/connection field test is still an operator verification step.

## Release regression and guard evidence

The retained 2.5.2 APK, AAB and IPA match all three SHA-256 values recorded for
the published release. All three lack `dji-provisioning/sealed.json` and are
rejected by the new artifact verifier. Previously configured development tablets
masked the absence; build/install success did not establish fresh-install setup.

- Android `verifyDjiReleaseProvisioning` fails without input and passes with the
  protected A5 Pro envelope. It is now a prerequisite of releaseVerification.
  assembleRelease/bundleRelease also verify the exact final packaged asset.
- Apple Release builds have an always-running build phase that requires a valid
  Apple v2 envelope, copies it and verifies the result. Debug builds remove stale
  output when no envelope is selected. The archive preflight, full release check,
  unsigned-archive and exported-IPA verifiers also enforce provisioning checks.
  The preflight missing-input negative check fails for the intended reason.
- Four Python regression tests pass: Android/Apple format separation, malformed
  or plaintext input rejection, absent APK/AAB/IPA/archive assets, exact-byte input
  mismatch, Apple missing Release input and Debug stale-asset cleanup.
- Apple Debug build passes with the new phase. Android Debug build passes with
  the protected envelope. Shell syntax, Xcode project syntax and diff checks pass.
- The release runbook now requires fresh-install and upgrade evidence on exact
  store candidates, using separate qualification devices to preserve user data.

No store release was rebuilt, uploaded or published. A public fresh-install
bootstrap is not implemented: existing envelopes are installation-specific. The
owner has been asked whether all fresh installs should automatically use R2C's
shared registration or receive it through organization/admin provisioning.
Packaging checks alone do not resolve that product decision or prove universal
registration availability.
