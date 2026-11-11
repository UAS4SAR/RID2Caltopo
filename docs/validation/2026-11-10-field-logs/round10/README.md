# Field logs — November 10, 2026, round 10

Read-only copies after the 15:09 install of 2.5.8 (444), commit `0baa4148`. Nothing was launched, cleared, or deleted.

The retest is one Matrice, `1581F8HGX255S00A0FZT`, on map `4J0LF02`. Times are America/Los_Angeles.

## Flights

| File | Owner | Points in the file | First point | Last point | Upload |
|---|---|---|---|---|---|
| iPad `…-170805….json` | iPad `f6cbd6d2` | 75 | 17:08:05 | 17:09:23 | Tracker 200 at 17:09:50 |
| A5 Pro `…-170808….json` | A5 Pro `833a8140` | 69 | 17:09:52 | 17:11:10 | Tracker 200 at 17:12:07 |
| iPad `…-171142….json` | iPad `f6cbd6d2` | 68 | 17:11:42 | 17:12:53 | Tracker 200 at 17:13:27 |

No 409. No flight left pending. No second upload. No `drone_lost` and no claim conflict.

## What worked

The lease moved both ways. At 17:08:03 the iPad took a free lease (`replaceConfirmedBy=none`) and got `local=true`. At 17:09:49 the A5 Pro tapped Save, Tracker granted `833a8140`, and the iPad closed its flight and uploaded it. At 17:11:41 the iPad Save named `833a8140`, got `local=true`, and the A5 Pro closed its flight.

Each flight had one confirmation panel. Clues taken while a tablet owned the flight all uploaded (iPad 200 at 17:08:43 and 17:12:22, A5 Pro 200 at 17:10:34). Nobody tapped Clue Snapshot while the other tablet held the lease, and the log has no "No active aircraft".

Video and Bluetooth positions were accepted. The iPad log has no `invalid_observation`. The A5 Pro's invalid count stayed 0, including the points it kept in memory after the lease went back to the iPad. Those points did not become a file.

The stored points do not overlap. The iPad's first flight ends at 17:09:23. The A5 Pro's file starts at 17:09:52. The iPad's second flight starts at 17:11:42. No shadow file, tail file, or fragment file was written.

## What did not

The A5 Pro's CalTopo shape was not saved. At 17:11:42 it tried to archive live-track id `dca603b1`, which is the iPad's already-finished shape, and CalTopo refused the overwrite (`shape-post-failed`). The Tracker file still uploaded once.

That happened because taking the lease reused the other tablet's live-track id and title. The A5 Pro file is named `170808` from the shadow start, and its title is `1sar7DjMtrc4td_170805Nov10`, the iPad's title. The file's `start_time` and its 69 points do start at the 17:09:51 grant. The in-memory track had 100 points; the shadow points were left out of the file.

Proposed fix, not built: when this tablet is granted the lease, start a new shape and name the label and the file from the grant. Do not keep the previous owner's live-track id or title.
