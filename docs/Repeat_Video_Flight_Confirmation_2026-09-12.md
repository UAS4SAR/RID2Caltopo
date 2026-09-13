# iPad repeat video-only flight confirmation

Fresh attached-iPad evidence: `outputs/repeat-video-flight-20260912/ipad.log`.

- First publisher starts at 17:39:36; confirmation is queued and saved at 17:39:39.
- Publisher stops at 17:42:30. Archive upload retries time out until 17:44:04.
- Second publisher starts at 17:45:16. No second confirmation is queued, and no current-flight decision clear is logged between flights.

The `ridTracks.$tracks` subscriber received a new snapshot but built its confirmation IDs by reading `ridTracks.tracks`. Swift's Published publisher delivers before assignment, so the empty snapshot was reconciled using the previous active track. With no further empty list publication, confirmation persisted into the next flight.

The subscriber now builds confirmation IDs from the emitted snapshot. On inactivity expiry, the model captures the completed flight's metadata and clues, then removes that flight from the published list before any asynchronous archive upload. This preserves archive identity/readiness while allowing current-flight confirmation to reset during offline retries. The existing RID/video inactivity grace period is unchanged, so a brief interruption remains the same flight.

Apple package tests passed: 338 Swift Testing plus 10 XCTest, including the definitive-flight-end/reconfirmation and continuous-flight/no-repeat tests. Signed device build 220 is the deployment candidate. Physical repeated-flight behavior remains to be checked on the iPad.

Android's R2CViewModel clears confirmed and prompted decisions through `clearFinishedFlightConfirmationState` after track completion, or when an unmatched stopped stream has no active track. Its state reconciliation uses the current supplied drone list; the Apple Published old-value bug does not apply. No Android source change was needed for this report.
