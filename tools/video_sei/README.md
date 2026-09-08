# H.264 SEI inspection

`analyze_sei.py` extracts timestamped H.264 Supplemental Enhancement
Information (SEI) payloads from MP4/fMP4 recordings without modifying or
decoding the video. It uses `ffprobe` only to locate video packets, then reads
the AVCC NAL units directly from the original file.

The initial use case is DJI Matrice 4-series RTMP recording analysis. Those
streams can contain a private, per-frame SEI message with payload type 245.
The payload is preserved as opaque binary data until individual fields are
validated against controlled flight and camera-state observations.

```bash
python3 tools/video_sei/analyze_sei.py recording.mp4 \
  --payload-type 245 \
  --hex-dump /tmp/recording-sei-payloads.hex.txt \
  --json /tmp/recording-sei-summary.json \
  --csv /tmp/recording-sei-payloads.csv \
  --candidate-csv /tmp/recording-sei-angle-candidates.csv \
  --caltopo-csv /tmp/recording-sei-caltopo-candidates.csv
```

For the first Matrice 4TD sample, the decoder recognizes a repeated
little-endian tag/length structure and reports each tag and body length. The
raw CSV includes packet timestamps, the complete payload in hexadecimal, and
one decimal column per payload byte. The candidate CSV turns smoothly changing
little-endian 16-bit values into provisional 0.01-degree time series.
The hex dump includes every selected message with its packet timestamp, NAL and
message indices, payload length, byte offsets, hexadecimal bytes, and printable
ASCII. No payload-length truncation or time sampling is applied offline.

Candidate names intentionally contain their tag, byte offset, encoding, and
scale rather than labels such as `heading` or `gimbal_pitch`. DJI has not
published the observed private schema, so those semantic labels require
controlled recordings—static, gimbal sweep, aircraft yaw, zoom changes, and
movement—correlated with known aircraft and camera state before any value is
used for an operational clue.

## First Matrice 4TD capture

The analyzed 734-second capture contains 21,882 type-245 messages: one for
every decoded picture. All messages parse without error into this layout:

| Tag | Body length |
| ---: | ----------: |
| 9 | 17 bytes |
| 6 | 9 bytes |
| 10 | 13 bytes |
| 4 | 39 bytes |

The fixed body sizes reveal a repeated packed-word convention: tag 6 is one
header byte plus two 32-bit words, tag 9 is one header byte plus four 32-bit
words, tag 10 is one header byte plus three 32-bit words, and tag 4 is three
header bytes plus nine 32-bit words. Android logs tag 6 and tag 9 once per
second as both unsigned and signed integers under `DjiSeiPacked`; these remain
diagnostic until a controlled movement establishes their meanings and scales.
`DjiSeiRaw245` also records the complete decoded payload as hexadecimal so
simultaneous RID and home coordinates can be searched using alternate byte
orders, scales, and fixed-point representations without formatting loss.
`DjiSeiPayload` enumerates every SEI message type seen in the H.264 access
units, including non-245 messages, and retains up to the first 256 payload
bytes for checking whether aircraft telemetry is carried in a sibling block.

The expected CalTopo camera fields map as follows after a controlled Matrice
4TD capture on August 19, 2026:

| CalTopo field | Candidate encoding | Confidence |
| --- | --- | --- |
| `camera:azimuth` | tag 4, offset 3, unsigned 32-bit binary angle (`raw * 360 / 2^32`), then convert clockwise magnetic bearing to clockwise true-north bearing as `raw - 90° + declination` | Field-validated |
| raw gimbal tilt | tag 4, offset 11, the same binary angle minus 90°, normalized signed | High |
| `camera:fov_width` | tag 10, offset 1, unsigned 32-bit / 256 | High |
| `camera:fov_height` | tag 10, offset 5, unsigned 32-bit / 256 | High |
| controller/home latitude | tag 4, offset 27, signed 32-bit × 180 / 2^32 | High |
| controller/home longitude | tag 4, offset 31, signed 32-bit × 360 / 2^32 | High |
| controller/home reference altitude | tag 4, offset 35, negated signed 32-bit millimetres | Medium |
| local north displacement | tag 4, signed 32-bit millimetres split low word at offsets 15-16 and high word at 21-22 | Field-validated |
| local east displacement | tag 4, signed 32-bit millimetres split low word at offsets 17-18 and high word at 23-24 | Field-validated |
| down coordinate | tag 4, signed 32-bit millimetres split low word at offsets 19-20 and high word at 25-26 | Field-validated |

No independently validated aircraft-yaw/compass-heading field has been found in
this payload. In the August 21 hover-and-rotate capture, treating tag 4 offset
15 as another binary angle appeared to reproduce the turns, but that
interpretation is invalid: offsets 15-18 are the already field-validated low
words of north/east position. The SEI camera azimuth can differ from aircraft
heading when the gimbal stabilizes or yaws relative to the airframe.

The FOV fields move with camera and zoom changes. Their ratio is approximately
1.778 for 16:9 imagery and exactly 1.25 for the 5:4 thermal image, which is
strong evidence for both their meaning and scale.

The initial implementation used normalized tag 4/offset 3 directly for CalTopo
camera azimuth. A later four-aspect weather-station test held the camera toward
one target while the controller reported approximately 270°, 180°, 89°, and
0°. Offset 3 instead reported 1°, 285°, 150°, and 76°, which initially made
the field appear unrelated. Comparing a subsequent four-vantage clue test with
the known target bearings exposed the missing camera-image axis conversion:
subtracting 90° from offset 3 produces the magnetic camera azimuth. Android and
Apple add local magnetic declination to produce a true-north bearing, freshness-gate the result, and prefer it over
movement-derived or Remote ID direction. The raw value and all nine aligned
tag-4 binary-angle candidates remain in diagnostics for continued field
qualification.
Tilt uses the controlled straight-down point and the refined horizontal point:
raw -90° = straight down (-90°) and raw -15.9° = controller horizontal (0°),
with a linear scale between those endpoints, clamped to -90°...+90°. Both raw
values remain available in diagnostics for field validation.

The controlled recording started at minimum zoom, moved to maximum zoom and
back, swept tilt fully up and down, then rotated through the compass points.
The decoded FOV moved 37.70° x 21.21° to 2.02° x 1.13° and back. Tilt reached
about +120°, then exactly -90°, before settling near -15°. A later cardinal-leg
flight showed that offset 3 was not directly expressed in compass-image axes;
the required -90° conversion was identified in the subsequent clue test. The
older offset-19 azimuth and offset-25 tilt guesses did not follow the controlled
inputs and have been discarded.

These are reverse-engineered field mappings rather than a DJI-published private
schema. Android and Apple now decode the exact type-245/tag-length form above
while displaying a live H.264 stream. A complete sample received within three
seconds of a position observation can supply `camera:azimuth`, `camera:tilt`,
`camera:fov_width`, and `camera:fov_height` without delaying position
publication. A fresh SEI camera azimuth also seeds clue heading ahead of
movement-derived or RID direction and remains operator-adjustable. It must not
be described as airframe compass heading. Tilt
continues to seed clue reports, while upward
or horizon-facing tilt safely leaves the projected clue at the aircraft
position instead of inventing a ground intersection.

The August 19 four-aspect diagnostic initially showed that the last three
tag-4 fields decode to a plausible 39.153° latitude, -121.133° longitude, and
approximately 575 m reference altitude. A subsequent orbit around one target
showed that the tuple remains fixed while RID aircraft position changes. It is
therefore a controller/home reference, not aircraft position. A subsequent
cardinal-leg flight initially made offsets 15, 17, and 19 appear to be wrapping
signed 16-bit north, east, and down displacement counters in millimetres. Adding
those sequentially unwrapped north/east values to the fixed geodetic reference reproduced the RID
path with 0.343 m RMS and 0.642 m maximum disagreement after timestamp alignment
over a 0.455-mile flight.

An August 21 controlled capture, moving east-west and then south-north, located
the missing high words in the same tag. Concatenating each low word with its
corresponding high word reconstructs a signed little-endian 32-bit value:

```text
north = signed32(tag4[15:17] + tag4[21:23])
east  = signed32(tag4[17:19] + tag4[23:25])
down  = signed32(tag4[19:21] + tag4[25:27])
```

All 4,861 captured north and east samples exactly matched the earlier
continuity-based unwrap. North ranged from -50,350 to 178,982 mm and east from
1,535 to 190,698 mm; their high words changed at the expected 65,536 mm
boundaries. Down ranged from -577,409 to -561,895 mm with a constant high word
of -9. With the fixed reference altitude of 537.027 m, the direct relation
`relative up = -down / 1000 - reference altitude` yielded 24.868 to 40.382 m.

Android and Apple therefore consume the complete signed 32-bit values in every
SEI frame. A pause, dropped frames, process restart, or reconnect no longer
loses a locally maintained wrap epoch. Fresh RID remains useful to reject an
implausible SEI position and as a fallback source, but it is not used to choose
the SEI counter epoch.

## Mavic 3 Pro with DJI RC Pro control capture

An August 19, 2026 Mavic 3 Pro ground recording supplied a 102.561-second,
1280x720 High-profile H.264 stream through DJI Fly on a DJI RC Pro controller.
The controlled sequence held gimbal angles at 0, -90, +35, -90, and 0 degrees,
zoomed from minimum to 28x and back, then rotated the aircraft approximately 90
degrees.

The MP4 contained 3,074 video packets. Its H.264 NAL units were picture slices,
SPS, and PPS only; it contained no type-6 SEI NAL units and therefore no SEI
messages of any payload type. Camera orientation and FOV cannot be recovered
from this recording. This is evidence that the tested DJI Fly RTMP path omits
the metadata preserved by the Matrice 4TD/Pilot 2 path, not evidence that the
Mavic aircraft lacks those values internally.

A second 101.322-second capture repeated the test while the Mavic 3 Pro was in
flight. It contained 2,986 video packets: 2,886 non-IDR picture slices, 100 IDR
picture slices, 100 SPS units, and 100 PPS units. It likewise contained no
type-6 SEI NAL units or SEI messages of any payload type. The airborne stream
used a substantially higher bitrate than the ground capture, but flight did
not enable embedded orientation or FOV metadata in the tested DJI Fly/RC Pro
RTMP path.

## Mini 4 Pro with DJI RC Pro 2 control capture

An August 19, 2026 Mini 4 Pro recording supplied a 73.987-second, 1280x720
High-profile H.264 stream using a DJI RC Pro 2 controller. The controlled
sequence held gimbal tilt at 0 and -90 degrees, moved to maximum upward tilt
(the displayed angle was not recorded), returned to 0, zoomed to maximum and
back to minimum, then rotated the aircraft approximately 90 degrees.

The MP4 contained 1,775 video packets. Its H.264 NAL units consisted of 1,715
non-IDR picture slices, 60 IDR picture slices, 60 SPS units, and 60 PPS units.
It contained no type-6 SEI NAL units and therefore no SEI messages of any
payload type. Camera orientation and FOV cannot be recovered from this
recording. Together with the Mavic 3 Pro result, this shows that both tested
consumer-drone/controller streaming paths omit the telemetry preserved by the
Matrice 4TD/Pilot 2 path. It does not establish whether the aircraft or
controllers make the same values available through some other interface.

## Avata 360 with DJI RC 2 ground captures

Two August 19, 2026 ground recordings supplied 49.838-second and 30.856-second
1280x720 High-profile H.264 streams using a DJI RC 2 controller. Tilt and zoom
controls were unavailable on the ground; the aircraft was manually yawed
approximately 90 degrees during the test.

The recordings contained 1,486 and 926 video packets respectively, but neither
contained a type-6 SEI NAL unit or an SEI message of any payload type. An
airborne capture is still required to determine whether flight enables a
different streaming or metadata mode. A useful flight sequence should include
known tilt, zoom, and yaw changes after takeoff.

## Autel Evo Max 4N multi-camera control capture

An August 19, 2026 Autel Evo Max 4N recording supplied a 269.650-second,
640x512 High-profile H.264 stream. The controller continued publishing the IR
camera while the operator selected Night, IR, and Wide; unlike the tested DJI
controllers, it did not change the active RTMP source during publication.

The MP4 contained 8,081 video packets: 7,945 non-IDR picture slices, 136 IDR
picture slices, 136 SPS units, and 136 PPS units. It contained no type-6 SEI
NAL units or SEI messages of any payload type. The MP4 has one video stream and
no separate timed-data or metadata stream. MediaMTX likewise reported a single
video-only RTMP track. If the controller transmitted an Autel packed telemetry
structure outside the H.264 elementary stream, it was not preserved in this
recording; a raw RTMP-message capture is required to test that possibility.

Run the focused tests with:

```bash
python3 -m unittest discover -s tools/video_sei -p 'test_*.py'
```
