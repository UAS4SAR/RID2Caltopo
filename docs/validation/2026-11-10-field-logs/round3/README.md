# Field logs — November 10, 2026, round 3

Read-only copies after the 2.5.8 (444) takeover install. Nothing was launched, cleared, released, or published, and the map was not written.

| Device | Log | Covers |
| --- | --- | --- |
| Ken's iPad | `ipad/Log_10Nov2026-085523-PST-0800.txt` | 08:55–09:09, header `2.5.8 (444)` |
| A5 Pro | `android/Log_10Nov2026-085531-PST-0800.txt` | 08:55–08:56 startup, no flight |
| A5 Pro | `android/Log_10Nov2026-085649-PST-0800.txt` | 08:56–09:09, all three flights |

Aircraft `1581F6Z9C24BH0036EJL` / `1sar7DjMn4Pr`. Map `4J0LF02`. iPad zone `f6cbd6d2-ea23-45cd-b4ef-a559822c31ad`. A5 Pro zone `833a8140-2a8b-3b23-8876-c3af62524ef0`.

The video moved to the next tablet only after the previous owner's lease had already expired. Each receiving tablet was offered the confirmation panel when its own video arrived. There was no still-held claim to replace.

## 09:00–09:02, A5 Pro

Video arrived at 09:00:12. The panel was queued. Save at 09:00:14. Tracker `drone_confirmed` names the A5 Pro. New shape `c7e942a9-d31f-59b7-9627-ffbd5fe757ce`. Tracker returned 200 once for `…-090020….json` at 09:02:27. The iPad never received the video, stayed unconfirmed, kept a local copy only, and logged `Owner expired` at 09:02:22.

## 09:02–09:05, iPad

Video arrived at 09:02:37, 15 seconds after that expiry. The panel was queued. Mine at 09:02:39. Tracker ownership is the iPad. New shape `61262d9f-b76f-5e0d-98b6-74f284e3efac`. Tracker returned 200 once for `…-090245….json` at 09:05:21. The A5 Pro stayed a non-published peer, saved `…-090242….json` as `unconfirmed`, and did not upload it. `Owner expired` at 09:05:20.

## 09:05–09:08, A5 Pro

Video arrived at 09:05:48, 28 seconds after that expiry. The panel was queued. Save at 09:05:50. Tracker `drone_confirmed` names the A5 Pro. New shape `bbc69a93-e4b1-54d9-80db-9b850cedd9dc`. Tracker returned 200 once for `…-090553….json` at 09:08:14. The iPad logged `rearm_no_candidate` because it had no video, kept the flight local only, and logged `Owner expired` at 09:08:06.

## Rest of the session

No held or pending review flights. No marker HTTP 4xx. Marker publication returned 200. The iPad logged `asyncpreemptoff=1` at 08:55:23.703. One iPad RTMP read stall at 09:03:48 was 201 ms with `fionread=0` and `drops=0`. The timing windows also show `drops=0`. No crashes. The iPad startup `ERROR` is the usual "RTMP listener unavailable while server is stopped; starting".
