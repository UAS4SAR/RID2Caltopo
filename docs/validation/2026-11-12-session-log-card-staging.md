# Session log staged off the SD card (Android) — November 12, 2026

## Incident (A5 Pro SM-X350, 17:57 PST)

- MediaProvider (`com.google.android.providers.media.module`) ran at 255/256 MB from
  17:40 and died with `OutOfMemoryError` at 17:57:00.
- At 17:57:07 the SD card (`public:179,1`, 4A21-0000) FUSE mount went down with it.
- vold found `/proc/14652/fd/209 -> /storage/4A21-0000/DroneTrax/tracks-12Nov2026/Log_12Nov2026-174016-PST-0800.txt`
  and sent SIGINT to R2C (exit at 17:57:08, `ApplicationExitInfo` reason SIGNALED).
- There was no Java crash, tombstone or ANR in R2C. Logs: `~/btrx-build-logs/crash-1756-*`.

## Change (83fa2fb0, Ken approved fix 2 only)

- `CaltopoClient.InitArchiveDir` still creates the `Log_` file in today's track folder,
  but the log writer now appends only to `noBackupFilesDir/session-log-staging/<same name>`.
  A `<name>.target` sidecar records the card file URI.
- `SessionLogCardSync` (one low-priority daemon thread, `r2c-log-card-sync`) copies new
  bytes to the card file every 3 s, on `R2CActivity.onStop`, and at session end. Each
  batch opens the file with "wa", appends, flushes and closes it.
- On failure the bytes stay in the private copy and retries back off from 3 s to 60 s.
  The next batch re-reads the card file length and appends from there, so a partial
  batch is never duplicated. A card file longer than the private log is never written.
- Session end runs a last batch with a bounded 3 s wait (skipped on the main thread). The
  private copy is deleted once the card holds every byte.
- Leftovers (from a killed run, or a card that was missing at shutdown) are caught up
  once per process, 15 s after start, in the background. They are kept while the card
  is unreachable and dropped after 14 days.
- Not changed: Archive Dir clearing when the card is missing (fix 1), other card
  traffic (fix 3), stream receiver start on service restarts (fix 4), flight `.kmz`
  writing.

## Verification

- `StagedCardLogTest` (7 tests) covers:
  - batched append with exact bytes and one open per batch
  - no card handle between batches, including after a failed batch
  - card missing, then a partial batch, then catch-up with no duplicates
  - leftover flushed on restart while the live log is left alone
  - leftover kept while the card is missing, then expired
  - non-prefix card file never written
  - source guard: the writer never opens the card file directly
- Full Android unit suite: 2,552 tests, 0 failures, 1 skipped. Debug APK builds.
- Not installed on any device (awaiting Ken's OK).

## Residual risk

- During a batch (milliseconds every 3 s) one card handle is open. An unmount in that
  window can still trigger vold's SIGINT. Before this change the handle was open the
  whole time.
- Up to about 3 s of log lines can be on the private copy only when the card goes away.
  They are kept and copied later.
