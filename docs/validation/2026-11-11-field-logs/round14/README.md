# Field logs — November 11, 2026, round 14

Read-only copies after Ken opened the iPad on 2.5.8 (444), commit `19771b0d`, installed at 09:03. Nothing was launched, cleared, or deleted. Times are America/Los_Angeles. The iPad answered the first copy.

## The 08:27 battery-death flight

The restart did not do what `19771b0d` was supposed to do.

There is no flight file for 08:27:54. `r2c_reported.txt` does not name one, and there is no Tracker upload for it. The waypoint-journal directory is empty, so the 65-point journal was retired anyway. The only remaining copy of those 65 points is the journal committed with round 13.

At 09:18:20 the iPad logged `Recovered interrupted LiveTrack` for `e0381722` with **62 points**, the interrupted snapshot, not the journal. The A5 Pro saw that id as a Shape at 09:18:38. At 09:18:58 CalTopo's id list no longer contained it, and the A5 Pro dropped it. The archive posts the shape and then deletes the live track of the same id. That delete removed the shape the A5 Pro had archived at 08:31. No second shape id was created.

The awaiting-map row and the interrupted-publication record are gone. Recovery did not send a confirmation. At 09:18:58 the lease was free (`ownerLeaseExpireTs=0`).

The local photo for clue `0EC6EDA2` was deleted at 09:18:24. CalTopo had already accepted that clue at 08:28:46. The same delete path removed the morning clue `7C51EF3C` at 09:18:04. The `e0381722` clue folder is now empty.

## Flights after 09:03

| Owner | Points | First–last point | Local file | Shape id and title | Live-track id | Upload |
|---|---|---|---|---|---|---|
| A5 Pro `833a8140` | 85 | 09:21:12–09:22:48 | `…-092111….json` | `7b94f9de` `1sar7DjMtrc4td_092111Nov11` | `7b94f9de` | Tracker 200 at 09:23:21. Shape at 09:23:25. |
| iPad `f6cbd6d2` | 175 | 09:23:11–09:26:13 | `…-092311….json` | `5db0dbe3` `1sar7DjMtrc4td_092311Nov11` | `5db0dbe3` | Tracker 200 at 09:26:31. Shape at 09:26:31. Clue `69870225` uploaded 200 at 09:24:58. |
| A5 Pro `833a8140` | 73 | 09:26:33–09:27:54 | `…-092632….json` | `f3959345` `1sar7DjMtrc4td_092632Nov11` | `f3959345` | Tracker 200 at 09:28:34. Shape at 09:28:29. |
| iPad `f6cbd6d2` | 79 | 09:28:24–09:29:46 | `…-092824….json` | `cb8fef88` `1sar7DjMtrc4td_092824Nov11` | `cb8fef88` | Tracker 200 at 09:30:18. Shape at 09:30:18. Clue `A012E63D` uploaded 200 at 09:29:06. |

Each of these opened one panel and sent one confirmation. The iPad's Saves named `833a8140`. The A5 Pro's 09:26:30 Save named `f6cbd6d2`. The 09:21:08 Save said `replaceConfirmedBy=none` because the lease was free. Each handoff archived the previous owner's shape, and each flight used a new live-track id. Files are named at the grant (`092106`→`092111`, `092627`→`092632`). No 409, no `drone_lost`, no `invalid_observation`, and no extra flight file.

`renameTrack(): received on inactive track` logged again at 09:21:11 and 09:26:32. Each flight still has one file and one shape.

## Proposed fix, not built

Load the awaiting-map row and the interrupted publication before deciding that a journal already started on the map. Write the flight file from every journal point, and delete the journal only after that file is on disk. Post those same points to shape `e0381722`. After that post, do not delete the id: the live-track delete is what removed the shape from the map. Leave the clue photo in place.
