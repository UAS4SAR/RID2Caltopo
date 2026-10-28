# 2.5.4 build 426 — private-state storage exemption

October 26, 2026. Rebuilt and installed in place on the iPad and A5 Pro.
The A5 Pro launched and completed background working-copy preparation. After
the owner unlocked the iPad, build 426 launched successfully and its running
process was confirmed (PID 1924).

Includes the owner-approved exemption for private settings, saved browser
sign-ins, credentials, security material and SDK installation state. The main
configuration store is no longer counted in the Android operator inventory;
the iPad inventory omits its browser/profile and known private provisioning/SDK
stores. The settings/saved-access shortcut was removed from Other Storage.
Private stores remain persistent in their existing protected locations. Bulk
operational storage, recording and sharing behavior are unchanged.

Five existing Android storage-inventory/allowance checks and five Swift
storage-category/inventory/allowance checks passed. Both device packages built;
the Apple archive, privacy declarations, native frameworks, signature,
bundle/team/device provisioning, Android package identity, DJI registration,
store-note checks and exact packaged release-note bytes were verified.

Before installation, the Android safety backup covered and verified 266 native
operational files (60,810,498 bytes) and 587 retained archive files (67,379,977
bytes). Private credentials, browser state and configuration stores were not
copied. The iPad operational backup was refreshed for its changed/new files.

Post-install byte checks found no Android operational payload mismatches. One
active diagnostic log grew while preserving its backed-up prefix; four generated
support-data metadata sidecars were refreshed. The existing 100 GB cache maximum
and 365-day age setting remained, and the crash buffer was unchanged. All 15,354
checked iPad operational files retained their sizes and modification times.

Both tablets have build 426 installed. Evidence and package/source hashes are in
`apple/Build/ReviewCandidate-426`. No physical instrumentation, uninstall, data
clear, reset, authentication, acknowledgment, commit, push, policy publication,
App Store upload, review reply or resubmission occurred.
