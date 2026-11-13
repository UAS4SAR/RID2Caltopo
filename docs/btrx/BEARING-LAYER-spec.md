# BTRX bearing layer: spec (milestone 1 of the map layer, draft, November 12, 2026)

Status: spec only. **Map-layer code is on hold.** This is the **first**
map-layer milestone, ahead of the grid in `MAP-LAYER-design.md` (r3)
section 5. It uses the same reading data model (r3 section 4), heading rule
(r3 section 3) and gating, and the same worker, parity and Developer Tools
placement (r3 section 10). **Nothing goes to CalTopo.** **[A]** marks an
assumption or a starting value; **[BT]** marks a BT RX statement not checked
in code.

Ken (8:54 PM and 9:00 PM PT):

> "If we can get to the point where we can draw rough bearing lines on the map
> from one hover and yaw location, then proceed to a triangulation point on an
> adjacent side of the search segment and perform another hover and yaw, we
> might be able to see where the vectors cross."

> "if we hover in a location and turn back and forth a few times around a
> target signal, we should be able to narrow its vector down... very faint and
> spread out initially... enough samples through several sweeps... a vector
> that is more definitive. Maybe we include drone movement in those sweeps."

Field procedure: `FIELD-TEST-plan.md` technique (a) (segment-edge hover and
yaw) and technique (e) (turn-and-compare).

```
          segment edge A                         S1, S2 = hover stations
   S1 (o)----------------------------+           ===  bearing ray, centre line
       \\  ===  ±1σ wedge (faint→solid)|          \\ // = ±1σ wedge edges
        \\  ===                       |           ## = overlap shaded
         \\    ===                    |           X  = best fit, ( ) = 1σ ellipse
          \\       ===   ##(X)##      |
           \\          ===##### =====|===== (o) S2
            \\           ###   ===   |         segment edge B
             (back ray, dashed only if front/back is unresolved)
```

## 1. Hover station

**When a station starts:**
- **Automatic:** RID ground speed below 1.5 m/s **[A]** and the drone within
  a 15 m radius **[A]** for at least 10 s.
- **Manual:** "Start station" in the layer's Developer Tools controls.

The station ends when the drone leaves the radius or the user ends it. The
station's position is the mean position over its readings. It records:
- altitude and start/end times;
- which yaw bins have data, drawn as the coverage arc on the map.

**Readings used:**
- STEER readings with a target, timed and posed as in r3 section 6.
- Heading is the **aircraft yaw, true north**: the DJI camera yaw minus 90°,
  normalized to 0–360° (`b5c048ea`). Android has
  `DjiCameraOrientation.aircraftYawTrueNorthDeg`; the iPad has
  `OperationalClueGeometry.djiAircraftYawTrueNorthDegrees`. On the M4TD the
  camera yaw is the nose yaw (Ken).
- **Gates** (r3 section 5):
  - yaw rate over the window ≤ 10°/s **[A]**;
  - yaw change within the window ≤ 5° **[A]**;
  - bit 4 set, bit 3 clear.
  - No DJI yaw means the reading doesn't join a station.

**Bins per source** (key: full address in memory, shown as a short ID, r3
section 7). Each bin is 5° of yaw **[A]** (72 bins), or 10° **[A]** if the
sample counts are low. A bin holds, per patch:
- hits;
- the hit-weighted sum of mean RSSI, and its sum of squares;
- the count of one-patch windows.

Then Δ̄_b = mean_port − mean_stbd, and
`σ_b² = 5.5² (1/N_port,b + 1/N_stbd,b)`, with 5.5 dB per sample **[BT]**. The
bin's effective N is capped (section 2).

**Model.** φ is the source's azimuth from the nose, positive to starboard.
φ = β − ψ, where β is the true bearing to the source and ψ is the aircraft
yaw. The pattern table gives Δ_tab(φ, ε) and the summed gain G_tab(φ, ε):
- **v1:** HPBW ≈ 90° patterns at ±45° azimuth, 45° down (r3 section 1), with
  ε set to the patch depression **[A]**.
- **Later:** the measured curve from baseline B1.

Near the nose, Δ ≈ c0 − S·φ with S ≈ 0.25 dB/deg **[A]**: positive Δ means
the source is to the left. As yaw ψ increases (a clockwise turn), φ falls and
Δ rises.

**Fit (one dimension, cheap).** For each candidate β in 1° steps (0–359),
compute

`J(β) = Σ_b (Δ̄_b − c0 − Δ_tab(β − ψ_b))² / (σ_b² + σ_c0²)`,

plus a weak level term that compares the summed level to
G_tab(β − ψ_b) + P0, with P0 a free offset per β **[A]**. The estimate β̂ is
the minimum of J, refined by a parabola through the three lowest points.

**Front or back.** Δ crosses zero twice: once with the source on the nose,
once on the tail.
- **Nose:** dΔ/dψ > 0, and the summed level is higher (both main lobes).
- **Tail:** dΔ/dψ < 0, and the level is lower (back lobes).

The slope sign alone decides it when a sweep crosses the zero. The level
confirms it. The back lobes are estimates until B1 **[A]**. If only one side
of the curve has data, both rays are shown, the back ray dashed and marked
"unresolved".

**c0, S and the crossing.** The board offset c0 shifts the zero crossing by
c0/S. A wrong slope S changes the width of the curve, **not** where the
crossing is. So c0 (from B1) matters and S matters little.

## 2. Uncertainty and how sweeps shrink it

Near the crossing, the fit's information is Σ_b S² / σ_b², which gives

`σ_β,stat = 5.5 · √(2 / N) / S` (degrees),

where N is the number of effective samples per patch within the roughly
linear region |φ| ≲ 45°.

Fixed error terms that more sweeps don't remove:
- σ_c0 / S, from c0's error;
- σ_yaw, the yaw bias after the B3 north check.

`σ_β = √(σ_β,stat² + (σ_c0/S)² + σ_yaw²)`

**One sweep** is one pass across ±45° around the bearing at 6°/s **[A]**:
15 s and about 15 STEER windows. Each extra back-and-forth pass adds one
sweep's worth of N, so σ_β,stat falls as 1/√(sweeps).

**Independence caveat [A].** In a still hover, the multipath at a given yaw
barely changes, so samples within one window, and repeat visits to the same
bin, are correlated. Two bounds:
- **Conservative:** 1 effective sample per patch per STEER window.
- **Optimistic:** 5 per window, all of a phone's hits independent.

Normal hover drift of a metre or more (several wavelengths at 12.5 cm) helps
decorrelate. That is one reason to add drone movement (section 4).

**Expected σ_β** (S = 0.25 dB/deg, σ_c0 = 0.5 dB, so 2°, σ_yaw = 2°, all
**[A]**):

| Sweeps (±45° at 6°/s) | Time | Conservative stat / total | Optimistic stat / total |
| --- | --- | --- | --- |
| 1 | 15 s | 8.0° / **8.5°** | 3.6° / **4.6°** |
| 2 | 30 s | 5.7° / **6.3°** | 2.5° / **3.8°** |
| 4 | 1 min | 4.0° / **4.9°** | 1.8° / **3.4°** |
| 8 | 2 min | 2.8° / **4.0°** | 1.3° / **3.1°** |
| 16 | 4 min | 2.0° / **3.5°** | 0.9° / **3.0°** |

- **Fast advertisers** (DJI-class, about 40 hits per patch per second) sit at
  the optimistic column or better within a sweep or two.
- **Error floor:** about 3° until c0 and the yaw bias are measured better.
- **Distance across the ray:** r · σ_β. At 200 m with σ = 4.9°, that's about
  ±17 m; at 300 m, about ±26 m.
- **Before the back-and-forth:** the technique (a) 360° pass at 6°/s gives a
  coarse β from the whole curve. Then sweep ±45° around it so every pass
  crosses the zero. A sweep that doesn't cross the zero only refines the side,
  not the bearing.

## 3. Rendering (in-app map, local only)

Android `SplitMapPane`, iPad `RIDTrackMapView`. Per source colour (same
palette as r3 section 9).

- **Station marker:** a circle at the station, with an arc showing which yaw
  bins have data.
- **Bearing ray:** a centre line from the station along β̂, out to r_max:
  - 400 m **[A]**, or the level-based upper range if that is shorter.
  - Inner wedge ±1σ, outer wedge ±2σ at half the opacity.
- **Opacity grows with confidence:**
  `α = clamp(0.10 + 0.60 · (1 − σ_β / 30°), 0.10, 0.70)` **[A]**.
  - The first readings draw a wide, faint wedge.
  - The wedge narrows and darkens live as sweeps accumulate (redrawn at most
    once a second).
  - No smoothing: each redraw is the current fit.
- **Back ray:** dashed, only while front and back are unresolved.
- **Label:** class · short ID · sweeps · ±σ, for example
  `phone · Q3x9Kd · 4 sw · ±5°`.
- **Per-reading wedges:** the faint per-reading wedges from r3 section 8
  stay, for the last 30 s.
- **Station history:** finished stations keep their final ray and fade with
  the 10 min half-life.

## 4. Several stations: crossing the rays

**Fit.** Work in local east/north metres. Station i is at p_i, with bearing
β_i and σ_i. Its unit normal is n_i = (cos β_i, −sin β_i). Each ray is the
line n_i · (x − p_i) = 0. The weight is w_i = 1 / (r_i σ_i)², where r_i is
the range from the station to the current estimate (iterate 2–3 times;
start with r_i = r_max/2):

`A = Σ w_i n_i n_iᵀ`, `x̂ = A⁻¹ Σ w_i n_i n_iᵀ p_i`, `Cov(x̂) = A⁻¹`.

- **Error ellipse:** the eigenvectors and eigenvalues of A⁻¹ give the 1σ
  ellipse (39% containment in 2D) and the 2σ ellipse (86%).
- **Rejected crossings:** behind a station, or beyond r_max.
- **More than two stations:** the same sums, with a χ² check to flag a bad
  station.

**Drawn:**
- the overlap of the ±1σ wedges, shaded as a polygon intersection;
- the best-fit point (X) and the 1σ and 2σ ellipses.

**Crossing angle (dilution).** Two rays crossing at angle γ, each with
cross-ray error σ_d = r·σ_β, give ellipse semi-axes
σ_d / (√2·sin(γ/2)) along the bisector and σ_d / (√2·cos(γ/2)) across it:

| γ | 90° | 60° | 45° | 30° | 20° | 15° |
| --- | --- | --- | --- | --- | --- | --- |
| long axis / σ_d | 1.00 | 1.41 | 1.85 | 2.73 | 4.07 | 5.42 |
| short axis / σ_d | 1.00 | 0.82 | 0.77 | 0.73 | 0.72 | 0.71 |

So aim for 60–120° between the rays. Below 30°, the point stretches along the
rays and the overlap mostly shows the line, not the spot. A station on the
**adjacent** side of the segment, as Ken describes, gives about 90°.

## 5. Moving sweeps (drone movement)

These use the same reading records. Two ways readings taken while moving
count:

1. **Per-reading wedge from its own position:** φ̂ = −(Δ − c0)/S, clipped to
   ±60°, with σ_φ = σ_Δ / S. That's about 30° or more for one window of a
   phone. This is exactly the r3 section 8 faint wedge.
2. **Sliding-window bearing:** if the path within the window is short
   compared with the range, treat the window as a station at the path's
   centroid and fit it as in section 1. Short means translation under 10% of
   the estimated range **[A]**, for example 20 m at 200 m. Gentle movement
   while yawing back and forth (a slow crab along the segment edge) also
   decorrelates the multipath (section 2).

**On to the grid.** Summing the log-likelihoods of the per-reading wedges
over cells **is** the grid model of r3 section 5. Each wedge adds its Δ
term, its level term and censoring along the ray. Stations are a fast special
case for a fixed position, and the grid is the general case for any flying
style. Milestone 2 renders the grid under the same rays, from the same
readings, and checks that the two agree.

## 6. Pilot hints (optional, Developer Tools)

- **"Keep sweeping"** while σ_β > 5° **[A]** or the zero isn't bracketed;
  **"Bearing ready"** when σ_β ≤ 5° and front or back is resolved.
- **Next station:** with a ray from S1 and a range estimate r̂ from the level
  (shown as an uncertain arc), a 90° crossing puts S2 at
  source + r̂ · (perpendicular). That is about r̂·√2 from S1, 45° off the ray.
  - Shown as a short arc of candidate points.
  - If the segment boundary is available in the app, the candidates snap to
    the adjacent edge. That only reads the boundary **[A]**.
- **Sweep counter** and the yaw arc still needed, for example "sweep 3 of 4,
  bins 280–010° need data".

## 7. Performance, parity, placement

- **Worker:** the r3 section 10 background worker (an Android single-thread
  executor; an iPad serial queue or actor).
  - It drops readings rather than queueing them, and publishes at most once a
    second.
  - Per reading: O(1) bin update.
  - Per source fit: 360 candidates × up to 72 bins, about 26k operations, at
    no more than 1 Hz.
  - Crossing the rays costs almost nothing.
- **Never on the UI thread.** The stream server, pod link, overlay and live
  views keep priority.
- **Placement:** Developer Tools only, off by default, and never started at
  launch.
- **Parity:** `BtrxBearingLayer.kt` and `BtrxBearingLayer.swift` share the
  same constants and test vectors. Both use the shared-C yaw.
- No CalTopo writes.

## 8. Validation

**Synthetic tests** (shared vectors on both platforms):
1. Generate readings from the pattern table with known β, c0 and N (5.5 dB
   Gaussian noise), and run 1,000 Monte Carlo trials. Pass when:
   - bias is under 1°;
   - the observed σ is within 20% of section 2 (independent case);
   - front or back is right 99% of the time when the zero is bracketed.
2. Wrap across 0/360° and ±180° (the aircraft-yaw cases from the yaw test).
3. The gates drop windows over 10°/s. One-patch windows count only with
   bit 4 set.
4. Two stations at 90°, 45° and 20°: 1σ and 2σ ellipse containment close to
   39% and 86%; the long axis follows the section 4 table.
5. Correlated noise (an AR(1) process per bin) gives a σ above the formula.
   That documents the conservative column.
6. Moving sweep: per-reading wedges summed on the grid give a peak that
   agrees with the station rays.

**Field** (`FIELD-TEST-plan.md`):
1. **B1:** measure Δ-versus-azimuth, S and c0, and replace the v1 pattern
   table.
2. **B3:** check the north offset, which sets σ_yaw.
3. **Technique (a):** a 360° pass at 6°/s, then technique (e) back-and-forth
   ±45° for 1, 2, 4 and 8 sweeps against the surveyed source. Record σ_β and
   the true bearing error at each count, and see whether results follow the
   conservative or the optimistic column.
4. A second station on the adjacent edge (about 90°), then one at about 30°.
   Measure the miss distance of the point and the ellipse size.
5. Repeat while crabbing slowly along the edge (section 5).
6. Replay the logs offline through both platforms' code paths and check they
   match.

## 9. Open questions

1. Will crews tolerate 1–2 minutes per station? The table says 4 sweeps
   (1 min) gives about 5° conservatively.
2. Should a station start automatically, by button, or both?
3. Should the segment boundary be read from the connected map for
   next-station hints (read only)?
4. Firmware option (r3 section 13): the top 2–3 sources in STEER would let
   one station build rays for several sources at once. Today the strongest
   source hides the others until it is muted.
