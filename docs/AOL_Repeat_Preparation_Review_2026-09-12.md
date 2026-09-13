# Repeat AOL preparation and map retention review — September 12, 2026

Fresh logs were copied from both attached devices, running 2.2.7 (208). No installation or relaunch was performed.

## Measured durations

| Stage | iPad | A5 Pro |
| --- | ---: | ---: |
| USGS catalog | 0.614 s | 0.588 s |
| Map/terrain planning and retrieval | 1.173 s | Not separately timed in this log |
| 129,065,894-byte lidar source transfer | 579.770 s | 652.970 s, including checksum |
| Source checksum | 0.113 s | Included above |
| AOL construction | 37.507 s | 19.219 s |
| Publication | 0.003 s | 0.018 s |
| Preparation job | 618.917 s including map/terrain | 672.254 s for AOL stage |

The iPad job ran 10:25:50–10:36:09 PDT. A5 AOL preparation ran 10:40:31–10:51:43 PDT. The delay is predominantly source transfer, not raster construction. These stage logs do not identify the reason for the slow network transfer.

Evidence: iPad `Log_12Sep2026-102514-PDT-0700.txt`, timing lines 108–126 and 442–464; A5 `Log_12Sep2026-103239-PDT-0700.txt`, `OfflineTiming` events for run `295dbff1-3da7-46ba-8c93-f81e97a22b96`.

## Why existing iPad tiles were not reused

The copied SurfaceV1 sets show an earlier 548 × 596-cell footprint and a new 599 × 653-cell footprint, at one metre per cell. The new footprint is 51 m wider and 57 m taller, with its center about 14 m farther south. Both are fresh, but the older set does not fully cover the new request and its required margin. Current reuse requires one complete prepared set to contain the requested region; it does not stitch partial coverage or retain raw lidar between requests. A slightly enlarged selection therefore downloads the whole source again. Fresh fully containing sets remain eligible for reuse.

## Progress correction

The old whole-job percentage and rate credited cached map/terrain bytes as completed work, then extrapolated that rate into an ETA for the remaining lidar work. This was not a measured transfer speed. Android already suppressed ETA after entering the AOL phase, but still offered a misleading ETA before that phase; Apple did not suppress it.

Both interfaces now prominently show the active phase with an indeterminate indicator for jobs that include AOL, from the beginning of the job. Existing lidar byte and construction updates remain visible in the phase message. The UI explicitly explains that remaining time varies with transfer and construction; it does not present the synthetic overall percentage, transfer rate, or countdown during these jobs. Map-only progress is unchanged.

## Why Taylor Site became standalone

The iPad log records `Leaving incident map id=4J0LF02 reason=display inactive` at 10:36:17.766, about nine seconds after completion. Preparation blocked the idle-disconnect policy while running, but completion immediately exposed the previously elapsed inactivity interval.

Apple now records completion (including cancellation/failure) and uses it as an inactivity baseline. Android's corresponding map-disconnect paths now respect the existing preparation activity timestamp. Both retain the incident map for a fresh five-minute quiet period after preparation; normal operational activity continues to prevent idle disconnection.

These changes do not accelerate network transfer or expand partial coverage reuse. They correct progress communication and premature map disconnection.

## Validation

Apple core suite passed: 329 Swift Testing tests plus 10 XCTest tests. Android full debug unit suite passed before adding the map-retention regression; the subsequent focused CaltopoMap suite passed all 25 tests including the new regression. Signed iPad application build 209 passed. Changes have not been installed or physically exercised on either device.
