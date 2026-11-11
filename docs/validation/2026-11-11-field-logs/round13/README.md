# Field logs — November 11, 2026, round 13

Read-only copies from the retest of 2.5.8 (444), commit `bebe8633`, after the 08:13 install. Nothing was launched, cleared, or deleted. The iPad answered the first copy. Times are America/Los_Angeles. The A5 Pro log snapshot runs through 08:42.

## Flights

| Owner | Points | First–last point | Local file | Shape id and title | Live-track id | Upload |
|---|---|---|---|---|---|---|
| A5 Pro `833a8140` | 198 | 08:23:40–08:27:29 | `…-082340….json` | `b65e121d` `1sar7DjMtrc4td_082340Nov11` | `b65e121d` | Tracker 200 at 08:27:57. Shape on the map at 08:27:55. |
| iPad `f6cbd6d2` | 65 in the journal | 08:27:54–08:29:01 | none | `e0381722` `1sar7DjMtrc4td_082754Nov11` | `e0381722` | Not uploaded. The A5 Pro turned this live track into that shape at 08:31:17. |
| A5 Pro `833a8140` | 151 | 08:30:37–08:33:26 | `…-083037….json` | `55a6e605` `1sar7DjMtrc4td_083037Nov11` | `55a6e605` | Tracker 200 at 08:34:05. Shape on the map at 08:33:59. |
| A5 Pro `833a8140` | 51 | 08:36:03–08:37:52 | `…-083600….json` | `88c5b52f` `1sar7DjMn4Pr_083600Nov11` | `88c5b52f` | Tracker 200 at 08:38:36. Shape on the map at 08:38:45. |

The Mini 4 Pro is `1581F6Z9C24BH0036EJL`. The other three rows are the Matrice `1581F8HGX255S00A0FZT`. No 409, no `drone_lost`, no `invalid_observation`, and no second file for any of these flights.

## One panel, one confirmation, prior owner archived

Each flight opened one panel and sent one confirmation.

The A5 Pro queued the Matrice at 08:23:26. Save at 08:23:37 sent one confirmation with `replaceConfirmedBy=none`, because nobody else held the lease. Tracker granted `833a8140` at 08:23:37.

The iPad queued at 08:27:49, when the video publisher changed to `192.168.68.56:52234`. Save at 08:27:50 sent one confirmation naming `833a8140`. Tracker granted `f6cbd6d2` at 08:27:51. The A5 Pro set itself to not-owner and archived its own 198-point shape. The file rename is `082327` to `082340`. The stored points start at the grant.

After the iPad stopped, the video returned to the A5 Pro on publisher `192.168.68.56:52894`. That queued one new panel at 08:30:32. Save at 08:30:35 sent one confirmation with `replaceConfirmedBy=none`. Tracker granted `833a8140` at 08:30:35. The iPad's last peer message was 08:29:00, so the lease was already free. The Mini queued at 08:35:37 and its Save at 08:35:39 did the same: one confirmation, `replaceConfirmedBy=none`, grant to `833a8140`.

No claim conflict reopened a panel. No Save sent a second confirmation.

## The iPad shutdown

The iPad log starts at 08:18:43 and stops at 08:29:02 on `Display locked or app in background`. There is no battery, jetsam, or shutdown line after that, and no newer log. The app has not been opened again. At 08:42 the tablet answered a file copy, so it had power, and the unfinished journal was still on disk.

The in-progress flight is intact as a journal and is not recovered. `e0381722-218d-5262-add1-4b3284a30694.ndjson` has a header, a verified Mine context for `1SAR7`, and 65 points from 08:27:54 to 08:29:01. There is no flight JSON. The awaiting-map row for `e0381722` is unfinished, `decision=bound`, `publicationStarted=true`, with 61 points. The interrupted-publication record for the same live track has 62 points and the title `1sar7DjMtrc4td_082754Nov11`. A clue taken at 08:28:31, id `0EC6EDA2`, was saved locally and CalTopo returned 200 at 08:28:46.

The Tracker lease did not stay with the iPad. Its last peer message reached the A5 Pro at 08:29:00. The next Save, at 08:30:35, was granted to the A5 Pro immediately. By 08:42 the A5 Pro heartbeat showed `ownerLeaseExpireTs=0`.

While the iPad held the lease, the A5 Pro kept that traffic as a peer shadow in memory. It did not write a shadow file and it did not send `drone_lost`. It did not take the next flight until the video publisher came back at 08:30:32. At 08:30:57, with no peer lease left, it archived the iPad's still-live track as a stale duplicate. At 08:31:17 that id was a Shape, `e0381722`, title `1sar7DjMtrc4td_082754Nov11`. That is the map copy. It is not a Tracker upload, and it is not a second recording of the A5 Pro's own points. The next A5 Pro point is 08:30:37, 96 seconds after the journal's last point.

Nothing is duplicated. The flight is pending on the iPad: the journal, the interrupted-publication record, and the unfinished awaiting-map row. It is not in the review inbox. The local flight file and the Tracker upload are absent. The clue and the CalTopo shape are present.

A restart would not produce a second file or a second shape. Apple recovery of a bound flight whose publication already started removes that awaiting-map row and deletes the journal without writing a flight file. Android recovery writes the GeoJSON before it deletes the journal. The iPad's interrupted-publication retry posts Shape `e0381722` again, which is the id the A5 Pro already archived, and then deletes the LiveTrack. That delete is aimed at a LiveTrack that is already a Shape. The 65-point journal would be gone, and Tracker would still not have the flight. The three points that are in the journal and not in the 62-point interrupted snapshot would be dropped.

## Proposed fix, not built

On Apple restart, write the flight file from the unfinished journal and upload it to Tracker once, as Android already does, before deleting the journal. If CalTopo already has a shape with that live-track id, update that shape from the journal. Do not create another shape id. Keep the journal until the file and the Tracker result are durable.

Each grant also logged `renameTrack(): received on inactive track` at 08:23:40, 08:30:37, and 08:36:00. The provisional track had already been replaced by the granted id. Each flight still has one file, named at the grant, and one shape. That log line did not leave a second track.
