# Field logs — November 10, 2026

Read-only copies from the 2.5.14 (443) tablets. Nothing was launched, cleared, released, or published. The held flight is still on the iPad.

| Device | Log | Covers |
| --- | --- | --- |
| Ken's iPad | `ipad/Log_10Nov2026-061453-PST-0800.txt` | 06:14–06:23 startup, no flight |
| Ken's iPad | `ipad/Log_10Nov2026-063103-PST-0800.txt` | 06:31–06:53, both flights |
| A5 Pro | `android/Log_10Nov2026-061520-PST-0800.txt` | 06:15–06:53, both flights |

Aircraft `1581F6Z9C24BH0036EJL` / `1sar7DjMn4Pr`. Map `4J0LF02`. iPad zone `f6cbd6d2-ea23-45cd-b4ef-a559822c31ad`. A5 Pro zone `833a8140-2a8b-3b23-8876-c3af62524ef0`.

## 06:35–06:40, iPad flight

The iPad selected Mine at 06:35:50. Tracker verified that zone as owner (`local=true`). The flight ended at 06:40:57. The shape `118c7307-97ed-51c8-9f5f-34132eea3ac2` was written at 06:40:59, 2 seconds later, and Tracker returned 200 for `…-063605….json` once. The A5 Pro stayed a peer (`non-published`, owner `f6cbd6d2`) and saved `…-063602….json` as `local_only`. It did not upload that file.

`Owner expired` at 06:40:57.833 is the iPad's own lease ending after that published flight.

## 06:43–06:48, the held flight

This is the A5 Pro's flight. Ken's suspicion matches the logs.

- 06:43:38 the aircraft published video to the A5 Pro. 06:43:40 the A5 Pro queued confirmation. 06:43:43.469 Tracker `drone_confirmed` `confirmedBy=833a8140` (the A5 Pro).
- 06:43:43.493 the iPad logged `Ownership … ownerZoneId=833a8140 local=false`.
- 06:43:48 the A5 Pro started its own track `1sar7DjMn4Pr_064346Nov10` and set `localOwner=true`.
- 06:43:50.465 the A5 Pro also called `addLiveTrack()` with id `118c7307`, the iPad's already-archived 06:36 shape, and then queued waypoints under the title `1sar7DjMn4Pr_063605Nov10`.
- 06:43:51.462 the iPad logged `Publication allowed … panelConfirmed=true` and `Panel path=rearm_already_confirmed`. It did not send a new Mine claim. There is no `Sent pending drone confirmation` for this flight.
- 06:47:53 the iPad ended the track, wrote `…-064351….kmz` (57 points), and logged `tracker upload pending`. No CalTopo shape from the iPad.
- 06:48:08 the iPad logged `Owner expired` again.
- 06:48:08 the A5 Pro archived `1sar7DjMn4Pr_063605Nov10` (130 points) and at 06:48:49 attached its recording to shape `118c7307`.
- 06:48:10 the A5 Pro uploaded `…-064346….json` (91 coordinates). Tracker returned 200 once, and `ReportStatsForFile` marked it reported.

The iPad journal entry `3351095c-3ff7-5c54-8705-a75ddf49c5e2` is still `decision=review`, `ownershipReviewPending=true`, `claim=expired`, `recordedPilot=1SAR7`, `publicationStarted=false`, 57 points from 06:43:51 to 06:47:19. It was not released.

### Why the iPad held it

Two code paths stacked. This was not a button press.

`isCurrentFlightConfirmed` is true when either the local session or a peer confirmation is stored (`RIDAircraftDetailView.swift`). The A5 Pro's `drone_confirmed` is applied with `applyPeerConfirmation`, which fills `peerIdentities`. A new RID fix after the previous flight then takes `rearm_already_confirmed` (`CompletedFlightConfirmationGuard` rearms on `fresh_aircraft`, and the panel treats the peer identity as already confirmed). The iPad therefore believed the aircraft was confirmed without claiming it.

`Owner expired` after the 06:36 flight calls `rememberClaimOutcome(expired)` for that remote id (`AppleTrackerCoordinator.swift`). The 06:43 flight never replaced that outcome, because the iPad never sent a new claim. Flight end then saw a confirmed aircraft with claim `expired`, which is the ownership hold. The hold itself did the right thing: no CalTopo shape and no Tracker upload from the iPad.

### Proposed fix, not applied

Do not treat another tablet's confirmation as this tablet's Mine. A fresh flight whose Tracker owner is another zone should stay unconfirmed on this tablet. Clear an `expired` outcome that belongs to a flight this tablet already finished, instead of leaving it on the aircraft for the next flight. On Android, do not call `addLiveTrack` with a live-track id that has already become a Shape, and do not attach the new recording to that old shape.

## Rest of the morning

No other flights. No crashes. No marker HTTP 4xx. The A5 Pro logged marker cleanup `http=0` at 06:15:25 for its own id `833a8140` (attempts 13 on `4J0LF02`, attempts 6 on `D0AK8KB`), before the map was up. The iPad logged `asyncpreemptoff=1` at 06:31:03.505. RTMP stalls during the iPad's 06:36 flight were mostly under 600 ms with `fionread=0` and `drops=0`. The A5 Pro's 23-second read at 06:48:08 was the publisher connection closing, `fionread=0`, `queueDepth=0`, `drops=0`. The iPad `ERROR` at startup is the usual "RTMP listener unavailable while server is stopped; starting".
