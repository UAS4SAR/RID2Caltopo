# BTRX field-test plan (draft, November 12, 2026)

Goal (Ken, 8:44 PM PT): develop a technique that lets drone teams detect and
locate sources as quickly as possible in a variety of search environments.
The search scenarios are still to be designed. This plan produces the data to
choose them. The map layer (`MAP-LAYER-design.md` r3) must not assume any one
flying style, so every technique below is logged the same way and replayed
through the same model.

**[A]** marks an assumption or a starting value to be tuned. Nothing here
replaces the team's own flight rules (section 6).

## Pod facts this plan relies on

- **Patches** (design doc §40): two patches at azimuth −45° (port) and +45°
  (starboard), 45° below horizontal, 152 mm apart. Each board scans on its
  own, so port and starboard samples come from different packets.
  Beamwidth is not measured; HPBW ≈ 90° is an estimate.
- **Noise:** about 5–6 dB per sample. The noise on Δ (port − stbd, positive =
  left) is about 5.5 · √(1/N_port + 1/N_stbd) dB, so:
  - about 15 samples per patch give 2 dB;
  - about 40 per patch per hold are needed to see a 20° turn-and-compare
    change at 3σ (about 5 dB at an estimated 0.25 dB/deg).
- **Reports:** STEER is about 1 per second (1 s windows) and covers the
  **single strongest** source only. Build 0x0103 (btrx `684cfa5`, not
  flashed yet) adds up to 2 more sources per STEER.
  DETECT lists up to 7 sources every 5 s.
- **Link:** LoRa on US SHORT_FAST. A STEER is about 63–71 ms on air.
- **Sample rates seen on the bench (Nov 12):** the DJI source gave about 40
  samples per patch per second; phone-class sources often about 5. So
  dwell times are set for the slow case.
- **Heading:** the DJI camera yaw, which on the M4TD is the nose yaw.
  True north = raw − 90; the outdoor check is in B3.

## 0. Baselines (do first, on the ground or in hover)

**B1. Pod rotation: Δ versus azimuth and board offset c0.**
- **Setup:**
  - Pod on a turntable or tripod head, level, about 1.5 m up **[A]**.
  - Source (a fixed-address beacon, plus a phone) at 20 m and 50 m, same
    height, open field.
- **Run:**
  - Rotate in 15° steps, holding each step until about 40 samples per patch
    (about 10 s for a phone-class source).
  - Do two full turns, one each direction.
  - Repeat with the pod tipped 45° nose-down **[A]**, so the source sits near
    boresight elevation.
- **Output:**
  - Δ(φ) table, the slope S near 0°, c0 (the mean Δ with the source dead
    ahead), and the noise σ per sample.
  - Check the signs: a source on the left must give Δ > 0, and roll must be
    positive starboard side down.

**B2. Own-aircraft emissions.**
- **M4TD and Mini 4 Pro**, each in four states: powered and idle; motors on
  (on the ground, props off or secured per the team's rules); hover at
  10 m; RC on alone with the aircraft off.
- **Record:** addresses, address types, company IDs, RSSI and hits per
  patch.
- **Questions to answer:**
  - Is `..493D` the aircraft or the RC Plus 2?
  - Does the address stay the same across power cycles?
  - How strong is our own drone with motors on? That sets the self-filter
    and the noise floor.
- **Then** put the stable own-aircraft address on the pod's ignore list (full
  address, not company ID) and repeat one hover to confirm.

**B3. DJI yaw north offset (outdoors).**
- Away from metal, point the nose at a far landmark of known true bearing
  (map or survey), then 90°, 180° and 270° off it.
- Expected: raw − 90 = true bearing. Record the residual. Indoors the "about
  north" start read 308.8° true (Ken: indoor metal).

**B4. LoRa link at distance.**
- Drone Pod on the aircraft, Desk Pod at the launch point.
- Fly out at 30 m and 60 m AGL **[A]** to the planned test distances (500 m
  and 1 km **[A]**, within visual line of sight).
- **Record:**
  - Desk Pod LoRa RSSI and SNR (in the app's frame log);
  - `steer_seq` gaps, which mean losses after the pod handed the frame off;
  - arrival − `window_end_ms` delay, and backlog;
  - the bearing layer's per-stage lag (`BBLAG`/`BBRND` lines and
    `lag_report.py` CSV, `BEARING-LAYER-spec.md` r3 section 3.5), to find
    the maximum normal delay and any slow path before lowering the 15 s
    budget.

## 1. Techniques against a known source at a surveyed point

Use the same source, point and logging for each technique. Run techniques
in a rotated order across trials so learning doesn't bias the results.

**(a) Segment-edge hover and slow yaw (primary quick-detect; Ken's
preference).**
- **Hover point:** just outside one edge of the search segment, facing in.
- **Altitude options:** 15, 30 and 60 m AGL **[A]**. The boresights hit the
  ground at a horizontal distance about equal to the altitude, and the
  estimated beam covers roughly from the horizon down to nadir.
- **Quick look:** one 360° yaw at **10°/s (36 s)**. Each 20° sector gets
  about 2 STEER windows, enough to see that something is there (the strongest
  source) and its side. Phone-class sources get only about 10 samples per
  patch per sector.
- **Bearing pass:** one 360° yaw at **6°/s (60 s)**. That is about 3.3 s per
  20° sector: about 15 samples per patch, or 2 dB, for a phone-class source.
  Turn the other way for a second pass. Each 1 s window then smears only
  about 6° of heading.
- **Why not faster:** above about 20°/s a 1 s window smears more than one
  20° sector, and the map layer's yaw-rate gate (10°/s **[A]**) drops those
  readings. So the quick look runs at its gate, and the bearing pass under it.
- **Where Δ crosses zero:** stop yaw there and do (e).
- **Output:** the bearing layer (`BEARING-LAYER-spec.md` r2, continuous, no
  button) paints the hover spot's wedges, which darken along the bearing as
  the yaw passes add up. Repeat from a second edge point: the two spots'
  wedges darken where they cross. Record the layer's estimate and ellipse.
- **Multiple sources:** until build 0x0103 is flashed, STEER shows
  only the strongest, so steering-mute it (the pod's STEER_MUTE, not the map
  panel's hide) from the
  BTRX pane and sweep again to find the next. DETECT's 5 s windows (30° of
  smear at 6°/s) give a presence list only.

**(b) Straight transects.**
- Legs at 5 m/s **[A]** across the segment at 30 m AGL, nose along the leg,
  spaced at about the detection range measured in section 4.
- The sign of Δ gives the side; the peak level gives the closest point of
  approach.
- Fly the second leg at 90° to the first.

**(c) Orbit.**
- An orbit of radius 50–100 m **[A]** around the suspected area, nose
  tangent (Δ points in or out) or nose in (Δ ≈ 0 means the source lies on the
  line to the centre).
- Compare both nose styles.

**(d) Lawnmower grid.**
- The existing systematic pattern, with line spacing set from the measured
  detection range.
- This is the coverage baseline for comparing (a)–(c).

**(e) Turn-and-compare refinement (after detection).**
- Hover. Hold yaw until about 40 samples per patch, then yaw 20° to port and
  hold again. If needed, hold a third time near the predicted zero crossing.
- About 1 s per hold for a fast advertiser, about 8 s for a phone. The board
  offset c0 and the source's transmit power cancel in the difference.
- Then move closer along the bearing and repeat. Note the altitude change
  near overhead.

## 2. Sources

Use team-owned devices only. Record each device's full address on the data
sheet (our own devices). The app log keeps only the last 4 characters.

| Source | Address type (expected [A]) | Notes |
| --- | --- | --- |
| Fixed-address BLE beacon | public or static | Control source; known transmit power if settable |
| Phone (Android, iPhone) | rotating (types 2/3) | Screen off and locked as well as in use; advertising rate varies |
| Smartwatch | rotating, often no maker | Tonight's likely watch showed as an unknown type 3 |
| Hearing aid (if available) | often static **[A]** | High-value SAR target |
| AirTag / Tile | AirTag rotates about every 24 h away from its owner **[BT]** | Separated mode as well as near-owner mode |

Placement for each: in the open on the ground; **on a person** (pocket, lying
down); **in a pack**. Body and pack can cost 10–20 dB (design doc §8.7).

## 3. Environments

1. Open field (the baseline; do B1–B4 here).
2. Light vegetation (scattered trees and brush).
3. Heavy canopy (conifer forest).
4. Terrain: a source in a drainage below the aircraft, and one behind a ridge
   or rock face.

Change one thing at a time: the same source, placement and altitude across
environments before changing anything else.

## 4. Metrics (per trial)

- **Time to first detection:** from the start of the technique to the first
  STEER or DETECT with the source's address.
- **Time to locate within X m:** X = 50, 25 and 10 m **[A]**, using the map
  layer's top cell (replayed afterward) or the pilot's estimate if the layer
  is off.
- **Localization error:** the final estimate against the surveyed point, in
  metres, with the uncertainty area.
- **Maximum detection range:** the slant and ground distance at first and
  last detection, per device class, placement, altitude and environment.
- **Bearing error:** for (a) and (e), the estimated bearing against the true
  bearing from the drone position.
- **Cost:** flight time, battery used, and the number of yaw holds or legs.
- **Time to first estimate:** from first report of the source to the bearing
  layer's first valid estimate and ellipse, and the miss distance then.
  Ken: 10 min (the default fade time) should be plenty; if a technique needs
  longer, the design needs revisiting (`BEARING-LAYER-spec.md` r3
  section 2.4). Record the fade time and shape in use.

## 5. Logging (enable before every flight)

- **App (Android A5; the iPad as a second receiver if available):**
  - Developer Tools > **LogLevel** at Debug, so Btrx frame lines (STEER,
    DETECT, HEALTH, pairing) are kept.
  - Developer Tools > **Video telemetry capture: On**. This writes research
    logs of the DJI SEI, which may contain aircraft positions.
  - Stream recording on (it keeps the per-frame SEI: yaw, pitch, position).
- **Clocks:** network time on the tablets; note the tablet's time against a
  GPS clock at the start. The pod's clock maps to the tablet clock through
  the arrival offset now, and through TIME_PING later.
- **After each flight, pull with adb (read-only):**
  - the session `Log_*.txt` (Archive Dir, or app storage if the card copy
    lags);
  - the stream recording;
  - logcat.

  File them under `~/btrx-build-logs/field-YYYYMMDD-HHMM-*`.
- **Ground truth:**
  - source position by GNSS (RTK if available; otherwise average 60 s **[A]**);
  - the hover points (a) and transect endpoints (b);
  - photos of each source placement.
- **Trial markers:** speak or write the start and end times of each
  technique on the data sheet to the second.

## 6. Roles, safety, data sheet

**Roles:**
- **Pilot in command**: flies and calls technique start and end.
- **Visual observer(s)**: as the team's rules require.
- **Data recorder**: data sheet, times, app checks.
- **Source handler**: places and retrieves sources, never in the flight path
  during a trial.

**Safety:**
- Fly under Part 107 (or the team's authorization), within visual line of
  sight, with the team's normal preflight, airspace and landowner checks.
- No flight over people not involved in the test.
- Stop on lost link, low battery or any doubt.
- Nothing in this plan overrides those rules.

**Data sheet (one row per trial):**

| Field | Example |
| --- | --- |
| Date, trial # | 2026-11-__, T03 |
| Environment, site | open field, ____ |
| Source (device, full address, address type, placement) | beacon AA:BB:..., public, ground |
| Source position (lat, lon, method) | ____, RTK |
| Aircraft, pod build, pod firmware | M4TD, v0.27, 0x0102 |
| Technique and parameters | (a) edge hover, 30 m AGL, 6°/s, 2 passes |
| Hover or leg coordinates | ____ |
| Start / end time (tablet clock, to 1 s) | 10:42:05 / 10:44:10 |
| First detection time, range | 10:42:31, 140 m |
| Estimate (lat, lon, ± m), time reached | ____ |
| Wind, temperature, notes | 5 mph W, 12 °C |

**Order of work:**
1. Day 1: B1–B4 in the open field, then technique (a) and (e) against the
   beacon and a phone.
2. Later days: add techniques (b)–(d), then the other sources, then the other
   environments.
