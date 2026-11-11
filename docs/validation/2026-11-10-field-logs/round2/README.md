# Field logs — November 10, 2026, round 2

Read-only copies after the 2.5.8 (444) install. Nothing was launched, cleared, released, or published, and the map was not written.

| Device | Log | Covers |
| --- | --- | --- |
| Ken's iPad | `ipad/Log_10Nov2026-074420-PST-0800.txt` | 07:44–07:57 |
| A5 Pro | `android/Log_10Nov2026-071628-PST-0800.txt` | 07:16–07:17 startup |
| A5 Pro | `android/Log_10Nov2026-072441-PST-0800.txt` | 07:24–07:57, all three flights |

Aircraft `1581F6Z9C24BH0036EJL` / `1sar7DjMn4Pr`. Map `4J0LF02`. iPad zone `f6cbd6d2-ea23-45cd-b4ef-a559822c31ad`. A5 Pro zone `833a8140-2a8b-3b23-8876-c3af62524ef0`.

At 07:45:52 the iPad log shows the earlier held flight `3351095c` discarded from the awaiting-map list. That happened before these three flights.

## Flight 1, 07:47–07:49, A5 Pro

Video from `192.168.68.63` reached the A5 Pro at 07:47:07. The confirmation panel was queued for the live stream. Save at 07:47:09 sent Mine. Tracker `drone_confirmed` at 07:47:10 names the A5 Pro. The track `1sar7DjMn4Pr_074711Nov10` was the local owner. It archived 54 points to shape `22b0b448-39b7-56cf-8954-389aee101b7e`, linked the recording, and Tracker returned 200 once for `…-074711….json`. The iPad stayed a peer and did not open a panel until the video later arrived there. The iPad's unanswered local copy was kept local when that sighting ended at 07:49:47, and Tracker then expired the A5 Pro lease.

## Flight 2, 07:50–07:52, iPad

The iPad's video started at 07:50:01 and the panel opened at 07:50:02 because it was a new publisher. Save at 07:50:05. Tracker `drone_confirmed` names the iPad. The A5 Pro logged the same confirmation at 07:50:05 and started a non-published peer track `1sar7DjMn4Pr_075007Nov10`. It rearmed confirmation for the new aircraft and then did not queue a panel. The iPad video stopped at 07:52:03. The iPad archived 37 points and one clue, Tracker returned 200 once for `…-075010….json`, and CalTopo archived shape `981fa02a-4e80-55ae-8ec3-df785974543c`. The A5 Pro kept its copy local with decision `unconfirmed` and did not upload it. Tracker expired the iPad lease at 07:52:29. The A5 Pro log has no `owner_expired` line.

## Flight 3, 07:53, video moved to the A5 Pro

The same aircraft's video reached the A5 Pro at 07:53:30 (`192.168.68.63:39014`, path `1sar7djmn4pr`) and stopped at 07:53:50. The stream was admitted and rendered. There is no `Queueing confirmation` line. At 07:53:50 the A5 Pro logged `Confirmation retired` and `video session ended before RID` for `1581F6Z9C24BH0036EJL`, so the video was matched to that aircraft and the aircraft was not an active local track. No new Mine, no new shape, and no Tracker upload.

The A5 Pro still treated the iPad's 07:50 confirmation as a reason not to offer the panel. A peer `drone_confirmed` was copied into the "already confirmed" set and `IsCurrentPeerDroneConfirmed` made `queueConfirmationIfNeeded` return without a log. Removing the separate "already claimed" notice left no other takeover offer. The 06:43 fixes did not cause this: peer confirmation was already barred from counting as Mine, and this flight did not reuse an archived shape. The missed flight-end clear on the A5 Pro's peer track left that peer decision in place when the video arrived.

## Rest of the session

No crashes. Marker publication returned 200, including the iPad marker `f6cbd6d2` and the A5 Pro marker `833a8140`. The iPad logged `asyncpreemptoff=1` at 07:44:20. RTMP read windows show `drops=0`. The longest media gap in these windows is 545 ms on the iPad's 07:50 flight. The iPad startup `ERROR` is the usual "RTMP listener unavailable while server is stopped; starting".
