# 2.5.4 build 420 — remove manual backup-file workflow

October 25, 2026. Installed in place and launched on iPad as 2.5.4 (420).
Android remains on 419; no Android workflow changed.

Deleted the manual encrypted settings-file export/import methods, Backup &
Transfer screen, navigation routes and --show-transfer entry point. Manage
Storage no longer exposes that screen. Existing cloud backup controls are under
Settings > iCloud Settings Backup. Cloud encoding/restore, credential filtering,
map tile package import/export and tracker enrollment remain available.

The three existing ConfigurationBackupPolicyTests and the Simulator common-menu
and refresh-stability regression passed. Metadata, archive, signature, app/team/
device identity, exact canonical-note, policy-link and shared About checks passed.
The compiled Release app excludes the removed workflow labels and retains the
cloud and map tools. No new disk, parsing or network work was added to UI code.

Refreshed the operational-only safety backup with three changed/new files.
Authentication/configuration stores were not copied. All 15,462 checked iPad
operational paths remain; size changes are recorded in the install summary.
No uninstall, data clear, reset or acknowledgment action was performed.

Logs, package hashes, source inputs, removal checks and installation summaries
are retained under apple/Build/ReviewCandidate-420. No policy publication, commit,
push, store upload, review message or resubmission occurred.
