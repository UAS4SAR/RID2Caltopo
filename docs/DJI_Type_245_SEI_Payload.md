# DJI H.264 Type-245 SEI Payload

## Scope

This document describes the private DJI telemetry payload observed in H.264 video
from a Matrice 4TD and decoded by RID2Caltopo as of 2026-09-08. It is based on
captured payloads, decoder tests, and controlled tabletop observations. It is not
an official DJI specification.

The payload is an H.264 SEI message with `payloadType = 245`. After removal of
H.264 emulation-prevention bytes, the payload contains little-endian TLV records:

| Field | Size | Encoding |
| --- | ---: | --- |
| Tag | 2 bytes | Unsigned little-endian |
| Length | 2 bytes | Unsigned little-endian |
| Value | `Length` bytes | Tag-specific |

A zero tag and zero length can terminate the record list. Remaining bytes must be
zero padding for the current decoder to accept the payload.

## Known TLV Records

| Tag | Length | Interpretation | Confidence |
| ---: | ---: | --- | --- |
| 4 | 39 bytes | Camera orientation, local displacement, and geodetic reference | High for layout; mixed for angle semantics |
| 6 | 9 bytes | One header byte and two packed 32-bit words | Unknown |
| 9 | 17 bytes | One header byte and four packed 32-bit words | Unknown |
| 10 | 13 bytes | Camera optics | High for horizontal and vertical field of view |

## Tag 4: Orientation And Position

Offsets below are relative to the beginning of the 39-byte tag value.

| Offset | Size | Current interpretation | Conversion | Confidence |
| ---: | ---: | --- | --- | --- |
| 0 | 3 bytes | Header or flags | Not decoded | Unknown |
| 3 | 4 bytes | Camera/world azimuth candidate | `u32 * 360 / 2^32` degrees | Strong candidate |
| 7 | 4 bytes | Stabilized camera roll candidate | `u32 * 360 / 2^32`, normalized signed | Unproven |
| 11 | 4 bytes | Camera tilt encoder | `u32 * 360 / 2^32 - 90`, normalized signed | Strong |
| 15 | 2 bytes | Relative north, low word | Part of split signed 32-bit millimeters | High |
| 17 | 2 bytes | Relative east, low word | Part of split signed 32-bit millimeters | High |
| 19 | 2 bytes | Down, low word | Part of split signed 32-bit millimeters | High |
| 21 | 2 bytes | Relative north, high word | Part of split signed 32-bit millimeters | High |
| 23 | 2 bytes | Relative east, high word | Part of split signed 32-bit millimeters | High |
| 25 | 2 bytes | Down, high word | Part of split signed 32-bit millimeters | High |
| 27 | 4 bytes | Reference latitude | `s32 * 180 / 2^32` degrees | High |
| 31 | 4 bytes | Reference longitude | `s32 * 360 / 2^32` degrees | High |
| 35 | 4 bytes | Reference altitude | `-s32 / 1000` meters | High |

The split displacement values are assembled as follows:

```text
north_mm = signed32(low16@15, high16@21)
east_mm  = signed32(low16@17, high16@23)
down_mm  = signed32(low16@19, high16@25)
```

The current altitude relation is:

```text
relative_up_m = -down_mm / 1000 - reference_altitude_m
```

### Important Diagnostic Correction

Earlier diagnostics read nine consecutive 32-bit values beginning at offset 3
and displayed all nine as angles. Only the first three reads are independent
orientation candidates. Reads 4–6 overlap the split N/E/Down words, and reads
7–9 are the latitude, longitude, and altitude fields. They must not be interpreted
as additional attitude angles.

In particular, the apparent tabletop "roll" response in diagnostic word 4 was
caused by changes in the packed displacement bits. The test did not identify a
platform-roll field.

## Tag 10: Camera Optics

| Offset | Size | Current interpretation | Conversion | Confidence |
| ---: | ---: | --- | --- | --- |
| 0 | 1 byte | Header or flags | Not decoded | Unknown |
| 1 | 4 bytes | Horizontal field of view | `u32 / 256` degrees | High |
| 5 | 4 bytes | Vertical field of view | `u32 / 256` degrees | High |
| 9 | 4 bytes | Additional optics value | Not decoded | Unknown |

The Matrice 4TD sample used during the 2026-09-08 test reported approximately
`37.703125` degrees horizontal and `21.20703125` degrees vertical.

## Controlled-Test Findings

- The offset-3 azimuth value changed substantially when the aircraft was yawed.
  The available controller could not rotate the gimbal independently, so the test
  could not prove whether this value is absolute camera azimuth, aircraft heading,
  or a value produced by gimbal-follow behavior.
- The offset-7 value remained close to zero while the airframe was tilted. This is
  consistent with stabilized camera roll, but is not sufficient to assign that
  meaning.
- The offset-11 tilt value remained near `75.437` encoder degrees, producing an
  operational tilt near `-14.563` degrees, while the airframe was tilted. This is
  consistent with gimbal-stabilized camera elevation.
- The decoded geodetic position wandered by roughly 4 meters horizontally and
  0.6 meters vertically while the aircraft remained on a table. A clue projection
  should therefore preserve source timestamps and should not assume each SEI
  position sample is survey-grade.
- Platform roll is not currently identified. For an exact optical-center ray,
  camera roll does not change the ray direction. Roll becomes relevant for an
  off-center image point or a calibrated boresight/crosshair offset.

## Operational Use

RID2Caltopo currently uses the decoded SEI camera position, azimuth candidate,
tilt, and field of view for camera display and clue projection. Device/model
calibration applied above this raw decoder is empirical and is not part of the
payload format.

SEI should be preferred over Remote ID for a video snapshot when fresh SEI is
available because it is carried with the video stream. Remote ID remains the
fallback when SEI is unavailable. Freshness and stream-to-aircraft association
must be checked before either source is used.

## Unresolved Fields And Recommended Tests

- Determine the meanings and scales of tags 6 and 9 from synchronized raw captures.
- Identify whether tag 4 offset 3 is true camera azimuth, aircraft heading, magnetic
  heading, or gimbal-follow output using a measured heading fixture.
- Test tag 4 offsets 7 and 11 with a fixture that independently controls airframe
  yaw, pitch, and roll without translation.
- Determine whether platform attitude or a boresight correction is present in the
  opaque fields.
- Retain raw type-245 payloads during research captures so future interpretations
  can be validated without relabeling overlapping bytes.
