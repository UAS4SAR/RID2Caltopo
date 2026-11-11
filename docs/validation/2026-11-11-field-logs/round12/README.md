# Field logs — November 11, 2026, round 12

Read-only copies from the morning retest of 2.5.8 (444), commit `0cac4277`. Nothing was launched, cleared, or deleted. Times are America/Los_Angeles.

## Flights

| Owner | Points | First–last point | Local file | Shape id and title | Live-track id | Upload |
|---|---|---|---|---|---|---|
| A5 Pro `833a8140` | 72 | 07:27:44–07:29:05 | `…-072743….json` | `ad537353` `1sar7DjMtrc4td_072743Nov11` | `ad537353` | Tracker 200 at 07:29:30. Shape on the map at 07:29:30. |
| iPad `f6cbd6d2` | 108 | 07:29:27–07:31:20 | `…-072927….json` | `357f87d2` `1sar7DjMtrc4td_072927Nov11` | `357f87d2` | Tracker 200 at 07:31:45. Shape archived 07:31:45. The A5 Pro saw this live track titled `1sar7DjMtrc4td_072926Nov11`. |
| A5 Pro `833a8140` | 98 | 07:31:47–07:33:37 | `…-073146….json` | `5bbf1a08` `MDjMtrc4td_073146Nov11` | `5bbf1a08` | Tracker 200 at 07:34:12. Shape on the map at 07:34:15. |

The three ids are different. No 409, no `drone_lost`, no `invalid_observation`, and `journalsRecovered=0`. Nothing was merged and nothing was left pending.

## 072743 and 072926

`072743` is the A5 Pro's first flight. `072926` is the iPad's flight: Save was 07:29:26, the file and the first stored point are 07:29:27, and the shape id is `357f87d2`.

They do not share a timestamp. The A5 Pro's last point is 07:29:05 and the iPad's first is 07:29:27, 22 seconds later, 0.2 m apart. The iPad track stays within 36 m of the pad (113 m of path). The A5 Pro track goes 129 m out and comes back (263 m of path). The farthest point on one track from the other is 128 m. This is the handoff split, not one span recorded twice.

At 07:29:26 the iPad Save named `833a8140` and Tracker granted `f6cbd6d2`. The A5 Pro set itself to not-owner and archived its own 72-point shape. The rename log is `072738` to `072743`. The stored points start at the grant, 07:27:44. The earlier name was not left behind.

## The two panels on the A5 Pro

The first flight had one panel: queued at 07:27:37 because video arrived, Save at 07:27:40, `replaceConfirmedBy=none` because nobody else held the lease.

The second showing is the 07:31 takeover. Video queued the panel at 07:31:41 while the iPad held the lease. Save at 07:31:44 dismissed it. One click sent three confirmations in 4 ms. The first two said `replaceConfirmedBy=none`. The third named `f6cbd6d2`, and Tracker granted `833a8140` at 07:31:44.294. The iPad archived `357f87d2` at that handoff.

At 07:31:44.198 the iPad's confirmation echo was still `confirmedBy=f6cbd6d2`. At 07:31:44.257 `requestDroneConfirmation` showed the panel again. That is not the automatic "Queueing confirmation" path. At 07:31:44.568 the claim-conflict handler logged `saved=false showing=true` and left that panel up. `e972e909` did not open a new one. Save had not stayed marked saved: a peer claim still on the books clears this flight's Mine, so the conflict arrived looking unsaved. Ken tapped Save again at 07:31:47. That second Save said `replaceConfirmedBy=none` because this tablet already held the lease. It did not start a second shape. The flight that followed is `5bbf1a08`.

The third shape's title is `MDjMtrc4td_073146Nov11`. Both Saves logged the callsign as `m`, then `M`, while the queued row still said owner `1sar7`.

## Proposed fixes, not built

Keep a Save marked saved from the click until this tablet's own grant comes back. Do not clear it because the previous owner's echo is still in the claim book, and do not call `requestDroneConfirmation` again for that aircraft while the Save is in flight. Send one confirmation, and put `replaceConfirmedBy` on it before the first send. Do not replace a known callsign with a one-letter value.
