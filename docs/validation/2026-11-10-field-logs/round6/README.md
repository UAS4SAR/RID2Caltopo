# Field logs — November 10, 2026, round 6

Read-only copies of the test after the 11:48 install of 2.5.8 (444), commits `e972e909` and `95516e32`. Nothing was launched, cleared, released, or published, and the map was not written. The session starts at 12:31.

| Device | Log |
| --- | --- |
| Ken's iPad | `ipad/Log_10Nov2026-123112-PST-0800.txt` |
| A5 Pro | `android/Log_10Nov2026-123123-PST-0800.txt` |

Aircraft `1581F8HGX255S00A0FZT`, mapped `1sar7DjMtrc4td`. iPad `asyncpreemptoff=1`.

## Flights

The iPad owned 12:34:33–12:36:34. One panel. Shape `c007543c-ce87-5735-90b6-23d3179b3bac`. Tracker returned 200 once for `…-123438….json`. It did not send `drone_lost` when the A5 Pro took the aircraft. The A5 Pro opened one panel at 12:36:31 and Save was at 12:36:34. That panel was not opened again.

From 12:36:36 the A5 Pro started a new track about every two seconds and saved each one `unconfirmed`. The shadow copy from 12:34:42 was one of those files. None of the A5 Pro files were uploaded, and there was no 409. The iPad's next flight, saved at 12:42:03 as `…-123638….kmz`, stayed `tracker upload pending` and its live track was discarded.

Two RTMP stalls on the iPad, 221 ms at 12:35:35 and 325 ms at 12:40:44, both with an empty socket. No crashes.
