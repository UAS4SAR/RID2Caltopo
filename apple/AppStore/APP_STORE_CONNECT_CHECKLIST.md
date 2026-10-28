# App Store Connect entry worksheet

This is a reviewable worksheet, not an automated submission. The July 19 entries
below are historical onboarding records, not current answers or release evidence.
Use the [October 23 submission audit](SUBMISSION_AUDIT_2026-10-23.md) and confirm
the live questionnaire before saving any declaration.

Current owner inputs and the formal interoperability statement are in
[review readiness](INTEROPERABILITY_AND_REVIEW_READINESS.md). The live console
remains unverified until an authenticated session is available.

## Review preparation for every submission

- [ ] Match the selected version/build to the actual IPA and build-specific notes.
  The current rejection concerns **2.5.1 (342)**; its supplemental notes are in
  [review-submissions/2.5.1-342/review_notes.txt](review-submissions/2.5.1-342/review_notes.txt).
  The main metadata directory describes current **2.5.4 (420)** source, not build 342.
- [ ] Explain ASTM/Bluetooth and RTMP interoperability, tested DJI/Autel/DroneScout
  examples, and independent distribution. Keep vendor APIs separate from standards.
- [ ] Attach a physical-device hardware demonstration and identify the exact app
  build, Apple device model, OS version, transmitter/controller and network setup.
- [ ] Explain that CalTopo/tracker are optional, user-configured third-party
  integrations and identify local workflows reviewable without those accounts.
  The current rejection does not request service credentials. If App Review
  requests a protected workflow, agree on a feasible review arrangement; do not
  presume the developer must purchase CalTopo Teams or hold Teams admin access.
- [ ] Explain all three background modes: Bluetooth, location and audio. Verify
  denied permissions, screen lock, stop/cleanup, battery/thermal limits and recovery.
- [ ] Match the public policy, in-app policy link, App Privacy answers and SDK/app
  manifests to actual video/audio, location, account and diagnostic data paths.
- [ ] Resolve service/API rights and content-rights answers; verify encryption
  classification for bundled libraries as well as Apple framework usage.
- [ ] Refresh screenshots, descriptions, regional limitations, reviewer contact,
  support/privacy URLs, business model, seller identity and distribution settings.
- [ ] Retain completed exact-build physical qualification reports. A blank template
  or an older development install is not a completed TestFlight qualification.
- [ ] Record live App Store Connect verification separately from local checks.

## New App record

- Platforms: iOS
- Name: `RID2Caltopo`
- Primary language: English (U.S.)
- Bundle ID: `org.ncssar.RID2CaltopoApple`
- SKU: `RID2CALTOPO-APPLE-001`
- User access: Full Access

The App Store Connect record was created on July 19, 2026:

- Apple ID: `6792518823`
- Version: `1.0` (`Prepare for Submission`)
- Record URL: <https://appstoreconnect.apple.com/apps/6792518823/distribution>

Version 1.0 build `202607191735` was uploaded July 19, 2026 and is **Ready to
Submit** in TestFlight. Its test instructions are saved. The earlier accidental
0.1 build `202607191720` is superseded and must not be assigned to testers or an
App Store version. The Free Apps Agreement is active. The Paid Apps Agreement
is not required while the app remains free with no in-app purchases.

## App information

- Subtitle: `Remote ID for SAR Operations` — saved July 19, 2026
- Primary category: Navigation — saved July 19, 2026
- Secondary category: Utilities — saved July 19, 2026
- Support URL: <https://github.com/kjtsar/RID2Caltopo>
- Privacy Policy URL:
  <https://github.com/kjtsar/RID2Caltopo/blob/main/PrivacyPolicyApple.md>
- Content rights: **No** was saved July 19, 2026. Reassess against map imagery,
  external service data and operator-provided media; the old statement that the
  app never shows third-party content is not a current factual conclusion.
- Encryption: `ITSAppUsesNonExemptEncryption` is `false` in the current app.
  Reconfirm classification using the complete native dependency inventory,
  encrypted backups and WebRTC/MediaMTX; use of standard algorithms alone does
  not establish exemption. Do not change the declaration without that determination.

The original shared policy URL was saved in App Store Connect on July 19,
2026. Publish the reviewed `PrivacyPolicyApple.md` and replace that field with
the URL above before resubmission. The local split is not yet published or saved
in App Store Connect.

## App Privacy draft

Current source draft updated **October 24, 2026**. Answer **Yes** to collection.
The [machine-readable catalog](privacy-disclosures.json) records the source basis
for each category and is checked against the app manifest. These twelve categories
supersede the four-category July setup; they have not been saved or attested in
App Store Connect by these local edits. Use **Linked to user: Yes**, **Tracking:
No**, **Purpose: App Functionality** for every row. No category is used for ads,
marketing or cross-app tracking; operational usage/diagnostics support the service.

| Data type | Linked to user | Tracking | Purpose | Why |
| --- | --- | --- | --- | --- |
| Name | Yes | No | App Functionality | Named pilot/aircraft owners in service records and operator-sent support correspondence. |
| Email Address | Yes | No | App Functionality | Optional embedded provider sign-in and support email sent by the operator. |
| Precise Location | Yes | No | App Functionality | Configured operator/aircraft positions, flight tracks, geotagged clues and media metadata. |
| User ID | Yes | No | App Functionality | Provider account IDs/usernames and operational pilot callsigns. |
| Device ID | Yes | No | App Functionality | App-install zone identity, enrolled device identity and device name. |
| Photos or Videos | Yes | No | App Functionality | Tracker thumbnails, selected photo clues, remote media and transferred recordings. |
| Audio Data | Yes | No | App Functionality | Source audio can be retained in recording files uploaded for requester delivery. Live-only VoIP alone is not the retained-audio basis. |
| Other User Content | Yes | No | App Functionality | Organization/aircraft descriptions, annotations, configuration and other shared operational records. |
| Product Interaction | Yes | No | App Functionality | Retained request/approval/session and operational usage events for service access and operation. |
| Performance Data | Yes | No | App Functionality | Link route/bandwidth estimates and selected timing/frame/drop/connection diagnostics. |
| Other Diagnostic Data | Yes | No | App Functionality | Operator-shared device/OS/network and operational troubleshooting information. |
| Customer Support | Yes | No | App Functionality | Operator-sent problem descriptions and diagnostic attachments. |

On-device-only analysis, reconstruction and caching are assessed separately from
off-device retained data. The app's own media thumbnails and photo/recording
uploads are explicit retained-image paths. Do not infer collection solely from
live transport or assume that all media fits Other User Content. Do not claim the
optional-feedback exception for support without verifying all its conditions.
Check actual deployment retention before narrowing these conservative declarations.
Provider browser sign-in is included; this is not a general open-web exception.

The public policy describes preview/catalog exchange, WebRTC setup/probes/relays,
full-session and recording consent, advance Remote Video Control authorization,
separate microphone consent, source audio, mapping/controller transfer, public
organization/map visibility, local retention and separate cloud/service deletion.
Publish that reviewed revision before presenting it as the live policy.

## Age Rating draft

Apple calculated 4+ from the July 19 questionnaire. That historical result is not
the current rating determination. The October 23 source-based draft is:

- Parental Controls / Age Assurance: No; app access controls are operational roles,
  not child-age controls.
- Messaging and Chat: **Yes** for optional direct VoIP communication between
  authorized participants. The previous No answer omitted this feature.
- Unrestricted Web Access: **Yes for the current implementation**: the embedded
  CalTopo browser permits HTTPS navigation to other hosts and follows new-window
  links. Its entry field accepts CalTopo links, but that is not a host restriction
  on subsequent browsing. Do not retain No without changing and testing that flow.
- User-Generated Content: **Requires live questionnaire review**. Private incident
  exchange is not a public social feed, but organizations can publish flight
  dashboards and map owners can share maps. Determine whether reachable public
  content is part of the app's intended experience under Apple's definition;
  describe the visibility controls rather than assuming all data is private.
- Social Media / Advertising: No; no social feed or advertising feature identified.
- Mature-content frequencies: match the current intended experience and reachable
  content; do not certify blanket None solely from the absence of bundled media.
- Let App Store Connect recalculate the rating and regional equivalents after the
  current answers. No rating or live answer was changed locally.

Reference: [Apple's capability definitions](https://developer.apple.com/help/app-store-connect/reference/app-information/age-ratings-values-and-definitions/).

## Version metadata and review

- Use `metadata/en-US` for name, subtitle, description, promotional text,
  keywords, what's new, and review notes.
- Use the six visually reviewed images from
  `apple/Build/AppStoreScreenshots`: three 1320 x 2868 iPhone screenshots and
  three 2064 x 2752 iPad screenshots.
- Uploaded July 19, 2026: three 1320 x 2868 screenshots in the iPhone 6.9-inch
  slot and three 2064 x 2752 screenshots in the iPad 13-inch slot. App Store
  Connect is using each set for compatible smaller display sizes.
- App Store Connect's live iPhone 6.9-inch slot was verified to accept
  1320 x 2868 on July 19, 2026. The iPad images use Apple's published
  13-inch 2064 x 2752 size.
- Price: Free, unless the distribution plan changes.
- Availability: decide whether the first build is TestFlight-only before
  selecting App Store territories.
- TestFlight feedback email: `kjt@uas4sar.com`
- TestFlight beta description, privacy URL, reviewer contact, no-sign-in
  selection, review notes, and build-specific test instructions — saved July
  19, 2026.
- App Review contact saved July 19, 2026: Kenneth Taylor,
  `+15305594250`, `kjt@uas4sar.com`.
- Sign-in required: No — saved July 19, 2026.
- Review notes from `metadata/en-US/review_notes.txt` — saved July 19, 2026.
- Release mode: manual — saved July 19, 2026.

## Account compliance

- Digital Services Act status: non-trader — saved July 19, 2026. App Store
  Connect reports the DSA requirement Active and all current regulatory
  requirements complete.
- Confirm tax/banking agreements only if paid distribution or in-app purchases
  are ever introduced.
- App Privacy publication: published July 19, 2026 by Kenneth Taylor. The live
  product-page preview shows all four data types under Data Linked to You.
- Review territories and the build's export-compliance result before any App
  Store submission. TestFlight remains the initial distribution path.

## Apple references

- [Add a new app](https://developer.apple.com/help/app-store-connect/create-an-app-record/add-a-new-app)
- [App privacy details](https://developer.apple.com/app-store/app-privacy-details/)
- [Set an app age rating](https://developer.apple.com/help/app-store-connect/manage-app-information/set-an-app-age-rating/)
- [Age-rating definitions](https://developer.apple.com/help/app-store-connect/reference/app-information/age-ratings-values-and-definitions/)
- [Screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/)
