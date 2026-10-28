# 2.5.4 build 425 — managed operational storage

October 25, 2026. Installed in place on the A5 Pro and iPad. Android was
launched normally and completed migration and the subsequent rebuild of its
temporary working copies. The iPad initially refused launch while locked;
after the owner unlocked it, build 425 launched successfully (process 1551).
No authentication or acknowledgment was accepted by
the agent.

Android's retained Local Maps, originals, decoded terrain, clue files, recording
data, review annotations and recovery/queue records now use the selected Archive
Dir. Native file consumers use disposable working copies. Migration verifies
the archive and hydrated copies before removing legacy sources. Interrupted
writes retain recovery records; interrupted uploads and native recordings have
durable checkpoints. Map products publish complete verified generations.
Credentials and protected settings remain in their existing private stores.

Manage Storage provides a category inventory, Local Maps management, cache
cleanup and other-file management. Cache and flight usage appear alongside
their maxima. Free space now and the estimate after unused cache/flight
allowances are distinct; limits do not reserve capacity. Temporary working
copies are reported separately. Map cache cleanup leaves Local Maps and flight
files alone. The managed shared directories contain `.nomedia` markers.

Storage scans, migrations, archive operations and native map-archive discovery
run on workers. UI status/protection locks no longer wait behind flight-storage
disk scans. The location callback enters its IO worker before checking Archive
Dir. Archive-file lists render lazily.

Validation passed: 28 Android host storage regressions, three terrain-prefetch
regressions, 15 Swift storage/flight-protection checks, eight migration/recovery
cases on the isolated emulator, two Android cache-panel UI checks and one iPad
cache-panel UI check on the dedicated simulator. Migration cases cover SAF tree
permissions and DocumentsContract operations, SD aliases, conflicts, verified
publication, resumed photos, deletion, evicted working copies, and interrupted
recording data including the final movie-header update.

Operational safety backups were refreshed before installation. All 266 retained
Android files matched their original byte checksums: 265 against safety backups
and the marker-cleanup journal against its verified migration source checksum.
Two obsolete `mapping-volume.json` placement descriptors were replaced by the
new layout. All 15,352 checked iPad operational files retained their sizes and
modification times. Android's existing 100 GB cache limit and 365-day age setting
were last saved at 11:02, before these installs; they were kept. Its crash buffer
contains no new crash from these updates.

Both packaged release-note files match the canonical notes exactly. Android
package metadata, Apple unsigned-archive checks, privacy declarations, device
signature, bundle/team/device provisioning and store-note checks passed. Build
424 was an intermediate local install; build 425 is the final installed build.
Evidence: `apple/Build/Storage-Consolidation-2026-10-25`,
`apple/Build/ReviewCandidate-424`, and `apple/Build/ReviewCandidate-425`.

No physical instrumentation, uninstall, data clear, reset, commit, push, policy
publication, App Store upload, review reply or resubmission occurred.

Follow-up after build 425: the owner approved keeping private settings, browser
sign-ins, security material and SDK installation state outside Archive Dir and
outside Manage Storage. The inventory and storage-settings shortcuts were updated
in source to apply that exemption. These follow-up changes are not in installed
build 425; they will be included in the next build. The iPad inventory measured
approximately 42 MB under Library/WebKit, so the private browser profile is larger
than a few hundred KB. No credential contents were read.
