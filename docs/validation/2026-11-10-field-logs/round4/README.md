# Field logs — November 10, 2026, round 4

Read-only copies of the Matrice 4TD test on 2.5.8 (444). Nothing was launched, cleared, released, or published, and the map was not written. The earlier logs through 09:37 have no flights after 09:15; these files are the 09:47 session.

| Device | Log |
| --- | --- |
| Ken's iPad | `ipad/Log_10Nov2026-094729-PST-0800.txt` |
| A5 Pro | `android/Log_10Nov2026-094744-PST-0800.txt` |

Aircraft `1581F8HGX255S00A0FZT`, mapped `1sar7DjMtrc4td`, model DJI Matrice 4TD. The stream designator is `1sar7djmtrc4td`. SEI telemetry is `dji-sei-245` with `remoteId=null`; the aircraft match is the configured stream designator, not an SEI serial. Map `4J0LF02`.

## 09:49–09:52, A5 Pro

Video arrived at 09:49:30. The panel was queued and Save was at 09:49:33. Tracker `drone_confirmed` names the A5 Pro. Live track `25fc3706-08af-5e38-98ac-71869fcd8c5c`. Video stopped at 09:52:57. The iPad stayed unconfirmed.

## 09:53–09:55, iPad

Video arrived at 09:53:19. The panel was queued and Mine was at 09:53:22. Tracker ownership moved to the iPad. The A5 Pro set its live track local owner to false. The iPad archived a new shape `ae8b53c4-124a-50af-a61c-77503a1174cd` and Tracker returned 200 once at 09:55:55. `Owner expired` at 09:55:54.

## 09:55, A5 Pro did not reacquire

Video for the same aircraft arrived again at 09:55:45, while the iPad claim was still live, and stopped at 09:56:00. There is no confirmation-panel line. The A5 Pro still treated its 09:49 Mine as this flight's decision. That decision was cleared only when the first track finished at 09:56:31, after the video was already gone. The first live track was then deleted because this tablet was no longer the owner, and `…-094931….json` was saved `local_only` rather than uploaded.
