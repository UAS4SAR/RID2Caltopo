# 2.5.4 build 423 — recording protection and archive capacity

October 25, 2026. Installed in place and launched on both iPad and A5 Pro as
2.5.4 (423). The iPad process remains running. A5 Pro is running at its normal
biometric prompt; the agent did not authenticate or accept acknowledgments.

Includes the qualified recorder-run and one-hour inactivity changes: old raw
recording names do not establish an active recorder after restarting. Intermediate
recording files untouched for an hour no longer imply ongoing writing. Today,
review/upload/finalization owners remain protected. Android's finalizer explicitly
holds its source/work days until completion/failure and releases them in finally.
The inactivity threshold itself does not delete a file; ordinary retention rules
and user-selected deletion remain separate.

Android's main Manage Storage row queries the selected Archive Dir directly,
showing its available capacity or Unavailable, without internal-cache fallback.
The cache panel retains the previously verified busy-parent/populated-panel
transition. No persistent-storage migration was performed; those audit gaps remain.

Qualified runtime and test source hashes match the prior 19 Swift, 18 Android host
and two isolated-emulator UI checks. This rebuild changes version metadata and
packages the current canonical notes. Both packages built. Unsigned archive,
device signature, app/team/device provisioning, version, note bytes, policy links,
shared About copy and store-metadata checks passed. The iPad export is for local
device review, not distribution upload.

Operational safety backups were refreshed and verified before installation.
All 15,464 checked iPad operational paths and 260 checked Android operational
paths remain, as do both checked Android external map-cache files. All eight
October 9 iPad recordings have unchanged sizes and modification dates. The active
iPad diagnostic log grew normally. Android's 10 GB cache limit is unchanged; the
age preference is still unset (existing 365-day default). Its crash buffer is
unchanged. No uninstall, data clear, reset or physical instrumentation was used.

Evidence and package/source hashes: apple/Build/ReviewCandidate-423. No commit,
push, policy publication, store upload, review reply or resubmission occurred.
