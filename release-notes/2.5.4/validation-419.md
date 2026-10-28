# 2.5.4 build 419 — aligned main menus

October 25, 2026. Installed in place on the iPad and A5 Pro. Both report
**2.5.4 (419)** and launched with running app processes.

## Changes

Both main menus now contain the same eight entries in source order: Settings,
Release Notes, Import Config, Manage Storage..., Send diagnostics to developer...,
Terms of Use, About & Privacy, Quit.

- On iPad, Backup & Transfer is now under Manage Storage. Its existing settings
  backups, iCloud controls and map tile package tools remain available.
- On Android, Archive Storage... is now under Manage Storage. It opens the same
  archive-location prompt and folder-selection workflow as before.
- Removed Android's duplicate External: Streams View shortcut from the main
  menu. External-display controls remain in Settings on both platforms.

These changes move navigation entries. Backup contents, transfer handling,
archive-location selection, recording, sharing and saved credentials are
unchanged. The stable-menu fix from 418 remains in place.

## Verification

- Android debug compilation and Apple Release archive compilation passed.
- Source comparison confirms identical top-level entry text and order, including
  when an external display is connected.
- The Simulator regression passed with all eight entries present, the two nested
  entries absent from the top level, no menu rebuild during eight one-second
  status updates, and working About & Privacy and Manage Storage action bindings.
- Reviewed all three Apple Manage Storage entry points: main menu, Settings and
  the storage-warning sheet pass the existing settings/organization/identity
  objects into the nested backup tools.
- Android closes Manage Storage before opening the archive-location prompt.
  Provider queries still run on Dispatchers.IO. Storage scans remain on the
  existing workers on both platforms; no new unbounded UI-thread work was added.
- Package signatures, identity/provisioning, exact canonical release notes,
  policy links, all 24 shared About rows and the existing acknowledgment were
  checked. Metadata validation includes four wording and seven privacy tests.

The physical-device checks establish installation, launch and data preservation.
They do not claim a visual navigation check on the iPad. The A5 Pro displayed its
device-authentication prompt, so physical menu navigation was not attempted past
that prompt. No authentication or acknowledgment was accepted by the agent.

## Data preservation

Refreshed the operational-only safety backups with two changed/new iPad files,
four Android files and two external Android map-cache files. Credentials and
saved-configuration stores were not copied.

All **15,460** pre-install iPad operational files remained. Only the active
diagnostic log grew, from 7,412 to 7,521 bytes. All **264** checked Android private
operational paths and both external map-cache paths remained. Android showed no
new app crash in the crash-buffer comparison.

Android used `adb install -r`; Apple retained app and Keychain identity. No
uninstall, data clear, reset or physical-device instrumentation runner was used.

[Package hashes](../../apple/Build/ReviewCandidate-419/artifacts.json),
[menu comparison](../../apple/Build/ReviewCandidate-419/menu-parity.json),
[Simulator result](../../apple/Build/ReviewCandidate-419/menu-ui-results.json),
[source inventory](../../apple/Build/ReviewCandidate-419/source-inputs.json),
build logs and device summaries are retained locally. No policy publication,
commit, push, store upload, review message or resubmission occurred.
