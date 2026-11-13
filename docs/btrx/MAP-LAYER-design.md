# BTRX map layer: design (draft, November 12, 2026)

Status: design only, no code. The layer is experimental and opens only from
Settings > Developer Tools on Android and the iPad. The stream-view steering
overlay (`BtrxSteering`) stays as it is.

Why: the overlay shows one source, with left/right hysteresis and smoothing so
the pilot's arrow doesn't flicker (`BtrxSteering`: confirm a side change over
two reports, EMA plus slew limit on the length). That makes it lag. Each
reading is timestamped and the app already knows where the drone was, so the
map can show what every reading says, at the place it was taken, with no
smoothing. The estimate comes from many readings agreeing, not from filtering
one stream.

Sources: app code on `feature/btrx-integration`; btrx `firmware/PROTOCOL.md`
and `docs/ble-sar-detector-design.md` at btrx `af2549e` (branch
`steering-report`, read with `git show`); tonight's A5 session logs
`~/btrx-build-logs/crash-1916-a5-Log_12Nov2026-185050/191149-PST-0800.txt`.
**[A]** marks an assumption. Everything else is from code, the protocol doc or
the logs.

## 1. What the app gets today (facts)

**STEER (0x73)**, 27 bytes, the single strongest source in the pod's last 1 s
window, about once a second (PROTOCOL.md "STEER"; `BtrxProtocol.Steer`):

| Field | Meaning |
| --- | --- |
| `steer_seq` | counts STEER frames; a gap means one was dropped |
| `flags` | bit 0 target heard, 1 new source, 2 address change linked, 3 attitude gated, 4 IMU ok, 5 a source is muted, 6 IMU calibrated |
| `window_end_ms` (u32) | pod A's clock at the end of the window, in **ms since power-up**. Not wall time. Going backward means the pod restarted |
| `age_ms` | time from the target's newest sample until the frame was handed to the radio |
| `src_id` | pod-assigned id; restarts at 1 on power-up; kept across a linked address change |
| `rssi_port`, `rssi_stbd` | mean dBm per patch over the window; −128 means not heard. Port is pod B; port stronger means the source is to the left |
| `peak_rssi`, `hits_port`, `hits_stbd` | strongest single sample, and the sample count per patch |
| `company_id`, `dev_class`, `addr_type` | maker; device kind; 0 public, 1 random static, 2 resolvable private, 3 non-resolvable |
| `mac` (6 bytes) | **the full address as received** |
| `roll_x2`, `pitch_x2` | from pod B's accelerometer, 0.5° units |

Facts that matter for this design:

- **The full 6-byte address already reaches the app.** STEER and the DETECT
  record both carry it (`macLsbFirst`). The app cuts it to the last 4 hex
  characters on purpose: the log is shared for support and must not hold
  bystanders' full addresses (`BtrxFrameLog` header comment; `addressTail`,
  "decision 3", on both platforms). Using the full address needs no firmware
  change. It is a privacy decision (Q2).
- **No wall-clock time comes from the pod.** PROTOCOL.md still marks clock sync
  (`TIME`/`RESYNC`) and the Desk Pod's "time it received the frame" as TBD.
  The app parses `TIME` (`parseTime`) but nothing uses it. HEALTH's
  `clock_offset_ms` is the firmware's internal clock-sync value, reported as
  0 when that clock isn't valid (`BtrxModule.cpp`). **[A]** It is not a pod
  to tablet offset.
- **Gating.** Bit 3 is set when the pod judges its attitude unusable (design
  doc §24.2 suggests |roll| > 10°, |roll rate| > 15°/s or |pitch| > 15°; the
  firmware sets the bit from its own aggregate). Bit 4 clear with port at
  −128 means pod B dropped off the link, not that the source is to starboard
  (`Steer.portBoardSilent`).
- **STEER covers one source only.** It is the strongest single sample. The
  current source is kept unless another is at least 3 dB stronger, and
  ignore-listed or muted sources are left out. Other sources are only in
  **DETECT (0x70)**: up to 7 records every 5 s, each covering a 5 s window,
  with per-record `age_ms`, `rssi_port`/`rssi_stbd`, `hits`, full address,
  `company_id`, `fp_hash`, `dev_class` and `addr_type`, plus window-level
  `window_end_ms`, roll and pitch. **Tonight's two session logs have no DETECT
  lines at all.** Whether DETECT isn't arriving or just isn't logged is Q4.
- **Air time.** On US SHORT_FAST a STEER is 63–71 ms on air. It is dropped,
  not queued, if it is more than 1 s old when the radio is free.

**Measured tonight** (live, non-backlog STEER only; arrival = the app log
timestamp; offsets measured against the smallest arrival − `window_end_ms`):

| Session | Reports | Span | Extra delay, median / p90 / p99 / max | `age_ms` median | `steer_seq` gaps |
| --- | --- | --- | --- | --- | --- |
| 18:50 | 1,522 | 26 min | 75 / 119 / 232 / 689 ms | 81 ms | 3 |
| 19:11 | 318 | 5 min | 54 / 103 / 392 / 1,032 ms | 51 ms | 0 |

The extra delay includes any pod clock drift over the span. The 43 "rx
backlog" frames that arrived when the link opened came in 5–20 s late and
must not be timed by arrival.

Address types tonight across 1,840 live STEER: type 0: 345, type 1: 1,
type 2: 707, type 3: 787. Most readings came from addresses that rotate.

**Drone pose the app has at a given time:**

- **RID track points:** `WaypointTrack.TrackPoint` (lat, lng, ele,
  `timestampMsec`, `receivedAtMsec`) and `DroneMapPoint`.
  `CtDroneSpec.PositionTelemetry.aircraftTrackDeg` is the **ground track**,
  not nose heading. PROTOCOL.md: "Remote ID gives the drone's track. It does
  not give nose heading." **[A]** Points arrive about once a second.
- **DJI stream SEI** (`FfmpegTelemetry`): `headingDeg`, `cameraYawDeg`,
  `gimbalPitchDeg`, lat/lon/alt, `sourceTimestampUs` and `renderLatencyMs`.
  Available only while a stream with SEI is live and bound to the aircraft.
  **[A]** `headingDeg` is the aircraft's nose yaw; this needs checking (Q1).
- **Pod:** roll and pitch only. There is no magnetometer, and gyro data
  (`IMU_SAMPLE`) stays on the pod's internal cable. The pod can't give yaw.

## 2. Data model

One `BtrxReading` per STEER that has a target (bit 0), and later one per
DETECT record:

```
t_ms            reading time, tablet clock (section 4); window centre
pose            lat, lon, alt, nose heading, heading source (SEI | RID track), pose age
rssi_port/stbd  dBm or null (−128); hits_port/stbd; peak
delta_db        port − stbd (only when both patches heard and IMU ok)
source_key      full 6-byte address (in memory only), plus addr_type
group_id        per-source group (section 5)
maker           company_id, dev_class
flags           gated, imu_ok, imu_cal, linked, new
pod_session     changes when window_end_ms goes backward (pod restart)
```

A reading is used for geometry only when: the target was heard, it isn't
gated, the IMU is ok (pod B present), both patches heard it (or one patch with
2 or more hits, the overlay's existing rule), and the pose has a heading
(section 4). The rest are kept for display only.

## 3. Geometry

The two patches are tilted outboard (the design doc's default is 20°,
adjustable). Δ = port − stbd gives which side the source is on and roughly how
far off the fore/aft line, with about 0.12 dB/deg at 20° tilt (design doc
§24.2, an estimate). It **does not give a bearing**: ahead and behind look the
same, and fading of 5–10 dB per reading (design doc §8.7) swamps single
readings. So each reading is a soft half-plane, not a ray.

| Option | What it is | Pros | Cons |
| --- | --- | --- | --- |
| A. Raw wedges | Draw each reading as a translucent half-disc (side, Δ-weighted), fading with age | Simplest; shows the raw data honestly | Clutter; the eye has to find the intersection |
| **B. Per-source score grid (v1)** | A grid of ground cells per source group; each reading adds a log-likelihood score to cells by side, with a weak range term | Shows where the half-planes agree; cheap; replayable; handles ahead/behind on its own as the path turns | Needs tuning; its range term is weak |
| C. Bayesian grid (design doc §8.4b) | Full path-loss model with unknown P0 and n marginalised; adds "heard nothing here" | Principled; gives an ellipse and next leg (§8.5) | More parameters to tune and more compute; needs antenna patterns |
| D. Particle filter (§8.4c) | Particles over x, y, P0, n | Handles a moving subject | Heaviest; v3 at best |

**Recommendation: B for v1**, built so it can grow into C, since both use the
same grid.

- **Grid:** local east/north metres, 10 m cells. The grid grows to cover
  500 m around the path **[A]**. A score grid is kept only for groups with at
  least 5 usable readings.
- **Side term:** for each cell, β = the angle off the drone's fore/aft
  vertical plane, positive to port. Score
  `w × tanh(Δ / 4 dB) × sin(β)`, clipped. This is positive on the side the
  reading points to and negative on the other side. With |Δ| < 2 dB (balanced)
  it instead adds a small positive score near the fore/aft line, ahead and
  behind: balanced readings are crossing lines too.
- **Weight `w`:** grows with hits (more samples, less fading) and drops with
  pose age and in turns (heading rate). Each reading's total is capped so one
  strong reading can't dominate.
- **Range term (weak prior):** strength fits a broad band of distances from the
  drone under log-distance path loss (design doc §8.3: n ≈ 2.5 ± 0.5, P0
  unknown). In v1 it only lowers the score of cells far beyond what the
  strength allows. **[A]** No hard ring: body loss alone is 10–20 dB (§8.7).
- **Decay:** each cell's score is multiplied by 0.5 per half-life (default
  10 min **[A]**). This forgets old readings without smoothing new ones.
- **Output per group:** a heat surface (normalised score), the top cell with
  the area holding the top 50% of the score, the reading count and the last
  heard time.

## 4. Time alignment and pose

- **Reading time.** Estimate the offset `off = min(arrival − window_end_ms)`
  over a sliding 2 min window of live frames, and restart the estimate when
  `window_end_ms` goes backward. Reading time is
  `t = window_end_ms + off − 500 ms` (the window's centre). The minimum
  absorbs the fixed air time and relay delay, and the sliding window tracks
  clock drift. Backlog frames are timed the same way once `off` is known, and
  dropped before then.
- **Error from LoRa and the relay.** Using arrival time alone would add the
  measured 54–75 ms median (p99 0.23–0.39 s, max about 1 s). At 6 m/s that is
  0.3–0.5 m median and about 2.5 m at p99. The 1 s window is itself a smear of
  about 6 m of path. Both are small next to RF geometry (tens of metres,
  §4.5). The `off` estimate removes most of the delay anyway.
- **Pose at reading time.** Interpolate linearly between the RID points either
  side of `t` (§4.5), using RID `timestampMsec`, not `receivedAtMsec`. Reject
  the reading if the gap between points is over 3 s.
- **Heading** matters most: a 10° error moves the half-plane edge about 35 m
  at 200 m. Use SEI `headingDeg` when it is fresh (≤ 1 s, bound to this
  aircraft). Otherwise use the RID ground track only when ground speed is at
  least 3 m/s **[A]**, and drop the reading's geometry in a hover with no SEI.
  **[A]** The pod's fore/aft axis is aligned with the aircraft nose.

## 5. Filtering and source grouping

- **Own aircraft.** Tonight the DJI source was company 0x08AA, `..493D`,
  addr_type 0 (public, stable). Developer Tools gets "Mark as own aircraft",
  which stores the public address plus company ID. That source is left out of
  the layer and can also go on the pod's ignore list (`FILTER_LIST`, static
  identifiers only), so it never wins STEER. A company-ID-only rule (0x08AA)
  stays a separate switch, since it would also hide other DJI aircraft.
  **[A]** The M4TD's in-flight emission, with props spinning, is not
  characterised yet (Ken saw nothing strong with props stopped).
- **Groups.** The key is the full address. The pod's `src_id` with bit 2
  (linked) joins address changes the pod judged to be the same device (rules
  in PROTOCOL.md: same company, kind and first 2 bytes of manufacturer data,
  the old address quiet ≤ 3 s before the new one, within 6 dB, exactly one
  candidate). The app adds the design doc §8.8 continuation rule using DETECT
  `fp_hash` (same company and kind, within 60 s, never heard together) and
  marks the result "probable". Type 3 sources with no company ID (tonight's
  likely watch) can't be linked reliably and show as short separate tracks.
  `src_id` is never a key across pod restarts.

## 6. Rendering

- **Where:** a local overlay on the in-app map: Android `SplitMapPane`
  (StreamsScreen), iPad `RIDTrackMapView`. Per group:
  - a heat surface in the group's palette colour (the same palette as the
    overlay where `src_id` matches);
  - the top-cell marker with maker, kind, last 4 address characters, count
    and age;
  - optionally the last 30 s of raw half-planes as thin wedges, for checking.
- **Decay:** opacity follows the decayed score. Nothing is smoothed on top of
  it.
- **CalTopo:** nothing goes up automatically in v1. A later "Send estimate to
  CalTopo" (polygon of the top 50% area plus marker, per §8.5) would go
  through the existing clue workflow, only on an operator tap (Q8).
- **Stream view:** unchanged.

## 7. Performance (standing rule)

- All of it runs on a background worker: Android a single-thread executor,
  iPad a serial queue or actor. The BTRX model hands each reading over without
  blocking.
- The work is small: about 1 reading per second per source, 10 m cells over a
  1 km square (10,000 cells), so a few hundred microseconds per reading
  **[A]**.
- The worker publishes an immutable snapshot at most once a second. The map
  draws it as a bitmap or polygon overlay. If the worker falls behind it drops
  readings and never queues them.
- The layer starts only when switched on in Developer Tools, and never at app
  start. Stream server, pod link, overlay and live views keep priority.

## 8. Parity and placement

- The same pure logic lives in `BtrxMapLayer.kt` and R2CCore
  `BtrxMapLayer.swift`, with shared test vectors, as with `BtrxSteering`.
- One Developer Tools switch, "BTRX map layer (experimental)", on both
  platforms, plus "Mark as own aircraft" in the BTRX pane. Ledger entry when
  built.

## 9. Validation

1. **Replay tonight's logs** (bench; the pod didn't move; no flight):
   - check the time mapping (offset stability, backlog handling, the 3
     `steer_seq` gaps);
   - check grouping (address-type counts, link rate, how many short type 3
     tracks);
   - check that the own-aircraft filter removes `..493D`.
   - Geometry can't be checked from these logs, because there was no pose.
2. **Synthetic tests:** simulated legs, a source at a known point, log-distance
   RSSI with 6–8 dB fading, the 0.12 dB/deg Δ slope. Pass if the top cell is
   within 30 m after two crossing legs, and no mirror solution remains after a
   turn **[A]**.
3. **Rotation test (bench or hover):** source fixed, rotate the pod 360° in
   30° steps with heading known. Check Δ's sign and sine shape (design doc
   §24.2 sweep), the gating, and the roll sign.
4. **Field test:** a known source with a stable address (static-address beacon
   or a phone app) at a surveyed point. First carry the pod along a path with
   a pose source; then fly lawnmower and crossing legs at 30 m AGL and
   5 m/s. Log the sessions with keyed address hashes (Q2) so they can be
   replayed.

## 10. Open questions

For Ken:

1. **Q1.** Is the pod mounted with fore/aft on the aircraft nose axis? Is SEI
   `headingDeg` the aircraft's yaw? (Check against `djiAttitudeAnglesDeg` on a
   flight log.)
2. **Q2.** Can the layer use the full address in memory, and write a keyed hash
   to the log (with a per-incident key that is never saved) so replays can
   group sources? Today's rule is last 4 only.
3. **Q8.** Local-only for v1, or also an operator-tap CalTopo push?
4. Is a 10 min default decay half-life right?

For BT RX (firmware):

5. **Q3.** Should the Desk Pod stamp its receive time, or should `TIME` sync
   be finished? Not needed for v1 (the offset estimate works), but it would
   remove the guesswork.
6. **Q4.** Should DETECT arrive at the app? None appeared in tonight's logs.
   Can STEER carry the top 2–3 sources, or a 1 s DETECT for the current
   target?
7. **Q5.** Could STEER carry the window's yaw rate (from pod B's gyro), so
   readings taken in turns can be dropped without depending on SEI?
8. **Q6.** What are the measured patch beamwidth and current tilt? They set the
   half-plane width and the dB/deg slope.
9. **Q7.** Does the M4TD emit BLE in flight (props spinning)? Is its address
   stable across power cycles? That decides how strong the own-aircraft
   filter needs to be.
