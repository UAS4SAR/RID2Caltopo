# iPad leftover recording recovery (2.6.0 (445), November 12, 2026)

Times PST. iPad 00008103-0010596E1AD3001E.

## Problem

The 17:42:50 install closed the app mid-segment while drone 192.168.68.50 published
`1sar7djmtrc4td` (since 17:40:13). MediaMTX never emitted `record file complete`, so
`1sar7djmtrc4td_2026-11-13_01-40-13-393436.mp4` stayed raw in the UTC-date folder
`FlightStorage/2026-11-13/`, unconverted and not catalogued. The iPad had no startup
recovery (Android has had it since 1c366086).

## Change (96594dae, ebf79b6f)

- `LeftoverRecordingRecovery` (R2CCore): candidates are raw UTC-named MediaMTX files
  anywhere under Flight Storage, modified and named before this recorder started, quiet
  10 s; localized names, hidden conversion temporaries and `.sei-original` copies are
  never candidates. `checkHeader` walks top-level boxes: needs a complete `moov` and
  media data (a cut-off last fragment is fine).
- `MediaMTXViewModel`: once per process, after MediaMTX starts, a background
  `SerialTaskChain` (separate from live finalization) runs each candidate through the
  normal steps: protect the source and local day, local-time rename into the local-day
  folder, iOS conversion, `recordFileCompleted` delivery (stream registry/catalog
  refresh). The recorded time is restored after conversion so the catalog does not
  list old segments as new.
- Failures (no moov, no media, rename or conversion failure) are logged; the original
  keeps its raw name and place and is remembered by size+mtime
  (`video.leftoverRecordingFailures`) so it is not retried. Nothing is deleted.

## Device result (96594dae installed 17:56:39–17:56:44, relaunched 17:56:54)

| Time | Event |
| --- | --- |
| 17:56:55.381 | RTMP listener opened on :1935 |
| 17:56:55.634 | `Leftover recording scan found=4` |
| 17:56:56.839 | Recovered `1sar7djmn4pr_2026-11-01_22-36-19-825656.mp4` → `2026-11-01/1sar7djmn4pr/1sar7djmn4pr_01Nov2026_143619_PST.mp4` |
| 17:56:57.419 | Recovered `1sar7djmtrc4td_2026-11-06_18-10-51-219249.mp4` → `2026-11-06/…/1sar7djmtrc4td_06Nov2026_101051_PST.mp4` |
| 17:56:57.717 | Recovered `1sar7djmtrc4td_2026-11-11_16-27-49-407920.mp4` → `2026-11-11/…/1sar7djmtrc4td_11Nov2026_082749_PST.mp4` |
| 17:56:58.188 | Recovered `1sar7djmtrc4td_2026-11-13_01-40-13-393436.mp4` → `2026-11-12/1sar7djmtrc4td/1sar7djmtrc4td_12Nov2026_174013_PST.mp4` (normalized frames=3805 mergedSei=3804) |

Recovered 17:40:13 segment pulled to the Mac: 157,234,406 bytes, ftyp/moov/free/mdat,
H.264 1920×1088, 3805 frames, 156.76 s, all frames decode. The raw backup
(`~/btrx-build-logs/ipad-cut-segment/seg.mp4`, 157,293,158 bytes) is kept.

The 17:43:28 segment was not a leftover: the publisher went idle at 17:48:21 and the
running app finalized it normally at 17:48:41 (`1sar7djmtrc4td_12Nov2026_174328_PST.mp4`).

The four files recovered by 96594dae carry their 17:56 conversion time as mtime;
ebf79b6f (restore recorded time) is committed but not installed.
