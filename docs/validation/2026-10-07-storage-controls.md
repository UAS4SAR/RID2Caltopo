# Storage controls and saved-limit feedback — October 7, 2026

Fresh read-only evidence: the A5 Pro (R52Y90C9XST) flight_storage preferences contain maximum_bytes=100000000000 and maximum_days=30. No preference was changed and no cleanup/reset was initiated during diagnosis. An actual return to 10 GB was not reproduced. Source searches found only the limit editor writing these keys; the editor continued displaying the fixed sentence “Default allowance: 10 GB” after saving.

Changes:
- Android Manage Storage actions, cache controls, flight folder links, and directory browser actions use explicit white-on-dark/black-on-light button styling.
- Flight limits enable Save Limits only for valid values differing from the saved values. Android shows Saving while committing on an IO dispatcher and reports success only after disk-write success and value readback; failure retains the pending edits.
- Both platforms display actual saved limits and persistent success feedback instead of the fixed 10 GB default sentence. iOS uses a similarly prominent button disabled for unchanged/invalid values.
- Existing storage cleanup/protection semantics are preserved. A successful user-requested save still requests the existing maintenance check.

Validation:
- Android debug build passed; two tests cover 100 GB conversion without integer overflow, numerically equivalent inputs, changed values, invalid fields, and upper bounds.
- Signed iOS device build passed.
- git diff --check passed.
- Not installed for this change. Dark-mode physical layout, save/reopen/relaunch persistence, and any recurrence of the reported 10 GB reset remain to be checked on devices. No claim that the unobserved reset cause has been fixed.
