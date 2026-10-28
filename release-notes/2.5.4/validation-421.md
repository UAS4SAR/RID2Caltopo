# 2.5.4 build 421 — consolidated map storage controls

October 25, 2026. Installed in place and launched on iPad and A5 Pro as 2.5.4
(421). Both use Main menu > Manage Storage > Map Cache Management for cache size
and maximum tile age. The duplicate editors in Map Management and Download Map
were removed; the download screen retains read-only capacity information.

The panel itemizes map imagery, terrain files, decoded terrain, AOL obstacle
tiles, icons/elevation samples, reserved downloads and Local Maps projects,
layers/models and processing work. Local Maps are measured separately and are
not removed by cache cleanup. Canonical-path deduplication avoids double-counting
SD-card targets and internal aliases. Existing storage placement is unchanged.

On the A5 Pro, the earlier figures described different volumes: approximately
217.4 GB internally and 982.5 GB on the archive SD card. Cache usage (2.8 GB),
configured limit (10 GB) and free capacity are distinct quantities. The new panel
labels the volumes. The main cache follows Archive Dir; decoded terrain and
some operational working files remain internal, and Local Maps retain their
existing internal/app-owned removable placement.

Scans, provider queries and initial capacity-based defaults run on workers.
Both inventory paths are serialized and propagate cancellation/stale-result
checks. Apple uses a short status lock for reserved-download reads, separate
from cache disk work. Android saves size and age together on an IO worker.

Validation: five focused Android host tests, two Swift inventory tests, and the
actual iPad Simulator panel/Cancel UI test passed. Both packages compiled;
metadata, archive, signature, team/device/app identity, exact release notes and
shared About copy checks passed. The Release package excludes the Simulator
probe and retired manual Backup & Transfer UI.

Refreshed operational safety backups before installation. All 15,463 checked
iPad operational files and all 260 checked Android operational files remain,
as do both checked Android external map-cache files. The active iPad diagnostic
log grew normally. Android's saved 10 GB limit is unchanged; its age preference
is still unset, retaining the existing 365-day default. No data clearing,
uninstall, managed physical test runner or acknowledgment action was used.

The iPad process remains running. A5 Pro launched to its normal biometric prompt;
the agent did not authenticate or navigate the physical panel. Simulator UI
evidence does not establish physical hardware or sustained-media qualification.
Logs, package hashes, source inputs and installation summaries are retained in
apple/Build/ReviewCandidate-421. No policy publication, commit, push, store upload,
review reply or resubmission occurred.
