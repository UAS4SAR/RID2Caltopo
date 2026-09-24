# A5Pro retained archive folder — 2026-09-23

The operator reset persistent state, selected Keep current folder, and saw
Archive folder again after restarting and on returning to Main Screen.
The connected A5Pro (R52Y90C9XST) was running 2.3.5 (249). Fresh logcat showed
an empty archivePath, while Android's persisted URI grant remained read/write
(mode 0x3, persisted 0x3) for the removable-storage DroneTrax tree.

ResetPersistedClientState creates an empty ClientClassState but retains the
folder hint. The Keep current folder branch only dismissed the dialog; it never
called SetArchiveUri to restore the active and durable archive path. Consequently
each new MainScreen or process could correctly detect the missing active folder
and incorrectly offer the same ineffective choice again.

Build 254 validates the retained Android authorization and activates/persists
the hinted folder through SetArchiveUri before accepting Keep current folder.
Storage-provider operations run off the UI thread. A missing grant returns to
explicit reauthorization; a remembered hint alone is never treated as access.
The existing folder contents are preserved. Apple uses app-owned storage and
does not have this Android folder-grant flow.

All 14 ArchiveDirPromptStateTest tests passed, including retained-folder
activation/persistence and refusing an unauthorized hint. Release build and
physical Main Screen/restart validation are recorded below when complete.

Build 254 validation: all 1,139 Android unit tests passed (zero failures, errors,
or skips), releaseVerification passed, and assembleRelease completed including
Crashlytics symbol publication in 5m 5s. The local A5 APK was signed with the
existing tablet development certificate for an in-place update; all 696
non-signature ZIP entries match the signed release payload. ADB verified
2.3.5 (254), lastUpdateTime 2026-09-23 22:16:55. No uninstall/data wipe occurred.

Fresh startup at 22:17:11 restored DroneTrax from backup preferences and opened
its archive log at 22:17:14. The prior session repeatedly prepared the same
archive prompt at 22:12:12, 22:12:52, and 22:13:02 while archivePath was empty.
The UI return/restart result remains pending operator confirmation.
