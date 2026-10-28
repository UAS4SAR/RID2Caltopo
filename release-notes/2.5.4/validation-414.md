# 2.5.4 build 414 — controller, media-server and microphone explanations

October 24, 2026. Both tablets now report **2.5.4 (414)** and launched normally.
Display-only follow-up to [413](validation-413.md).
Recording, sharing, microphone controls and credential behavior are unchanged.

## Copy changes

- Both screens use the requested sentence: "Controller and local-browser
  connections can exchange flight plans, settings, aircraft and controller
  identifiers, telemetry, and original photographs." Existing CalTopo sign-in
  and organization/map visibility explanations remain after it.
- The Capture Streams paragraph now first identifies MediaMTX as the media server
  running on this device to receive incoming RTMP controller streams. It then
  explains local recording and direct-viewing restrictions using the existing
  setting defaults. This describes the existing ingest/recording path.
- Replaced the vague hardware-off warning with identical shared wording explaining
  outgoing voice muting and continued incoming sound. A separate paragraph on
  each platform describes its actual microphone capture behavior.

## Why the microphone detail differs

The earlier generic warning was too broad for Android. In the existing Android
implementation, `ManagedVideoMediaPeer.setMicrophoneEnabled(false)` calls
`peer.setAudioRecording(false)`, disables its outgoing track and deactivates the
sender. The app also disables capture at peer creation. The
[WebRTC API](https://webrtc.googlesource.com/src.git/+/5b2567d137fa05094278483573289b2b150fc292/sdk/android/api/org/webrtc/PeerConnection.java)
identifies this as the audio-recording enable/disable control.

The existing Apple peer instead toggles `audioTrack.isEnabled` while keeping its
WebRTC system audio session enabled for incoming sound. That session uses
`playAndRecord`/`voiceChat`; its microphone can remain active while the outgoing
track is muted. About & Privacy now says this directly on Apple. Android's extra
paragraph says the app disables capture for the voice connection when muted.
The shared public policy source also distinguishes these existing behaviors.
No runtime audio implementation was changed for this wording correction.

## Verification

- All 24 shared paragraphs/permission rows remain identical and in matching order.
  The old Pilot 2 transfer sentence and vague hardware-off warning are absent from
  both privacy screens. The camera and microphone retain separate rows/icons.
- Fresh Android compilation and Apple Release archive, separate device/distribution
  signing, package identity/notes checks, privacy declarations and protected
  app-registration checks. The package verifier checks every shared paragraph
  in the actual DEX/Mach-O bytes and rejects the retired hardware-off sentence.
- Existing metadata validation includes four UI-copy and seven privacy tests.
  No new runtime tests were added or prior full suites rerun for static copy.
  [412 validation](validation-412.md) records the backend/session tests; 407 is
  the prior full native/shared/Simulator release-gate evidence.
- No file access, parsing, network or audio work was added to UI rendering.
  These source checks do not establish a new physical microphone/OS-indicator,
  flight, background or sustained-video qualification result.

[Shared copy](../../apple/Build/ReviewCandidate-414/shared-about-copy.json),
[artifact identities/hashes](../../apple/Build/ReviewCandidate-414/artifacts.json)
and [source inputs](../../apple/Build/ReviewCandidate-414/source-inputs.json) are
retained with build/install logs. The corrected public policy source is local;
GitHub authorization remains read-only. No commit, tag, publication, store upload
or resubmission is performed.

In-place updates reuse the operational-only safety baseline from the continuous
409–414 session. No uninstall, data clear, reset or managed Android instrumentation
runner is used. Post-install verification checks the 264 private Android
operational paths, both removable map-cache files and 15,351 iPad operational
files without reading credential values. The
[remaining resubmission work](validation-412.md#before-resubmission) applies to
**414** as the next candidate.

## Android installation result

A5 Pro reports 2.5.4 (414), and normal launch returned `Status: ok`. Main and
isolated browser processes remained running. All 264 baseline private operational
paths and both removable map-cache files remained present. Retained crash-buffer
comparison found no new app-process crash after the in-place update.

## iPad installation result

The iPad reports 2.5.4 (414), launched normally and retained its app process.
All 15,351 baseline operational files remained present with unchanged sizes.
The update retained app identity and protected storage.
