# Apple submission audit October 23 2026

The immediate hardware question is addressed in the revised review notes. The
submission still needs hardware demonstration, clear optional-service review instructions,
publication of the revised policy, current App Store Connect answers and owner
confirmation of service rights/export classification. The local policy, in-app
summaries, twelve-category disclosure draft and packaging checks are now aligned.
Local checks alone do not establish submission readiness.

October 24 disclosure correction: the shared policy distinguished Android's
existing Firebase Analytics/Crashlytics from the submitted build, which does not
bundle those SDKs. The earlier policy-wide no-analytics claim was incorrect. Review
Google Play Data safety separately against those existing Android integrations;
no live console answers or Firebase project retention settings were changed.
Credential exports and legacy credential file/QR imports have now been removed.
Settings backups allow only supported preferences and aircraft mappings; restore
preserves credentials already saved on the device. Saved credentials remain
persistent. Apple device-bound Keychain items can still be recovered onto the
original iPad through system backup; no new device-passcode requirement is imposed.

The October 24 [2.5.4 (407) candidate validation](../../release-notes/2.5.4/validation.md)
records the completed full Apple release gate. The subsequent
[2.5.4 (408) rebuild](../../release-notes/2.5.4/validation-408.md) includes the
plain-language release notes and corrected credential/analytics disclosures.
The [2.5.4 (416) follow-up](../../release-notes/2.5.4/validation-416.md) records
credential-transfer removal, safe settings backups and the final privacy wording.
Remaining hardware evidence, optional-service instructions, owner declarations and publication
steps still apply. Earlier source-only validation below is historical evidence.

October 24 interoperability clarification: local review notes now answer the two
hardware questions directly, distinguish ASTM/Bluetooth and RTMP from CalTopo
Teams/account APIs and the newer DJI-specific Cloud API, and state independent
non-endorsed distribution. The public-description draft is aligned. A separate
342-only Resolution Center reply is prepared. See
[remaining owner inputs and supporting references](INTEROPERABILITY_AND_REVIEW_READINESS.md).
These are post-build metadata drafts; installed/signed 416 and its operational
acknowledgment have not changed. App Store Connect was checked again and requires
sign-in. No message, console field, upload or publication was submitted.

## Submission identity and evidence

Apple's supplied review message identifies **2.5.1 (342)**, submission
`9c3dbf61-18f3-41d7-b3b0-aba4be8398e2`, reviewed October 23 on an iPad Air
11-inch (M3). That is Apple's review device, not a developer qualification result.

The retained signed `apple/Build/TestFlight-signed-342/RID2CaltopoApple.ipa` and
`apple/Build/RID2CaltopoApple-342.xcarchive` identify 2.5.1/build 342, minimum OS
17.0, iPhone/iPad device families and background modes audio, bluetooth-central
and location. The archive's release notes describe map shortcuts and persistent
device names. They do not describe local mapping. The signed IPA contains no
`dji-cloud` assets. Release source `1fc157b2` has no native Pilot mapping module.
The working copy of `release-notes/2.5.1/whats_new.txt` contains later mapping
changes and must not be used as evidence of build 342 behavior; it was left intact.

The base candidate source is **2.5.3 (406)** at commit `4dfbd780`; local follow-up
changes are packaged as **2.5.4 (416)**. Its primary metadata
mirror describes that candidate prepared October 24,
including native DJI Pilot 2 mapping and the disclosure corrections. The
separate [342 notes](review-submissions/2.5.1-342/review_notes.txt) address the
existing information request without attributing later features to build 342.

App Store Connect remains at sign-in, so selected build, live metadata, privacy
answers, attachments, agreements, territories and seller identity are unverified.
No reply, upload, resubmission or release was performed.

## Corrections prepared locally

| Area | Finding and correction | Release implication |
| --- | --- | --- |
| Hardware explanation | Notes now lead with ASTM F3411/Bluetooth and RTMP. DJI, Autel and DroneScout are verified examples, not exclusive suppliers or manufacturer endorsement. DJI Cloud API and CalTopo interfaces are identified separately from standards. | Use 342 notes for the existing review; current notes for the next candidate. No manufacturer representation agreement is asserted. |
| Background description | Notes, Status, About & Privacy and the location purpose text now explain the declared Bluetooth, location and audio modes consistently. | Source text corrected; physical background behavior remains an exact-build test item. |
| Reviewer access | Old notes said no review credentials were needed. Unauthenticated inspection cannot exercise protected tracker, CalTopo, FAA and managed-video paths. Revised notes identify that boundary. | Clarify optional third-party prerequisites; address a specific access request through a feasible agreed arrangement. |
| In-app policy access | `App/AboutPrivacyView.swift` had privacy summaries and a website link, but no direct policy link. Added one using the policy URL already documented for App Store Connect. | New source only; build 342 cannot receive this change through metadata. Public URL reachability still needs confirmation. |
| Required reason APIs | The app-owned manifests in source and build 342 declared only UserDefaults and file timestamps, despite disk-capacity and `systemUptime` use. Added DiskSpace reasons E174.1/85F4.1 for storage guards/display and SystemBootTime reason 35F9.1 for elapsed-event timing. | New binary required. Review approved-reason data-flow restrictions and complete SDK privacy reports before submission. |
| Public description | Current description omitted local mapping and understated optional track/video sharing. Updated the local draft to match current features and explain hardware requirements. | Do not paste this newer description as an account of build 342's features. |
| Preflight | Review-note limits, direct policy link, policy/catalog date, required reasons and source/packaged app/SDK disclosures are checked. Reviewer notes may name test devices while public-copy checks remain enforced. | Retained old archives are not rewritten and do not satisfy the current twelve-category check. |
| Media, identity and deletion | Policy, in-app summaries and twelve-category manifest/catalog now cover source-evidenced media, identity, session records and diagnostics. Uninstall is not promised to erase Keychain credentials. | Publish the policy and update live answers separately; new binary required. |
| Consent boundaries | Catalog/previews and connection setup can precede full media. Remote Video Control provides advance authorization; direct local-server viewing is a separate option. | Existing control behavior preserved; notes no longer promise a prompt for every request. |

## Evidence and questions to resolve before submission

### Hardware and protected workflows

**Priority: high. Criteria: 2.1 and 2.3.1.** Attach a video filmed on a physical
Apple device that shows actual hardware interaction. Include exact app build,
device model, OS version, transmitter/bridge/controller and network setup. Show
direct Bluetooth or DS110-relayed reception, controller RTMP video and detector
Off/Color/Infrared. For the current candidate, add Local Maps, mission preparation,
Pilot 2 connection and photo return using a safe non-flight demonstration.
Demonstrate operator approval/decline for remote viewing and microphone controls.
The public site's tutorials are possible supporting material, but their existence
does not establish an exact-build hardware demonstration.

Correction to the earlier access recommendation: the supplied rejection requests
hardware-brand and distribution-authority information, not a CalTopo Teams account.
CalTopo/tracker are optional external integrations; local Remote ID, archives and
controller-video analysis are available without either account. The owner has no
CalTopo Teams administrative privileges and is not expected to purchase a Teams
subscription for review. Explain the client model and prerequisites. If App Review
later needs a particular protected workflow, seek a feasible arrangement instead
of presuming a developer-owned paid account or production credentials. Apple's
general review-access guidance still applies; this is not a blanket exemption.

Neither a completed exact-build device/OS list nor a reviewer demo attachment was
established from the inspected release evidence. `apple/DEVICE_QUALIFICATION_REPORT.md`
is a template. Refresh it and `apple/RELEASE_ACCEPTANCE.md` for the selected
processed TestFlight build, including iPhone/iPad layout, permission denial,
offline behavior, screen lock, recovery, sustained video and thermal behavior.
No physical-device tests or app installations were run for this audit.

### Background behavior

**Priority: high. Criterion: 2.5.4.**
`App/AppleLocationProvider.swift` enables background location for the active
session, shows its indicator, and comments that it also keeps video and advisories
running. `App/AppleSpokenWarningCenter.swift` activates a playback/spoken-audio
session for warnings and respects an active VoIP session. The permission string
also names locked-screen video ingest. Explain the user-facing location need,
when monitoring starts/stops, and how consent is withdrawn. Validate that location
and audio are used for their permitted purposes, rather than merely retaining
execution for unrelated processing. Revised wording does not establish that the
runtime use meets Apple's background policy. Status copy and the location purpose
string have been reconciled locally; physical background qualification is still open.

### Privacy policy and App Privacy answers

**Local corrections complete; publication and live answers pending.**
Root `PrivacyPolicyApple.md` is dated October 25. It and About & Privacy describe
catalog/thumbnail publication, media/recording authorization including advance
Remote Video Control, separate microphone permission, connection setup and probes,
relay services, recording delivery and source audio. Newer mission/controller/photo
transfer, public organization/map visibility, diagnostics and deletion boundaries
are included. Reset, Clear login, backup disabling and cloud-file removal are
documented without promising Keychain erasure on uninstall. The policy now stands
alone and describes the app without referring to another platform's implementation.
`PrivacyPolicyAndroid.md` provides the corresponding device-specific policy;
`PrivacyPolicy.md` is an index retained for existing links. Publish the reviewed
files and update the App Store Connect policy URL before resubmission.

The [source-evidenced catalog](privacy-disclosures.json), manifest and current
worksheet now include Name, Email Address, Precise Location, User ID, Device ID,
Photos or Videos, Audio Data, Other User Content, Product Interaction, Performance
Data, Other Diagnostic Data and Customer Support. All are linked, not used for
tracking, and for App Functionality. Retained thumbnails/photo clues/recording
uploads establish media collection; source audio in transferred files establishes
the audio disclosure independently of live-only VoIP. Local-only analysis is
distinguished from these paths. Provider web sign-in and retained service session
records are also covered. This is a local declaration draft, not a live attestation.
Publish the reviewed policy and save matching current App Store Connect answers.

`AppleManagedVideoMediaPeer.swift` uses a full-duplex audio session while its
outgoing audio track is muted. The policy, purpose string and summaries now
distinguish permission/transmission from hardware activity; the microphone switch
is not described as a hardware-off guarantee. Physical input/indicator behavior
with permission granted/denied and during receive-only voice remains a device test.

The policy removes the promise that uninstall erases the saved backup passphrase.
The existing explicit reset clears app-accessible Keychain items and local
settings, retaining operational files. Disabling automatic backup does not clear
its passphrase or cloud file. No reset, deletion or credential change was executed.

The old GitHub support URL redirects to `UAS4SAR/RID2Caltopo`. Earlier policy-page
fetches failed, which does not establish that the URL is broken. Browser verification
was subsequently blocked by a saved origin permission, including after the owner
authorized read-only access in chat; no alternate GitHub access was attempted.
Confirm the saved policy URL displays the published revision without sign-in.
Local edits are not publication.

### Required reason APIs and native dependencies

**Priority: high for the next binary.** Inspected disk-space uses include
`App/AppleMapOfflineManager.swift`, `Sources/R2CCore/AppleFlightStorage.swift`,
`App/AppleMappingMissionView.swift`, `Sources/R2CMapping/PilotCloudHTTP.swift`
and `MappingReconstruction.swift`. Uptime measures event intervals and timers in
the importer, media pipeline, storage/progress code and core policies. The added
declarations cover those inspected purposes. Check that raw boot-time/disk-space
values and derived data are not exported contrary to the selected reasons; elapsed
event durations have a specific exception. Generate the privacy report from the
new archive and inspect all native frameworks/SDKs. The retained 342 IPA has two
privacy manifests; counting manifests is not validation of their completeness.

### Service rights and manufacturer identity

**Priority: medium. Criteria: 5.2.1 through 5.2.3.** Keep protocol documentation and
evidence of actual permitted service/API use available. Generic standards support
does not establish CalTopo session/API rights, DJI Cloud API license scope, map
imagery reuse rights, or vendor trademark permissions. Current source installs
an app-wide DJI registration; registration is not proof of endorsement or an
agreement to distribute on DJI's behalf. Do not attach application keys or license
secrets as evidence. Use redacted registration or correspondence only if requested
and appropriate. Check component notices, offline map exports and media ownership.

The local integration inventory is:

| Integration | Interface and source evidence | Remaining rights evidence |
| --- | --- | --- |
| Remote ID | ASTM F3411/Bluetooth; `Sources/R2CAppleRadios/BluetoothRIDScanner.swift`, OpenDroneID notices in `THIRD_PARTY_NOTICES.md`. | Compatibility is not a manufacturer distribution agreement. |
| Controller video | RTMP into embedded MediaMTX; `MediaMTXRuntimeConfiguration.swift`, MIT notice and FFmpeg build configuration. | Retain exact component licenses/relinking/source obligations and permission to share operator media. |
| DJI mapping | Pilot 2 Cloud API/Open Platforms, app registration and local route/photo service in `Sources/R2CMapping`. | Redacted registration/license-scope evidence, without exposing app keys. |
| CalTopo | Teams API and optional account-bound browser/session requests in `CaltopoLiveClient.swift` and `AppleCaltopoPersonalProbe.swift`. | Confirm actual API/session and map-content/export permission; independent-project wording does not establish it. |
| FAA and public geographic data | Configured tracker proxy, MapKit, terrain and source-catalog boundary endpoints. | Provider terms, attribution, cache/export rules and geographic limitations. |
| Managed service | Enrolled tracker identity, WebRTC requests, previews, file delivery and optional VoIP. | Isolated review access, deployment retention, organization visibility and service operator responsibilities. |

Revisit the historical Content Rights answer. The app displays map content,
service data and operator-provided media, so the old worksheet's assertion that
it never accesses third-party content is overbroad. Determine the live answer
from the actual question and applicable rights; this audit does not select it.

### Business model, authentication and distribution

**Priority: medium. Criteria: 3.1, 4.8 and 5.1.1(v).** Notes describe a free app
without ads or in-app purchases. The [public tracker page](https://rid2caltopo.com/tracker)
currently says the managed pilot accepts no payments and has no fixed end date.
Explain organization enrollment, self-hosted services and CalTopo access separately
from buying app functionality. Confirm the selected build's screens have no
unexplained purchase/unlock flow. Reassess payment rules if the service model changes.

Personal CalTopo login uses `App/AppleCaltopoPersonalProbe.swift` and a provider
browser/session to access that user's service content. Verify whether any reachable
account-creation or social-login path requires additional account deletion or login
handling. Clearing a local session is not deletion of a provider account. Identify
the applicable third-party-client exception rather than adding an unrelated login.

The website identifies UAS4SAR LLC as developer/operator, while historical signing
and submission records name an individual team. Verify current seller identity
and ownership/authorization with the owner. This discrepancy is a clarification
item, not a finding that an account transfer is necessarily required. The app's
standalone local functions should be explained if Apple asks why public distribution
is appropriate for a tool also used by enrolled organizations.

### Safety, permissions, content and geographic scope

**Priority: medium. Criteria: 1.4, 1.7, 2.5.14 and 5.1.5.** The app provides
supplemental incident awareness and includes an operational acknowledgement; it
does not grant flight authorization. Demonstrate the boundaries of proximity and
anomaly outputs, stale/offline data, manual verification and route execution.
Keep SAR support distinct from emergency dispatch, autonomous aircraft control,
or public reporting of alleged crimes. User disclaimers do not substitute for
safe runtime behavior. Explain US-specific FAA/land datasets and their unavailable
states outside coverage; do not claim all services work worldwide.

Demonstrate permission refusal and later recovery for Bluetooth/location/local
network/camera/microphone, with visible consent and indication for recording and
sharing. Incident sharing and video operate among authorized peers; confirm that
this access boundary holds before treating public/social-content rules as
inapplicable. Recheck the age-rating questionnaire against actual reachable media,
web and communications features, rather than reusing the old 4+ answer blindly.
The local age-rating draft now marks Messaging and Chat Yes for VoIP and
Unrestricted Web Access Yes for the current unrestricted HTTPS navigation policy.
The browser's CalTopo-only entry-field check does not restrict later navigation
or new-window links. Broad user-content classification still needs a live answer
based on public-dashboard/map reachability and the intended experience.

### Store presentation, encryption and live account fields

**Priority: medium. Criteria: 1.5 and 2.3.** Historical screenshots date to July;
the documented `apple/Build/AppStoreScreenshots` folder is absent here. Refresh
actual screenshots for the selected build and required sizes, including current
mapping only when that build contains it. Verify Support/Privacy URLs, contact,
category, copyright, selected build, release mode, price/availability, privacy,
age rating, agreements and DSA status directly in App Store Connect. No live
field was certified by this audit.

`ITSAppUsesNonExemptEncryption` remains false. Inventory Apple CryptoKit backup
encryption plus bundled WebRTC/MediaMTX/other native cryptography against Apple's
export questionnaire. The old rationale mentioning only HTTPS/HMAC/Keychain is
incomplete; standard algorithms alone do not determine exemption. No export
classification, legal attestation or distribution setting was changed.

Locally confirmed encryption inputs for that determination:

- Apple CryptoKit AES-GCM configuration backups and Security-framework
  RSA-OAEP-SHA256/AES-GCM protected provisioning are implemented in
  `AppleConfigurationTransfer.swift` and `PilotCloudProvisioner.swift`.
- The project pins the WebRTC package to `125.6422.09`. The retained 406 bundle
  contains its SDK privacy manifest with no declared collected data and file-time/
  boot-time reasons; this does not remove the app's own collection disclosures.
- The prepared MediaMTX v1.16.2 source imports Go `crypto/tls`, `crypto/hmac`,
  `crypto/rand` and `crypto/sha1`. Its `go.mod` includes Pion WebRTC v4.2.9,
  DTLS v3.1.2, SRTP v3.0.10 and `golang.org/x/crypto` v0.48.0. This is not an
  inventory limited to Apple operating-system cryptography.
- The retained FFmpeg device `config.h` identifies LGPL 2.1-or-later and disables
  GPL/nonfree, OpenSSL, GnuTLS, Gcrypt, mbedTLS, libtls and SecureTransport. Those
  local build flags do not establish all distribution/relinking obligations or
  classify the complete app's encryption.

## Validation and completion

Validation passed October 23:

- `apple/AppStore/verify-metadata.sh --marketing-version 2.5.3`: versioned notes,
  metadata character limits, public-copy scan, four UI-copy tests, direct policy
  link, twelve-category catalog/date and privacy-manifest checks passed.
- Privacy validator: seven tests passed, including missing media declarations,
  incorrect Apple enum casing, missing required reasons, SDK tracking/analytics
  declarations and traversal of embedded SDK manifests.
- Retained 406 WebRTC SDK declarations satisfy the current catalog restrictions.
  A synthetic app bundle combining its SDK manifest with the new app manifest
  passes the two-manifest check. The unchanged old four-category 406 app manifest
  is correctly rejected as stale; this is not a freshly built IPA.
- `python3 -m unittest discover -s tools/store_notes -p 'test_*.py'`: eight tests
  passed, including allowing device/OS detail in reviewer notes while continuing
  to reject platform references in public description text.
- `plutil -lint apple/App/Info.plist apple/App/PrivacyInfo.xcprivacy`: passed.
- Swift parsing of AboutPrivacyView, CaltopoSettingsView and ContentView passed;
  this is syntax validation, not a fresh typechecked app build or device run.
- Shell syntax checks for metadata and signed/unsigned archive/export scripts passed.
- `git diff --check`: passed.

These changes update text, purpose strings, declarations and build-time validation;
they do not move runtime work, change data flow, or establish a fresh complete
release gate. Build a newly numbered
candidate from reviewed source and complete the normal release/physical
qualification process before submitting it. Preserve the retained 342 artifacts
for comparison.

## Apple references

- [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/) — current published edition inspected October 23, 2026; not evidence of identical wording on the original submission date.
- [Provide information for a complete review](https://developer.apple.com/help/app-review/before-submitting-for-review/complete-review)
- [Test compatible devices](https://developer.apple.com/help/app-review/before-submitting-for-review/test-compatible-devices)
- [App Privacy details](https://developer.apple.com/app-store/app-privacy-details/)
- [Required reason API categories and reasons](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype)
- [Add required reason entries](https://developer.apple.com/documentation/technotes/tn3183-adding-required-reason-api-entries-to-your-privacy-manifest)
- [Export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance)
- [Review-note character limit](https://developer.apple.com/documentation/appstoreconnectapi/betaappreviewdetail/attributes-data.dictionary)
