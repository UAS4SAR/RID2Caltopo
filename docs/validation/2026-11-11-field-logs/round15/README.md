# Field logs — November 11, 2026, round 15

Read-only copies after Ken flew 2.5.8 (444), commit `98e0a722`, installed at 09:57. Nothing was launched, cleared, deleted, or written to CalTopo. The iPad copy succeeded on the first try. Times are America/Los_Angeles.

File counts use the whole FlightStorage tree (flight JSON plus `clues.json`) and the A5 `tracks-*` folders. iPad Nov 10 is still 34 JSON and 12 logs. iPad Nov 11 is 11 JSON and 5 logs. A5 Nov 10 is 34 JSON and 18 logs. A5 Nov 11 is 9 JSON and 4 logs.

## Flights after 09:57

| Owner | Points | First–last point | Local file | Shape id and title | Live-track id | Upload |
|---|---|---|---|---|---|---|
| iPad `f6cbd6d2` | 109 | 10:06:06–10:07:59 | `…-100606….json` | `594876e2` `1sar7DjMtrc4td_100606Nov11` | `594876e2` | Tracker 200 at 10:08:26. Shape at 10:08:26. Clue `487F8943` uploaded 200 at 10:07:15. |
| A5 Pro `833a8140` | 160 | 10:08:28–10:11:32 | `…-100828….json` | `2ce65f29` `1sar7DjMtrc4td_100828Nov11` | `2ce65f29` | Tracker 200 at 10:11:59. Shape at 10:11:52. Clue `483b0f7b` uploaded 200 at 10:10:58. |
| iPad `f6cbd6d2` | 142 | 10:11:51–10:14:18 | `…-101151….json` | `af1ebb77` `1sar7DjMtrc4td_101151Nov11` | `af1ebb77` | Tracker 200 at 10:14:39. Shape at 10:14:39. Clue `72033B7C` uploaded 200 at 10:13:34. |
| A5 Pro `833a8140` | 65 | 10:14:40–10:15:51 | `…-101440….json` | `2707afc4` `1sar7DjMtrc4td_101440Nov11` | `2707afc4` | Tracker 200 at 10:16:37. Shape at 10:16:37. Clue `c746b12c` uploaded 200 at 10:15:56. |

Each flight had one confirmation. The iPad's 10:06:03 Save said `replaceConfirmedBy=none` because the lease was free. Its 10:11:48 Save named `833a8140`. The A5 Pro's 10:08:25 Save named `f6cbd6d2`, and its 10:14:38 Save named `f6cbd6d2`. Each handoff archived the previous owner's shape. The four live-track ids are different. None of these four ids was pruned after it became a Shape.

Files are named at the grant. The A5 Pro renamed `100817` to `100828` and `101436` to `101440`, and the earlier names are not on disk. No shadow, tail, fragment, or second flight file.

No journal was waiting at startup (`journalsRecovered=0`, iPad archives flushed = 0), so the new restart recovery did not run.

No 409, `drone_lost`, or `invalid_observation`. No flight was left pending or held. `renameTrack(): received on inactive track` logged at 10:08:28 and 10:14:40. Each of those flights still has one file and one shape.

The 10:08 A5 Pro file's `r2c_flight_id` is `e483e246`, not the shape id `2ce65f29`. The 10:14 file's `r2c_flight_id` matches its shape. The same split showed up on the 09:21 file. It did not create a second file or a second shape.

## Shapes in the 10:04 id list

Both tablets opened map `4J0LF02` at 10:04 and received the same 32-feature catalog. That catalog does not contain `7b94f9de`, `5db0dbe3`, `f3959345`, `cb8fef88`, or `e0381722`. `e0381722` was already gone after the 09:18 delete. This session does not show a delete or an ids-prune of the four 09:21–09:30 shapes. The line count in that catalog was 4 before this session and 8 after these four tracks were archived.

The four new shapes are in the later deltas and are still class Shape at 10:16–10:18.
