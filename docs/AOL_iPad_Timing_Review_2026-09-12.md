# iPad AOL timing review — September 12, 2026

Evidence: fresh Ken's iPad log `Log_12Sep2026-075526-PDT-0700.txt` and the
prepared set `1789225146750-2D0412DC-9692-4F01-94DC-17AC8E56E75F` copied from its
app container. All displayed times are PDT. The set contains one 548 × 596 tile.

| Portion | Observed time | Duration / interpretation |
|---|---|---|
| Open Download Map, first entry | 07:56:04.160 → 07:56:04.221 | 61 ms from selection log to presentation log |
| First catalog lookup | Started 07:56:11.659 | No completion logged; cannot assign a completed duration |
| Open Download Map, second entry | 07:57:15.542 → 07:57:15.587 | 45 ms from selection to presentation |
| Successful catalog lookup | 07:57:18.389 → 07:58:23.496 | 65.107 seconds; one source, advertised 129,065,894 bytes |
| Catalog ready → AOL preparation start | 07:58:23.496 → 07:59:06.750 | 43.254 seconds; includes any operator delay and preceding map/terrain work; cannot split these |
| AOL preparation start → package write | 07:59:06.750 → about 08:05:40–44 | Approximately 6 min 33–37 sec for download, checksum, assembly, and package write combined |
| Successful lookup start → package written | 07:57:18.389 → about 08:05:44 | Approximately 8 min 26 sec including the intervening delay/work |

The preparation start is encoded from the device clock in the set identifier at
staging-directory creation. ZIP member timestamps are 08:05:40, with two-second
DOS timestamp precision; the copied tile's modification time is 08:05:44. These
are corroborating file timestamps, not instrumented stage completion events. They
do not establish an exact atomic-publication time.

The original logs do **not** contain source-transfer start/end, checksum duration,
rasterization duration, compression/validation duration, or exact publication
completion. It would be misleading to assign the six-plus minutes to processing
alone, or to derive a network speed by dividing source bytes by that whole interval.
Likewise, the 65-second catalog span cannot be separated into retries and individual
requests from these original logs. Service latency, transfer throughput, cache
state and conversion can all contribute, but their shares are not established here.

Build 206 adds a modal catalog check with Cancel and timing markers for future runs:
individual catalog attempts/status; terrain planning; map and terrain work; each
lidar transfer; checksum; assembly; and publication. Assembly currently includes
rasterization, ZIP creation, and package validation. The improved logs should make
service delay, transfer delay, and local processing distinguishable on the next run.
