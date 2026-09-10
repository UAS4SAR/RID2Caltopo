# RID2Caltopo 2.2.7 snapshot

Source snapshot dated September 10, 2026; Android and Apple version 2.2.7,
build 197. App tag: `v2.2.7`.

Includes direct import of Tracker SVG QR downloads, deferred protected
configuration synchronization during required authentication, prior-credential
proof for enrollment, and bounded Apple enrollment requests with timing logs.

The companion r2c-tracker commit is `b9b1264` on
`project/modularize-coordination`, documented as undeployed v1.4.88 in
`changes.txt`. Preserving existing tablet authorization requires that server
change as well as the updated app.

Implementation validation before the version/notes update: Android releaseCheck
passed with 986 unit tests; Apple passed 287 tests and an arm64 Simulator app
build; Tracker passed all 352 tests. The version/notes update was checked with
Android release-note generation and Apple metadata verification.

This tag records source, not a store release or production deployment. Build
197 artifacts and physical tablet qualification remain to be completed before
distribution.
