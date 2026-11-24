# BTRX bearing layer: spec r3, continuous (milestone 1 of the map layer, draft, November 13, 2026)

Status: **implemented** on branch `feature/btrx-integration` (November 13, 2026:
`e23986fd` C library, `9970eebe` Android, `53593f83` iPad; not pushed, not
flashed or bench-tested). Section 12 lists where the code differs from this
text. r2 replaces the r1
hover-station design (`8130f673`). There is **no station, no "Start station"
button and no enforced hover or pan.** While the layer is switched on, every
STEER report is matched to where the drone was and where its nose pointed at
that report's time, and it paints a faint wedge on the map. Areas where many
different reports agree get darker. This folds the former milestone 2 (the
grid of `MAP-LAYER-design.md` r3 section 5) into milestone 1.

r3 (November 13, morning) folds in Ken's answers to the five r2 open
questions: a **10 min fade, linear by default** (section 2.4; Ken's 9:18 AM
addition makes the total time and the fade shape user settings), a **tap-to-mute panel**
(section 5.2), **15 s of pose history with lag instrumentation** (sections
3.3–3.5), **one fade rate for all sources** (section 2.9), **top 2–3 sources
per STEER** (section 4.1; the frame is now defined by BT RX in btrx
`684cfa5`, build 0x0103) and a **latest-ray outline** (section 5.1).

The core is a **portable C library** (C11, stdlib, pthreads only), shared by
Android and the iPad, with thin platform boundary layers for inputs and
outputs (sections 6–8). It keeps r3's data model (section 4), heading rule
(section 3), gating, Developer Tools placement and priority rules.
**Nothing goes to CalTopo**: subjects are marked through the existing video
clue capture. **[A]** marks an assumption or a starting value; **[BT]** marks
a BT RX statement not checked in code.

Ken (November 13):

> "Can we have the measurement and display be on-going as long as the feature
> is enabled? ... As reports from the pod come in, they are synched to where
> the drone was previously located and pointing at that time, so map updates
> are naturally delayed. ... this will paint the map with colored wedges that
> start out faint, but darken over time as more/different reports roll in."

> "I would like most of this functionality to be implemented as a
> well-defined/portable module/library (maybe C, stdlib, pthreads), so most
> of the code is common between Android and iOS, with small boundary layer
> inputs and outputs that are platform specific."

```
   flying past                    hovering and yawing at H
   o----o----o----o---->               (H)
    \    \    \    \                  / | \       each report: one faint wedge
     \    \    \    \               /   |   \     from where the drone was,
      \    \    \    \  ....      /     |     \   in the nose direction
       \    \   #####  .....    /       |       \ at that time
        \    \##(X)##........ /         |
         \   #######.......  wedges overlap -> darker; (X) best estimate,
              (  1σ ellipse  )            shown only once one exists
```

## 1. Facts the model uses

- **STEER (0x73), about 1 Hz** on LoRa ShortFast (SF7, 250 kHz). One report
  per 1 s window. Today it carries the **single strongest** source. Ken
  asked for the top 2–3 (r3 section 13 option A, "Yes please"). BT RX
  defined the frame (btrx `684cfa5`, branch `steering-report`, draft PR #18,
  build 0x0103, **not flashed yet**): the first 27 bytes are unchanged, then
  up to 2 extra 6-byte records (section 4.1). The library takes up to 3
  sources per report; 27-byte frames from older firmware stay supported.
- **Δ = rssi_port − rssi_stbd** (mean dB per patch). Positive Δ means the
  source is to the **left (port)**. −128 means that patch did not hear it.
- **Roll** is positive with the starboard side down; **pitch** is positive
  nose up. Both come from the pod at **send time** (r3 section 2), so they are
  used for gating only in v1.
- **window_end_ms** is pod A's `millis()` since power-up. TIME_PING (0x30) and
  TIME_PONG (0x31) give a min-RTT offset to the tablet clock (section 3.2).
- **Patches as built (v0.27):** boresights at azimuth ±45°, tilted 45° down,
  152 mm apart. **HPBW ≈ 90° is an estimate**, not measured. Expect about
  5–6 dB of noise per sample **[BT]**; this doc uses σ_s = 5.5 dB.
- **Heading:** the pod front lines up with the camera, and on the M4TD the
  camera yaw is the nose yaw (Ken). DJI type-245 SEI tag 4 bytes 3–6, degrees
  × 1e7: **true north = raw − 90**, normalized
  (`R2CDJIAircraftYawTrueNorthDegrees` in `native/R2CDJICameraTelemetry.h`).
  Geometry never uses the RID ground track.
- **Sources:** keyed by the full address in memory only; shown as a **short
  base64 ID** plus a **device class** (phone / watch / hearing aid / tag /
  unknown) when known (r3 section 7).
- **Fade:** total fade time N = 10 min by default and linear by default
  (Ken): a report counts in full when new, half at 5 min and not at all at
  10 min. N and the fade shape are user settings (section 2.4). All sources
  fade at the same rate.

## 2. Continuous model

### 2.1 Per-report wedge

For each accepted report, the library has the drone pose at the report time
(section 3): position, true-north yaw ψ, and height above ground h. For every
grid cell within the footprint radius r_max = 400 m **[A]** of the drone:

1. Horizontal range r and true bearing β from the drone to the cell centre.
   The source is assumed on the ground, so its depression is
   ε = atan2(h, r).
2. Azimuth off the nose φ = wrap(β − ψ), positive to starboard.
3. Pattern look-up: predicted Δ_pred(φ, ε) and summed gain G(φ, ε). The v1
   table is built at create time from the as-built geometry: each patch
   G_i = −12 (θ_i / HPBW)² dB, floored at −20 dB **[A]**, where θ_i is the
   angle between the cell direction and patch i's boresight
   (body frame x forward, y starboard, z down; boresights
   (0.5, ∓0.5, 0.707)). Table: 1° in azimuth × 2° in depression. It is
   replaced by the measured curve from baseline B1 (`FIELD-TEST-plan.md`)
   through the config, with no code change.
4. Residual ρ = Δ_obs − c0 − Δ_pred, where c0 is the board offset (0 until
   B1 measures it, σ_c0 = 0.5 dB **[A]**).
5. Variance σ² = σ_s² (1/N_port + 1/N_stbd) + σ_c0² + σ_m², with a pattern
   model error σ_m = 2 dB **[A]**.
6. Robust log-likelihood (Student-t, ν = 4 **[A]**):
   ℓ = −((ν + 1)/2) · ln(1 + ρ² / (ν σ²)). ℓ = 0 where the cell agrees
   exactly, and is negative elsewhere. It is clamped below at ln p_far
   (section 2.2).
7. **One patch heard** (the other is −128), only with bit 4 set and at least
   2 hits on the heard patch: the silent side is censored below the scan floor
   (−100 dBm **[A]**), ℓ = ln Φ((floor − rssi_heard + Δ_pred') / σ), where
   Δ_pred' is the predicted heard-minus-silent gain. Otherwise the report is
   not used.

This is the r3 section 5 likelihood. The "wedge" is the set of cells with ℓ
near 0: a sector out from the drone, on the side Δ says, narrow where Δ
changes fast with φ (near the nose and the tail) and wide abeam.

### 2.2 Accumulation per source

Each source accumulates on 10 m cells in a north-up local east/north tangent
plane anchored at the first pose after the layer is switched on. Storage is
in 16 × 16-cell tiles keyed by world tile position (section 2.4), so there
is no fixed grid to shift. The rendered and estimated **view** is 192 × 192
cells (1.92 km square) **[A]**, re-centred by whole tiles when the drone comes
within 300 m of its edge. Two quantities per cell:

- **Evidence E** (for the estimate). Each report adds κ_r · ℓ_r(cell) inside
  its footprint. Cells outside the footprint get the constant ln p_far
  (p_far = 0.05 **[A]**: a source heard at all is probably within detection
  range). That constant is kept as one per-source scalar, so a report touches
  only its footprint (about 5,000 cells), and cells with no tile yet have the
  correct "far from every report" value. Stored per cell as
  E′ = κ_r · (ℓ_r − ln p_far) ≥ 0, so E = E′ + the far scalar.
- **Support S** (for the display). Each report adds q_r · exp(ℓ_r(cell))
  inside its footprint: up to q_r in its wedge, near 0 outside it. Overlapping
  wedges from different reports add up. This is the "faint wedges that darken"
  quantity.

Per-report weights:

- **Information** q_r = min(1, (2 dB / σ_Δ)²) **[A]**, with
  σ_Δ = σ_s √(1/N_port + 1/N_stbd). A phone window with 5 hits per patch
  (σ_Δ ≈ 3.5 dB) counts 0.33; a DJI-class window with 40 per patch counts 1.
- **Tempering** κ_r = 0.5 **[A]**, because hits within a window are
  correlated (r1 section 2, kept below in section 2.6).
- **Diversity discount [A].** A report taken within 5 m and 5° of yaw of
  k earlier reports of the same source in the last 30 s has both weights
  divided by (1 + k), capped at k = 9. Holding still at one nose direction
  therefore does not keep darkening the same broad wedge; yawing, moving or
  both bring new geometry and full weight.

### 2.3 How single reports stay faint

Display opacity for a cell is a linear ramp,
α = α_max · min(1, S / S_sat), with α_max = 0.7 and S_sat = 8 **[A]**. One
full-weight report gives at most 1/8 of α_max (about 0.09), a phone report
about 0.03. A cell needs about 8 full reports from different geometry that
agree to reach full opacity. One noisy report never paints a dark patch, and
a dark patch always means many reports agree.

The ramp is linear (r2 used 1 − exp(−S/S0)) so that the fade in section 2.4
shows up one-for-one in opacity: below saturation, with the default linear
shape, a patch whose reports are all 5 min old is 50% as opaque as when they
were new (within the bucket tolerance of section 2.4). A saturated
patch (S > S_sat) stays at α_max until it fades below S_sat.

### 2.4 Fade: total time N and shape p (user settings)

Ken:

> "N=10 minutes should be plenty of time to detect and locate a source - if
> it isn't our design needs to be updated. Within that N minutes though, an
> original source should be 50% as opaque at 50% * N minutes."

> (9:18 AM PT) "I think the user should be able to configure the 10 minute
> total fade value and we might want to have an option for controlling the
> rate of fade from linear to exponential, where the fade accelerates towards
> the end of that period."

**Rule.** A report of age a = now − t_r counts with weight

`w(a) = max(0, 1 − (a/N)^p)`

- **N, total fade time:** user setting, default **10 min**, range **1–30 min**
  (Ken: "1-30 minutes should be plenty"). At age N a report is gone.
- **p, fade shape:** user setting, default **p = 1 (linear)**: 50% at N/2,
  as Ken asked. p > 1 holds strength longer, then fades faster toward the end
  (Ken's "accelerates towards the end"). It is still exactly 0 at N.
- **With p > 1 the 50% point moves later**, to a₅₀ = N · 2^(−1/p):

| Slider point | p | Weight at N/2 | 50% at (N = 10 min) | 90% still at |
| --- | --- | --- | --- | --- |
| Linear (left end, default) | 1 | 50% | 5:00 | 1:00 |
| Middle | 2 | 75% | 7:04 | 3:10 |
| Accelerating (right end) | 4 | 94% | 8:25 | 5:37 |

  An exponential-family curve with the same end point,
  w = 1 − (e^(k·a/N) − 1)/(e^k − 1) (k → 0 is linear), would work equally;
  the power curve is used because one number describes it. The mechanism
  below supports any shape, so this choice can change without touching the
  storage.
- The same weight applies to evidence E, support S and the far scalar, so
  the estimate and the shading forget at the same rate. With the linear
  opacity ramp (section 2.3), opacity follows w one-for-one below saturation.
- **If 10 min turns out not to be enough to detect and locate a source, the
  design needs revisiting, not just N** (Ken). The field plan records time to
  first estimate to check this.

**Mechanism: per-age buckets of tiles, weighted at composite time.** Because
the shape is applied when the layer is drawn, not when reports are added,
any w(a) works, and N and p can change at any time.

- **Buckets:** each source's reports go into the bucket for their report time.
  A bucket holds the integer sums of E′ and S for its reports, plus its far
  scalar, its start and end time and the mean report time t̄_b of its
  entries.
- **Bucket width scales with N:** `bucket_s = clamp(N / 10, 6 s, 60 s)`
  **[A]**. A fixed 60 s width does **not** work at N = 1 min: the whole fade
  would be one bucket, so a report would sit at one weight and then vanish.
  With N/10, every N from 1 to 10 min gets 10 buckets (6 s wide at 1 min,
  60 s at 10 min). Above 10 min the width stays at 60 s (up to 30 buckets at
  N = 30 min), because a wider bucket could overflow the uint16 sums. When N
  changes, existing buckets keep their own width and new buckets use the new
  one; each bucket carries its own times, so nothing is re-bucketed. The
  per-source bucket table holds 64 buckets **[A]** (30 at N = 30 min plus
  room for a change from a short N); if it is full, the oldest bucket expires
  early and is counted.
- **Tiles:** a bucket stores only the 16 × 16-cell tiles its footprints
  touched, taken from one **tile pool** allocated at create (default 4,096
  tiles × 1 KB = 4 MB **[A]**). A tile holds 256 cells × (uint16 E′ in units of
  2⁻⁸ nat, uint16 S in units of 2⁻¹⁰ of a full report), with saturating adds.
  A bucket of at most 60 s holds at most about 60 entries per source, so E′
  stays under about 23,000 and S under about 61,000, both within uint16. Integer sums are
  order-independent and exact.
- **Composite (publish):** per source, the bucket weight
  w_b = w(now − t̄_b) is evaluated once, then the view cells sum w_b × the
  bucket's tile values over the live buckets, into two reused float scratch
  grids (E and S for the 192² view, about 300 KB in total). The estimate and
  the α raster are computed from those.
- **Expiry:** a bucket whose weight has reached 0 (t̄_b ≤ now − N) is dropped
  whole and its tiles go back to the pool. Nothing is recomputed or
  subtracted.
- **Timing accuracy:** reports in one bucket differ in age by at most
  `bucket_s`, and the bucket is weighted at its mean time. So a single report
  is weighted as if up to `bucket_s`/2 older or younger: ±N/20 for N up to
  10 min (±3 s at N = 1 min, ±30 s at 10 min), and ±30 s above that. With the
  linear default that is 45–55% instead of 50% at N/2 for N ≤ 10 min, and a
  report leaves within ±`bucket_s`/2 of N.
- **Pool full:** the oldest bucket (across sources) is expired early and
  counted in the stats (`buckets_expired_early`), and the stats show pool
  use, so pool sizing can be checked in the field. Nothing is allocated.

**Changing N or p at runtime.** `btrx_bearing_set_fade(h, total_s, shape_p)`
can be called from any thread (the settings UI). It is queued and applied at
the next tick, which republishes at once.

- No re-bucketing is needed: buckets are kept by absolute report time, and
  the new w(a) is applied to every held bucket's real age at the next
  composite.
- **Shortening N** immediately drops buckets older than the new N.
- **Lengthening N** applies to every report still held. Reports already
  expired under the shorter N stay gone: the library does not keep data past
  the current N.
- **Changing p** only changes the weights; nothing is dropped.

**Settings UI (identical on both platforms).** In the BTRX settings in
Developer Tools:

- **"Fade time"**: minutes, 1–30, default 10.
- **"Fade shape"**: a **slider** over p = 1 to 4, default 1, labelled
  **Linear** at the left end and **Accelerating** at the right (Ken: "a slider
  would be fine"). Under it, a live readout of the half-strength point for the
  current fade time, for example "half strength at 7:04" (N · 2^(−1/p)),
  updated as the slider or the fade time moves. The table
  above lists reference points on the slider.
- Both are saved with the app settings and passed in at layer start and on
  every change.

**Alternatives not used.** r3's first draft kept exact linear sums
(Σv and Σv·t per cell, subtracting each report again at expiry). That only
gives an exact *linear* fade, does not allow a shape change, and needs every
report recomputed at expiry. Dense per-bucket grids over the whole view cost about
12–24 MB at 10 buckets and 8 sources; tiles keep only the area actually flown. Exponential decay with
no end point is not what Ken described.

**Cost.**

- **Memory:** tile pool 4 MB at the default (`tile_pool_tiles`). A hover
  touches about 25–36 tiles per source per minute; flying 10 m/s, about 50.
  With several sources heard at once and N = 10 min, typical use is under
  half the pool. **For N = 20–30 min** the pool should be doubled to
  8,192 tiles (8 MB); that is a create-time setting, not a runtime one.
  **Short N:** at N = 1 min there are still 10 buckets, but each is 6 s wide,
  so a hover's bucket touches the same footprint (about 25–36 tiles) for a
  tenth of the time: tile use is about the same as N = 10 min for hovering
  and lower for flying, well inside the pool. Plus
  the view scratch grids (about 300 KB) and a small 30 s ring of recent
  entries for the diversity discount (about 10 KB).
- **CPU, add:** one pass per source entry over its footprint (about 5,000
  cells), roughly 0.2–0.5 ms on the A5 **[A]**. At 1 Hz with 3 entries per
  STEER, about 1–1.5 ms per second.
- **CPU, composite:** live tiles × 256 cells × 2 multiply-adds. At a typical
  1,000–2,000 live tiles that is 0.5–1 M operations, about 1–3 ms **[A]**, at
  most once a second.
- **CPU, expiry:** returning tiles to the pool, O(tiles in the bucket).

### 2.5 Front/back ambiguity

Both patches look forward, so Δ is close to the same for a source at φ and at
its mirror 180° − φ (front-left and back-left both give positive Δ). One
report's wedge therefore has a weaker **mirror lobe** behind the drone. (In
the v1 table the back lobes sit near the −20 dB floor, so the mirror is broad
and flat.) It resolves through accumulation, with no special mode:

- **Yawing in place:** the true wedge stays fixed on the map while the mirror
  direction is 2ψ + 180° − β, which turns at twice the yaw rate, so it smears
  out and stays faint.
- **Moving:** the mirror lobes from different positions don't cross at one
  point; the true ones do.
- **Level (later):** the summed level G separates nose from tail across
  reports, but P0 (transmit power plus body loss) is unknown. v1 leaves the
  level term off (weight 0 in the config); v2 can profile P0 per source.

The estimate (section 2.7) reports a second peak while its posterior mass is
over 25% **[A]**, and the label says "ambiguous".

### 2.6 What hover-and-yaw gives you (guidance only, estimates)

Hover-and-yaw is still the quickest way to sharpen a bearing, because it
compares the two patches across many nose angles from one spot. The r1
numbers are kept as **estimates** of what one spot gives, not as a mode.
Assumes S = 0.25 dB/deg near the nose, σ_c0 = 0.5 dB, σ_yaw = 2°, one sweep
= ±45° at 6°/s (15 s, about 15 reports), all **[A]**:

| Sweeps | Time | Bearing σ, conservative (1 sample/patch/window) | Optimistic (5/window) |
| --- | --- | --- | --- |
| 1 | 15 s | 8.5° | 4.6° |
| 2 | 30 s | 6.3° | 3.8° |
| 4 | 1 min | 4.9° | 3.4° |
| 8 | 2 min | 4.0° | 3.1° |

- Error floor about 3° until c0 and the yaw north offset are measured.
- Across the bearing: r · σ, about ±17 m at 200 m for σ = 4.9°.
- **Crossings** (Ken's segment-edge technique, a second hover point on an
  adjacent side): two bearings crossing at angle γ give a long axis of
  σ_d / (√2 sin(γ/2)), σ_d = r·σ. Long axis / σ_d: 1.00 at 90°, 1.41 at 60°,
  2.73 at 30°, 4.07 at 20°. Aim for 60–120°. In r2 the crossing is not
  computed separately: it is where the two hover spots' wedges overlap in E
  and S.

### 2.7 Best estimate and confidence ellipse

At publish time (≤ 1 Hz), per source: the posterior p ∝ exp(E − max E)
over the grid. The library takes the connected region around the top cell
with p > 0.01, and from it the mean point and 2 × 2 covariance. The 1σ and
2σ ellipses (39% and 86% in 2D) come from the covariance eigenvalues. An
estimate **exists** only when all of these hold **[A]**:

- at least 10 effective reports (sum of discounted weights);
- geometry diversity: a yaw span of at least 60° or a baseline of at least
  50 m among those reports;
- a 2σ long axis of 200 m or less.

Otherwise the source shows its shading only, with no point. A second peak
(section 2.5) is reported with its mass.

### 2.8 Gates (unchanged from r3)

A report is not accumulated (it is counted per reason in the stats) when:

- the DJI yaw rate over the 1 s window exceeds 10°/s, or the yaw changed by
  more than 10° within it **[A]** (implementation: 10°, not the 5° first
  written here, which would gate every report of the 6°/s hover-yaw
  scenario in section 9.3; section 12);
- bit 3 (gated) is set or bit 4 (IMU ok) is clear;
- there is no yaw sample within 0.5 s of the report time ("no heading");
- there is no position within 3 s, or the height above ground is unknown and
  no default is configured (default 30 m **[A]**, flagged in the stats);
- the source is on the ignore list (own aircraft, by full address).

**Muted (hidden) sources are still accumulated** (section 5.2), so unmuting
shows them as if they had never been hidden.

A no-target STEER (bit 0 clear) is evidence that nothing was above the floor,
but v1 ignores it. A source missing from a window is not evidence, because
STEER reports only the strongest one (or, later, the strongest 2–3).

### 2.9 Moving subject caveat

The model assumes a **stationary source**. A walking subject at 1 m/s moves
600 m within the default 10 min fade time. Old wedges then point at old positions:
the patch smears along the subject's path, and the estimate lags behind the
subject, sitting between old and new positions. Searchers carrying phones are
the common case. The fade clears the oldest part of the trail.

**All sources fade at the same rate** (Ken: "Initially, I'd say all fade at
same rate, but if it turns out we can determine a moving source with any
level of accuracy/reliability, then we should reconsider this"). Meanwhile
the user hides a smear with the mute panel (section 5.2).

**Future item: moving-source detection.** Candidate signals: the estimate
drifting consistently over minutes; recent reports' residuals at the
estimate growing with report age; a widening ellipse while reports still
agree among themselves. Only if one of these proves reliable in field logs
should the fade become per source. v1 shows the ellipse and does not model
motion.

## 3. Time and pose

### 3.1 Clocks

The library has **no clock of its own**. Every time is int64 microseconds on
one monotonic **host clock** supplied by the boundary layer (Android
`SystemClock.elapsedRealtimeNanos() / 1000`; iPad
`clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1000`). The boundary stamps each
input with the host time it applies to, not only when it arrived:

- **DJI yaw:** the host time the SEI frame was decoded, minus the video
  pipeline latency L_v (a config value, unknown **[A]**, to be measured in
  B3; start at 0 and treat it as a known error).
- **Position:** the RID fix time mapped to the host clock (or arrival time if
  RID carries none), with height above ground from the existing altitude code
  (Android `DroneAltitudeCoordinator`; the iPad equivalent).
- **STEER:** the host arrival time, plus the raw `window_end_ms`.

### 3.2 Pod clock offset

Report time = window_end_ms + offset − 500 ms (the window centre), mapped to
the host clock.

- **TIME_PING/TIME_PONG (the clock sync; Ken, via BT RX, November 13).**
  The only timestamp that matters is the RSSI window time `window_end_ms`
  mapped into tablet time; there will be **no Desk Pod receive timestamp**.
  **The app sends TIME_PING to the Drone Pod periodically** while the layer
  is on: every 30 s **[A]**, plus a burst of 3 at layer start and after a
  pod restart or reconnect, so a low-RTT sample arrives quickly. This is new
  work on both platforms (`BtrxClient` can send the frame, but nothing sends
  it periodically today). The boundary passes (t1 host send, t2 pod receive, t3 pod send, t4 host
  receive) to `push_time_sync()`. offset = ((t2 − t1) + (t3 − t4)) / 2,
  RTT = (t4 − t1) − (t3 − t2). The library keeps the sample with the
  smallest RTT over the last 2 min; its error is under RTT/2. Whether the
  Desk Pod relays the ping to the Drone Pod is still a BT RX bench check.
- **Fallback:** the minimum of (arrival − window_end_ms) over a sliding 2 min
  window (r3 section 6). Measured delay was 54–75 ms median, 0.23–0.39 s p99,
  so the fallback adds about 60 ms of bias (about 0.4 m at 6 m/s).
- **New pod session** (offset state reset) when window_end_ms goes backward,
  the arrival offset jumps by more than 2 s **[A]**, or HEALTH `uptime_s`
  drops (`push_health()`).

### 3.3 Pose ring buffers

Ken: "The initial design should track 10-15 s of previous telemetry while we
determine what the maximum normal delay is." Two fixed rings, allocated at
create, sized for at least 15 s plus the 1 s window:

| Ring | Rate | Capacity | Span |
| --- | --- | --- | --- |
| Yaw (and optional aircraft pitch/roll) | about 30 Hz (up to 60) | 1,024 | about 34 s at 30 Hz, 17 s at 60 Hz |
| Position (lat, lon, height above ground) | about 1 Hz | 64 | about 60 s |

Create fails with `BTRX_BEARING_E_INVALID` if the configured capacities
cannot cover `max_report_lag_ms` + 2 s at the configured maximum rates.

Interpolation at the report time: yaw by shortest-arc linear interpolation
(0/360 wrap), position linearly in the local tangent plane. The window's yaw
rate and change for the gate come from the yaw samples inside
[t_end − 1 s, t_end]. Gaps: no yaw within 0.5 s or no position within 3 s
makes the report unusable (section 2.8).

### 3.4 Lag budget and late reports

A report waits in a pending ring (capacity 32 frames) until the pose rings
cover its window: a yaw sample at or after t_end, and a position sample at or
after the report time (or one within 1.5 s **[A]** before it, extrapolated
with the last velocity). This is the natural display lag Ken expects: usually
under 1 s, limited by the 1 Hz RID position.

A report older than the **lag budget**, `max_report_lag_ms`, **15 s by
default** (configurable), when it arrives or while it waits, is **dropped**
and counted with the stage that made it late (section 3.5). This answers the
r2 Desk Pod backlog question: backlog frames up to 15 s late are used, and
older ones (r3 measured 5–20 s) are dropped and counted. The budget is
lowered once field logs show the maximum normal delay.

**Desk Pod backlog is history, not live steering** (btrx PROTOCOL.md at
`684cfa5`). While no app is attached, the Desk Pod keeps up to 32 messages
for the app. **Ken's decision (via BT RX, November 13), in Desk Pod build
0x0104 (btrx `e116ac6`, draft, not flashed): when the queue is full, the
oldest message is discarded before a new one is accepted**, so the newest
32 are kept. This covers all queued messages, btrx and Meshtastic alike.
After a reconnect the app receives up to the 32 most recent queued messages.
Backlog STEER frames with an old `window_end_ms` are still history (below).

- **Lost count: HEALTH (0x80) `phone_lost`** (btrx `firmware/PROTOCOL.md`
  at `e116ac6`, "HEALTH (0x80)"):
  - HEALTH is now **34 bytes**. Bytes 0–29 are unchanged; **offset 30 is
    `phone_lost`, u32 little-endian**: messages that board dropped from its
    full app queue (oldest first) since it started. It stops at 0xFFFFFFFF.
  - It resets only when the board restarts (its `uptime_s` goes down) and is
    **not cleared by reading**. So lost between two HEALTH frames from the
    same board = the difference; after a restart, the new value **is** the
    delta.
  - A **30-byte HEALTH** is older firmware with no count: shown as
    **unknown**, never as 0.
  - The **Desk Pod's own HEALTH (role = 3)** reaches the app every 30 s (60 s
    while scanning) plus one about 2 s after the app connects, after any
    backlog, so the count is seen at once. Pod A's HEALTH carries the field
    too and is normally 0 (the Drone Pod boards' once-a-second emptying with
    no app attached is not counted).
  - The boundary passes each HEALTH's role, `uptime_s` and `phone_lost` (or
    "unknown") to `btrx_bearing_push_health()`, which also does the restart
    detection of section 3.2. The library keeps the running total per role
    and the increase since the last app connect (`desk_queue_lost`,
    `desk_queue_lost_since_connect`; `desk_queue_lost_known` false for
    30-byte HEALTH).
  - The **BTRX status screen** (the Desk Pod status/health screen,
    `BtrxDeskPodModel`/`BtrxDeskPodText` on both platforms) shows "Reports
    lost while no app: N" (N since connect, plus the total since the Desk Pod
    started), or "unknown (older firmware)".
  - **Parsers:** the Kotlin and Swift `BtrxProtocol.parseHealth` check a
    **minimum** of 30 bytes (`need(p, 30, …)`), not exactly 30, so they
    already accept 34-byte HEALTH without error. They ignore bytes 30–33
    today; both need an optional `phoneLost` field (null for 30 bytes), with
    shared test vectors for 30- and 34-byte payloads.

- The library judges every frame by its own time (`window_end_ms` + offset),
  never by arrival. A backlog frame within the 15 s budget whose pose is
  still in the rings is used like any other.
- Older backlog frames get the fate **`history`**, not `late`: counted
  separately (`backlog_history`) and logged in `BBLAG`, but kept **out of the
  late-drop and stage histograms**, so a reconnect does not look like a slow
  path. The poses for those windows have already left the 15 s rings anyway.
- A frame is treated as backlog when `BtrxClient` marks it so, or when its
  window ended more than 5 s **[A]** before the newest window already seen.
- The pod offset is never estimated from backlog arrivals (r3 section 6).

### 3.5 Lag instrumentation

Ken: "I think we want to measure and debug the slow path so that we can
locate and eliminate any significant delays."

**Per-report stage times**, all on the host clock:

| Stamp | Meaning | Source |
| --- | --- | --- |
| T0 window end | `window_end_ms` + pod offset | pod, section 3.2 |
| T0′ hand-off | T0 + (time to `send_steer`); `age_ms` gives the newest sample's age at hand-off | pod |
| T1 Desk Pod | **none, by decision**: no Desk Pod receive timestamp is planned (Ken). T0→T2 stays one stage (LoRa air time 63–71 ms + Desk Pod relay + BLE), measured against the TIME_PING-synced window time | — |
| T2 BLE arrival | the BLE/USB read callback in `BtrxClient` (for a Desk Pod backlog frame this includes the time queued, so backlog frames are excluded from the stage histograms, section 3.4) | boundary |
| T3 push | `btrx_bearing_push_report()` call | library |
| T4 pose ready | pending report gets its pose; records which ring it waited for (yaw or position) | library |
| T5 accumulated | added to the grids in `tick` | library |
| T6 published | the snapshot containing it is published | library |
| T7 rendered | the boundary calls `btrx_bearing_note_rendered(seq, host_us)` once the overlay with that snapshot is on screen | boundary |

Stages reported: **pod→arrival** (T2 − T0), **arrival→push** (T3 − T2),
**pose wait** (T4 − T3, split by yaw or position), **tick wait**
(T5 − T4), **publish wait** (T6 − T5), **render** (T7 − T6) and **total**
(T7 − T0). Pose inputs get the same treatment: **yaw input lag** (push time
− the yaw stamp) and **position input lag** (push time − RID fix time).

- **Histograms:** per stage, a ring of the last 600 reports (about 10 min,
  14 KB). At publish the snapshot carries count, p50, p90, p99 and max per
  stage, plus late-drop counts by the stage that crossed the budget.
- **Debug log line** (`lag_trace` config, Developer Tools only): one line per
  report when its fate is final, through the log callback, which the
  boundary writes to the session log:
  `BBLAG seq=<steer_seq> src=<short_id> rank=<0-2> fate=used|gated|late|history|ignored wait=yaw|pos|none t0=… t2=… t3=… t4=… t5=… t6=…`
  and one `BBRND snap=<seq> t7=<host_us>` line per rendered snapshot.
- **CSV:** `tools/btrx_bearing_test/lag_report.py` joins `BBLAG` and `BBRND`
  lines from a session log into one CSV row per report (all stamps and
  stages) and prints the percentiles, so slow paths can be found per stage.

## 4. Sources, IDs, colours

- **Key:** the library hashes the full 6-byte address and its address type
  with SipHash-2-4 under a 16-byte **incident key** the boundary passes at
  create (random per incident, never saved). `btrx_bearing_source_key()`
  returns the 64-bit key. Inputs and logs carry only that key. The
  STEER parser (section 4.1) computes it for the target, so the raw address
  never enters a report struct or a log.
- **Extras have no address**, only the pod's `src_id`. The library keeps a
  `src_id` → source table per pod session (section 4.1) and resolves extras
  through it.
- **Short ID:** base64url of the top 36 bits, 6 characters (for example
  `Q3x9Kd`). Same on both platforms by construction.
- **Class:** from the pod's `dev_class` (target byte 17, or extra-record
  `rflags` bits 2–4): 1 phone → phone, 2 wearable → watch, 3 tracker → tag,
  4 audio → **audio**, 5 computer and 0 → unknown. "Audio" (earbuds,
  headphones, hearing aids) becomes **hearing aid** only when the boundary's
  existing evidence says so (DETECT hearing service UUIDs, `BtrxMakers`),
  through `btrx_bearing_set_source_class()`. The library keeps the most
  specific class seen.
- **Colour:** a fixed 8-colour colour-blind-safe palette (Okabe–Ito order:
  orange, sky blue, bluish green, yellow, blue, vermillion, reddish purple,
  black, all **[A]**), assigned in order of first report and kept for the
  source's lifetime.
- **Slots:** 8 sources **[A]**. A new source when all slots are full replaces
  the least recently heard source without a valid estimate (or, failing
  that, the least recently heard).
- **Ignore (own aircraft):** `btrx_bearing_set_source_flags(IGNORED)` by key
  from the BTRX pane. Ignored sources are never accumulated.
- **Mute / unmute = hide / show on the map** (section 5.2). A muted source is
  not drawn, but its data keeps accumulating and fading, so unmuting restores
  it. Mute never deletes anything. The flag lasts as long as the source's
  slot: until the layer is switched off, the source is evicted, or
  `reset`.
- **Map mute is not the pod's steering mute.** The stream overlay's "Mute"
  sends STEER_MUTE (0x74), which makes the pod stop choosing that source, so
  no more reports arrive for it. The map panel only hides; it never sends
  STEER_MUTE.

### 4.1 Multi-source STEER (defined by BT RX, build 0x0103)

Source: btrx `firmware/PROTOCOL.md` at `684cfa5` (branch `steering-report`,
draft PR #18), read with `git show`. **Not flashed yet.** BT RX estimates
the 53-byte frame at about 78 ms on air, so STEER stays at 1 s under the
existing 8% duty cap.

**Payload** (little-endian):

| Sources | Payload | btrx frame |
| --- | --- | --- |
| 1 (or none heard) | 28 bytes (27 on older firmware) | 41 bytes |
| 2 | 34 bytes | 47 bytes |
| 3 | 40 bytes | 53 bytes |

- **Bytes 0–26:** the existing single-source STEER, unchanged: the
  **primary** (target), the strongest source with the pod's 3 dB hold.
- **Byte 27, `ext`:** bits 0–3 = count of extra records (0–2); bits 4–7 =
  format (1).
- **From byte 28:** extra records, strongest first, 6 bytes each:

| Offset | Type | Field | Meaning |
| --- | --- | --- | --- |
| +0 | u16 | `src_id` | same id space as the target's |
| +2 | i8 | `rssi_port` | mean dBm over the 1 s window; −128 = not heard |
| +3 | i8 | `rssi_stbd` | same, starboard |
| +4 | u8 | `hits` | bits 0–3 port samples, bits 4–7 starboard samples, each saturating at 15 (15 = 15 or more) |
| +5 | u8 | `rflags` | bit 0 new (not in the previous window's report), bit 1 address change linked in this window, bits 2–4 `dev_class`, bits 5–7 zero |

**Parsing rules** (one C parser, `btrx_bearing_parse_steer()`, used by both
platforms; shared test vectors):

1. 27 bytes: older firmware, the primary only.
2. 28 bytes or more: byte 27 is `ext`. Format 0 or count 0 means no extras.
   With flags bit 0 clear (nothing heard) the count is 0.
3. Record length = (payload length − 28) / count. Read only the first 6 bytes
   of each record and skip the rest (a later format may be longer).
4. If the record length works out under 6, or the count is over 2, the frame
   is cut short: use bytes 0–26 only, and count it (`steer_truncated`).
5. The existing Kotlin and Swift `BtrxProtocol.parseSteer` check a
   **minimum** length of 27 (`need`), so the stream-view overlay keeps working
   on 28–40-byte payloads unchanged.

**What the extras lack and how the library copes:**

- **No address, maker, peak or age.** An extra's address comes from an
  earlier STEER where that `src_id` was the primary. The library keeps a
  `src_id` → source table per pod session (cleared when a restart is
  detected, section 3.2, because ids restart at 1).
- **Unresolved extra:** a `src_id` never seen as primary gets a
  **provisional source**, keyed by a hash of (pod session, `src_id`), shown
  with its class and colour like any other, labelled "unresolved". When that
  `src_id` later appears as the primary with an address, the provisional
  source is **merged** into the address's source (or simply takes that key if
  the address is new). The merge adds the bucket tiles (integer sums, aligned
  by absolute bucket time), so it is exact and allocation-free; the address
  source keeps its colour.
- **Linked address change** (target flags bit 2, or extra `rflags` bit 1):
  the `src_id` stays, so the source stays the same; the new address key is
  added as an alias (up to 4 **[A]**), and ignore or mute by any alias
  applies.
- **Age:** within the same 1 s window as the target; the library uses the
  window time for all entries.
- **Peak:** not used in v1 (the level term is off).
- **Strength order:** because of the hold, an extra can read up to 2 dB
  stronger than the primary. Nothing in the model depends on rank; rank is
  kept for the stats and `BBLAG` only.
- **Frame-wide fields:** flags bit 3 (attitude gated), bit 4 (IMU ok) and
  roll/pitch apply to every source in the frame, so the gates (section 2.8)
  accept or reject the whole frame; per-source gates (one-patch rule) still
  apply per entry.

**Hits weight each record's noise.** Every entry, primary or extra, uses its
own hit counts in σ_Δ = 5.5 √(1/N_port + 1/N_stbd) (section 2.1) and in the
information weight q_r = min(1, (2 dB / σ_Δ)²) (section 2.2). Fewer samples
give a wider, fainter wedge. Extras' 4-bit counts saturate at 15; the
library uses 15, which slightly understates a fast advertiser's information
(σ_Δ ≥ 2.0 dB, q ≤ 1, so the weight is already at its cap). The one-patch
rule (at least 2 hits on the heard patch, bit 4 set) applies per entry.

**Pose:** every entry of a frame is posed once, at the frame's report time,
and accumulated into its own source's buckets.

**Still no negative evidence:** a source missing from a 3-entry frame may be
muted or ignored on the pod, or simply fourth.

## 5. Rendering output

The library renders the shading. The platforms only show its pixels, the
outlines and labels.

- **Composite raster:** one RGBA8 premultiplied image, one pixel per cell,
  north-up, with its north/south/east/west bounds in degrees. Per cell, each
  **unmuted** source's colour at its opacity α (section 2.3) is blended
  "over" in order of last heard (most recent on top). Cells with α < 0.02
  are transparent.
- **Per-source opacity rasters:** one uint8 α per cell per source, muted ones
  included, for the tap query (section 5.2). 192² × 8 = 295 KB per snapshot.
- **Per source:** short ID, class, colour, muted flag, last heard, report
  counts, and the estimate (point, 1σ/2σ ellipses as semi-axes in metres plus
  orientation, optional second peak) when one exists. **A muted source's
  estimate and ellipse are not drawn** (still computed, so unmute shows them
  at once).
- **Label:** `phone · Q3x9Kd · 23 rpt · ±18 m` (2σ long axis) or
  `phone · Q3x9Kd · 23 rpt` without an estimate, plus "ambiguous" when a
  second peak is shown.
- Published at most once a second, with a sequence number so the UI skips
  unchanged snapshots, and **at the next tick after a mute change** (within
  200 ms). No smoothing: each snapshot is the current state.

### 5.1 Latest-ray outline

Ken: "I don't want to add a lot of processing yet, but would be nice if new
rays were obvious overlays on top of previous."

- Per unmuted source, the **latest accepted report's wedge** is drawn as a
  **stroke-only** outline in the source's colour, on top of the shading:
  apex at the drone's position at report time, centre line, and the two
  edges out to the footprint radius. A dashed thinner outline shows the
  mirror lobe while it is significant (section 2.5).
- Geometry comes from the report itself, not the grid: the library evaluates
  ℓ on a 1° ring at one range (360 evaluations) to find the centre bearing
  and the half-width where ℓ > −0.5 **[A]**. No extra grid work.
- The outline fades out over 5 s **[A]** (stroke alpha 1 → 0, from the
  report's time) or is replaced by the source's next report, whichever is
  first. The fade is a native animation on the platform (or stepped at the
  1 Hz snapshot rate); the library supplies only the geometry and t_us.

### 5.2 Tap-to-mute panel

Ken: "having the ability to click on a location and get a panel of
color-coded checkboxes along with their descriptions to mute/unmute might be
the best way to go. When running a BTRX scanner overlay on the map, that
functionality should have right of first refusal on any single/double taps
(aside from the basic pan/pinch/zoom)."

Ken (9:41 AM PT) narrowed this: "Double-tap shouldn't be intercepted
yet"; "Drone or waypoints should win. If a user is interested in a nearby
ray, they are going to tap on it instead of the drone or waypoint. I was
mostly concerned about selecting an area/line assignment."

**Tap routing (both platforms).** While the BTRX overlay is on, only a
**confirmed single tap** is routed; the priority order is:

1. **Point map items win first:** drone (aircraft) markers and waypoints.
   **Clue markers are treated like waypoints [A]** (Ken named drones and
   waypoints only).
2. **Then the BTRX overlay:** it calls
   `btrx_bearing_snapshot_sources_at(snapshot, lat, lon, radius_m)` on the
   current snapshot. The radius comes from the zoom: a 24 pt **[A]** touch
   radius converted to metres, at least one cell. If a source has shading
   there (α ≥ 0.02 in its raster, muted sources included), the overlay
   **consumes** the tap and opens the panel.
3. **Then area/line assignment shapes and everything else** (assignment
   polygons and route lines, other overlays, the map's own single-tap
   handling). The BTRX overlay beats assignment selection: a tap on shading
   inside an assignment opens the BTRX panel, not the assignment.

- **Double taps are not intercepted:** they go straight to the map's normal
  handling (zoom). The single-tap path waits for the double-tap timeout
  (Android `onSingleTapConfirmed`; on the iPad a passive 2-tap recognizer the
  BTRX path waits on), so the first tap of a double tap never opens the
  panel.
- **Pan, pinch and zoom gestures are untouched**: they never reach the
  overlay.

**How each platform enforces the order.**

- **Android (osmdroid, `SplitMapPane` in `MapPane.kt`):** osmdroid offers a
  single tap to overlays from the top of the overlay list down, and stops at
  the first that returns `true`. The BTRX tap overlay
  (`onSingleTapConfirmed` only; `onDoubleTap` not overridden, returns
  `false`) is inserted **below** the `Marker` overlays (drone, waypoint and
  clue markers) and **above** the assignment `Polygon`/`Polyline` overlays
  and the existing `MapEventsOverlay` (`singleTapConfirmedHelper`, which
  returns `false`). As a guard against list-order mistakes, the BTRX overlay
  also declines when any visible `Marker` passes `hitTest` at the tap point.
  Where the managed overlays are added (and in what order) is to be checked
  when coding.
- **iPad (`RIDTrackMapView.swift`, `OperationalMKMapView` coordinator):**
  the existing `handleArtifactTap` already hit-tests in this order: visible
  annotation views first (aircraft via `point(inside:)`, other annotations
  with an 8 pt margin) and returns if one is hit, then the `StyledPolygon` /
  `StyledPolyline` assignment overlays. The BTRX check is inserted **between
  those two steps**: after the annotation check, before the assignment
  shapes. When it consumes the tap, the assignment hit test is skipped. The
  BTRX branch acts only once a passive 2-tap recognizer
  (`cancelsTouchesInView = false`) has failed, so double taps reach MapKit's
  zoom unchanged.

`sources_at` returns up to 8 sources ordered by their contribution at the
spot (the sum of α over the cells in the radius, highest first). It is a pure
function of an acquired snapshot, bounded by the radius (at most a few
thousand cells), and runs on the UI thread in microseconds.

**Panel (identical on Android and iPad).**

- A small card anchored at the tap point, titled **"BTRX sources here"**.
- One row per source, in `sources_at` order:
  - colour swatch (the source's colour);
  - checkbox: **checked = shown, unchecked = muted (hidden)**;
  - short ID, for example `Q3x9Kd`;
  - device class: phone / watch / hearing aid / tag / unknown;
  - last heard, for example "12 s ago";
  - report count in the fade window.
- Footer: **"Mute all"** and **"Unmute all"**, applied to the rows listed.
- Changing a checkbox applies at once (`set_source_flags`, shown within
  about 200 ms) and leaves the panel open. Nothing is deleted: muted sources
  keep accumulating and still fade out at N (10 min by default).
- **Dismissing** the panel (tap outside it, or "Done") only hides the panel.
- **Rows only show or hide** a source's shading, with its latest-ray outline
  and estimate (Ken: "panel rows just enable/disable shading"). The panel has
  **no steering actions**: no pod steering mute (STEER_MUTE) and no steering
  recommendations. The map layer gives no pilot hints at all (Ken: "I don't
  think the map-centric ray representations require any steering
  recommendations at all"); the r1 pilot-hints section stays removed.
- A muted source can be found again by tapping where it was (its hidden
  shading still answers `sources_at`) or from the source list in the BTRX
  pane in Developer Tools, which shows the same rows for every source.

## 6. C library: `btrx_bearing`

### 6.1 Rules

- **C11 + stdlib + pthreads only.** Allowed headers: `<stdint.h>`,
  `<stddef.h>`, `<stdbool.h>`, `<stdlib.h>`, `<string.h>`, `<math.h>`,
  `<pthread.h>`, and `<stdatomic.h>` inside `.c` files only (the public
  header stays atomic-free so Swift and JNI can import it). No `<jni.h>`, no
  Apple or Android headers, no I/O, no logging library, no clock, no `rand`.
- **Opaque handle**, versioned ABI: `BTRX_BEARING_ABI_VERSION`, a
  `struct_size` and `abi_version` in the config, and
  `btrx_bearing_abi_version()`. A mismatch fails create with
  `BTRX_BEARING_E_ABI`.
- **No allocation after create.** Allocated in `btrx_bearing_create()`:
  - the tile pool, 4,096 tiles × 1 KB (section 2.4), 4 MB, plus bucket
    tables and the view scratch grids (about 300 KB);
  - the diversity ring of recent entries (about 10 KB);
  - pose rings, pending ring and lag histograms (under 100 KB);
  - the pattern table (about 130 KB);
  - three snapshot buffers, each 147 KB RGBA + 295 KB per-source α +
    metadata, about 1.3 MB in total.

  About 6 MB in all at the defaults. `push_*`, `tick`, `acquire`,
  `release`, `sources_at` and `note_rendered` never call `malloc`.
- **Bounded work.** Push is O(1). Tick processes at most
  `max_reports_per_tick` frames (default 4, up to 3 source entries each),
  each about 5,000 footprint cells, plus bucket expiry (returning tiles) and
  at most one publish (live tiles × 256 cells). The limits are
  counts, not time, so results stay deterministic.
- **Deterministic.** Same inputs in the same order give the same outputs.
  Both builds use `-std=c11 -O2 -ffp-contract=off` and no `-ffast-math`.
  The tiles hold fixed-point integer sums, so they match exactly whenever the
  per-cell values do. libm differs between bionic and Apple in the last
  bits, which can move a value by one quantum, so cross-platform tests
  compare with tolerances (section 9).
- **Errors:** every call returns a `btrx_bearing_status_t`; nothing aborts.
  A full input ring drops the **oldest** item and returns
  `BTRX_BEARING_W_DROPPED` (a warning, > 0), counted in the stats.

### 6.2 Thread-safety contract

| Call | Threads | Blocking |
| --- | --- | --- |
| `push_pose`, `push_report`, `push_time_sync`, `push_health`, `set_source_flags`, `set_fade`, `note_rendered`, `source_key` | any thread, concurrently | takes only the **input mutex**, for an O(1) copy; never waits for tick |
| `tick` | one thread at a time (a second concurrent caller gets `BTRX_BEARING_E_BUSY`) | drains inputs in a bounded batch under the input mutex, then works with no input lock held |
| `snapshot_acquire` / `snapshot_release` | **one** reader thread (the UI or render thread) | triple buffer; a pointer swap under a separate tiny mutex. Never waits for tick |
| `snapshot_sources_at` | the reader thread, between acquire and release | pure function of the snapshot; no lock |
| `start_worker` / `stop_worker` | the owner thread | stop joins the worker |
| `reset` | any thread | queued as a command, applied at the next tick |
| `destroy` | the owner, after `stop_worker` and with no call in flight | |

Two ways to run the work, chosen by the boundary:

- **Caller-driven (default on both apps):** the platform's existing
  low-priority BTRX worker (r3 section 10: an Android single-thread executor
  at background priority; an iPad serial queue at `.utility` QoS) calls
  `btrx_bearing_tick()` every 200 ms **[A]**. Thread priority stays a
  platform decision, which needs platform APIs the library must not use.
- **Own worker (host tools, replay):** `btrx_bearing_start_worker()` runs a
  pthread that waits on a condition variable and ticks when input arrives or
  every 200 ms, with `now` from the `now_us` callback in the config.

The UI thread calls only `set_source_flags` (from the panel),
`note_rendered`, `snapshot_acquire/release` and `snapshot_sources_at`. None
of them can block on model work.

### 6.3 Header sketch (`include/btrx_bearing.h`)

```c
#ifndef BTRX_BEARING_H
#define BTRX_BEARING_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

#define BTRX_BEARING_ABI_VERSION 1u
#define BTRX_BEARING_MAX_SOURCES 8
#define BTRX_BEARING_MAX_REPORT_SOURCES 3    /* primary + up to 2 extras (build 0x0103) */
#define BTRX_BEARING_RSSI_NOT_HEARD (-128)

typedef struct btrx_bearing btrx_bearing_t;   /* opaque */

typedef enum {
    BTRX_BEARING_OK = 0,
    BTRX_BEARING_W_DROPPED = 1,      /* accepted; oldest item in a full ring dropped */
    BTRX_BEARING_E_INVALID = -1,     /* NULL, NaN, out-of-range field, rings too small */
    BTRX_BEARING_E_NOMEM = -2,       /* create only */
    BTRX_BEARING_E_ABI = -3,
    BTRX_BEARING_E_BUSY = -4,        /* concurrent tick */
    BTRX_BEARING_E_LATE = -5,        /* report beyond the lag budget, dropped */
    BTRX_BEARING_E_NO_SNAPSHOT = -6,
} btrx_bearing_status_t;

typedef enum {
    BTRX_BEARING_CLASS_UNKNOWN = 0,
    BTRX_BEARING_CLASS_PHONE = 1,
    BTRX_BEARING_CLASS_WATCH = 2,
    BTRX_BEARING_CLASS_HEARING_AID = 3,
    BTRX_BEARING_CLASS_TAG = 4,
    BTRX_BEARING_CLASS_AUDIO = 5,    /* pod "audio": earbuds, headphones, maybe hearing aid */
} btrx_bearing_class_t;

typedef int64_t (*btrx_bearing_now_us_fn)(void *ctx);           /* host monotonic */
typedef void (*btrx_bearing_log_fn)(void *ctx, int level, const char *msg); /* tick thread; must not block */

typedef struct {
    uint32_t struct_size;            /* sizeof(btrx_bearing_config_t) */
    uint32_t abi_version;            /* BTRX_BEARING_ABI_VERSION */
    /* grid */
    float cell_m;                    /* 10 */
    int32_t grid_cells;              /* 192 (square) */
    int32_t max_sources;             /* 8 (<= BTRX_BEARING_MAX_SOURCES) */
    float footprint_m;               /* 400 */
    float recentre_margin_m;         /* 300 */
    /* model */
    float sigma_sample_db;           /* 5.5 */
    float sigma_c0_db, c0_db;        /* 0.5, 0 */
    float sigma_model_db;            /* 2 */
    float student_nu;                /* 4 */
    float p_far;                     /* 0.05 */
    float scan_floor_dbm;            /* -100 */
    float hpbw_deg;                  /* 90 (v1 table) */
    const float *pattern_delta_db;   /* optional measured table, NULL = v1 */
    const float *pattern_sum_db;     /* same shape: 360 az x 46 depression */
    float level_term_weight;         /* 0 in v1 */
    /* weights, fade, display */
    float tempering;                 /* 0.5 */
    float info_ref_db;               /* 2 */
    float diversity_m, diversity_deg;/* 5, 5 */
    int32_t diversity_window_ms, diversity_cap; /* 30000, 9 */
    float fade_total_s;              /* N = 600 (user setting 60..1800) */
    float fade_shape_p;              /* p in [1, 4], 1 = linear; weight = max(0, 1 - (age/N)^p) */
    float bucket_min_s, bucket_max_s;/* 6, 60: bucket width = clamp(N/10, min, max) */
    int32_t buckets_per_source;      /* 64 */
    int32_t tile_pool_tiles;         /* 4096 x 1 KB (16x16 cells, uint16 E' and S) */
    float alpha_max, support_sat;    /* 0.7, 8: alpha = alpha_max * min(1, S/support_sat) */
    float alpha_visible;             /* 0.02: transparent below; sources_at threshold */
    float ray_halfwidth_ll;          /* -0.5: latest-ray edge level */
    /* gates and timing */
    float max_yaw_rate_dps, max_yaw_change_deg; /* 10, 10 (section 12) */
    int32_t max_yaw_gap_ms, max_pos_gap_ms;     /* 500, 3000 */
    int32_t max_pos_extrapolate_ms;  /* 1500 */
    int32_t max_report_lag_ms;       /* 15000 (Ken: 10-15 s while measuring) */
    int32_t yaw_ring_capacity;       /* 1024 */
    int32_t pos_ring_capacity;       /* 64 */
    float max_yaw_rate_hz, max_pos_rate_hz; /* 60, 2: create checks ring span */
    int32_t yaw_latency_ms;          /* L_v, 0 until measured */
    float default_agl_m;             /* 30; NaN = unknown AGL drops the report */
    int32_t sync_window_ms;          /* 120000 */
    /* lag instrumentation */
    bool lag_trace;                  /* BBLAG/BBRND lines through log() */
    int32_t lag_history;             /* 600 reports */
    /* estimate */
    int32_t est_min_reports;         /* 10 */
    float est_min_yaw_span_deg, est_min_baseline_m; /* 60, 50 */
    float est_max_axis_2s_m;         /* 200 */
    float second_peak_mass;          /* 0.25 */
    /* work */
    int32_t max_reports_per_tick;    /* 4 frames */
    int32_t publish_interval_ms;     /* 1000; a mute or fade change publishes at the next tick */
    btrx_bearing_now_us_fn now_us;   /* own-worker mode only; may be NULL */
    btrx_bearing_log_fn log;         /* may be NULL */
    void *callback_ctx;
} btrx_bearing_config_t;

/* Pose: fields valid per mask; position and yaw may arrive separately. */
enum {
    BTRX_BEARING_POSE_POSITION = 1u << 0,   /* lat, lon */
    BTRX_BEARING_POSE_AGL = 1u << 1,
    BTRX_BEARING_POSE_YAW = 1u << 2,        /* true north, degrees */
    BTRX_BEARING_POSE_ATTITUDE = 1u << 3,   /* aircraft pitch, roll (not in SEI today) */
};
typedef struct {
    int64_t t_us;                    /* host time the pose applies to */
    int64_t push_us;                 /* host time the boundary got it (input-lag stats) */
    uint32_t mask;
    double lat_deg, lon_deg;
    float agl_m;
    float yaw_true_deg;              /* R2CDJIAircraftYawTrueNorthDegrees(raw) */
    float pitch_deg, roll_deg;       /* + nose up, + starboard down */
} btrx_bearing_pose_t;

/* One source entry of a STEER: the primary (bytes 0-26) or an extra record. */
typedef struct {
    uint16_t src_id;                 /* pod id; same id space for primary and extras */
    uint64_t addr_key;               /* keyed hash of the address; 0 for extras (no address) */
    int8_t rssi_port, rssi_stbd;     /* mean dBm over the window; -128 = not heard */
    int8_t peak_rssi;                /* primary only; -128 for extras */
    uint8_t hits_port, hits_stbd;    /* extras: 4-bit, 15 = 15 or more */
    uint16_t age_ms;                 /* primary only; 0xFFFF for extras */
    uint8_t rank;                    /* 0 primary, 1-2 extras (frame order) */
    bool is_new;                     /* primary: flags bit1; extra: rflags bit0 */
    bool linked;                     /* primary: flags bit2; extra: rflags bit1 */
    uint8_t pod_dev_class;           /* pod dev_class 0-5 (PROTOCOL.md table) */
    uint16_t company_id;             /* primary only; 0 for extras */
    uint8_t addr_type;               /* primary only */
} btrx_bearing_report_source_t;

/* One STEER (0x73): 27-byte (older firmware) or 28/34/40-byte (build 0x0103). */
typedef struct {
    int64_t arrival_us;              /* T2: host BLE/USB arrival */
    uint32_t window_end_ms;          /* pod A millis() */
    uint8_t steer_seq;
    uint8_t flags;                   /* frame flags: bit0 target .. bit6 imu cal; bit3/4 apply to all entries */
    int8_t roll_x2, pitch_x2;        /* pod, at send time; whole frame */
    bool backlog;                    /* BtrxClient marked it queued by the Desk Pod */
    uint8_t ext_format;              /* byte 27 bits 4-7; 0 for 27-byte frames */
    bool truncated;                  /* extension unreadable: bytes 0-26 used only */
    uint8_t source_count;            /* 0 (nothing heard) .. BTRX_BEARING_MAX_REPORT_SOURCES */
    btrx_bearing_report_source_t src[BTRX_BEARING_MAX_REPORT_SOURCES]; /* frame order */
} btrx_bearing_report_t;

/* Parse a STEER payload by the section 4.1 rules. Pure apart from reading the
 * handle's incident key (to hash the primary's address). Any thread. */
btrx_bearing_status_t btrx_bearing_parse_steer(const btrx_bearing_t *h,
        const uint8_t *payload, size_t len, int64_t arrival_us, bool backlog,
        btrx_bearing_report_t *out);

/* Lifecycle */
uint32_t btrx_bearing_abi_version(void);
void btrx_bearing_config_default(btrx_bearing_config_t *cfg);
btrx_bearing_status_t btrx_bearing_create(const btrx_bearing_config_t *cfg,
        const uint8_t incident_key[16], btrx_bearing_t **out);
void btrx_bearing_destroy(btrx_bearing_t *h);
const char *btrx_bearing_status_string(btrx_bearing_status_t s);

/* Inputs: any thread, O(1), short lock */
btrx_bearing_status_t btrx_bearing_push_pose(btrx_bearing_t *h, const btrx_bearing_pose_t *p);
btrx_bearing_status_t btrx_bearing_push_report(btrx_bearing_t *h, const btrx_bearing_report_t *r);
btrx_bearing_status_t btrx_bearing_push_time_sync(btrx_bearing_t *h, int64_t t1_host_us,
        uint32_t t2_pod_ms, uint32_t t3_pod_ms, int64_t t4_host_us);
/* HEALTH (0x80): role (3 = Desk Pod, 2 = pod A), uptime_s, and phone_lost (payload
 * offset 30, u32 LE; lost_known = false for 30-byte HEALTH from older firmware).
 * Also feeds restart detection. Lost between frames = difference; after a
 * restart (uptime_s lower) the new value is the delta. */
btrx_bearing_status_t btrx_bearing_push_health(btrx_bearing_t *h, int64_t host_us, uint8_t role,
        uint32_t uptime_s, bool lost_known, uint32_t phone_lost);
/* T7: the overlay showing snapshot `seq` is on screen. */
btrx_bearing_status_t btrx_bearing_note_rendered(btrx_bearing_t *h, uint64_t seq, int64_t host_us);

/* Sources */
btrx_bearing_status_t btrx_bearing_source_key(const btrx_bearing_t *h,
        const uint8_t addr_msb_first[6], uint8_t addr_type, uint64_t *key_out);
void btrx_bearing_short_id(uint64_t key, char out[8]);    /* 6 chars + NUL */
enum {
    BTRX_BEARING_SRC_IGNORED = 1u << 0,  /* own aircraft: never accumulated */
    BTRX_BEARING_SRC_MUTED = 1u << 1,    /* hidden on the map; still accumulated */
};
/* Sets or clears `mask` bits to `value` for each key (panel rows, "Mute all"). */
btrx_bearing_status_t btrx_bearing_set_source_flags(btrx_bearing_t *h, const uint64_t *keys,
        size_t key_count, uint32_t mask, uint32_t value);

/* Class upgrade from boundary evidence, e.g. AUDIO -> HEARING_AID. */
btrx_bearing_status_t btrx_bearing_set_source_class(btrx_bearing_t *h, uint64_t key,
        btrx_bearing_class_t cls);

/* Fade settings (section 2.4): any thread, applied at the next tick.
 * total_s in [60, 1800], shape_p in [1, 4]. Shortening drops older buckets. */
btrx_bearing_status_t btrx_bearing_set_fade(btrx_bearing_t *h, float total_s, float shape_p);

/* Work */
btrx_bearing_status_t btrx_bearing_tick(btrx_bearing_t *h, int64_t now_us, bool *published);
btrx_bearing_status_t btrx_bearing_start_worker(btrx_bearing_t *h);  /* needs cfg.now_us */
btrx_bearing_status_t btrx_bearing_stop_worker(btrx_bearing_t *h);

enum { BTRX_BEARING_RESET_ALL = 0, BTRX_BEARING_RESET_SOURCE = 1, BTRX_BEARING_RESET_CLOCK = 2 };
btrx_bearing_status_t btrx_bearing_reset(btrx_bearing_t *h, int what, uint64_t key);

/* Snapshot for rendering (one reader thread) */
typedef struct {
    bool valid, ambiguous;
    double lat_deg, lon_deg;
    float axis_major_1s_m, axis_minor_1s_m, axis_major_deg; /* 2σ = 2x */
    double second_lat_deg, second_lon_deg;
    float second_mass;
} btrx_bearing_estimate_t;

typedef struct {                     /* latest-ray outline, section 5.1 */
    bool valid;
    double apex_lat_deg, apex_lon_deg;
    float centre_true_deg, half_width_deg, length_m;
    float mirror_true_deg;           /* NaN when the mirror lobe is not significant */
    int64_t t_us;                    /* report time; the outline fades over 5 s from here */
} btrx_bearing_ray_t;

typedef struct {
    uint64_t key;                    /* address key, or provisional (pod session, src_id) */
    bool provisional;                /* "unresolved": seen only as an extra so far */
    char short_id[8];
    btrx_bearing_class_t dev_class;
    uint8_t rgb[3];
    uint32_t flags;                  /* IGNORED, MUTED */
    int64_t first_heard_us, last_heard_us;
    uint32_t reports_in, reports_used, reports_in_window;
    float effective_reports;
    float peak_alpha;
    const uint8_t *alpha;            /* width*height, row 0 = north; muted sources too */
    btrx_bearing_estimate_t estimate;   /* drawn only when not muted */
    btrx_bearing_ray_t latest_ray;      /* drawn only when not muted */
} btrx_bearing_source_t;

typedef enum {
    BTRX_BEARING_STAGE_POD_TO_ARRIVAL = 0,  /* T2 - T0 (LoRa + Desk Pod + BLE) */
    BTRX_BEARING_STAGE_ARRIVAL_TO_PUSH,     /* T3 - T2 */
    BTRX_BEARING_STAGE_POSE_WAIT_YAW,       /* T4 - T3, waited for yaw */
    BTRX_BEARING_STAGE_POSE_WAIT_POS,       /* T4 - T3, waited for position */
    BTRX_BEARING_STAGE_TICK_WAIT,           /* T5 - T4 */
    BTRX_BEARING_STAGE_PUBLISH_WAIT,        /* T6 - T5 */
    BTRX_BEARING_STAGE_RENDER,              /* T7 - T6 */
    BTRX_BEARING_STAGE_TOTAL,               /* T7 - T0 */
    BTRX_BEARING_STAGE_YAW_INPUT,           /* yaw push - yaw stamp */
    BTRX_BEARING_STAGE_POS_INPUT,           /* position push - RID fix time */
    BTRX_BEARING_STAGE_COUNT
} btrx_bearing_stage_t;

typedef struct {
    uint32_t count;
    int32_t p50_ms, p90_ms, p99_ms, max_ms;
    uint32_t late_drops;             /* reports that crossed the budget in this stage */
} btrx_bearing_lag_t;

typedef struct {
    uint32_t reports_in, entries_in, used, gated, no_heading, no_position,
             late_dropped, ring_dropped, buckets_expired_early, ignored,
             backlog_history, steer_truncated, extras_unresolved, provisional_merges;
    bool desk_queue_lost_known;      /* false until a 34-byte Desk Pod HEALTH arrives */
    uint32_t desk_queue_lost;        /* Desk Pod phone_lost, summed across its restarts */
    uint32_t desk_queue_lost_since_connect;
    uint32_t pod_a_queue_lost;       /* pod A phone_lost, normally 0 */
    uint32_t tiles_in_use, tiles_total; /* pool use */
    float fade_total_s, fade_shape_p;   /* settings in effect */
    int32_t pod_offset_ms;           /* host - pod */
    int32_t pod_offset_err_ms;       /* RTT/2, or -1 for the arrival fallback */
    uint8_t offset_source;           /* 0 none, 1 arrival minimum, 2 TIME_PONG */
    uint32_t pod_session;
    int32_t display_lag_ms;          /* now - newest accumulated report time */
    btrx_bearing_lag_t lag[BTRX_BEARING_STAGE_COUNT];  /* last lag_history reports */
} btrx_bearing_stats_t;

typedef struct {
    uint64_t seq;                    /* increments on every publish */
    int64_t t_us;
    int32_t width, height;           /* cells = pixels */
    double north_deg, south_deg, east_deg, west_deg;
    const uint8_t *rgba;             /* width*height*4, premultiplied, row 0 = north; unmuted only */
    int32_t source_count;
    btrx_bearing_source_t sources[BTRX_BEARING_MAX_SOURCES];
    btrx_bearing_stats_t stats;
} btrx_bearing_snapshot_t;

/* Valid until release; acquire again only after release. */
const btrx_bearing_snapshot_t *btrx_bearing_snapshot_acquire(btrx_bearing_t *h);
void btrx_bearing_snapshot_release(btrx_bearing_t *h, const btrx_bearing_snapshot_t *s);

/* Tap query (section 5.2): sources with visible shading (muted included)
 * within radius_m of the point, by contribution, highest first.
 * Returns the number written to out (<= max_out). Pure; reader thread. */
typedef struct {
    int32_t source_index;            /* into snapshot->sources */
    float contribution;              /* sum of alpha over cells in the radius */
    float max_alpha;
} btrx_bearing_hit_t;
int32_t btrx_bearing_snapshot_sources_at(const btrx_bearing_snapshot_t *s,
        double lat_deg, double lon_deg, float radius_m,
        btrx_bearing_hit_t *out, int32_t max_out);

/* Tests and debugging: one source's faded E or S grid as float (row 0 = north). */
enum { BTRX_BEARING_GRID_EVIDENCE = 0, BTRX_BEARING_GRID_SUPPORT = 1 };
btrx_bearing_status_t btrx_bearing_copy_grid(btrx_bearing_t *h, uint64_t key, int which,
        int64_t now_us, float *out, size_t out_count, int32_t *width, int32_t *height);

#ifdef __cplusplus
}
#endif
#endif
```

`copy_grid` takes the work lock and is for tests and debug views only,
never the UI thread.

### 6.4 Source files

```
apple/Sources/CBtrxBearing/
  include/btrx_bearing.h     public API (the only header Swift/JNI see)
  bb_internal.h              private structs
  btrx_bearing.c             handle, config, input rings, tick, worker
  bb_clock.c                 pod offset (min-RTT, arrival fallback), sessions
  bb_pose.c                  pose rings, interpolation, gates
  bb_pattern.c               v1 pattern table, measured-table loader
  bb_grid.c                  likelihood, footprint add into bucket tiles
  bb_tiles.c                 tile pool, age buckets, expiry, fade weights, composite
  bb_diversity.c             30 s ring of recent entries (diversity discount)
  bb_lag.c                   stage stamps, histograms, BBLAG/BBRND lines
  bb_estimate.c              posterior region, mean, ellipse, second peak
  bb_render.c                RGBA composite, alpha rasters, latest ray, triple buffer,
                             sources_at
  bb_siphash.c               SipHash-2-4, short ID
  bb_steer.c                 STEER parser (27/28/34/40 bytes), src_id table, provisional merge
  README.md                  rules from 6.1; excluded from the SwiftPM target
```

## 7. Platform boundary layers (thin, matching)

Each side does the same five jobs and nothing else: **create/destroy** with
the switch, **stamp and push** inputs, **drive tick** on its BTRX worker,
**turn a snapshot into a map overlay**, and **log inputs** for replay.

| Job | Android | iPad |
| --- | --- | --- |
| Native binding | `app/src/main/cpp/btrx_bearing_jni.c` (JNI, `jlong` handle, flat primitive arguments; snapshot copied into a direct `ByteBuffer` and a small `IntArray`/`DoubleArray` for metadata) | none: SwiftPM C target `CBtrxBearing`, `import CBtrxBearing` in Swift |
| Wrapper class | `btrx/BtrxBearingNative.kt` (`external fun`s, `System.loadLibrary("btrx_bearing")` in `runCatching` as in `MappingSurfaceNative`) and `btrx/BtrxBearingLayer.kt` | `R2CCore/Btrx/BtrxBearingLayer.swift` (owns the `OpaquePointer`, `deinit` destroys) |
| Switch | Settings > Developer Tools, off by default, never at launch | same |
| Fade settings (section 2.4) | BTRX settings in Developer Tools: "Fade time" (1–30 min, default 10) and a "Fade shape" slider (p 1–4, Linear to Accelerating, live "half strength at m:ss" readout), saved in the app config; `set_fade` on change | same controls, labels and defaults, saved in the iPad settings; `set_fade` on change |
| STEER, PONG, HEALTH | `BtrxClient.Listener.onFrame` (stamps T2 at the BLE/USB read) → raw payload to `btrx_bearing_parse_steer` (the shared C parser, section 4.1) → `push_report`; the existing `BtrxProtocol.parseSteer` stays for the stream overlay; PONG → `push_time_sync`; HEALTH (30 or 34 bytes) → `push_health` (role, uptime, `phone_lost` or unknown). **TIME_PING sent by the same client every 30 s plus a burst of 3 at start, restart and reconnect** (new work) | `BtrxClient` listener `onFrame` → same fields; same TIME_PING schedule (new work) |
| DJI yaw | the SEI path that fills `DjiCameraOrientation` (from `ffmpeg_bridge.c`, `native/R2CDJICameraTelemetry.h`) → `push_pose(YAW)` with `R2CDJIAircraftYawTrueNorthDegrees` | `R2CFFmpegSessionCopyLatestDJICameraTelemetry` (`R2CFFmpegMobile.c`, same header) → `push_pose(YAW)` |
| Position, AGL | RID track points + `DroneAltitudeCoordinator` → `push_pose(POSITION, AGL)` | RID track + the iPad altitude source → same |
| Own aircraft (ignore) | BTRX pane action → `set_source_flags(IGNORED)` | same |
| Map taps (section 5.2) | confirmed **single taps only**: an osmdroid `Overlay` overriding `onSingleTapConfirmed` only, placed below the `Marker` overlays and above the assignment `Polygon`/`Polyline` overlays and the `MapEventsOverlay`; it declines (`false`) if a visible `Marker` is hit or no source has shading there, else consumes (`true`) and opens the panel. Double taps, scroll, fling and pinch untouched | the BTRX check inside the existing `handleArtifactTap`, after the annotation hit test and before the `StyledPolygon`/`StyledPolyline` assignment hit test; acts only after a passive 2-tap recognizer fails. Double taps, pan and pinch untouched |
| Mute panel (section 5.2) | Compose card anchored at the tap point: swatch, checkbox, short ID, class, last heard, report count; "Mute all" / "Unmute all"; "Done" | SwiftUI card (popover style) with the same rows, order, labels and buttons |
| Tick | BTRX single-thread executor, background priority, every 200 ms | serial `DispatchQueue`, `.utility`, every 200 ms |
| Overlay | `SplitMapPane` (osmdroid): `Bitmap.copyPixelsFromBuffer` from the RGBA buffer, drawn as an osmdroid `GroundOverlay` on the snapshot bounds; ellipses as osmdroid `Polygon`s; the latest-ray outline (section 5.1) as stroke-only `Polyline`s with an alpha animation; labels as markers; muted sources skipped | `RIDTrackMapView` (`OperationalMKMapView`): `MKOverlay` with the snapshot bounds and an `MKOverlayRenderer` drawing a `CGImage` from the RGBA bytes; ellipses as `MKPolygon`; the latest-ray outline as stroke-only `MKPolyline` with an alpha animation; labels as annotations; muted sources skipped |
| UI update | on `seq` change only, at most 1 Hz (sooner after a mute change); bitmap built off the main thread, swapped on it; `note_rendered(seq)` after the swap | same |
| Input and lag log | one `BBv1` line per input, plus the library's `BBLAG`/`BBRND` lines (section 3.5), in the session log; Developer Tools only | same |

Both wrappers expose the same Kotlin/Swift surface:
`start(incidentKey)`, `stop()`, `onSteer`, `onPong`, `onHealth`, `onYaw`,
`onPosition`, `setSourceFlags(keys, muted)`, `setFade(totalS, shapeP)`,
`sourcesAt(lat, lon, radiusM)`,
`noteRendered(seq)`, `reset`, and a `snapshot` flow / publisher.
Constants (the config defaults) live only in C (`btrx_bearing_config_default`);
the wrappers do not repeat them.

## 8. How it fits the existing C build

The repo already has both patterns; this library follows **CAOL**, the one
built for code shared by Android JNI and SwiftPM:

- **iPad / macOS.** SwiftPM requires target sources inside the package root
  (`apple/`), so the library lives in `apple/Sources/CBtrxBearing/`, exactly
  as `apple/Sources/CAOL/` does. In `apple/Package.swift`:
  `.target(name: "CBtrxBearing", exclude: ["README.md"], publicHeadersPath: "include", cSettings: [.unsafeFlags(["-ffp-contract=off"])])`
  and `R2CCore` gets `dependencies: ["CAOL", "CBtrxBearing"]`. SwiftPM makes
  the module map from `include/`, so **no bridging-header change**
  (`apple/App/RID2CaltopoApple-Bridging-Header.h` stays for the anomaly and
  FFmpeg xcframeworks) and **no new xcframework or build script**. The app
  target already links `R2CCore` (`project.pbxproj`, "R2CCore in
  Frameworks"). `swift test` on the Mac mini builds the C natively for host
  tests. (If `unsafeFlags` is refused for a local package dependency, use the
  pragma `#pragma STDC FP_CONTRACT OFF` in each `.c` instead.)
- **Android.** `app/src/main/cpp/CMakeLists.txt` already reaches CAOL with
  `set(AOL_SOURCE_ROOT "${CMAKE_SOURCE_DIR}/../../../../apple/Sources/CAOL")`
  and builds `aol_prepare` from it plus `aol_jni.cpp`. Same here:
  `set(BTRX_BEARING_ROOT ".../apple/Sources/CBtrxBearing")`,
  `add_library(btrx_bearing SHARED btrx_bearing_jni.c ${BTRX_BEARING_ROOT}/btrx_bearing.c ...)`
  with each `.c` of section 6.4 listed by name (as for `ffmpeg_bridge`),
  `target_include_directories(... "${BTRX_BEARING_ROOT}/include")`,
  `target_compile_options(... -std=c11 -O2 -ffp-contract=off)`, and
  `target_link_libraries(... m)`. Bionic has pthreads in libc, so no
  `-lpthread`. A separate `.so`, like `aol_prepare`, keeps it out of
  `ffmpeg_bridge` and its FFmpeg linkage. arm64-v8a only and the 16 KB page
  linker flag apply as for the other libraries (`app/build.gradle`).
- **Not the anomaly pattern.** The anomaly core lives in
  `app/src/main/cpp/` and reaches the iPad through
  `apple/Native/AnomalyCore/build-xcframework.sh` →
  `R2CAnomalyApple.xcframework` → the bridging header. That needs a build
  script, a binary artifact, the `release-check.sh` symbol checks and the
  `tools/licenses` relink lists. CAOL's route needs none of that.
- **Shared header use.** The library does not include
  `native/R2CDJICameraTelemetry.h`: the boundary converts yaw with
  `R2CDJIAircraftYawTrueNorthDegrees` before `push_pose`, so the library
  stays independent of the SEI decoder.
- **Checks to add:** the host test in `apple/release-check.sh` next to the
  existing `native/tests` builds, and the new `libbtrx_bearing.so` in
  `tools/licenses/native-runtime-inputs.json` if that list covers every
  Android `.so` (to confirm when coding).

## 9. Testing

### 9.1 C unit tests (host)

`tools/btrx_bearing_test/` (CMake, like `tools/anomaly_test/`), built with
`-std=c11 -Wall -Wextra -Werror -ffp-contract=off` and run on the Mac mini
and in `apple/release-check.sh`:

1. **SipHash** reference vectors; the short ID is stable; different incident
   keys give different IDs.
2. **Clock:** min-RTT selection; the arrival fallback; restart detection
   (window_end_ms backward, offset jump, uptime drop).
3. **Pose:** interpolation across 0/360° and ±180° (the yaw-test wrap
   cases); gaps; extrapolation limit; the yaw rate and change gates.
4. **Lag:** reports wait for pose; a report past `max_report_lag_ms` is
   dropped and counted; pending-ring overflow drops the oldest.
5. **Model:** the v1 table's signs (source to port → Δ_pred > 0; Δ_pred ≈ 0
   on the nose and tail); the censored likelihood; the clamp at ln p_far.
6. **Accumulation:** one report keeps α under 0.1 everywhere; the view
   re-centres by whole tiles and cells without tiles have the far value; the
   diversity discount for repeated geometry.
   **Fade:** for p = 1, 2 and 4 a single report's weight follows
   1 − (a/N)^p within the bucket tolerance (age ± `bucket_s`/2) and is
   exactly 0 after N + `bucket_s`, at N = 1, 10 and 30 min (bucket width 6,
   60 and 60 s); a change from N = 30 to 1 min and back keeps each bucket's
   own width; when every bucket has expired the pool is full again (no
   leaked tiles); `set_fade` shortening drops old buckets at the next tick,
   lengthening keeps held ones, changing p drops nothing; pool exhaustion
   expires the oldest bucket early and counts it; tile sums are identical
   for any input order within a bucket.
   **Mute:** a muted source is absent from the RGBA composite and its estimate
   and ray are flagged hidden, while its grids keep changing; unmute gives the
   same snapshot as never muting.
   **Tap query:** `sources_at` ordering by contribution, radius clipping at
   the grid edge, muted sources included, empty result off the shading.
   **Lag:** stage stamps and percentiles against a scripted timeline; late
   drops counted against the right stage; `BBLAG`/`BBRND` line format.
   **Multi-source:** a 3-entry report feeds three grids with one pose; a
   1-entry report matches today's single-source path bit for bit.
   **STEER parser vectors** (shared with the Kotlin/Swift tests): 27 bytes
   (old firmware); 28 bytes with count 0; 34 and 40 bytes; format 0 with a
   count; count 3 (invalid → first 27 only); a 30-byte payload with count 1
   (record under 6 bytes → first 27 only); a future format with 8-byte
   records (first 6 read); hits nibbles at 0, 1, 15; `rflags` class bits;
   bit 0 clear with count 0. BT RX's `r2c_sim` encoder output is the source
   of truth for the bytes.
   **Source resolution:** an extra with an unknown `src_id` makes a
   provisional source; the same id later as primary merges it exactly (tile
   sums add, colour of the address source kept); a pod restart clears the
   id table; a linked address change adds an alias.
   **Backlog:** the 32 newest queued frames (oldest discarded by the Desk
   Pod) after a reconnect: those within 15 s with poses are used, older ones
   get `history` and stay out of the lag histograms; `push_health` lost totals (difference, restart, saturation, 30-byte unknown)
   and the since-connect increase reach the stats.
   **Clock sync:** the TIME_PING schedule (every 30 s, burst of 3 at start,
   restart and reconnect) on both platforms' boundary tests.
7. **Threading:** pushes from 3 threads while ticking and acquiring for 10 s
   under ThreadSanitizer (`-fsanitize=thread`, host only); no `malloc` after
   create (an interposed counting allocator).
8. **ABI:** a wrong `struct_size` or `abi_version` fails create.

### 9.2 Replay of recorded logs

- **Input log `BBv1`:** the boundary writes one text line per input, keys
  only, no addresses:
  `BBv1 P <t_us> <mask> <lat> <lon> <agl> <yaw> <pitch> <roll>`,
  `BBv1 R <arrival_us> <window_end_ms> <seq> <flags> <roll_x2> <pitch_x2> <backlog> <ext_format> <truncated> <n>`
  followed by n entries `<src_id> <addr_key16hex|0> <port> <stbd> <peak> <hp> <hs> <age_ms> <new> <linked> <pod_class> <company>`
  (the parsed report, so replay needs no raw payload and no address),
  `BBv1 S <t1> <t2> <t3> <t4>`, `BBv1 H <t_us> <role> <uptime_s> <phone_lost|unknown>`,
  `BBv1 M <t_us> <key16hex> <muted>` (mute changes),
  `BBv1 F <t_us> <total_s> <shape_p>` (fade setting changes).
- **`btrx_bearing_replay`** (host tool, same directory) feeds a log through
  the library in own-worker-free mode (it calls `tick` with the logged
  times) and writes the snapshots as PNG plus a CSV of estimates and stats.
- **Converter for today's logs:** `tools/btrx_bearing_test/convert_yawtest.py`
  turns `~/btrx-build-logs/yawtest-2021-Log_201714-card.txt` (STEER lines:
  `window_end_ms`, rssi, hits, flags, `[..27CA]` tails, arrival wall time) and
  `yawtest-2021-sei-fields.csv` (`camera_bearing_true_deg` per frame) into
  `BBv1`, using wall time as the host clock and a fixed indoor position. The
  pod sat still indoors, so this checks timing, yaw sync, wraps and gates, not
  geometry. The 1,840 STEER frames of the 18:50 and 19:11 A5 sessions check
  timing and backlog handling the same way.
- **Golden outputs:** checked in small (`vectors/*.bbin` plus expected
  `*.json`: per-source estimate, counts, and the top 20 cells of E and S).

### 9.3 Synthetic scenarios

Generated from the pattern table with 5.5 dB Gaussian per-sample noise,
hits drawn per class (phone 5, DJI 40 per patch per window), and optional
AR(1) correlation:

1. Hover-and-yaw ±45° at 6°/s at one spot: the S ridge points at the source
   within 2σ of section 2.6; the mirror lobe stays below 25% mass once the
   yaw span passes 90°.
2. Two hover spots on adjacent segment edges (about 90°), then about 30°:
   the 1σ/2σ ellipses contain the true point at about 39%/86% over 1,000
   runs.
3. Straight transects, an orbit and a lawnmower (`FIELD-TEST-plan.md` (b)–(d)):
   the estimate is within 30 m **[A]** after two crossing legs, with no mirror
   solution left.
4. Turns above 10°/s are gated; one-patch windows count only with bit 4 set.
5. Moving source at 1 m/s: the estimate trails it, and the doc's caveat
   holds (documented, not a pass/fail). Muting it hides the smear; unmuting
   restores it.
6. Two sources alternating as the strongest: separate colours, no merging.
   The same with three entries per report (section 4.1).
7. **Ten-minute check:** for the techniques in scenarios 1–3, the time to
   the first valid estimate is well under 10 min. If a technique needs more,
   flag it: the fade window, or the design, needs revisiting (section 2.4).
8. Delayed inputs: reports and poses delayed 0–15 s with jitter are all used;
   over 15 s they are dropped and counted in the right stage.

### 9.4 Platform suites

The same `vectors/` run in all three places with one tolerance
(positions 1 m, α 0.005, E 1e-3 relative):

- **Host:** `tools/btrx_bearing_test` (above).
- **iPad/macOS:** `apple/Tests/R2CCoreTests/BtrxBearingLayerTests.swift`
  (`swift test`) through the Swift wrapper.
- **Android:** `app/src/androidTest/.../BtrxBearingNativeTest.kt` through
  JNI on the device (needs an install, so only with Ken's OK), plus JVM unit
  tests in `app/src/test` for the Kotlin feed and frame mapping with a fake
  native layer. `docs/PLATFORM_PARITY_LEDGER.md` gets an entry when coded.
- **Tap routing (UI tests, both platforms):** with the overlay on, a single
  tap on a drone marker, waypoint or clue marker over shading selects that
  item, not the panel; a single tap on shading inside an assignment polygon
  or on a route line opens the panel and does not select the assignment; a
  single tap off shading selects the assignment as before; a double tap
  anywhere (on or off shading) zooms and never opens the panel; pan and pinch
  behave as with the overlay off. The panel's rows,
  order and labels match between the two platforms for the same vector.

## 10. Performance and priority

- Push: one struct copy under a mutex, well under 1 µs.
- Per source entry: about 5,000 cells × (atan2, table look-up, log1p, exp),
  once, about 0.2–0.5 ms on the A5 **[A]**. At 1 Hz with 3 entries per
  report, about 1–1.5 ms per second.
- Publish: live tiles × 256 cells weighted per bucket, then α, estimate and
  composite over the 192² view, about 2–4 ms **[A]**, at most 1 Hz plus one
  after each mute or fade change.
- Expiry: returning a bucket's tiles to the pool, no recomputation.
- Estimate: one pass over the posterior per source per publish.
- Latest ray: 360 evaluations per accepted entry.
- `sources_at`: at most a few thousand byte reads on the UI thread per tap.
- Lag stats: a few stamps per report; percentiles over 600 samples per stage
  at publish.
- Memory: about 6 MB at the defaults (8 MB pool advised for N over
  20 min), all at create (section 6.1).
- Nothing runs on the UI thread except acquire/release, `sources_at`,
  `set_source_flags` and showing the bitmap. The stream server, pod link,
  overlay and live views keep priority; if the worker falls behind, reports
  age past the lag budget and are dropped (and the lag stats show where),
  never queued without limit.

## 11. Open questions

Answered by Ken (November 13, morning), r2 questions 1–5:

1. **Fade:** 10 min, linear, 50% at 5 min (section 2.4); if 10 min isn't
   enough to locate a source, the design needs revisiting. Plus the
   tap-to-mute panel with first refusal on taps (section 5.2). 9:18 AM
   addition: total fade time and fade shape (Linear to accelerating) are
   user settings, changeable while running.
2. **Lag:** keep 10–15 s of telemetry, measure and debug the slow path
   (sections 3.3–3.5). Late budget 15 s, so this settles the backlog
   question.
3. **Moving subjects:** one fade rate for all; reconsider only if moving
   sources can be detected reliably (section 2.9).
4. **Firmware:** yes to the top 2–3 sources per STEER. BT RX defined the
   frame (btrx `684cfa5`, build 0x0103, not flashed); section 4.1.
5. **Latest ray:** yes, as a cheap outline over the shading (section 5.1).

Answered by Ken (9:41 AM PT), the four r3 questions:

1. **Double tap:** not intercepted; it zooms the map (section 5.2).
2. **Map items:** drone markers and waypoints win over the overlay, and the
   overlay wins over area/line assignment selection (section 5.2). Clue
   markers are treated like waypoints **[A]**.
3. **Panel:** rows only show or hide shading; no steering mute or steering
   recommendations anywhere in the map layer.
4. **Fade controls:** fade time 1–30 min; fade shape as a slider (section
   2.4).

New, for Ken:

- **Clue markers:** OK to treat them like waypoints (they win over the
  overlay)?

Firmware decisions (Ken, relayed by BT RX, November 13):

- **Desk Pod receive timestamp:** never planned. Clock sync is TIME_PING/
  TIME_PONG with the Drone Pod; only `window_end_ms` mapped to tablet time
  matters (section 3.2).
- **Desk Pod queue full:** the oldest message is discarded and the newest
  32 kept; HEALTH (0x80) `phone_lost` at offset 30 carries the count (build
  0x0104, btrx `e116ac6`, not flashed; section 3.4). Backlog frames are still judged by window time
  (over 15 s old = history).
- **`src_id` in DETECT records:** **undecided.** The spec keeps the
  "unresolved" provisional sources (section 4.1).

For BT RX:

5. **Extras' addresses:** until `src_id` in DETECT is decided, extras
   resolve only after the same `src_id` appears as primary.

## 12. Implementation notes and deviations (November 13, 2026)

Where the code (`e23986fd`, `9970eebe`, `53593f83`) differs from or adds to
the text above. Each is the simplest option faithful to the spec's intent.

C library (`apple/Sources/CBtrxBearing`, also built for Android by CMake):

- `max_yaw_change_deg` default **10°**, not 5° (section 2.8): at 6°/s over a
  1 s window the yaw changes 6°, so 5° would discard every hover-yaw report.
- Added to the API: `push_us` on reports (lag stage T2), `note_connected`,
  `parse_health`, `snapshot_copy` (a flat copy the UI thread can query),
  `bucket_width_s`, `half_strength_s` (the slider readout) and
  `pattern_delta_db` (tests).
- Uses `stdio.h` (`vsnprintf`, log lines) and `time.h` (`timespec_get`, the
  self-test clock) beyond the section 6.1 list; no allocation outside create.
- Floating-point contraction is disabled by `#pragma STDC FP_CONTRACT OFF` in
  the sources (and `-ffp-contract=off` on Android), not by SwiftPM
  `unsafeFlags`, so the package stays usable as a dependency.
- Pod restart is detected only on unflagged (non-backlog) frames.
- Pose wait uses stream liveness: a report waits for yaw at most 2.5 s and for
  position at most 1.5 s past its window, then is dropped as "no heading" /
  "no position".
- "Ignore (own aircraft)" frees that source's buckets at once; the v1 table
  mirror used for the replay check is approximate.
- Host tests use a counting allocator (no allocation after create) and a
  1.5 s threading test. `THIRD_PARTY_LICENSES` / licenses JSON is unchanged:
  the library is our own code and the JSON lists only prebuilt Apple static
  libraries.

Platform boundary (section 7), same on Android and iPad:

- One custom overlay draws raster, latest-ray outline (5 s fade, dashed
  mirror), estimate dot, 1σ/2σ ellipses and labels, instead of separate
  ground-overlay, polygon, polyline and marker objects. Android: osmdroid
  `Overlay` before the local markers. iPad: one world-sized `MKOverlay` kept
  last at `.aboveLabels` (above assignments and lines, below every marker
  view).
- TIME_PING burst: 3 pings spaced 1 s apart (not back to back) at start,
  reconnect and pod-session change, then every 30 s; a ping that cannot be sent
  is not retried early.
- Arrival time (T1) comes from the radio meta's wall-clock `arrivalMs`, mapped
  to the host monotonic clock as `now - clamp(wall_now - stamp, 0, 2 s)`;
  yaw 2 s, position 3 s the same way.
- The lost count and lag lines show in "Link health" only while the layer is
  on (the snapshot carries them).
- Changing "Lag trace log" restarts the layer (trace is a create option).
- Fade time / shape slider ranges (1–30 min, p 1–4) are duplicated in Kotlin
  and Swift; the library clamps to its own limits.
- The "BTRX sources here" panel opens at the tap point without clamping to the
  screen edge.
- Inset maps draw the overlay but take no taps.
- Double tap: iPad uses a passive double-tap recognizer the BTRX single tap
  waits on (map zoom unaffected); Android uses osmdroid's confirmed single tap.
- Position feed: Android uses its own `CaltopoLiveTrack.LocalTrackListener`
  (fix timestamp) and AGL from `DroneAltitudeCoordinator`, which updates only
  while the map or a stream consumes it. iPad feeds from ContentView on each
  `RIDTrackViewModel.tracks` change, picking the aircraft bound to the chosen
  stream (`boundAircraftID(for:)`, or aircraft ID equal to the stream ID), with
  the point's receive time and the display AGL when not stale. Without AGL the
  library default (30 m) applies.
- Class 3 ("audio" in the DETECT class list) shows as "audio".
- The layer switch is not saved (off at every launch); fade settings and lag
  trace are saved (Android prefs `bearing_*`, iPad `btrx.bearing.*`).

Field test fixes (November 13, 2026, evening):
- Pose stream: with exactly one live stream, that stream carries the Drone Pod
  (`BtrxSteeringTarget.effective`); the "Drone Pod is on this drone" pick is
  needed only when several streams are live. Before this, one live stream left
  the layer with no designator, so every yaw and position was rejected.
- `btrx_bearing_stats_copy()`: the counters are copied out at least once per
  publish interval even when no snapshot is published (no pose yet), so the
  apps can say why nothing is drawn.
- Map status chip (top centre, main map only, not tappable) while the layer is
  on and draws nothing: first reason of no reports / no drone video / pick the
  stream / no SEI heading / no RID position / all hidden / nothing placed /
  too faint, plus the report counts. Same text on both apps.
- Double tap on the main map opens the Desk Pod view while the Desk Pod link is
  ready and a frame arrived in the last 10 s; otherwise the double tap zooms as
  before. Other tap rules unchanged.
- Android logs the bearing layer at debug (info lines are dropped at the
  default log level).
- A drone on the ground (AGL 0) is placed: the library clamps AGL to 1 m.

Bench checks still open (need builds 0x0103 / 0x0104 flashed): 34-byte HEALTH
lost count, TIME_PONG offset, STEER extras, hover-yaw ridge on real data, tap
routing, mute/unmute, fade sliders, lag trace through `lag_report.py`.

## Nov 16 watch test changes (ABI 2)

From the 14:17 PT watch test (Garmin on the ground, phone on the drone) and the BT RX
steering-report notes (btrx d74aaec). Works fully on Drone Pod 0x0109; nothing depends on 0x010A.

- **History kept.** Up to 24 sources (max 32). Eviction drops the least evidence (fade-weighted
  reports, +1 if heard in 30 s; focused > with estimate > others), not the least recent. An
  extra is tracked only after 2 sightings within 60 s. Colours come from a 12-colour palette,
  remembered per source key, so a returning source keeps its colour.
- **Launch zone (Nov 23, Timberland flight).** Searchers around the takeoff point only appear
  as the drone lifts (XY_Rdy -58 dBm at 6 m AGL and ZA-hl- -60 dBm at 12 m AGL, both 2 m from
  takeoff, 41-44 s after it). A source heard -70 dBm or stronger within 30 m of the takeoff
  point, on the ground or through the initial climb up to 40 m AGL, in the first 300 s, is
  flagged and muted (`BTRX_BEARING_LAUNCH_*`, `launch_zone_max_agl_m`). The climb ends the
  first time the drone leaves that zone; after that only reports at 5 m AGL or lower count
  (Nov 20: the watch 16 m N of takeoff, heard on the way back at 10 m AGL, stays unmuted). The
  Nov 23 replay mutes 8 (was 4), not the hearing aids (-88/-92 dBm, 300 m out) or the iPhone;
  the Nov 20 replay mutes 7 (was 3), including the laptop on the back deck.
- **Staleness (Nov 23, Timberland flight).** Presentation only: a source not heard for 25 s
  fades from the RGBA, estimates and labels, linearly to nothing at 60 s, and is off the map and
  out of the tap panel from then on (the panel's "Show N older" lists it). A focused source not
  heard for 60 s loses focus (`BBFOCUS ... cleared`), so it no longer holds its slot against
  newcomers. Evidence, estimates and the snapshot's source list are unchanged (replay, logs).
  Engine: `stale_fade_start_s` / `stale_hide_s` (default 0 = off; the apps set
  `BTRX_BEARING_STALE_FADE_START_S` 25 / `_HIDE_S` 60). A source first heard within 10 s is
  tagged "new" and its label ranks with the focus matches. The tap panel sorts new, fresh,
  fading, stale, then latest RSSI less 0.5 dB per second unheard, re-sorting at most every 12 s
  and never under a finger (test-fixtures/btrx/staleness.tsv, both platforms).
- **On-drone suggestion.** Stronger than -40 dBm (reset below -46 dBm or after a 10 s gap)
  for 60 s while the drone moved 30 m sets `SRC_OWN_SUSPECT`: no estimate is drawn and the map
  asks "Own aircraft (saved)" / "Not on the drone" (`SRC_OWN_CLEARED`). Never a mute by itself.
- **STEER bit 3 is advice.** Reports are no longer dropped on bit 3 (0x0109: roll > 10 deg or
  pitch > 15 deg). Roll from bytes 25/26 (0.5 deg) corrects port - stbd by k x roll (k 0.10 up to
  30 deg off nadir, 0.13 at 45, 0.22 straight below); weight 1 at 10 deg falling to 0.25 at 45
  deg; over 45 deg gated. Pitch ignored. IMU not OK: half weight. `gate_on_attitude_flag`
  restores the old behaviour.
- **Averaging.** Each source's report is averaged with up to 2 earlier windows within 2.6 s
  (hit-weighted) before the bearing likelihood.
- **Close range.** Wedge length from the RSSI range (-38 dBm at 1 m, exponent 2.2, x3 margin,
  30-400 m); ambiguous lobes (within 0.5 nats) are drawn alike, centre on the in-front lobe.
  The coarse footprint stays 400 m. Labels sit inside the shading (cells >= 90 % of peak).
- **Fine layer.** 2.5 m cells over 400 m, 100 m footprint, when AGL <= 30 m and speed <= 4 m/s
  or the report is >= -65 dBm; composited with the same colours and focus.
- **Focus this source.** Others at 25 % alpha; the snapshot carries the focused source's strength
  trail (drone positions with RSSI) and "strongest here".
- **Pinned marker.** An estimate with 2 sigma <= 30 m is drawn as a ring-and-cross marker; tap
  opens an inspect card (ID/type, estimate, uncertainty, reports, last heard, peak RSSI, Focus).
- **Camera crosshair** (map only, not CalTopo): the optical-axis ground point by the same
  projection as the FOV outline, with distance and bearing to the inspected/focused source.
- **HEALTH 46 bytes** (0x010A): `scan_lost_per_s` u16 at 44, shown in Drone Pod health when
  present; 44-byte HEALTH stays valid.
