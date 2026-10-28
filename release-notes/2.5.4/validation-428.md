# 2.5.4 build 428 — settings help and Developer Tools cleanup

October 26, 2026. Installed in place and launched on the iPad and A5 Pro.
The iPad running process was confirmed (PID 1957). The A5 Pro kept PID 32653
through a 90.6-second startup observation, completed map working-copy
preparation, and added no crash-buffer entries.

The Content help sheet inherited the disabled state of its external-display
setting. Its label could open help, but Done was disabled. The shared help
sheet now explicitly enables its own controls. An XCUITest reproduced the
original failure before the fix. Four settings UI tests then passed on the
dedicated iPad Simulator: OS mirroring, Off, App-managed, and the existing
Save/Cancel/relaunch test. Help coverage includes opening each tested sheet
twice and verifying Done closes it while Settings remains open. A focused
Content regression passed again after the Developer Tools changes. The final
Apple UI source and test source match those tested files exactly.

Removed Personal CalTopo Login and Load Config File from Developer Tools on
both platforms. Removed Android's Restart MediaMtx Server shortcut; that entry
was already absent from the corresponding Apple panel. Removed the unused
developer-only file picker and restart handler. Main-menu Import Config,
normal CalTopo sign-in, and the stream tile's Reset Streams Server remain.

Post-install checking of build 427 exposed an Android startup crash: recording
inventory polling attempted archive preparation on Main. Build 428 moves
catalog scanning/recovery and camera-metadata refresh to IO. UI playback
callbacks use a bounded, immutable inventory without acquiring the catalog's
disk-operation lock. Cancellation and incident-change checks prevent stale
results from being published. Seven recording tests passed, including a new
test holding that lock while checking that the UI inventory lookup completes.

Both packages built and passed identity, version, signature and exact packaged
release-note checks. The Apple archive also passed native-framework, privacy
manifest and DJI registration checks; its provisioning covers this iPad.

The refreshed operational backup is under
`.local-deployment/device-backups/2026-10-26-build427-operational`, continuing the
existing iPad backup chain. Android verification found all 266 checked native
working-copy files unchanged and all 589 retained archive paths present. One
active diagnostic log grew with its original bytes preserved; four generated
support-data metadata sidecars refreshed. The 100 GB cache maximum and 365-day
age setting remain. All 15,356 checked iPad retained paths remain; the only
size/mtime change was a growing diagnostic log.

Evidence: `apple/Build/Content-Help-2026-10-26` and
`apple/Build/ReviewCandidate-428`. No physical instrumentation, uninstall,
data clear, device reset, authentication, acknowledgment, commit, push, store
upload, review reply or resubmission was performed.
