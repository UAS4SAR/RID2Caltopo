# RID2Caltopo v2.2.7 source verification

On September 11, 2026, the operator confirmed the updates working on iPad 2.2.7 (202) with public Tracker v1.4.90, including the aircraft upload workflow. This is operator verification of those updates, not a claim that every terrain, video, or flight scenario was qualified.

The source release includes the accumulated Android and Apple changes since the earlier build-197 snapshot: terrain and cache improvements, wired controller addresses, aircraft list/detail editing, flight readiness and operating profiles, compact drone confirmation, saved flight-type choice, and persistent aircraft field labels.

The iPad build 202 was installed in place and launched successfully. Android changes have automated validation but were not installed or physically verified in this session. Repository build counters remain 197; build 202 was a development build override. No store submission or mobile binary publication is part of this source commit/tag request.

Companion Tracker release: v1.4.90, source 15eed5746bbded7d49bdb16a8407c2ac17dac30e. Its 397 tests, guarded staging/candidate checks, production health checks, and audit backfill completed successfully.

Final source validation: 1,023 Android unit tests passed with no failures, errors or skips; 310 Swift Testing tests plus 10 XCTest tests passed. The first Apple run found a stale source-signature assertion for terrain prefetch; its expectation was updated for the existing optional radius parameter and the full suite then passed. Apple metadata verification passed with synchronized release notes. The signed build-202 app build had already passed before the operator's device verification.

The earlier local, unpublished v2.2.7 build-197 snapshot tag is preserved as v2.2.7-build197-snapshot; v2.2.7 now identifies this completed source release. This request commits and tags locally without pushing or publishing app binaries.
