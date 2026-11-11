# Field logs — November 10, 2026, round 11

Read-only copies after the 17:34 install of 2.5.8 (444), commit `8847a988`. Nothing was launched, cleared, or deleted. Times are America/Los_Angeles. Only the flights after that install are covered.

## Flights

| Owner | Points in the file | First–last point | Local file | Shape id and title | Live-track id | Upload |
|---|---|---|---|---|---|---|
| iPad `f6cbd6d2` | 92 | 17:38:57–17:40:57 | `…-173857….json` | `0c97d2c3` `1sar7DjMtrc4td_173857Nov10` | `0c97d2c3` | Tracker 200 at 17:41:20. Shape archived 17:41:20. |
| A5 Pro `833a8140` | 143 | 17:41:21–17:44:06 | `…-173917….json` | `42762db9` `1sar7DjMtrc4td_174121Nov10` | `42762db9` | Tracker 200 at 17:45:04. Shape was on the map at 17:44:32. |
| iPad `f6cbd6d2` | 169 | 17:44:33–17:48:01 | `…-174434….json` | `d7a4c172` `1sar7DjMtrc4td_174434Nov10` | `d7a4c172` | Tracker 200 at 17:48:35. Shape archived 17:48:36. |

The three live-track ids are different. None is `dca603b1`. There is no "refusing to overwrite" and no "minting shape" fallback. The A5 Pro saw the iPad's live track `0c97d2c3` and left it alone, then started `42762db9` at 17:41:22.

## Handoffs and panels

The iPad's first Save at 17:38:53 was a free lease (`replaceConfirmedBy=none`) and became `local=true`. One panel.

The A5 Pro tapped Save at 17:41:18. Tracker granted `833a8140` at 17:41:19. The iPad closed that flight and archived shape `0c97d2c3`. The A5 Pro log still does not print the zone it named. One Save.

The iPad's Save at 17:44:30 named `833a8140` and became `local=true` the same second. The A5 Pro archived its own shape `42762db9` (143 points) at that handoff. One panel for that flight.

## Clues and positions

The iPad clue at 17:39:54, while it owned the flight, uploaded 200. The A5 Pro clue at 17:42:59, while it owned the flight, uploaded 200. Nobody tapped Clue Snapshot while the other tablet held the lease. The iPad kept accepting Bluetooth positions during that gap (17:42 and 17:43). There is no `invalid_observation`. The A5 Pro's invalid count stayed 0.

## What is still off

The A5 Pro file is named `173917`, the shadow-track start at 17:39:01. Its title, `start_time`, and first stored point are the grant, 17:41:21. The file has 143 points. The archive log's "181 coordinates" is the in-memory track, which still included the shadow points. Those points are not in the file, and there is no shadow, tail, or fragment file for this flight. The shape title is the grant time.

Startup recovered one journal from the previous flight and wrote `…-170949….json` at 17:38:08. That file is the 17:09:49–17:11:10 flight, marked recovered and local-only, and it was not uploaded. It is not one of these three flights.

No 409, no `drone_lost`, no claim conflict, and nothing from these three flights left pending.

Proposed fix, not built: name the archive file from the grant time, the same way the shape title is named.
