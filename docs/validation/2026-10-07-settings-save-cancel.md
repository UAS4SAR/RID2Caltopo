# Settings Save / Cancel — October 7, 2026

Both platforms now stage Settings edits until Save. Cancel discards pending edits. Back or sheet dismissal with pending edits offers Save Changes, Discard Changes, and Keep Editing. Separate map connection, sign-in, deletion, and test actions retain their own behavior. The standalone Save Teams Credentials control is removed from the iOS Settings form.

Drafts include alarm volume, bridge warning mute, proximity enable/scope, and other previously immediate controls. Proximity consent acknowledgments are collected locally and applied only on Save. Android settings refreshes preserve edited draft fields while updating untouched values. iOS navigation to child panels preserves the draft; unchanged credential fields do not overwrite changes from separate connection workflows.

## Automated validation

- Android: all 1,398 unit tests passed; debug APK built successfully.
- Apple: 80 XCTest tests and 572 Swift Testing tests passed; signed arm64 device build succeeded.
- Apple UI copy checker: zero violations. Git diff whitespace check passed.
- Added regression tests for deferred writes, discarding drafts, reverting edits, and preserving pending edits across refreshes.

## Installation evidence

At approximately 08:27 PDT, installed in place and launched successfully on both devices:

- Samsung A5 Pro, SM-X350, serial R52Y90C9XST: install succeeded; launch succeeded; package query confirmed 2.5.0 (339).
- Ken’s iPad, iPad13,4, CoreDevice 694108CB-8CBE-593D-ABE1-D9EDD947B901: signed app installation and launch succeeded. Artifact Info.plist is 2.5.0 (339). CoreDevice's app inventory returned no rows, so it did not independently verify installed version metadata.

Existing app data was preserved; neither device was uninstalled or reset. No store or server changes were made.

## Physical validation remaining

User to exercise Save, Cancel, dirty Back/dismissal, credential validation, child-panel navigation, and reopen/relaunch persistence on both devices. In particular, verify iPad interactive sheet dismissal presents the unsaved-edits prompt. Automated tests and successful launches do not establish physical UI behavior or field readiness.
