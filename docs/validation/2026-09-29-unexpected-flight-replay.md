# Unexpected flight replay: device evidence

Read-only inspection of both attached devices on September 29, 2026, approximately 07:42–07:45 PDT. Neither app was restarted or updated; no incident-map or device-journal changes were made.

Evidence copies are under `/tmp/r2c-upload-audit-20260929/{android,apple}`.

## Android A5 Pro R52Y90C9XST

The current app log is `DroneTrax/tracks-29Sep2026/Log_29Sep2026-065442-PDT-0700.txt`, copied to `android/current-log.txt`.

All five screenshot labels match Android journal entries targeting Taylor Site (`4J0LF02`):

| Flight suffix | Publication ID |
| --- | --- |
| 175153Sep27 | 1567987e-9b1c-45f2-a565-7c900fe7b045 |
| 175914Sep27 | bff08683-f73c-417a-837d-9ca760a7e128 |
| 185526Sep27 | 7c834d06-a78d-4ae4-b25d-35dbb1bbc1c3 |
| 192314Sep28 | e1351dc0-3fa8-4aa9-a307-283ff28508b7 |
| 065726Sep29 | 4fbcb562-0caf-4bbf-9e11-5f9f74675f0f |

At 07:01:51 four older entries reached `Interrupted LiveTrack deletion deferred`; at 07:02:14 all five did. Lines 8585–8604 show HTTP 400 `Error saving object` on DELETE `/LiveTrack/<id>` for all five. The recovery callback reaches DELETE only after the Shape write succeeds. Both Android journals still retain all five: awaiting-map decisions are `publish`, and interrupted-track entries remain present. The separate Sep27 19:31 flight is marked `local` and is not in the recovery journal.

`AwaitingMapFlights.reconcile` queues every previously approved unfinished publication for the connected destination. `CaltopoInterruptedTrackJournal.recover` writes its Shape again, then requires LiveTrack deletion success before marking it published/removing the journal entry. A standalone archive may have no LiveTrack to delete. This cleanup failure leaves successful archive uploads eligible for replay and explains recreation after manual deletion. The dialog selects one flight; background recovery independently replays older approved entries.

## Ken's iPad 694108CB-8CBE-593D-ABE1-D9EDD947B901

The current log is `Documents/RID2Caltopo/FlightStorage/2026-09-29/Log_29Sep2026-065512-PDT-0700.txt`, copied to `apple/current-log.txt`.

The interrupted-publications journal is empty. Four older awaiting-map entries are `queued`; another is `local`, and one remains `review`. The current log has no interrupted-recovery entries or references to the five Android publication IDs/labels. At 07:01:49 it explicitly records that the unconfirmed flight was ignored and archive/upload skipped. Later archive retry warnings alone do not establish a successful upload.

The observed five-flight replay is attributable to Android. This does not assert that iOS can never have another publication bug; there is no evidence here that it replayed these five flights.

## Required correction

Separate completed archive publication from LiveTrack cleanup. A successful Shape upload must not be recreated merely because cleanup fails or the user later deletes that Shape. Preserve retryability for genuinely failed uploads, and audit the same completion semantics on Apple. Regression cases should cover standalone uploads with no LiveTrack, successful Shape plus failed cleanup, reconnect after manual Shape deletion, and selecting one flight with unrelated review/local entries.

## Android correction completed in source

- Journal now persists `archiveUploaded` before deleting the LiveTrack. A restart or cleanup failure retries only DELETE, never the successful Shape write.
- Successful archive publication marks the selected awaiting-map entry `published` before cleanup, preventing reconciliation from re-queuing it.
- DELETE LiveTrack treats 400/404 as already absent, matching Apple's existing API behavior; other failures retain cleanup-only work.
- Both recovery call sites supply the incident-map artifact snapshot. Legacy journal entries with a matching Shape containing all pending coordinates are completed without writing the Shape again. Partial geometry does not count as upload completion.
- Legacy records already deleted from the map, with no durable completion flag, cannot be distinguished from never-uploaded records. They may require one final recovery upload; once completion is recorded, later deletion is respected. This fix does not silently discard potentially unsent geometry or modify the current tablet journal.

Validation: 435 Android data/publication tests passed with zero failures/errors; debug APK build succeeded. Regression coverage includes missing LiveTrack 400/404, cleanup 503 across restart and an absent Shape, legacy complete/partial Shapes, failed upload retry, and one selected flight alongside review/local entries. Log: `/tmp/r2c-upload-fix-validation.log`. `git diff --check` passed. No install or device/map mutation was performed.

## Android installation

Installed version 2.3.7 (303) in place on A5 Pro `R52Y90C9XST`; installation and launch succeeded, and device package metadata confirms build 303. Existing and new APK signing certificates match. App data was retained. Build log: `/tmp/r2c-install-303-android.log`. The user's physical replay retest remains pending; no iPad update was performed.

## Build 303 operator retest

Copied the current A5 log and both journals to `/tmp/r2c-upload-retest-303`. New flight `1sar7DjMn4Pr_082147Sep29` has ID `f40eca4a-5e95-43fb-a6dc-9d043a4c10d4`, destination `4J0LF02`, and decision `published`; the interrupted journal is empty. All five older records are now `published`. The operator subsequently confirmed the new flight appeared, but slowly.

The UI records the choice and queues the archive; recovery dispatch currently runs after a map refresh, scheduled every 20–30 seconds. This can contribute to the delay before network upload begins. The copied log does not timestamp the Publish tap, so it does not establish an exact end-to-end delay. The separate archive-server HTTP 200 at 08:28:22 is Tracker archive evidence and is not used as proof of CalTopo publication.

Updated canonical 2.3.7 release notes, synchronized the Apple metadata mirror, and updated the Google Play text (416 characters). These note changes are not yet installed on either device.
