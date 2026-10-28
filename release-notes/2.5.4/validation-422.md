# 2.5.4 build 422 — Android cache-panel loading

October 25, 2026. A5 Pro updated in place and launched as 2.5.4 (422). iPad
remains on 2.5.4 (421); its loading behavior is unchanged.

The Android dialog previously opened with empty fields and then resized as its
worker supplied usage data. Manage Storage now remains visible with a busy
indicator until a complete snapshot can initialize Map Cache Management. The
cache panel opens once with populated fields. Duplicate taps are disabled.
Closing the parent cancels loading; a cancelled request cannot navigate later.
A failed scan keeps a retryable error in the parent. All scanning stays on IO.

Validation: two actual-dialog Compose regressions passed on the isolated
R2C_Units_Qualification emulator, using controlled scan completion. They verify
busy parent/no premature form, populated fields, a single load, Cancel returning
to Manage Storage, off-main loader execution, and dismissal preventing a late
panel. Five focused existing storage host tests passed. Debug app and test APKs
compiled. Package identity, signature, exact release notes, metadata and diff
checks passed. No new Apple binary was built for this Android-only fix.

Operational safety backups were refreshed and verified before the physical
update. All 260 checked Android operational paths and both checked external
map-cache files remain. The 10 GB saved cache limit matches 421, the age key is
still unset (365-day default), and the crash buffer is unchanged. No uninstall,
data clear, physical instrumentation or acknowledgment occurred. The normal
biometric prompt prevents physical-panel navigation by the agent; UI behavior
was verified on the emulator instead. Source changes do not relocate or delete
operational data and do not resolve the separate storage-placement audit gaps.

Evidence: apple/Build/ReviewCandidate-422. No commit, push, policy publication,
store upload, review message or resubmission occurred.
