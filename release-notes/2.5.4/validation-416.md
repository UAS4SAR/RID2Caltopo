# 2.5.4 build 416 — individual operational acknowledgment

October 24, 2026. Both tablets report **2.5.4 (416)** and launched successfully.
Owner-requested follow-up to [415](validation-415.md).

Removed the final notice sentence, "This acknowledgement does not accept service
terms or bind your organization," from both clients. Both checkboxes now say:
**"I have read and understand these operational warnings."** The former
organization-representation qualifier is removed. Existing safety warnings,
software-license explanation and separate connected-service terms remain.

The complete notice text and checkbox are identical on both platforms. The
notice version is **2026-10-24.1**. Existing acceptance records are retained;
their previous version/text is no longer current, so each user must explicitly
acknowledge the revised notice once. The installation checks do not check the box
or accept the notice on the user's behalf. Saved service credentials and
operational files are preserved.

## Verification

- Android: fresh development build and three existing `TermsAcceptanceTest` tests
  passed, covering first launch/damaged records, persistence, changed notice/
  version rejection and SHA-256 fingerprinting.
- Swift: two existing `applicationTerms` tests passed, covering persisted records,
  changed notice/version rejection, damaged records, timestamps and fingerprints.
- Fresh Apple Release archive, separate device/distribution exports, signature,
  identity, protected-registration, privacy declaration and canonical-note checks.
- Package checks require the exact new checkbox and notice version, reject both
  removed organization phrases, and verify all 24 unchanged shared privacy rows.
  [Notice review](../../apple/Build/ReviewCandidate-416/terms-review.json) records
  the matching complete notice fingerprint and renewal behavior.
- Existing metadata validation includes four UI-copy and seven privacy tests.
  Existing JDK 25 JaCoCo instrumentation warnings remain; passing JUnit results
  do not claim complete coverage instrumentation. Full backend/native/Simulator
  evidence retains its prior scope in [412](validation-412.md) and 407.
- These are static text/version edits; acceptance persistence/gating logic,
  recording, sharing, microphone, credentials and backup implementation are unchanged.
  No disk/provider/network/parsing work was added to either UI thread.

## Device-data protection and remaining release work

In-place Android installation uses `adb install -r`; Apple retains app identity.
Operational-only safety backups from the continuous 409–416 session are reused.
Checks compare the 264 Android operational paths, two removable map-cache files
and 15,351 iPad operational files. No uninstall, data clear, reset or managed
Android instrumentation runner is used. Credential values are not read and the
old acknowledgment record is not cleared by the installation tools.

[Artifact hashes](../../apple/Build/ReviewCandidate-416/artifacts.json),
[source inputs](../../apple/Build/ReviewCandidate-416/source-inputs.json) and build/
install logs are retained. No commit, tag, policy publication, store upload or
resubmission. The [remaining release work](validation-412.md#before-resubmission)
applies to **416**; installation and focused tests do not establish fresh flight,
background, sustained-video or physical microphone qualification.

## Package identity

The development APK and both Apple exports identify 2.5.4 (416). Each contains
the exact revised complete notice, checkbox text and version 2026-10-24.1; neither
removed organization phrase is present in its compiled app text. Device and
Apple distribution signature/profile checks passed. Canonical release notes
match all packages. Distribution artifacts are retained locally, without upload.

## Installation results

A5 Pro reports 2.5.4 (416) and launch returned `Status: ok`. Its app process was
running, all 264 baseline private operational paths and both removable map-cache
files remained, and crash-buffer comparison found no new app-process crash.
An optional hierarchy read did not expose the safety form's text; it is retained
as observational evidence only, not a visual-form acceptance result. The complete
notice/checkbox/version are independently verified in the installed APK bytes.

The iPad reports 2.5.4 (416), launched successfully and retained its running app
process. All 15,351 baseline operational files remained present. Only the
public-land cache changed size after normal refresh; other baseline sizes were
unchanged. App identity and protected storage were retained on both devices.
No checkbox or acknowledgment action was performed by the agent. The revised
notice must be acknowledged by each user; recording/sharing and credential
settings are unchanged.
