# BTRX map layer: design (draft r3, November 12, 2026)

Status: design only. **Map-layer code is on hold.** The layer is
experimental and opens only from Settings > Developer Tools on Android and the
iPad. The stream-view steering overlay (`BtrxSteering`) stays as it is.
**Nothing from BTRX goes to CalTopo**: subjects are marked through the
existing video clue capture.

r2 folds in BT RX's review of r1 (1667bdd3) and Ken's answers (8:06 PM PT). r3 (after the 20:18 PT indoor yaw test and Ken's 8:39 PM PT note) makes
the DJI camera yaw, equal to the aircraft nose yaw on the M4TD, the heading
source.
Each review point was checked against btrx `af2549e` (branch `steering-report`,
read with `git show`) and the RID2Caltopo code. Section 12 lists where this
doc qualifies or does not adopt a point. **[A]** marks an assumption; **[BT]**
marks a BT RX statement that could not be checked in either repo.

**Milestone 1 is the bearing layer** (`BEARING-LAYER-spec.md` r2,
November 13): a **continuous** layer with no hover stations and no button.
While it is on, every STEER report is posed at its own time and paints a
faint wedge into a per-source grid that darkens where reports agree. That
grid **is** the section 5 model, so the former milestone 2 is folded into
milestone 1. The core is a shared C library (C11, stdlib, pthreads) with thin
Android and iPad layers; the bearing-layer spec supersedes sections 5, 6, 8
and 10 below where they differ.

Why: the overlay shows one source with hysteresis and smoothing
(`BtrxSteering`: a side change needs two reports; EMA plus slew limit on the
length), so it lags. Every reading is timestamped and the app knows where the
drone was, so the map can show each reading at once, where it was taken,
unsmoothed. The estimate comes from many readings agreeing.

## 1. As-built geometry and signs

**Patches (design doc §40, v0.27, by construction).** Two Linx ANT-2.4-CPA
patches. Their boresights in level flight point azimuth −45° (port-forward)
and +45° (starboard-forward), each 45° below horizontal. Both look forward.
The ceramic centres are at (∓76, 18, −30) mm, so they are 152 mm apart (more
than λ = 125 mm). The port patch is fed by pod B (the IMU board) and the
starboard patch by pod A (the LoRa board). The v0.12 "20° outboard,
0.12 dB/deg" numbers (§24) are superseded and not used here.
**Beamwidth is not measured.** HPBW ≈ 90° is an estimate.

**Signs used in this doc** (PROTOCOL.md STEER; `BtrxProtocol.Steer`):

- Δ = rssi_port − rssi_stbd. Positive means the source is to the **left**.
- −128 means not heard.
- Roll is positive with the starboard side down; pitch is positive nose up.

Design doc §24.2 defines Δ the other way (S − P), so none of its formulas are
copied here.

**What Δ says.**

- The sign of Δ gives the side, both ahead and behind: a source behind-left is
  still nearer the port boresight.
- Δ ≈ 0 on the fore/aft line, ahead or behind.
- |Δ| depends on azimuth **and** depression angle, so it is not a fixed slope
  times bearing.
- Ahead and behind look the same in Δ. They differ in total level: behind
  means both back lobes, so for the same RSSI the source must be much closer.
  That can only be used **across readings**: P0 (transmit power plus body
  loss, 10–20 dB of body loss alone, design doc §8.7) is unknown, so one
  reading's level can't tell "behind and close" from "ahead and far".
- Each board has a fixed offset (cable, ceramic, foil, radio), so the model
  carries a board offset c0. It is estimated, not assumed zero.

**Rough slope [A].** Model each pattern as G(θ) ≈ −12(θ/HPBW)² dB. Count
azimuth only, ignore the 45° depression, and take HPBW 90°. Then near the nose
Δ ≈ −0.27 dB/deg × φ, where φ is the source azimuth, positive to starboard.
The depression and the source's elevation lower this. Use 0.2–0.27 dB/deg
until flight test 1 measures it.

## 2. What the app gets (facts)

**STEER (0x73), about 1 per second.** (Ken asked for the top 2–3 sources per
STEER, November 13; format pending BT RX, `BEARING-LAYER-spec.md` r3
section 4.1.) The single strongest source in the pod's
1 s window. The pod keeps the current target unless another is at least 3 dB
stronger, and leaves out ignore-listed and muted sources.

| Field | Meaning |
| --- | --- |
| `steer_seq` | incremented only after the frame is handed to the mesh (`send_steer`), so gaps are losses after hand-off. Windows the pod skipped (stale, busy, pairing) don't bump it; they show as `window_end_ms` steps over 1 s |
| `flags` | bit 0 target, 1 new source, 2 linked, 3 gated, 4 IMU ok, 5 muted, 6 IMU calibrated |
| `window_end_ms` | pod A's `millis()` at the window end, since power-up |
| `age_ms` | target's newest sample until hand-off |
| `src_id` | pod id; restarts at 1 at power-up; a source unheard for 60 s is forgotten (the pod remembers 16) and comes back with a new id |
| `rssi_port`/`rssi_stbd` | **mean dB** of that patch's samples (`mean8`) |
| `peak_rssi`, `hits_port`, `hits_stbd` | strongest single sample; sample counts per patch |
| `company_id`, `dev_class`, `addr_type` | maker; pod-decided kind (phone, wearable, tracker, audio, computer, unknown); address type 0–3 |
| `mac` | **the full 6-byte address** |
| `roll_x2`, `pitch_x2` | attitude at **send time** (below) |

**The patches aren't paired.** Each board runs its own BLE scan, and pod B's
samples reach pod A over the UART (§40, PROTOCOL.md). The two means come from
different packets **[BT]** on different channels, so Δ is not measured on the
same packet. Expect 5–6 dB of noise per sample **[BT]** (design doc §8.7 says
5–10 dB). Ground reflection and body loss mostly change the total level, not
Δ **[BT]**.

**Attitude flags (checked in firmware).**

- Roll and pitch come from `atan2` of the **latest** pod B accelerometer
  sample (`btrx_attitude_from_accel`, overwritten on every `IMU_SAMPLE`).
  STEER copies whatever is current at send time (`send_steer` reads
  `agg.att_flags`, `agg.roll_x2`).
- Bit 3 (gated) is set when |roll| > 10°, |pitch| > 15° or |roll rate|
  > 15°/s (`btrx_attitude_gated`; the roll rate is gx/10).
- In a coordinated banked turn, or while accelerating, the accelerometer still
  reads near level. Only the roll-rate check works then, and a steady turn has
  yaw rate but no roll rate. **Bit 3 is a weak gate.**
- Bit 4 (IMU ok) means the latest `IMU_SAMPLE` said IMU ok **and** arrived
  within 1 s (`last_imu_ms`). Bit 4 clear with port at −128 means pod B
  dropped off the link, not a direction (`Steer.portBoardSilent`).

**DETECT (0x70).** Up to 7 records every 5 s, each covering a 5 s window. Each
record's `meta` carries a **class_hint**, a different list from STEER's
`dev_class`: unknown, Apple, Google, Samsung, hearing, tracker, fitness,
other. The app names it `deviceClass`; that's a naming bug to fix later.
`hits` is port + stbd combined, and `rx_mask` says which patches heard it.
**No DETECT lines in tonight's logs.** BT RX says DETECT should arrive every
5 s when anything is heard and will bench-check it. A 5 s window is 25–50 m of
path, so DETECT is a poor geometry source: use it for the source list and
class only.

**HEALTH.** `clock_offset_ms` is the pod A/B cable clock offset
(`btrx_clock.cpp`), not the tablet offset. `uptime_s` is useful for spotting
restarts.

**Measured tonight** (A5 session logs 18:50 and 19:11, 1,840 live STEER
frames). Delay over the smallest arrival − `window_end_ms`:

| | Median | p90 | p99 | Max |
| --- | --- | --- | --- | --- |
| Delay | 54–75 ms | 103–119 ms | 0.23–0.39 s | 1.03 s |

- `steer_seq` gaps: 3 and 0.
- 43 "backlog" frames came in 5–20 s late. In the app, backlog means frames
  the Desk Pod queued while R2C was away (`BtrxClient`). PROTOCOL.md says
  messages waiting for an app are dropped when no app is attached, so the
  queue is short.
- Address types: 0: 345, 1: 1, 2: 707, 3: 787.
- Hits: the DJI source (0x08AA, `..493D`) gave 39–41 per patch per window;
  phone-class sources often about 5.

**Drone pose in the app.**

- **RID track points** (`WaypointTrack.TrackPoint`: lat, lng, ele,
  `timestampMsec`) give position. `PositionTelemetry.aircraftTrackDeg` is the
  **ground track**.
- **DJI stream SEI.** The DJI binary SEI path (`emit_dji_camera_telemetry`,
  tag `dji-sei-245`) fills `cameraYawDeg` from tag 4 bytes 3–6 (signed
  degrees × 1e7) and passes NaN for `headingDeg` (field logs show
  `headingDeg=null`; that field is only set by the text-key SEI path, from a
  key containing "heading" or "course"). **On the M4TD the camera yaw is the
  aircraft nose yaw**: the camera's yaw only changes by yawing the aircraft
  (Ken, 8:39 PM PT). The 20:18 PT indoor yaw test confirmed it is the only
  field in the type-245 SEI that follows yaw: +358.7° clockwise in four
  quarter turns and back (r = 0.993; tags 6 and 9 constant). The other angles
  are gimbal pitch (bytes 11–14) and a likely gimbal roll (bytes 7–10, scale
  unconfirmed). Aircraft roll and pitch are not in the SEI.
  True-north bearing = raw − 90, normalized (the existing rule from the
  2026-10-06 circular flights; now also `R2CDJIAircraftYawTrueNorthDegrees`,
  `DjiCameraOrientation.aircraftYawTrueNorthDeg`,
  `OperationalClueGeometry.djiAircraftYawTrueNorthDegrees`). Indoors the start
  heading read 308.8° true for a nose set "about north" on the mat; Ken puts
  that on indoor metal. **An outdoor check with the nose on a known landmark
  is still needed to confirm the north offset.**
- **Pod:** roll and pitch only. There is no magnetometer, and gyro data
  doesn't leave the pod.

## 3. Heading rule (Ken, 8:06 PM PT)

The pod's front lines up with the drone's camera direction: the aircraft nose
(yaw), not the direction of travel. The drone can move in any direction.
**Geometry uses DJI aircraft yaw only, never the RID track.**

**Heading source:** the DJI camera yaw, which on the M4TD is the aircraft
nose yaw (section 2), converted with the existing true-north rule (raw − 90).
Yaw rate comes from differencing it (about 30 frames per second); the yaw test
saw hand turns at up to 145°/s. The pod's fore/aft axis is aligned with the
nose **[A]** (mounting, not checked). Other aircraft would need their own
check that camera yaw equals nose yaw.

Readings with no fresh yaw (older than 0.5 s at reading time) are shown as
wedges with a gray "no heading" style and don't feed the grid.

## 4. Data model

One reading per STEER that has a target:

```
t_ms             reading time on the tablet clock (section 6)
pose             lat, lon, alt from RID; aircraft yaw and yaw rate over the window from DJI SEI; roll, pitch from the pod
rssi_port/stbd   mean dB or null; hits_port/hits_stbd; peak
source_id        short ID: base64 of a keyed hash of the full address (section 7)
class            phone / watch / hearing aid / tag / unknown (section 7)
company_id, addr_type, flags (gated, imu_ok, imu_cal, linked, new)
pod_session      section 6
```

The full address stays in memory only, where it's needed: grouping, the
own-aircraft ignore list, and mutes.

## 5. Model (v1)

The grid is the same as r1 (local metres, 10 m cells, about 500 m around the
path [A], per source), and so is the cost. Each cell gets a better likelihood:

- **Look-up.** For each cell, compute azimuth φ and depression ε from the
  drone pose (position plus DJI yaw, roll and pitch). Look up the predicted Δ
  and the summed gain Gp + Gs in a pattern table. v1 table: HPBW ≈ 90°
  patterns at the as-built boresights. Replace it with the measured
  Δ-versus-azimuth curve from flight test 1 (yaw in place).
- **Δ term (robust).** Use a Student-t or Huber likelihood on
  `Δ_obs − Δ_pred(φ, ε) − c0` with variance
  `σ² = σ_fade² (1/N_port + 1/N_stbd) + σ_c0²`, where σ_fade ≈ 5.5 dB.
  BT RX proposed `σ_fade²/N` with N the smaller hit count. Section 12 explains
  the difference.
- **One-patch readings.** When only one patch heard the source, treat the
  other side as censored below the scan floor. That is strong side evidence
  near abeam, but only with bit 4 set (pod B present) and at least 2 hits on
  the heard patch. With fewer hits, a slow advertiser may simply not have been
  caught on the other board.
- **Range term (weak).** Path loss with unknown P0, using the summed-gain
  look-up. Including the gain is what separates ahead from behind over several
  readings.
- **Negative evidence.** STEER carries only the strongest source (later the
  top 2–3), so a source
  missing from a window is **not** evidence it isn't there. A no-target STEER
  (bit 0 clear) is evidence: nothing above the floor was heard in that window.
- **Roll correction:** not applied yet. The sign depends on elevation, and for
  distant sources it's roughly Δ − S·roll (§41.2 switch stays off). Readings
  are gated instead (below).
- **Decay:** superseded by `BEARING-LAYER-spec.md` r3 section 2.4: weight
  max(0, 1 − (age/N)^p), N = 10 min and p = 1 (linear) by default, both user
  settings.

**Gating in the app**, since bit 3 is weak:

- Drop a reading from the grid when the DJI yaw rate over the window exceeds
  10°/s [A], or the yaw changed more than 5° within the window [A]. Aircraft
  roll and pitch aren't in the SEI, so banked-turn gating relies on the yaw
  rate (a steady turn is a yaw-rate turn) plus the pod's roll-rate check
  (bit 3).
- Also drop it when bit 3 is set or bit 4 is clear.

**Smear:**

- The 1 s window is 5–10 m of path, which is fine.
- A 30°/s turn smears heading by 30° within one window; that is what the yaw
  rate gate is for.

## 6. Time and sessions

- **Now.** Estimate the offset as the minimum of `arrival − window_end_ms`
  over a sliding 2 min window. Reading time is
  `window_end_ms + offset − 500 ms`, the window's centre.
- **Better, no firmware change.** `TIME_PING` (0x30) and `TIME_PONG` (0x31)
  exist. The 16-byte payload is seq, t1, t2, t3. The Drone Pod answers on the
  mesh with `t2 = now` and `t3 = millis()`, pod A's clock, the same clock as
  `window_end_ms` (`BtrxModule::on_mesh_frame`, any role, no extra policy past
  TX on). The app already names these types. A min-RTT ping every 30 s [A]
  gives the offset directly. One thing not checked: that the Desk Pod relays a
  ping from the app to the Drone Pod. The bench check settles it. A Desk Pod
  receive stamp isn't needed **[BT]**.
- **Backlog frames:** used if no more than 15 s late, the initial lag budget
  (Ken, November 13; `BEARING-LAYER-spec.md` r3 sections 3.4–3.5, with
  per-stage lag instrumentation). Buffer them, and time them once live frames (or a pong)
  give the offset. Don't time them by arrival.
- **New pod session** when any of these happens:
  - `window_end_ms` goes backward;
  - the arrival offset jumps by more than 2 s [A];
  - HEALTH `uptime_s` drops.

  `window_end_ms` alone can miss a restart if the pod ran longer than it had
  before.
- **Error.** Arrival-only timing adds 54–75 ms median (about 0.3–0.5 m at
  6 m/s). The min-offset or ping estimate removes most of it.
- **Pose interpolation.** Interpolate RID positions linearly at reading time
  (reject gaps over 3 s), and DJI attitude likewise from SEI
  `sourceTimestampUs` mapped to the tablet clock [A].

## 7. Sources, IDs and class

- **Key: the full address, never `src_id`.** `src_id` restarts at power-up and
  changes after 60 s unheard. The pod's bit 2 links are suggestions only.
- **Shown ID (Ken):** a short base64 ID from a keyed hash of the full address,
  for example 6 characters. The key is per incident and never saved, so logs
  and replays can group sources without holding bystanders' addresses. That
  keeps today's rule (`BtrxFrameLog`: no full addresses in shared logs).
- **Class, shown first (Ken):** phone / watch / hearing aid / tag / unknown,
  from STEER `dev_class`, then DETECT `class_hint`, then company ID
  (`BtrxMakers`) and DETECT service UUIDs (hearing 0xFDF0/0x1854/0x1850/0x184E,
  heart rate 0x180D, Tile 0xFEED/0xFEEC, per PROTOCOL.md). BLE Appearance isn't
  carried in STEER or DETECT. That would be a firmware option.
- **Rotation (warnings).**
  - The pod's link rule needs the same 2-byte manufacturer-data prefix. For
    Apple that's the Continuity type and length, which every nearby iPhone
    shares **[BT]**, so false links are possible. The app marks Apple links
    "probable".
  - AirTags away from their owner rotate about every 24 h **[BT]**, so they
    work well as a single key.
  - Type 3 sources with no maker (tonight's likely watch) stay as short
    separate tracks.
- **Own aircraft.** Ignore it by **full address**, never by company ID (0x08AA
  is DJI; other DJI aircraft must still show).
  - The M4TD has Bluetooth per FCC **[BT]**; its RID goes over Wi-Fi **[BT]**.
  - Whether `..493D` is the aircraft or the RC Plus 2 is unverified.
  - "Mark as own aircraft" in the BTRX pane can also push the address to the
    pod's ignore list (`FILTER_LIST`, static identifiers only).

## 8. Single readings and turn-and-compare

**Per-sample noise is 5–6 dB [BT], so a 1–2 dB Δ from one sample means
nothing.** Each reading is still plotted at once as a faint wedge, with
opacity set by its confidence:

- σ_Δ = 5.5 · √(1/N_port + 1/N_stbd) dB;
- confidence ≈ |Δ − c0| / σ_Δ, clipped to 0–3;
- wedge direction from the sign of Δ (port or starboard of the DJI yaw);
- wedge width from the pattern table.

**Samples needed** (σ = 5.5 dB per sample, N per patch, independent samples
**[A]**; samples from a hovering drone are correlated, so the real N needed is
larger):

| N per patch | σ_Δ | Bearing σ at 0.25 dB/deg [A] |
| --- | --- | --- |
| 1 | 7.8 dB | not usable |
| 5 | 3.5 dB | ~14° |
| 15 | 2.0 dB | ~8° |
| 40 | 1.2 dB | ~5° |
| 60 | 1.0 dB | ~4° |
| 120 | 0.7 dB | ~3° |

Rule of thumb: N ≈ 2 · (5.5 / target σ_Δ)². That means about 15 samples per
patch for 2 dB and about 60 for 1 dB. Tonight's DJI source gave about 40 per
patch per second, so about 1.2 dB per STEER. A phone at 5 hits per window
takes about 12 s of hovering for 1 dB.

**Turn-and-compare (null-seeking).**

1. Hover, hold yaw, and average Δ over K windows until σ_Δ is about 1 dB.
2. Yaw 20° to port, hold, and average again.
3. The change ΔΔ ≈ S × 20° (about 5 dB at 0.25 dB/deg). **Board offset c0 and
   P0 cancel in the difference**, so two holds also measure S on this source.
4. The zero crossing is at yaw ψ* = ψ0 − (Δ0 − c0)/S. A third hold near ψ*
   confirms it: Δ ≈ 0 means the source is on the nose or the tail. Higher
   total level means the nose; the tail is both back lobes.
5. To see a 5 dB change at 3σ, the difference needs σ ≤ 1.7 dB, so each hold
   needs σ_Δ ≤ 1.2 dB: about 40 samples per patch. That's about 1 s for a fast
   advertiser and about 8 s for a phone.

In the continuous layer (`BEARING-LAYER-spec.md` r2) holds need no
detection or special drawing: each hold's reports are ordinary readings, and
their wedges stack up and darken on the grid like any others.

## 9. Rendering

- A local overlay on the in-app map: Android `SplitMapPane`, iPad
  `RIDTrackMapView`. Per source it shows:
  - the heat surface in the source's colour;
  - the top-cell marker labelled with class, short ID, count and age;
  - faint per-reading wedges, accumulated and fading over the fade time
    (10 min, linear by default; no separate bearing lines);
  - the latest report's ray as a stroke-only outline on top;
  - a tap on the shading opens a mute/unmute panel (r3 spec section 5.2).
- No smoothing and nothing sent to CalTopo.
- The stream overlay is unchanged.
- Milestone 1 rendering (accumulated wedges, estimate and ellipse) is
  specified in `BEARING-LAYER-spec.md` r2 section 5. The library renders the
  raster; the platforms only show it.

## 10. Performance, parity, placement

- All computation runs on a background worker (an Android single-thread
  executor; an iPad serial queue or actor). The worker drops readings rather
  than queueing them, and publishes a snapshot at most once a second.
- The layer starts only from its Developer Tools switch, never at app start.
  The stream server, pod link, overlay and live views keep priority.
- Shared logic will live in the C library `btrx_bearing`
  (`apple/Sources/CBtrxBearing`, built by SwiftPM and by the Android CMake
  file, as CAOL is), with thin `BtrxBearingLayer.kt` / `BtrxBearingLayer.swift`
  wrappers and shared test vectors (`BEARING-LAYER-spec.md` r2 sections
  6–9). The DJI SEI decoder is shared C (the Android `ffmpeg_bridge.c` / `native/R2CDJICameraTelemetry.h`; the iPad
  uses `R2CFFmpegMobile.c`), so the yaw field and its true-north helper are
  the same on both.

## 11. Validation

1. **Replay tonight's logs:** check timing, backlog buffering, session
   detection, the short IDs and the own-aircraft filter. They can't check
   geometry: the pod sat still and there was no pose.
2. **Synthetic tests:** the pattern table, 5.5 dB per-sample noise, and
   independent boards. Pass if the top cell is within 30 m after two crossing
   legs [A], no mirror solution remains, and turn-and-compare recovers a set
   bearing within 2σ.
3. **Flight test 1 (yaw in place):** a source at a known bearing and distance.
   Hover and step yaw in 30° increments, holding each step for about 40
   samples per patch. This gives the Δ-versus-azimuth table, c0 and S. Before
   it, point the nose at a known outdoor landmark to confirm the yaw's north
   offset (raw − 90 = true north).
4. **Field test:** a stable-address source at a surveyed point, with
   lawnmower and crossing legs, then turn-and-compare holds. Logs keep the
   short IDs only.

## 12. Review points: adopted, qualified, not verified

Adopted after checking the code or the design doc:

- **1:** geometry (§40).
- **2:** signs.
- **6:** firmware attitude, gate and snapshot.
- **7:** smear.
- **9:** `src_id` lifetime; uptime for restarts.
- **12:** `steer_seq` increments after hand-off.
- **14:** class_hint, combined `hits`, `rx_mask`, `clock_offset_ms`.
- **Firmware answer:** the TIME_PING/PONG handler.

Qualified:

- **3:** ahead versus behind from total level holds only across readings,
  since P0 is unknown.
- **4:**
  - Var(Δ) of two independent means is `σ²(1/N_port + 1/N_stbd)`.
    `σ²/min(N)` understates it by up to 2× when the counts are equal. This
    doc uses the sum, which is the same idea.
  - One-patch censoring only with bit 4 set and at least 2 hits.
- **5 and Ken's rule:** adopted. The DJI camera yaw is the aircraft nose yaw
  on the M4TD (Ken; confirmed by the 20:18 PT yaw test). `headingDeg` stays
  NaN and is not used.
- **8:** bit 4 also requires the sample's own IMU-ok flag, not just arrival
  within 1 s.
- **11:** consistent with app code (the Desk Pod queue while R2C is away) and
  with PROTOCOL.md (the queue is dropped when no app is attached). Queue depth
  not checked.
- **13:** independent boards checked; "different channels" and the 5–6 dB
  figure are BT RX's.

Not verifiable in either repo (adopted as **[BT]**):

- **10:** the Apple prefix is shared by all iPhones; AirTag rotation is
  about 24 h.
- The M4TD FCC Bluetooth and Wi-Fi RID facts.
- Whether the Desk Pod relays TIME_PING (bench check).

## 13. Firmware options for Ken (not decided)

| Option | Cost (BT RX) | Gain |
| --- | --- | --- |
| A. Top 2–3 sources per STEER (**Ken: yes**, November 13; format pending BT RX) | ~7.9–9.4% air time; needs the STEER cap raised from 8% to 10%, or DETECT at 10 s | More sources get 1 s geometry, not just the strongest |
| B. STEER carries yaw rate and max roll rate over the window, and bit 3 holds for the whole window | 0–2.6 ms CPU, a few bytes | Gate turns without depending on the DJI stream; fixes the send-time snapshot |
| C. Carry BLE Appearance (or an appearance-derived class) | not estimated | Better phone/watch/hearing-aid class |
| D. Desk Pod receive stamp | not worth it (BT RX) | TIME_PING already covers it |

## 14. Open questions

Answered by Ken (8:06 PM PT):

- Pod front = camera/nose direction; use aircraft yaw, never track. On the
  M4TD the camera yaw only changes by yawing the aircraft, so camera yaw =
  nose yaw (8:39 PM PT).
- A short base64 ID is fine; full address only where needed; class matters
  most.
- No BTRX data to CalTopo.
- Fade 10 min to start; November 13: linear (50% at 5 min), with the total
  time and the fade shape as user settings (`BEARING-LAYER-spec.md` r3
  section 2.4).

Still open:

- **Ken:** which firmware options (section 13), if any? (A: yes.) (Hover versus passes
  is answered, November 13: the layer runs continuously with no enforced
  hover; hover-and-yaw just sharpens a spot. See `BEARING-LAYER-spec.md` r2.)
- **BT RX:** bench-check that DETECT arrives, and that a TIME_PING from the
  app reaches the Drone Pod. Which board is `..493D` (aircraft or RC Plus 2)?
- **Outdoor landmark check** of the yaw's north offset (indoors read 308.8°
  true at a nose set about north; Ken expects indoor metal).
