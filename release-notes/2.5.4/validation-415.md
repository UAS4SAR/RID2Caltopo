# 2.5.4 build 415 — diagnostic-sharing context

October 24, 2026. Both tablets report **2.5.4 (415)** and launched normally.
Display-only follow-up to [414](validation-414.md).

Both clients now say: "When sending diagnostics to the developer, you choose
which log days to package." The rest of that paragraph retains the selected-day,
location-detail and optional JSON-track explanations.

Replaced "This does not stop other configured service exchanges" with:
"CalTopo publishing, tracker coordination, and video sharing operate separately,
according to their own settings." The purpose is to distinguish the operator's
manual support bundle from enabled operational sharing. Selecting or declining a
diagnostic bundle is not a control for those features. Existing Android Firebase
Analytics/Crashlytics disclosures remain separately visible under Privacy.

## Terms question reviewed

The Terms of Use menu opens the app's Safety & License acknowledgment. Both
clients use the same operational/safety notice, software-license explanation
and separate connected-service paragraph. The checkbox explicitly describes
individual acknowledgment. The save handlers retain the notice version/text and
timestamp on the device; they do not submit an organization's acceptance of a
CalTopo or hosted tracker service agreement. That separation is the intended
meaning of the final sentence. This is a description of the implementation and
wording, not a determination of contract enforceability.

No Terms text, acceptance version/fingerprint, checkbox behavior or save handler
was changed. Existing acknowledgment records therefore are not invalidated by
this build. The reviewer source record is retained in
[terms-review.json](../../apple/Build/ReviewCandidate-415/terms-review.json).

## Verification and scope

- All 24 shared paragraphs/permission rows still match in wording and order.
- Fresh Android compilation and Apple Release archive, device/distribution signing,
  packaged shared-copy/canonical-note/identity checks, privacy declarations and
  protected-registration checks. The vague service-exchange sentence is rejected
  by the package verifier in both DEX and Apple binaries.
- Existing metadata validation includes four UI-copy and seven privacy tests.
  No new runtime tests or full-suite rerun for these two static-text edits.
  Prior [412 backend evidence](validation-412.md) and the 407 full release gate
  retain their original scope; this build does not establish fresh flight,
  background, sustained-video or physical microphone qualification.
- Static text rendering adds no parsing, disk, provider or network work to either
  UI thread. Runtime sharing, audio, recording, credentials and backups are unchanged.
- Operational-only backups from the continuous 409–415 update session are reused.
  In-place installs preserve app identity; no uninstall, data clear, reset or
  managed Android instrumentation runner is used. Verification checks 264 Android
  private operational paths, two removable map-cache files and 15,351 iPad files.

[Shared copy](../../apple/Build/ReviewCandidate-415/shared-about-copy.json),
[package hashes](../../apple/Build/ReviewCandidate-415/artifacts.json) and
[source inputs](../../apple/Build/ReviewCandidate-415/source-inputs.json) accompany
build and install logs. No publication, upload, resubmission, commit or tag.
The [remaining resubmission work](validation-412.md#before-resubmission) applies
with **415** as the next candidate.

## Android installation result

A5 Pro reports 2.5.4 (415). Normal launch returned `Status: ok`; main and isolated
browser processes remained running. All 264 baseline private operational paths
and both removable map-cache files remained present. Retained crash-buffer
comparison found no new app-process crash after the in-place update.

## iPad installation result

The iPad reports 2.5.4 (415), launched normally and retained its running app
process. All 15,351 baseline operational files remained present. Only the public-land cache changed size after normal refresh; all other baseline
sizes were unchanged. The update retained app identity and protected storage.
