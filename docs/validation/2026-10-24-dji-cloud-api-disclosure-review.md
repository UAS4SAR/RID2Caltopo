# DJI Cloud API integration and disclosure review

October 24, 2026. The owner asked what the prepared reply's build-342/newer-feature
postscript meant, whether iOS links DJI Cloud API libraries, and whether DJI
requires disclosure to Apple or users. This review makes no runtime or binary change.

## Reply correction

The postscript was internal version bookkeeping. It was not a newly identified
DJI or Apple disclosure obligation. The reply's opening already identifies the
submission and build, so the postscript was redundant and confusing. It has been
removed, along with the speculative invitation to request additional API evidence.
The reply directly answers the two questions for 342 and does not introduce the
newer candidate's mission-transfer functionality.

This does not remove actual functionality from current-candidate review notes.
Apple's [2.3.1(a)](https://developer.apple.com/app-store/review/guidelines/#accurate-metadata)
asks developers to describe new functionality specifically and make it available
for review. A concise functional account of controller mission/photo transfer in
the new candidate's own notes satisfies the purpose better than speculative
licensing warnings or unrelated historical explanations. Approval is not predicted.

## Actual iOS architecture: build 416

- The Xcode package graph references the project's local R2C packages and pinned
  WebRTC. The project declares local MediaMTX, FFmpeg, anomaly and mapping native
  libraries. No DJI native SDK package/framework/library is declared in that graph.
- `otool -L` on the fresh 416 archive finds Apple/system dependencies and WebRTC;
  no DJI load dependency. The only embedded dynamic third-party framework is
  `WebRTC.framework`. Swift source imports contain no DJI module imports.
- Cloud API service endpoints are implemented in the project's Swift files:
  `PilotCloudServer`, `PilotCloudHTTP`, `PilotCloudMQTT`, `PilotCloudPlatform` and
  `PilotCloudStorage`. They serve HTTP, MQTT/WebSocket and S3-compatible uploads.
- The packaged `dji-cloud/index.html`, `native.js` and `style.css` match the project
  source in both device and distribution exports. HTML loads the local native.js,
  not an external DJI JavaScript SDK. The script calls `window.djiBridge` supplied
  by Pilot 2 on the controller; that bridge is not an iOS framework in our app.
- The Cloud API registration asset and existing license verification are present.
  The script calls `platformVerifyLicense` using the configured app registration.
  No app key/license values or private registration file were read for this review.

Thus this is the server side of a DJI Cloud API protocol integration. It is not
an iOS app linking the DJI Mobile SDK. The evidence supports that specific technical
finding; it is not a blanket exemption from DJI contracts or a forensic provenance
claim for every compiled byte.

Machine-readable evidence: [architecture/package inspection](../../apple/Build/ReviewCandidate-416/dji-integration-audit.json).

## Public DJI documentation checked

| Source | Relevant finding |
| --- | --- |
| [Cloud API overview](https://developer.dji.com/cloud-api/) | Describes third-party platforms using MQTT, HTTPS and WebSocket with Pilot 2/Dock. This matches the server-side architecture. |
| [Pilot access to cloud](https://developer.dji.com/doc/cloud-api-tutorial/en/feature-set/pilot-feature-set/pilot-access-to-cloud.html) | Requires developer registration, a Cloud API app ID/key/license and Pilot 2 JSBridge license verification. These are implemented. |
| [SDK EULA](https://developer.dji.com/policies/eula/) | Covers broad SDK materials including APIs/documentation, and conditionally requires notices when distributing SDK object code. No Apple-specific Cloud API notification requirement was identified. No DJI native SDK redistribution was found in the reviewed iOS graph. |
| [Developer Policy](https://developer.dji.com/policies/developer/) | Includes user privacy-policy/notice obligations and conditional SDK redistribution terms, plus use/safety restrictions. These are distinct from notifying Apple of Cloud API use. |
| [Developer terms](https://developer.dji.com/policies/terms/) | Its App Store references address DJI software obtained from the store, not a direction to report third-party Cloud API integrations to Apple. |

SDK EULA section 6.2 also contains a user-notice/consent provision for SDK-collected
analytics and warranty logs when applicable. That is not an Apple notification
rule. No DJI native SDK collector was found in this iOS dependency graph; do not
claim that R2C performs those SDK collections without evidence. Registration-
specific scope and actual controller-side behavior remain relevant.

No requirement specifically instructing a third-party Cloud API developer to
notify Apple was found in these public documents. That is a bounded search result,
not proof that no additional registration-specific condition exists.

The generic EULA defines SDK materials broadly, so absence of a native framework
alone does not settle all contractual scope questions. The developer policy's
use/safety restrictions, including section 1.4, need assessment if that policy
governs this registration. The exact agreements accepted for this Cloud API
application have not been inspected. Those agreement names/text—not app keys—are
the additional material needed for a definitive registration-specific review.
Do not automatically apply the separate, newer Cloud API 2 agreement to the
legacy Pilot 2 JSBridge integration; its applicability is unestablished.

## Practical disclosure recommendation

- Keep the existing 342 Resolution Center response focused on the hardware and
  representation questions. No future-feature postscript or speculative SDK warning.
- In the new build's own Notes for Review, identify optional compatible-controller
  mission transfer and original-photo return and explain how to test it. Do not
  describe the whole app as exclusively manufacturer-neutral or as DJI-endorsed.
- User-facing feature instructions should state compatible hardware requirements.
  Privacy disclosures should describe actual route/identifier/telemetry/photo
  exchanges; the existing local policy and About copy already cover these data flows.
- Do not add a DJI native-SDK analytics assertion or SDK-object-code copyright
  attribution solely because the feature uses Cloud API. Base additional notices
  on actual components and the applicable agreement.
- Policy publication, actual API-use permission, review access and ordinary Apple
  metadata accuracy remain the separate readiness items already recorded. Nothing
  was sent to Apple, no agreement was accepted and no app was rebuilt or uploaded.
