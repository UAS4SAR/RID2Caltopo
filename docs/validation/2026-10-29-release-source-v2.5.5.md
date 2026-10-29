# Release source v2.5.5 — October 29, 2026

Source snapshot for version **2.5.5 (432)**, requested for `main` and tag `v2.5.5`.
Combines the pending startup/storage and worker-thread improvements with
personal credential login from PR #7 (`07aefc1581768a3303939bdfbef0182ddcb2ac89`),
track title/recording-link fixes, required fresh-terrain clue preparation and
background map history/geometry preparation. Includes regression and startup
qualification source, shared release notes, and current validation records.
Generated benchmark output and older local capture artifacts remain local.

## Validation and installed artifacts

- Android complete unit suite: 1,811 cases, zero failures/errors, one existing
  skip (1,810 passed). All 11 personal account activation tests passed.
- Apple CalTopo regression selection: 60 XCTest and 16 Swift Testing cases passed.
- Android debug APK assembly, unsigned iOS build and optimized signed iOS Debug
  build passed during integration/install preparation.
- Android and Apple release version/build and shared notes match: 2.5.5 (432).
- Source changes passed `git diff --check` before staging.

Both tablets received in-place updates on October 29 at 08:52 PDT. App identity,
version and installation results were verified through the OS. Neither app was
launched; account enrollment/publication and field verification remain pending.
See [installation and artifact hashes](2026-10-29-pr7-device-update.md) and
[PR integration checks](2026-10-29-pr7-integration.md).

The Android installed artifact used the validated APK with only its embedded
release-note asset replaced, followed by alignment and signing with the same
certificate, after permission review timed out on a notes-only Gradle rebuild.
All other non-signature APK entry bytes were verified unchanged.

No build counter increment, app source edit, device operation or additional test
run was needed for this commit/tag operation. A generated-build exclusion was
added for the new startup benchmark module.
