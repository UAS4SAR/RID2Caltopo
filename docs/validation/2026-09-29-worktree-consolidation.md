# Worktree consolidation — 29 September 2026

The active integration project is `/Users/kjt/Projects/RID2Caltopo` (`main`).
Changes were combined from `/Users/kjt/.codex/worktrees/outage-recovery/RID2Caltopo`
(`codex/outage-recovery`), preserving uncommitted work on both sides. Both started
from commit `e3e1cab50`. The original outage-recovery worktree remains intact.

The combined project is version **2.3.7, build 300**. This identifies the source
under verification; no device installation, store upload, release commit, or tag
was performed by this consolidation.

## Recovery and merge evidence

`outputs/controlled-merge-20260929/` contains:

- `snapshots/primary` and `snapshots/outage`: binary Git patches, staged patches,
  status listings, file hashes, and copies of changed tracked files and incoming
  untracked files that could participate in the merge.
- `inventory.json`: disposition of all 100 candidate paths.
- `proposed/`: the resolved three-way merge before the build-number adjustment.
- `preservation-check.json`: unrelated primary files were unchanged.
- Platform release-check logs.

The initial merge had 41 incoming tracked changes, 22 incoming new files,
11 clean overlapping merges, 19 identical paths, two primary-only tracked changes,
and five conflicted paths. Both Git indexes were preserved. A post-merge hash
audit confirmed the source worktree was unchanged.

## Resolution decisions

- Retained the newer Android operator header, horizontal status controls, and
  organization/user information that the user verified on build 299.
- Retained the newer connection-dialog regression tests.
- Retained shared observable iPad map settings; its conflict was a comment.
- Advanced Android and Apple build numbers together to 300.
- Preserved the main project's additional Apple NOTAM map focus, geometry details,
  and service-notice wording alongside the newer layout and recovery work.
- Preserved primary-only App Store notes and the platform parity ledger.
- Included automatic network-restoration checks and both platforms' new tests.
- Compared native libraries/frameworks against the outage-recovery worktree:
  all 19 checked libraries, headers, and metadata files matched.

## Validation

Release checks are run from `/Users/kjt/Projects/RID2Caltopo`:

- Android: `R2C_TRACKER_DIR=/Users/kjt/Projects/r2c-tracker ./gradlew :app:releaseCheck --offline`.
- Apple: `apple/release-check.sh --skip-native-rebuild --archive-path apple/Build/RID2CaltopoApple-merged-300.xcarchive`.
  Existing native frameworks are verified, rather than rebuilt; no native source
  changed in this merge.

Both complete release-check commands passed from the main project.

- Android: 1,222 unit tests, zero failures/errors/skips; complete release checks
  and release APK build succeeded (6m 51s). Artifact metadata is 2.3.7 (300).
- Apple: 434 Swift tests passed; 5,209 portable anomaly checks passed; native
  verification, color/person qualification, clean simulator link, device archive,
  privacy metadata, and embedded WebRTC verification passed.
- `git diff --check` passed. All 79 written paths matched the reviewed merge
  (with the explicit build-300 adjustment). All other existing primary files were
  unchanged. Both Git indexes and the outage-recovery worktree were unchanged.
- `merged-source-sha256.json` fingerprints all 100 candidate paths;
  `android-artifact.json` and `apple-artifact.json` identify the built artifacts.

Artifacts:

- Android: `app/build/outputs/apk/release/app-release.apk`.
- Apple: `apple/Build/RID2CaltopoApple-merged-300.xcarchive` (unsigned).

The combined changes remain uncommitted in the main project. Use that project for
subsequent integration and release preparation; do not build a release from the
retained outage-recovery copy without reconciling it first. No devices were
modified, and no app-store release was submitted. Physical UI, network outage,
radio, and video qualification remain separate from these automated checks.

## Subsequent authorized device installation

On 29 September, following the user's installation request, build 300 was built
from the consolidated main project and installed in place on A5 Pro
`R52Y90C9XST` and Ken's iPad `694108CB-8CBE-593D-ABE1-D9EDD947B901`.
All 100 merged-source fingerprints still matched before these builds.
Android used the development certificate matching the existing installation;
the iPad app passed signature verification. Both installation and launch commands
succeeded, and device queries confirmed **2.3.7 (300)** on each device.
No uninstall or data reset was performed. Physical testing remains with the user.
Build logs: `android-install-build.log` and `apple-install-build.log` in the
consolidation evidence directory.
