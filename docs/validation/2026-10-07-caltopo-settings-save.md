# CalTopo settings save clarity — October 7, 2026

Removed the iOS catch-all Save CalTopo Configuration button and its ambiguous connection-status footer.

Teams account fields now edit a local draft, including the Teams domain. Save Teams Credentials is beside the fields, validates complete-or-empty credentials, and is disabled when unchanged or invalid. Explicit save stores the secret in Keychain before applying the rest of the local preferences. A failure does not apply partially edited credentials. Success reports that settings were saved on this device. In personal mode, this does not switch credential sources, replace the selected personal map, or apply Teams edits to personal publishing. Existing configuration import/managed-profile persistence paths are retained.

Publishing enable/disable now persists its own preference and applies immediately, with inline feedback. It does not save credential drafts. The manual Map ID editor and implicit save-before-browse path are replaced by Select Incident. Export and cloud backup are explained as separate operations.

Android was audited: its general Settings Save/Close flow is distinct from the removed iOS catch-all CalTopo button and was not changed.

Validation: 25 XCTest personal-session tests and three Swift Testing cases (two credential-draft and one personal-flight regression) passed. UI copy checker and git diff --check passed. Final signed iOS device build passed. No installation in this turn. Physical checks remain for personal versus Teams mode, draft cancellation, Keychain-save error presentation, immediate publishing toggle behavior, and incident navigation.
