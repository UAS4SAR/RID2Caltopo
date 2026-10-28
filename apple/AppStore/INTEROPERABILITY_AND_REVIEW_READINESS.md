# Interoperability statement and remaining Apple review inputs

October 25, 2026. Review build **2.5.4 (423)** is signed and installed on iPad and A5 Pro. It
has passed the recorded package/focused checks. Installation is not completed
hardware, background or sustained-media qualification. Apple originally asked
about hardware brands and authority to distribute **2.5.1 (342)** on their behalf.
The two versions must not be described interchangeably.

## Formal public statement

> RID2Caltopo is independently developed and distributed. It monitors supported
> ASTM F3411 Remote ID broadcasts and receives RTMP streams through its embedded
> MediaMTX server. CalTopo connectivity uses its Teams API and authenticated
> account/session interfaces. Optional DJI mission transfer uses DJI Cloud API
> and requires compatible DJI equipment. RID2Caltopo is not sponsored, endorsed,
> or distributed on behalf of CalTopo, DJI, Autel Robotics, BlueMark Innovations,
> or any other drone hardware manufacturer. Manufacturer and product names
> identify tested interoperability.

Core Remote ID/RTMP interoperability is based on supported formats, not an
exclusive manufacturer arrangement. Remote ID monitoring decodes broadcast
advertisements without pairing with or controlling the transmitter. The
controller's RTMP client publishes to our server at the operator-selected address. Testing has included the DJI/Autel controller
video and DroneScout DS110 relay implementations identified by the owner. That
is neither an all-model compatibility promise nor a certification claim. Optional
DJI mission transfer is deliberately described separately. CalTopo Teams API and
DJI Cloud API are provider interfaces, not generic Remote ID/RTMP standards.

The full explanation is now in the local App Review notes and public-description
draft. The draft above is also suitable for an optional **Compatibility and
independence** section in About. Such a section belongs outside Privacy and the
individual operational acknowledgment. It has not been added to the installed
423 binary; adding it would require a new build. Store/reviewer metadata alone
can be updated without rebuilding 423.

The separate [342 Resolution Center reply](review-submissions/2.5.1-342/resolution-center-reply.txt)
answers Apple's two existing questions directly. Its redundant future-feature
postscript has been removed. Version differences are internal preparation context,
not an additional disclosure warning. The reply has not been sent.

## Optional third-party services and review access

The owner has no CalTopo Teams administrative privileges and does not plan to buy
a Teams subscription for Apple review. Do not list procuring such an account,
creating a service account or supplying a team map as a required owner input.
The supplied rejection asks about hardware brands and distribution authority;
it does not request a CalTopo/tracker login or a paid subscription.
Earlier validation reports list preparatory test-account suggestions; those are
historical recommendations, not the owner's current mandatory input list. This
section and the current checklist supersede those account-procurement suggestions.

RID2Caltopo is a client with optional external integrations. Local Remote ID,
archives and controller-video analysis do not need CalTopo or tracker accounts.
Users with existing authorized CalTopo access can select a map; users of a
configured tracker can enroll/sign in to that separate deployment. No developer-
operated paid CalTopo service is implied. Personal CalTopo login is another
optional provider path, not an RID2Caltopo account-creation requirement.

A team administrator could technically authorize an existing team test map through
tracker-managed service-account access; source map selection supports it. That
was an optional possible arrangement, not evidence that the owner can or must
provide one. No such environment has been provisioned or promised to Apple.

The review instructions should identify the standalone workflow and optional
service prerequisites. If Apple later requests hands-on testing of a particular
external integration, explain these constraints and ask what feasible arrangement
it will accept. Existing authorized test access, a demonstration or an approved
full demo mode may be discussed; do not promise any alternative is already
accepted. Apple's broad login/demo guidance is not an established client-app
exemption, but it did not justify my statement that the owner must obtain Teams
access or buy a subscription.

The phrase "not manufacturer-independent" referred only to optional DJI mission
transfer using DJI Cloud API and compatible DJI equipment. Core Remote ID/RTMP
interoperability accepts supported formats from independent implementations.
A vendor-specific interface does not imply vendor ownership or endorsement.

## Permission is separate from endorsement

Apple's [Guideline 5.2.2](https://developer.apple.com/app-store/review/guidelines/#intellectual-property)
requires permitted third-party service use and authorization evidence when
requested. An independence statement explains who distributes the app; it does
not establish every API, content or brand-use permission.

CalTopo's [Supported API for Teams](https://training.caltopo.com/all_users/team-accounts/teamapi)
is useful supporting documentation: it describes team-admin-created service
accounts and authenticated requests. It does not document permission for every
personal-account/browser path or map-tile redistribution use. Confirm the basis
for those additional uses against the applicable terms or existing correspondence.
We should not claim a special endorsement or demand a new manufacturer distribution
agreement merely because a compatible controller publishes standard RTMP.

The existing DJI app registration is already packaged and verified. Registration
is not a claim that DJI endorses the app. Retain appropriate license-scope evidence
and any provider correspondence; redact secrets before any external sharing.

## Inputs still needed from the owner

| Input | What is needed | What can be prepared locally |
| --- | --- | --- |
| Acceptance and hardware demonstration | Confirm whether 423 passes the intended operational checks and make the relevant transmitter/controller/relay available for a brief filmed demonstration. Identify exact models/firmware tested. | Test script, device/build evidence, screenshots, video outline and final review attachments. |
| Identity and service-use facts | Confirm the distributing owner/seller identity and branding; identify the published terms or existing permission/correspondence covering CalTopo, DJI and applicable content/export use. Manufacturer endorsement is not requested. | Supported-API references, integration inventory and a redacted evidence index. |
| Store declarations | Verify the factual basis and approve final privacy, age/content-rights and encryption/export answers. The local drafts and crypto inventory exist; no fresh live attestation has been made. | Reconcile the exact live questions against source, prepare proposed answers, and flag facts only the owner can establish. |
| Publishing and submission | Supply an authenticated App Store Connect session when ready, authorization to publish the revised public policy/release source, and final upload/resubmission approval. GitHub authorization currently permits reads only. | Final metadata, a distribution export from the reviewed source, policy/public-URL checks, upload plan and submission checklist. |

Apple's [complete-review guidance](https://developer.apple.com/help/app-review/before-submitting-for-review/complete-review/)
describes review access and, for hardware-dependent features, a video showing
the app running on a physical Apple device interacting with the hardware. Use
non-operational data and demonstrate the supported workflow without an aircraft
flight. Include background/lock recovery, viewing approval and microphone controls
in final acceptance; installation/startup checks do not prove those behaviors.

The [store worksheet](APP_STORE_CONNECT_CHECKLIST.md) contains the twelve-category
privacy and age-rating drafts. The [submission audit](SUBMISSION_AUDIT_2026-10-23.md)
contains the current encryption inventory, content-rights and background-policy
questions. Apple describes its export workflow in
[export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance).
Do not equate a successful signature with an export classification.

## Verified status and work remaining

- Current signed/installed iPad binary: **2.5.4 (423)**. Its complete notice and checkbox
  are the owner-approved individual wording; this work does not change them.
- Local review notes and description now formalize standards/API/independence
  distinctions. They have not been saved to App Store Connect.
- App Store Connect was opened read-only and redirected to sign-in; current live
  privacy, age, rights, selected-build, seller and agreement fields are unverified.
- The separate policies are local: `PrivacyPolicyApple.md` and
  `PrivacyPolicyAndroid.md`. The source links select the matching policy. Publish
  the reviewed files and verify unauthenticated reachability before distribution.
- Refresh store screenshots, complete exact-build physical reports and assemble
  the demo/access attachments before treating the candidate as ready.
- Build 423 is installed on both tablets for review. A device-signed iPad export
  exists; create a fresh distribution export before upload. No policy push, review
  message, upload, resubmission or account change was performed.

## DJI library/disclosure check — October 24 follow-up

[The architecture and documentation review](../../docs/validation/2026-10-24-dji-cloud-api-disclosure-review.md)
finds no linked DJI native SDK in 416: the iPad implements the Cloud API server
and serves a project page that invokes the bridge in Pilot 2 on the controller.
No Apple-specific Cloud API notification rule was found in the public DJI sources
reviewed. Registration-specific accepted agreements remain unchecked; do not infer
full contractual exemption from the absence of a native SDK. Current-build review
notes still describe the actual controller mission/photo-transfer functionality.
