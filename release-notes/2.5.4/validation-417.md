# 2.5.4 build 417 — device-specific privacy-policy links

October 25, 2026. Installed in place and launched successfully on the iPad and
A5 Pro. Both devices report **2.5.4 (417)**.

Each About & Privacy link now selects the matching standalone policy:
`PrivacyPolicyApple.md` or `PrivacyPolicyAndroid.md`. Both documents use “the app,”
share the same general disclosures and include only the relevant device-specific
details. `PrivacyPolicy.md` remains an index for older links. The Android About
text now says “The app uses Firebase Analytics,” without a platform qualifier.
Canonical release notes describe the revised policy link and wording.

Recording, sharing, microphone controls, credential persistence, backups and
the safety acknowledgment are unchanged by this build. No unbounded work was
added to either UI thread.

## Verification

- Android debug compilation and Apple Release archive compilation passed.
- Metadata validation, four store-wording tests and seven privacy-declaration
  tests passed. Shared policy disclosures match; platform-specific disclosures
  are isolated in the correct documents.
- Both installation packages contain the matching policy URL, exact canonical
  release notes, all 24 shared About rows and the existing complete safety notice,
  checkbox and notice version. Retired credential-export controls remain absent.
- Android APK signature checks and Apple archive, device signature, team,
  app identity and target-device provisioning checks passed.
- A5 Pro launch returned `Status: ok`, retained a running process and showed no
  new app crash in the crash-buffer comparison.
- iPad launch succeeded and its app process remained running.

## Data preservation

Updated the existing operational-only safety backup with 108 changed/new iPad
files, four changed Android files and the two external Android map-cache files.
Authentication, enrollment and saved-configuration stores were not copied.
Android installation used `adb install -r`; Apple retained the same application
and Keychain identity. No uninstall, data clear, reset or managed instrumentation
runner was used.

All 264 Android private operational paths and both external map-cache paths
remained. All 15,457 pre-install iPad operational files remained, with unchanged
sizes. No operational acknowledgment was accepted on the user's behalf.

## Artifacts and publication

[Package hashes](../../apple/Build/ReviewCandidate-417/artifacts.json),
[source inventory](../../apple/Build/ReviewCandidate-417/source-inputs.json),
build logs and installation summaries are retained in `ReviewCandidate-417`.
The Apple export is signed for device review; this report does not claim a new
distribution export or renewed flight/background/sustained-media qualification.

The new public policy files have not been published. Their links require
publication before distribution. No commit, push, store upload, review message
or resubmission was performed.
