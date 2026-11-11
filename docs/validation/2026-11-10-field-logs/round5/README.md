# Field logs — November 10, 2026, round 5

Read-only copies of the battery-and-pilot swap retest on 2.5.8 (444), commit `210d7e2d`. Nothing was launched, cleared, released, or published, and the map was not written.

| Device | Log |
| --- | --- |
| Ken's iPad | `ipad/Log_10Nov2026-111546-PST-0800.txt` |
| A5 Pro | `android/Log_10Nov2026-111608-PST-0800.txt` |

Aircraft `1581F8HGX255S00A0FZT`, mapped `1sar7DjMtrc4td`. Map `4J0LF02`. iPad `asyncpreemptoff=1`.

## Flights

The A5 Pro owned 11:17:38–11:25:12. One panel, Save at 11:17:42. Shape `219c248d-28b3-5750-87f7-d0710048733b`. Tracker returned 200 once for `…-111738….json`. The iPad kept an unconfirmed local copy and did not upload it. The lease expired at 11:25:12.

The iPad owned 11:25:38–11:28:48. One panel. Shape `5c9830ad-0bf0-5d01-ab57-d93e1a467408`. Tracker returned 200 once for `…-112542….json`.

The A5 Pro took that aircraft at 11:28:45. Its panel opened once for the Matrice. Save at 11:28:48 sent the replacement, and Tracker's claim conflict opened the same panel again. Save at 11:28:53. Ownership moved to the A5 Pro at 11:28:50. The segment it closed at 11:31:36 was shape `c85f49a4-71e5-5a3b-a6a2-930df464ae15`, decision `publish`, and Tracker returned 409: the file still contained the shadow points from 11:25:46, which overlap the iPad's log. The A5 Pro did not send `drone_lost`.

The iPad took the aircraft back at 11:31:31. One panel. It closed its previous flight at the handoff and did call `drone_lost` from the flight-end handler. Shape `a53484f7-f51c-51f1-835e-40e96505380a`. Tracker returned 200 once for `…-112915….json` at 11:36:05. The A5 Pro's following copy was `unconfirmed` and was not uploaded.

Both confirmation panels were for this one Matrice. There was no second aircraft.
