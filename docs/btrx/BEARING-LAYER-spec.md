# BTRX bearing layer: spec r2, continuous (milestone 1 of the map layer, draft, November 13, 2026)

Status: spec only. **Map-layer code is on hold.** r2 replaces the r1
hover-station design (`8130f673`). There is **no station, no "Start station"
button and no enforced hover or pan.** While the layer is switched on, every
STEER report is matched to where the drone was and where its nose pointed at
that report's time, and it paints a faint wedge on the map. Areas where many
different reports agree get darker. This folds the former milestone 2 (the
grid of `MAP-LAYER-design.md` r3 section 5) into milestone 1.

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
  per 1 s window, for the **single strongest** source only (firmware may
  later send the top 2–3, r3 section 13 option A).
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
- **Fade:** 10 minutes to start (Ken). v1 uses a 10 min half-life **[A]**
  (open question 1).

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

Each source has a georeferenced grid: 10 m cells, 192 × 192 (1.92 km square)
**[A]**, north-up in a local east/north tangent plane anchored at the first
pose after the layer is switched on. When the drone comes within 300 m of an
edge, the grid shifts by whole cells (ring indexing, no copy). Two
accumulators per cell, both float:

- **Evidence E** (for the estimate). Each report adds κ_r · ℓ_r(cell) inside
  its footprint. Cells outside the footprint get the constant ln p_far
  (p_far = 0.05 **[A]**: a source heard at all is probably within detection
  range). That constant is kept as one per-source scalar, so a report touches
  only its footprint (about 5,000 cells), and cells that shift into the grid
  start with the correct "far from every report" value.
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

Display opacity for a cell is α = α_max · (1 − exp(−S / S0)), with
α_max = 0.7 and S0 = 8 **[A]**. One full-weight report gives at most
1 − e^(−1/8) ≈ 12% of α_max (about 0.08), a phone report about 4% (about 0.03). A cell needs
roughly 8 full reports from different geometry that agree to reach 63% of
α_max. One noisy report never paints a dark patch, and a dark patch always
means many reports agree.

### 2.4 Time decay

Both E and S, and the per-source far scalar, decay with the same half-life
T½ = 10 min **[A]**. The library uses a global scale: a report at time t adds
its value × 2^(t/T½), and readers divide by 2^(now/T½). When the scale
passes 1e6, every cell is rescaled once (O(cells), rare). Decay costs nothing
per cell per report.

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
  more than 5° within it **[A]**;
- bit 3 (gated) is set or bit 4 (IMU ok) is clear;
- there is no yaw sample within 0.5 s of the report time ("no heading");
- there is no position within 3 s, or the height above ground is unknown and
  no default is configured (default 30 m **[A]**, flagged in the stats);
- the source is on the ignore list (own aircraft, by full address) or muted.

A no-target STEER (bit 0 clear) is evidence that nothing was above the floor,
but v1 ignores it. A source missing from a window is not evidence, because
STEER reports only the strongest.

### 2.9 Moving subject caveat

The model assumes a **stationary source**. A walking subject at 1 m/s moves
600 m in one half-life. Old wedges then point at old positions: the patch
smears along the subject's path and the estimate lags behind the subject,
sitting between old and new positions. The fade lets the old trail lighten
rather than vanish. A shorter half-life for one source is a config value
(open question 3). The estimate's covariance growing over time with a
consistent drift is a hint that the source is moving. v1 only shows the
ellipse and does not model motion.

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

- **TIME_PING/TIME_PONG (preferred).** The boundary sends TIME_PING every
  30 s **[A]** and passes (t1 host send, t2 pod receive, t3 pod send, t4 host
  receive) to `push_time_sync()`. offset = ((t2 − t1) + (t3 − t4)) / 2,
  RTT = (t4 − t1) − (t3 − t2). The library keeps the sample with the
  smallest RTT over the last 2 min; its error is under RTT/2. Whether the
  Desk Pod relays the ping to the Drone Pod is still a BT RX bench check.
- **Fallback:** the minimum of (arrival − window_end_ms) over a sliding 2 min
  window (r3 section 6). Measured delay was 54–75 ms median, 0.23–0.39 s p99,
  so the fallback adds about 60 ms of bias (about 0.4 m at 6 m/s).
- **New pod session** (offset state reset) when window_end_ms goes backward,
  the arrival offset jumps by more than 2 s **[A]**, or HEALTH `uptime_s`
  drops (`push_pod_uptime()`).

### 3.3 Pose ring buffers

Two fixed rings, allocated at create:

| Ring | Rate | Capacity | Span |
| --- | --- | --- | --- |
| Yaw (and optional aircraft pitch/roll) | about 30 Hz | 2,048 | about 60 s |
| Position (lat, lon, height above ground) | about 1 Hz | 128 | about 2 min |

Interpolation at the report time: yaw by shortest-arc linear interpolation
(0/360 wrap), position linearly in the local tangent plane. The window's yaw
rate and change for the gate come from the yaw samples inside
[t_end − 1 s, t_end]. Gaps: no yaw within 0.5 s or no position within 3 s
makes the report unusable (section 2.8).

### 3.4 Lag budget and late reports

A report waits in a pending ring (capacity 32) until the pose rings cover its
window: a yaw sample at or after t_end, and a position sample at or after the
report time (or one within 1.5 s **[A]** before it, extrapolated with the last
velocity). This is the natural display lag Ken expects: usually under 1 s,
limited by the 1 Hz RID position.

A report older than the **lag budget**, 5 s **[A]** (`max_report_lag_ms`),
when it arrives or while it waits, is **dropped** and counted. That drops the
Desk Pod backlog frames (5–20 s late, r3 section 2) by default (open
question 2).

## 4. Sources, IDs, colours

- **Key:** the library hashes the full 6-byte address and its address type
  with SipHash-2-4 under a 16-byte **incident key** the boundary passes at
  create (random per incident, never saved). `btrx_bearing_source_key()`
  returns the 64-bit key. Inputs and logs carry only that key.
- **Short ID:** base64url of the top 36 bits, 6 characters (for example
  `Q3x9Kd`). Same on both platforms by construction.
- **Class:** the boundary passes phone / watch / hearing aid / tag / unknown,
  from its existing mapping (STEER `dev_class`, DETECT `class_hint`,
  `BtrxMakers`). The library keeps the most specific class seen.
- **Colour:** a fixed 8-colour colour-blind-safe palette (Okabe–Ito order:
  orange, sky blue, bluish green, yellow, blue, vermillion, reddish purple,
  black, all **[A]**), assigned in order of first report and kept for the
  source's lifetime.
- **Slots:** 8 sources **[A]**. A new source when all slots are full replaces
  the least recently heard source without a valid estimate (or, failing
  that, the least recently heard).
- **Own aircraft / mute:** `btrx_bearing_set_source_flags()` by key, from the
  existing BTRX pane actions. Ignored sources are never accumulated.

## 5. Rendering output

The library renders. The platforms only show its pixels and labels.

- **Composite raster:** one RGBA8 premultiplied image, one pixel per cell,
  north-up, with its north/south/east/west bounds in degrees. Per cell, each
  source's colour at its opacity α (section 2.3) is blended "over" in order
  of last heard (most recent on top). Cells with α < 0.02 are transparent.
- **Per source:** short ID, class, colour, last heard, report counts, and
  the estimate (point, 1σ/2σ ellipses as semi-axes in metres plus
  orientation, optional second peak) when one exists.
- **Latest wedge** per source (apex, centre bearing, half-width, length,
  front and mirror), for a brief outline so the user sees each report land.
  Optional in the UI.
- **Label:** `phone · Q3x9Kd · 23 rpt · ±18 m` (2σ long axis) or
  `phone · Q3x9Kd · 23 rpt` without an estimate, plus "ambiguous" when a
  second peak is shown.
- Published at most once a second, with a sequence number so the UI skips
  unchanged snapshots. No smoothing: each snapshot is the current state.

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
- **No allocation after create.** Rings, grids (8 × 192² × 2 floats, about
  2.4 MB), the pattern table (about 130 KB), the pending ring and three
  snapshot buffers (each about 150 KB of RGBA plus metadata) are allocated in
  `btrx_bearing_create()`. `push_*`, `tick`, `acquire` and `release` never
  call `malloc`.
- **Bounded work.** Push is O(1). Tick processes at most
  `max_reports_per_tick` reports (default 4), each about 5,000 footprint
  cells, plus at most one publish (192² cells × sources). The limits are
  counts, not time, so results stay deterministic.
- **Deterministic.** Same inputs in the same order give the same outputs.
  Both builds use `-std=c11 -O2 -ffp-contract=off` and no `-ffast-math`. libm
  differs between bionic and Apple in the last bits, so cross-platform tests
  compare with tolerances (section 9).
- **Errors:** every call returns a `btrx_bearing_status_t`; nothing aborts.
  A full input ring drops the **oldest** item and returns
  `BTRX_BEARING_W_DROPPED` (a warning, > 0), counted in the stats.

### 6.2 Thread-safety contract

| Call | Threads | Blocking |
| --- | --- | --- |
| `push_pose`, `push_report`, `push_time_sync`, `push_pod_uptime`, `set_source_flags`, `source_key` | any thread, concurrently | takes only the **input mutex**, for an O(1) copy; never waits for tick |
| `tick` | one thread at a time (a second concurrent caller gets `BTRX_BEARING_E_BUSY`) | drains inputs in a bounded batch under the input mutex, then works with no input lock held |
| `snapshot_acquire` / `snapshot_release` | **one** reader thread (the UI or render thread) | triple buffer; a pointer swap under a separate tiny mutex. Never waits for tick |
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

The UI thread only calls `push_*` (never needed there) and
`snapshot_acquire/release`. Neither can block on model work.

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
#define BTRX_BEARING_RSSI_NOT_HEARD (-128)

typedef struct btrx_bearing btrx_bearing_t;   /* opaque */

typedef enum {
    BTRX_BEARING_OK = 0,
    BTRX_BEARING_W_DROPPED = 1,      /* accepted; oldest item in a full ring dropped */
    BTRX_BEARING_E_INVALID = -1,     /* NULL, NaN, out-of-range field */
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
} btrx_bearing_class_t;

typedef int64_t (*btrx_bearing_now_us_fn)(void *ctx);           /* host monotonic */
typedef void (*btrx_bearing_log_fn)(void *ctx, int level, const char *msg); /* tick thread; must not block */

typedef struct {
    uint32_t struct_size;            /* sizeof(btrx_bearing_config_t) */
    uint32_t abi_version;            /* BTRX_BEARING_ABI_VERSION */
    /* grid */
    float cell_m;                    /* 10 */
    int32_t grid_cells;              /* 192 (square) */
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
    /* weights and display */
    float tempering;                 /* 0.5 */
    float info_ref_db;               /* 2 */
    float diversity_m, diversity_deg;/* 5, 5 */
    int32_t diversity_window_ms, diversity_cap; /* 30000, 9 */
    float half_life_s;               /* 600 */
    float alpha_max, support_s0;     /* 0.7, 8 */
    /* gates and timing */
    float max_yaw_rate_dps, max_yaw_change_deg; /* 10, 5 */
    int32_t max_yaw_gap_ms, max_pos_gap_ms;     /* 500, 3000 */
    int32_t max_pos_extrapolate_ms;  /* 1500 */
    int32_t max_report_lag_ms;       /* 5000 */
    int32_t yaw_latency_ms;          /* L_v, 0 until measured */
    float default_agl_m;             /* 30; NaN = unknown AGL drops the report */
    int32_t sync_window_ms;          /* 120000 */
    /* estimate */
    int32_t est_min_reports;         /* 10 */
    float est_min_yaw_span_deg, est_min_baseline_m; /* 60, 50 */
    float est_max_axis_2s_m;         /* 200 */
    float second_peak_mass;          /* 0.25 */
    /* work */
    int32_t max_reports_per_tick;    /* 4 */
    int32_t publish_interval_ms;     /* 1000 */
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
    uint32_t mask;
    double lat_deg, lon_deg;
    float agl_m;
    float yaw_true_deg;              /* R2CDJIAircraftYawTrueNorthDegrees(raw) */
    float pitch_deg, roll_deg;       /* + nose up, + starboard down */
} btrx_bearing_pose_t;

/* One STEER (0x73) as parsed by BtrxProtocol, addresses already keyed. */
typedef struct {
    int64_t arrival_us;              /* host arrival */
    uint32_t window_end_ms;          /* pod A millis() */
    uint16_t steer_seq;
    uint8_t flags;                   /* bit0 target .. bit6 imu cal */
    uint16_t age_ms;
    int8_t rssi_port, rssi_stbd, peak_rssi;  /* dB; -128 = not heard */
    uint8_t hits_port, hits_stbd;
    int8_t roll_x2, pitch_x2;        /* pod, at send time */
    uint64_t source_key;             /* btrx_bearing_source_key() */
    btrx_bearing_class_t dev_class;
    uint16_t company_id;
    uint8_t addr_type;
    bool backlog;                    /* BtrxClient marked it queued by the Desk Pod */
} btrx_bearing_report_t;

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
btrx_bearing_status_t btrx_bearing_push_pod_uptime(btrx_bearing_t *h, int64_t host_us, uint32_t uptime_s);

/* Sources */
btrx_bearing_status_t btrx_bearing_source_key(const btrx_bearing_t *h,
        const uint8_t addr_msb_first[6], uint8_t addr_type, uint64_t *key_out);
void btrx_bearing_short_id(uint64_t key, char out[8]);    /* 6 chars + NUL */
enum { BTRX_BEARING_SRC_IGNORED = 1u << 0, BTRX_BEARING_SRC_MUTED = 1u << 1 };
btrx_bearing_status_t btrx_bearing_set_source_flags(btrx_bearing_t *h, uint64_t key, uint32_t flags);

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

typedef struct {
    bool valid;
    double apex_lat_deg, apex_lon_deg;
    float centre_true_deg, half_width_deg, length_m;
    float mirror_true_deg;           /* NaN when no mirror lobe */
    int64_t t_us;
} btrx_bearing_wedge_t;

typedef struct {
    uint64_t key;
    char short_id[8];
    btrx_bearing_class_t dev_class;
    uint8_t rgb[3];
    uint32_t flags;
    int64_t first_heard_us, last_heard_us;
    uint32_t reports_in, reports_used;
    float effective_reports;
    float peak_alpha;
    btrx_bearing_estimate_t estimate;
    btrx_bearing_wedge_t latest_wedge;
} btrx_bearing_source_t;

typedef struct {
    uint32_t reports_in, used, gated, no_heading, no_position, late_dropped,
             ring_dropped, ignored;
    int32_t pod_offset_ms;           /* host - pod */
    int32_t pod_offset_err_ms;       /* RTT/2, or -1 for the arrival fallback */
    uint8_t offset_source;           /* 0 none, 1 arrival minimum, 2 TIME_PONG */
    uint32_t pod_session;
    int32_t display_lag_ms;          /* now - newest accumulated report time */
} btrx_bearing_stats_t;

typedef struct {
    uint64_t seq;                    /* increments on every publish */
    int64_t t_us;
    int32_t width, height;           /* cells = pixels */
    double north_deg, south_deg, east_deg, west_deg;
    const uint8_t *rgba;             /* width*height*4, premultiplied, row 0 = north */
    int32_t source_count;
    btrx_bearing_source_t sources[BTRX_BEARING_MAX_SOURCES];
    btrx_bearing_stats_t stats;
} btrx_bearing_snapshot_t;

/* Valid until release; acquire again only after release. */
const btrx_bearing_snapshot_t *btrx_bearing_snapshot_acquire(btrx_bearing_t *h);
void btrx_bearing_snapshot_release(btrx_bearing_t *h, const btrx_bearing_snapshot_t *s);

/* Tests and debugging: copy one source's E or S grid (row 0 = north). */
enum { BTRX_BEARING_GRID_EVIDENCE = 0, BTRX_BEARING_GRID_SUPPORT = 1 };
btrx_bearing_status_t btrx_bearing_copy_grid(btrx_bearing_t *h, uint64_t key, int which,
        float *out, size_t out_count, int32_t *width, int32_t *height);

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
  bb_grid.c                  likelihood, E/S accumulation, decay scale, recentre
  bb_estimate.c              posterior region, mean, ellipse, second peak
  bb_render.c                RGBA composite, wedges, snapshot triple buffer
  bb_siphash.c               SipHash-2-4, short ID
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
| STEER, PONG, HEALTH | `BtrxClient.Listener.onFrame` → `BtrxProtocol.parseSteer` → `push_report` with `source_key` from the address; PONG → `push_time_sync`; HEALTH → `push_pod_uptime`. TIME_PING sent every 30 s by the same client (new: `BtrxClient` can send the frame, but nothing sends it periodically today) | `BtrxClient` listener `onFrame` → same fields |
| DJI yaw | the SEI path that fills `DjiCameraOrientation` (from `ffmpeg_bridge.c`, `native/R2CDJICameraTelemetry.h`) → `push_pose(YAW)` with `R2CDJIAircraftYawTrueNorthDegrees` | `R2CFFmpegSessionCopyLatestDJICameraTelemetry` (`R2CFFmpegMobile.c`, same header) → `push_pose(YAW)` |
| Position, AGL | RID track points + `DroneAltitudeCoordinator` → `push_pose(POSITION, AGL)` | RID track + the iPad altitude source → same |
| Own aircraft, mute | BTRX pane actions → `set_source_flags` | same |
| Tick | BTRX single-thread executor, background priority, every 200 ms | serial `DispatchQueue`, `.utility`, every 200 ms |
| Overlay | `SplitMapPane` (osmdroid): `Bitmap.copyPixelsFromBuffer` from the RGBA buffer, drawn as an osmdroid `GroundOverlay` on the snapshot bounds; ellipses and wedges as osmdroid `Polygon`s; labels as markers | `RIDTrackMapView` (`OperationalMKMapView`): `MKOverlay` with the snapshot bounds and an `MKOverlayRenderer` drawing a `CGImage` from the RGBA bytes; ellipses/wedges as `MKPolygon`; labels as annotations |
| UI update | on `seq` change only, at most 1 Hz; bitmap built off the main thread, swapped on it | same |
| Input log | one `BBv1` line per input in the session log (section 9.2), Developer Tools only | same |

Both wrappers expose the same Kotlin/Swift surface:
`start(incidentKey)`, `stop()`, `onSteer`, `onPong`, `onHealth`, `onYaw`,
`onPosition`, `setSourceFlags`, `reset`, and a `snapshot` flow / publisher.
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
6. **Accumulation:** one report keeps α under 0.1 everywhere; decay halves
   S after 600 s; the rescale is invisible; a recentre keeps cells and the
   far value; the diversity discount for repeated geometry.
7. **Threading:** pushes from 3 threads while ticking and acquiring for 10 s
   under ThreadSanitizer (`-fsanitize=thread`, host only); no `malloc` after
   create (an interposed counting allocator).
8. **ABI:** a wrong `struct_size` or `abi_version` fails create.

### 9.2 Replay of recorded logs

- **Input log `BBv1`:** the boundary writes one text line per input, keys
  only, no addresses:
  `BBv1 P <t_us> <mask> <lat> <lon> <agl> <yaw> <pitch> <roll>`,
  `BBv1 R <arrival_us> <window_end_ms> <seq> <flags> <port> <stbd> <peak> <hp> <hs> <roll_x2> <pitch_x2> <key16hex> <class> <backlog>`,
  `BBv1 S <t1> <t2> <t3> <t4>`, `BBv1 U <t_us> <uptime_s>`.
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
   holds (documented, not a pass/fail).
6. Two sources alternating as the strongest: separate colours, no merging.

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

## 10. Performance and priority

- Push: one struct copy under a mutex, well under 1 µs.
- Per report: about 5,000 cells × (atan2, table look-up, log1p, exp), about
  0.2–0.5 ms on the A5 **[A]**. At 1 Hz that is under 0.1% of a core.
- Publish: 192² cells × up to 8 sources, about 1–3 ms **[A]**, at most 1 Hz.
- Estimate: one pass over the posterior per source per publish.
- Memory: about 3 MB, all at create.
- Nothing runs on the UI thread except acquire/release and showing the
  bitmap. The stream server, pod link, overlay and live views keep priority;
  if the worker falls behind, reports age past the lag budget and are dropped,
  never queued without limit.

## 11. Open questions for Ken

1. **Fade:** a 10 min half-life (a wedge is at half strength after 10 min,
   still faint after 30), or fully gone after 10 min?
2. **Late reports:** drop anything over 5 s late, including the Desk Pod's
   backlog frames from while R2C was away? Or keep backlog up to 30 s?
3. **Moving subjects:** keep one fade for all sources, or a shorter one for a
   source you mark as moving?
4. **Firmware:** worth asking BT RX for the top 2–3 sources per STEER
   (r3 section 13 option A), so several sources paint at once?
5. **Latest wedge outline:** show a brief outline as each report lands, or
   only the accumulated shading?
