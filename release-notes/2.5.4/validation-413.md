# 2.5.4 build 413 — matching About & Privacy explanations

October 24, 2026. Both tablets now report **2.5.4 (413)** and launched normally.
This display-only follow-up aligns the shared privacy content
and section order on both clients. Build 412 contains the credential-transfer,
backup and browser-startup changes; see its [validation](validation-412.md).
No recording, sharing, approval, microphone, credential or backup implementation
was changed in 413.

## What was corrected

Android had a shorter Permissions section, omitted the entire Video sharing and
recording section, omitted photo/controller-transfer explanations, placed the
policy link under Additional information and used different deletion text.
These were differences in maintained copy, not intentional feature differences.

Both screens now use Privacy, Permissions and network use, Video sharing and
recording, Logs and deletion, and Contact in that order. The policy link appears
under Privacy. **Twenty-four shared paragraphs/permission rows have identical
wording and order**, including credentials, map tile packages, camera/microphone,
controller/Pilot transfer, media catalogs/approval, local recording, diagnostic
sharing and deletion. Camera and microphone have separate matching icons/rows.
Native platform layouts remain List/sections and scrolling dialog respectively.

## Differences that describe actual platform behavior

| Topic | Android | Apple |
| --- | --- | --- |
| Analytics/crashes | Existing Firebase Analytics and Crashlytics disclosure | No analytics SDKs |
| Credential storage | App-private configuration | Apple Keychain |
| Settings backup | Google Drive and the system backup service | Encrypted iCloud Drive backup and Keychain passphrase |
| Monitoring permissions | Foreground-service notification and Nearby Wi-Fi | Background location indicator |
| Map service | Shared configured-provider explanation | Same explanation plus explicit Apple MapKit disclosure |
| Deletion controls | Remove Drive/system backup copies through their service | iCloud removal, automatic-backup passphrase retention and Apple-only persisted-state/Keychain reset explanation |

Saved credentials persist across restarts and updates on both platforms. A settings
backup omits them without clearing them from the current device. The Apple system
Keychain same-device recovery distinction described in 412 remains unchanged;
no new passcode policy was introduced.

## Verification

- Source comparison confirms all 24 shared rows and the five section headings
  occur in the same order; no mutual-aid wording occurs in either privacy screen.
- Fresh Android debug compilation and Apple Release archive/export compilation.
  These changes only render static text/icons; no disk, parsing, provider or
  network work was added to either UI thread.
- Package verification checks every shared row inside Android DEX and both Apple
  exported app binaries, canonical release-note bytes, build identity, signatures,
  protected app-registration assets and current privacy declarations.
- Existing metadata checks include four UI-copy and seven privacy-validation
  tests. No new runtime tests are needed for these text/icon changes. Prior
  backend evidence is 1,614 Android host tests and 140 XCTest + 590 Swift Testing
  tests on 409, 16 label/lifecycle tests on 411 and 25 personal-session/security
  tests on 412. Those suites were not rerun for this display-only follow-up.
- Build 407 remains the full native/shared/Simulator release-gate evidence.
  Installation and packaged text checks are not exact-build flight/background/
  thermal acceptance or a visual layout review of every device size.

The machine-readable [shared copy](../../apple/Build/ReviewCandidate-413/shared-about-copy.json)
and [artifact hashes](../../apple/Build/ReviewCandidate-413/artifacts.json) are retained
with the build logs. The [source record](../../apple/Build/ReviewCandidate-413/source-inputs.json)
identifies current local changes; no commit, tag, policy publication, store upload
or resubmission is performed by this work.

## Device-data protection and remaining release work

In-place updates use the operational-only safety backups from this continuous
409–413 update session. No uninstall, data clear, device reset or managed Android
instrumentation runner is used. Post-install checks compare the 264 Android
private operational paths, two removable map-cache files and 15,351 iPad
operational files against that baseline. Credential values are not read.

The [remaining qualification and owner-input list](validation-412.md#before-resubmission)
still applies with **413** as the next candidate: review access/demonstration,
owner declarations, live console answers, policy publication and explicit upload/
resubmission authorization. This copy correction does not complete field acceptance.

## Android installation result

A5 Pro reports 2.5.4 (413). Normal launch returned `Status: ok`; main and isolated
browser processes were both running. All 264 baseline private operational paths
and both removable map-cache files remained present. Retained crash buffers had
no new app-process crash record after this installation. No sign-in or settings
reset was performed.

## iPad installation result

The iPad reports 2.5.4 (413) and its normal app process remained running. All
15,351 baseline operational files remained present. Only the public-land cache
changed size after its normal location/timestamp refresh; all other baseline
sizes were unchanged. The install retained the app identity and protected storage.

## Subsequent microphone disclosure correction

[Build 414](validation-414.md) replaces the common hardware-off warning after
tracing both audio implementations: Android explicitly disables capture when
muted; Apple can keep input active through its two-way incoming-audio session.
This is a disclosure correction, not a change in either audio implementation.
