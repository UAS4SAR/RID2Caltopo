# v2.5.1 (342) — October 9, 2026

Android and Apple share version 2.5.1/build 342. The map-name shortcut opens Map Options directly when connected and the map picker when disconnected; disconnected map labels read No map. Named incidents without a map remain available in Settings. Apple new-device selection opens an editable naming form, and Settings offers Rename device. Mac installs identify the model as Mac.

Both Debug apps were built and installed in place on the connected A5 Pro and iPad, preserving app data. The operator accepted the corrected map flow. Forty-one focused Android coordinator/action tests passed, nine Apple device-reconciliation tests passed, and both app builds passed.

Tracker v1.4.96 was separately qualified, tagged, pushed and deployed before this mobile release. Its 429 tests, migration/runtime and security checks passed. Hosted synthetic-device naming/validation and two reconnects passed in isolated staging, the exact tested image was promoted to production, and the operator confirmed the chosen iPad name persists after closing and reopening the installed 2.5.1 app. See the sibling Tracker repository's docs/releases/v1.4.96.md for its deployment evidence.

Full mobile release qualification and store-submission results are retained separately with the signed release artifacts. A source tag is not proof of store approval or exhaustive physical Android 15/16 qualification.
