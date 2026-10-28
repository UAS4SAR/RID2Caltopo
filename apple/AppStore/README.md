# App Store Connect metadata

These files are the reviewed English (U.S.) drafts for App Store Connect. They
are deliberately kept beside the source so release wording and application
behavior can be checked together.

The [October 23 submission audit](SUBMISSION_AUDIT_2026-10-23.md) records current
review risks, evidence and unfinished preparation. The main metadata directory
describes the current 2.5.4 (423) candidate. Use the separate
[2.5.1 (342) supplemental review notes](review-submissions/2.5.1-342/review_notes.txt)
for the October 23 information request; do not describe later mapping features
as if they were present in that reviewed binary. Neither file has been saved
to App Store Connect by the local metadata verifier. A concise
[Resolution Center reply](review-submissions/2.5.1-342/resolution-center-reply.txt)
answers the hardware/manufacturer questions for 342. The
[interoperability and readiness note](INTEROPERABILITY_AND_REVIEW_READINESS.md)
separates the current candidate from that reply and lists remaining owner inputs.

- Bundle ID: `org.ncssar.RID2CaltopoApple`
- SKU suggestion: `RID2CALTOPO-APPLE-001`
- Primary category suggestion: Navigation
- Secondary category suggestion: Utilities
- Support URL: <https://github.com/kjtsar/RID2Caltopo>
- Privacy Policy URL:
  <https://github.com/kjtsar/RID2Caltopo/blob/main/PrivacyPolicyApple.md>
- Copyright suggestion: `2026 Kenneth Taylor`
- App Store Connect Apple ID: `6792518823`

The original shared policy URL was saved in App Store Connect on July 19,
2026. Publish the reviewed `PrivacyPolicyApple.md` and save its URL before
resubmission; the current local split has not been published or saved there.

Every release has one cross-platform source at
`../../release-notes/<version>/whats_new.txt`. It must describe shared changes,
platform-specific changes, and known platform differences. Synchronize the
reviewed App Store metadata mirror and run:

```sh
tools/sync_release_notes.sh <version>
apple/AppStore/verify-metadata.sh --marketing-version <version>
```

`verify-metadata.sh` checks that the canonical notes exist, are bundled by the
app, and match the App Store text. It also checks local field presence and
Apple's text limits.
Reviewer notes have a 4,000-character budget and may name test devices/OS versions;
the public-copy platform denylist does not apply to that private field. Required
reason declarations and the direct in-app privacy-policy link are also checked.
It also checks that the privacy manifest matches the source-evidenced
`privacy-disclosures.json` catalog and the policy date. Archive/IPA verifiers
check the packaged app and SDK declarations too. See the current linked-data
worksheet in `APP_STORE_CONNECT_CHECKLIST.md`. These checks do not contact or
modify App Store Connect or publish the privacy policy.

The following is a historical onboarding snapshot, not current release
evidence. Reconfirm every field and agreement in App Store Connect before each
upload. The subtitle, Navigation/Utilities categories, Apple's calculated 4+ age
rating, and all six iPhone/iPad screenshots were saved in App Store Connect on
July 19, 2026. Content Rights is saved as No. The DSA non-trader declaration is
Active, and the reviewer phone, contact information, no-sign-in selection,
review notes, and public Privacy Policy URL are saved. The four App Privacy
data types are configured consistently with the privacy manifest and were
published July 19, 2026 after explicit owner acceptance of Apple's final legal
accuracy/compliance attestation. Version 1.0 build `202607191735` is uploaded,
processed, and Ready to Submit in TestFlight. Its beta metadata and test
instructions are saved. Build `202607191720` advertised version 0.1 and is
superseded; the archive/export verifier now rejects a marketing-version mismatch.

Generate clean Release-build Simulator screenshots at Apple's required 6.9-inch
iPhone and 13-inch iPad sizes with:

```sh
apple/AppStore/capture-screenshots.sh
```

The script launches the real application with deterministic Remote ID demo data
and captures Nearby Aircraft, Live Map, and Status views. It suppresses radio
and location permission prompts only for screenshot capture; production startup
behavior is unchanged.

The dimensions are taken from Apple's current
[screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/).
