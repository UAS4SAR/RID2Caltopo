/*
 * btrx_bearing: continuous BTRX bearing layer (docs/btrx/BEARING-LAYER-spec.md r3).
 *
 * C11 + stdlib + pthreads only. Shared by the iPad (SwiftPM target CBtrxBearing)
 * and Android (libbtrx_bearing.so via btrx_bearing_jni.c). No clock, no I/O,
 * no allocation after btrx_bearing_create(). All times are int64 microseconds on
 * one host monotonic clock supplied by the caller.
 */
#ifndef BTRX_BEARING_H
#define BTRX_BEARING_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

#define BTRX_BEARING_ABI_VERSION 3u
/* Nov 23 staleness shared by Android and the iPad (test-fixtures/btrx/staleness.tsv). */
#define BTRX_BEARING_STALE_FADE_START_S 25
#define BTRX_BEARING_STALE_HIDE_S 60
/* Nov 23 launch zone (Ken: searchers around the takeoff point only appear as the drone lifts):
   sources heard at least LAUNCH_RSSI_DBM while the drone is within LAUNCH_RADIUS_M of its
   takeoff point and below LAUNCH_ZONE_MAX_AGL_M (the initial climb, until it first leaves that
   zone), in the first LAUNCH_WINDOW_S, are muted.
   Shared by Android and the iPad (the apps' settings text; test-fixtures/btrx/launch-zone.tsv). */
#define BTRX_BEARING_LAUNCH_RSSI_DBM (-70)
#define BTRX_BEARING_LAUNCH_RADIUS_M 30
#define BTRX_BEARING_LAUNCH_ZONE_MAX_AGL_M 40
#define BTRX_BEARING_LAUNCH_WINDOW_S 300
/* 32 (was 8, Nov 16 watch test: an 8-slot LRU erased the watch's history). */
#define BTRX_BEARING_MAX_SOURCES 32
#define BTRX_BEARING_TRAIL_MAX 600            /* strength trail points of the focused source */
#define BTRX_BEARING_MAX_REPORT_SOURCES 3    /* primary + up to 2 extras (build 0x0103) */
#define BTRX_BEARING_RSSI_NOT_HEARD (-128)
#define BTRX_BEARING_MAX_ALIASES 4
#define BTRX_BEARING_PATTERN_AZ 360          /* pattern table: azimuth 0..359 (deg from nose, + starboard) */
#define BTRX_BEARING_PATTERN_DEP 46          /* depression 0..90 in 2 deg steps */

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
    BTRX_BEARING_CLASS_COMPUTER = 6, /* pod "computer": laptops, desktops, tablets (ABI 3) */
} btrx_bearing_class_t;

typedef int64_t (*btrx_bearing_now_us_fn)(void *ctx);           /* host monotonic */
typedef void (*btrx_bearing_log_fn)(void *ctx, int level, const char *msg); /* tick thread; must not block */

typedef struct {
    uint32_t struct_size;            /* sizeof(btrx_bearing_config_t) */
    uint32_t abi_version;            /* BTRX_BEARING_ABI_VERSION */
    /* grid */
    float cell_m;                    /* 10 */
    int32_t grid_cells;              /* 192 (square, multiple of 16) */
    int32_t max_sources;             /* 32 (<= BTRX_BEARING_MAX_SOURCES); full: evict least evidence */
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
    int32_t tile_pool_tiles;         /* 8192 x 1 KB (16x16 cells, uint16 E' and S) */
    float alpha_max, support_sat;    /* 0.7, 8: alpha = alpha_max * min(1, S/support_sat) */
    float alpha_visible;             /* 0.02: transparent below; sources_at threshold */
    float ray_halfwidth_ll;          /* -0.5: latest-ray edge level */
    /* gates and timing */
    float max_yaw_rate_dps, max_yaw_change_deg; /* 10, 10 (spec r3 said 5; see spec 2.8 note) */
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
    float est_max_axis_2s_m;         /* 100 (was 200 before Nov 20: weak sources slid ~200 m along the ray) */
    float second_peak_mass;          /* 0.25 */
    /* work */
    int32_t max_reports_per_tick;    /* 4 frames */
    int32_t publish_interval_ms;     /* 1000; a mute or fade change publishes at the next tick */
    /* sources (Nov 16 watch test) */
    int32_t extra_min_sightings;     /* 2: an extra (no address) not yet tracked becomes a source
                                        on its 2nd sighting within extra_sighting_window_ms */
    int32_t extra_sighting_window_ms;/* 60000 */
    /* close range: footprint and ray length from the RSSI range estimate */
    float range_ref_dbm;             /* -38: strongest-patch mean RSSI at 1 m */
    float range_exponent;            /* 2.2: path-loss exponent */
    float range_margin;              /* 3: reach = range * margin (about +10 dB): drawn wedge length */
    float range_min_m;               /* 30: reach floor; footprint_m is the ceiling */
    /* range cap (Nov 20, ABI 3): a strong report while low bounds the coarse footprint, so a
       nearby source's evidence cannot slide toward the footprint edge. Off: range_cap_dbm >= 0. */
    float range_cap_dbm;             /* -55: reports this strong (strongest patch mean) ... */
    float range_cap_max_agl_m;       /* 40: ... while the drone is this low or lower ... */
    float range_cap_margin;          /* 4: ... use footprint = clamp(range * margin, range_cap_min_m, footprint_m) */
    float range_cap_min_m;           /* 25 */
    float range_cap_nats_per_100m;   /* 20: the estimate's evidence is lowered by this per 100 m
                                        beyond the capped range from where the drone heard the
                                        source strongest while low (fade window); 0 = off */
    /* coverage (Nov 20, ABI 3): the estimate's evidence is reduced in cells far from anywhere
       the drone flew this session (the grid cannot tell "far" from "beyond the track"). */
    float cover_free_m;              /* 60: no penalty within this distance of the track */
    float cover_nats_per_100m;       /* 3: penalty per 100 m beyond cover_free_m; 0 = off */
    /* launch site (Nov 20, ABI 3; zone Nov 23): the takeoff point is the first position with a
       known AGL if that AGL <= launch_max_agl_m. Sources heard at least launch_rssi_dbm while
       the drone is within launch_radius_m of it and at most launch_zone_max_agl_m AGL (on the
       ground and through the initial climb, until the drone first leaves that zone; after
       that at most launch_max_agl_m AGL), during the first launch_window_s after it, get
       BTRX_BEARING_SRC_LAUNCH and, with launch_auto_mute, BTRX_BEARING_SRC_MUTED (the app can
       unmute). Nov 20 was on the ground only (radius 15 m, AGL 5 m). */
    float launch_rssi_dbm;           /* BTRX_BEARING_LAUNCH_RSSI_DBM, -70 */
    float launch_radius_m;           /* BTRX_BEARING_LAUNCH_RADIUS_M, 30 (was 15) */
    float launch_max_agl_m;          /* 5: the takeoff point only */
    float launch_window_s;           /* BTRX_BEARING_LAUNCH_WINDOW_S, 300 */
    bool launch_auto_mute;           /* true */
    float launch_zone_max_agl_m;     /* BTRX_BEARING_LAUNCH_ZONE_MAX_AGL_M, 40 (Nov 23) */
    /* own aircraft suggestion: stronger than own_rssi_dbm for own_hold_ms while the drone
       moved own_moved_m sets BTRX_BEARING_SRC_OWN_SUSPECT (a suggestion, never a mute) */
    float own_rssi_dbm;              /* -40 */
    int32_t own_hold_ms;             /* 60000 */
    float own_moved_m;               /* 30 */
    /* fine layer: reports while low and slow, or strong, also go to a finer grid */
    float fine_cell_m;               /* 2.5; 0 = no fine layer */
    int32_t fine_grid_cells;         /* 160 (400 m) */
    float fine_footprint_m;          /* 100 */
    float fine_max_agl_m, fine_max_speed_mps; /* 30, 4: "low and slow" */
    float fine_strong_dbm;           /* -65: or this strong */
    int32_t fine_tile_pool_tiles;    /* 3072 */
    /* latest ray: lobes within ray_ambiguous_ll nats of each other are drawn alike; the
       in-front lobe (|bearing from the nose| < 90) is the main one then */
    float ray_ambiguous_ll;          /* 0.5 */
    float focus_dim;                 /* 0.25: alpha of the other sources while one is focused */
    float pin_max_axis_2s_m;         /* 30: an estimate this tight is "pinned" (map marker) */
    /* presentation staleness (Nov 23, Ken: old devices fade away and lose focus within 60 s):
       a source not heard for stale_fade_start_s fades from the RGBA linearly to nothing at
       stale_hide_s, and a focused source (by key) not heard for stale_hide_s loses focus.
       Evidence, estimates and the snapshot's source list are unchanged (replay, logs, panel).
       0 = off (the default; the apps set BTRX_BEARING_STALE_FADE_START_S / _HIDE_S). */
    float stale_fade_start_s;        /* 0 (apps: 25) */
    float stale_hide_s;              /* 0 (apps: 60) */
    /* attitude and averaging (pod build 0x010A, PROTOCOL.md "Attitude and the gate") */
    bool gate_on_attitude_flag;      /* false: STEER flags bit 3 is advice; true = old reject */
    bool roll_correct;               /* true: port-stbd + k(depression) * roll, no pitch term */
    float roll_full_weight_deg;      /* 10: full weight up to this |roll| */
    float roll_max_deg;              /* 45: weight falls to 0.25 here; beyond, gated */
    int32_t avg_windows;             /* 3: average a source over up to 3 STEER windows */
    int32_t avg_span_ms;             /* 2600: ... whose window ends lie within this span */
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

/* Gyro yaw (STEER extension format 2, firmware 0x010C). t_off_ms == this means sample time unknown. */
#define BTRX_BEARING_YAW_T_OFF_UNKNOWN ((int16_t)-32768)
#define BTRX_BEARING_YAW_F_RUNNING  0x01u
#define BTRX_BEARING_YAW_F_BIAS     0x02u
#define BTRX_BEARING_YAW_F_STILL    0x04u
#define BTRX_BEARING_YAW_F_REG      0x08u  /* register samples (FIFO not working) */
#define BTRX_BEARING_YAW_F_LOST     0x10u  /* samples lost since the last report */
#define BTRX_BEARING_YAW_EPOCH_SHIFT 5

/* One STEER (0x73): 27-byte (older firmware), 28/34/40-byte format 1 (build 0x0103),
 * or 38/44/50-byte format 2 with a 10-byte gyro yaw block (build 0x010C). */
typedef struct {
    int64_t arrival_us;              /* T2: host BLE/USB arrival */
    int64_t push_us;                 /* T3: host time of push_report (0 = use arrival_us) */
    uint32_t window_end_ms;          /* pod A millis() */
    uint8_t steer_seq;
    uint8_t flags;                   /* frame flags: bit0 target .. bit6 imu cal; bit3/4 apply to all entries */
    int8_t roll_x2, pitch_x2;        /* pod, at send time; whole frame */
    bool backlog;                    /* BtrxClient marked it queued by the Desk Pod */
    uint8_t ext_format;              /* byte 27 bits 4-7; 0 for 27-byte frames */
    bool truncated;                  /* extension unreadable: bytes 0-26 used only */
    uint8_t source_count;            /* 0 (nothing heard) .. BTRX_BEARING_MAX_REPORT_SOURCES */
    btrx_bearing_report_source_t src[BTRX_BEARING_MAX_REPORT_SOURCES]; /* frame order */
    /* Format 2 gyro yaw; has_yaw is false when the format is not 2 or the length does not fit. */
    bool has_yaw;
    uint16_t yaw_x10, yaw_fixed_x10; /* 0.1 deg, 0-3599; relative to the bias-settled zero */
    int16_t bias_z_mdps, t_off_ms;   /* t_off: yaw sample = window_end_ms + t_off; UNKNOWN = not ready */
    int8_t turns;                    /* unwrapped = turns * 360 + yaw_x10 / 10 degrees */
    uint8_t yflags;                  /* running, bias, still, reg, lost; bits 5-7 epoch */
} btrx_bearing_report_t;

/* Parse a STEER payload by the section 4.1 rules. Pure apart from reading the
 * handle's incident key (to hash the primary's address). Any thread.
 * h may be NULL (addr_key is then 0). */
btrx_bearing_status_t btrx_bearing_parse_steer(const btrx_bearing_t *h,
        const uint8_t *payload, size_t len, int64_t arrival_us, bool backlog,
        btrx_bearing_report_t *out);
/* window_end_ms + t_off_ms as int64. Returns false when t_off is UNKNOWN. */
bool btrx_bearing_yaw_sample_ms(uint32_t window_end_ms, int16_t t_off_ms, int64_t *out);

/* HEALTH (0x80) fields the layer uses: 30 bytes (older firmware, lost unknown), 34, or 44
 * (build 0x0106: reset_reason etc. at 34..43, read by the app, not the layer). Longer is fine. */
typedef struct {
    uint32_t uptime_s;
    uint8_t role;                    /* 1 port, 2 starboard (pod A), 3 Desk Pod */
    bool lost_known;
    uint32_t phone_lost;             /* offset 30, u32 LE, saturating */
} btrx_bearing_health_t;
btrx_bearing_status_t btrx_bearing_parse_health(const uint8_t *payload, size_t len,
        btrx_bearing_health_t *out);

/* Lifecycle */
uint32_t btrx_bearing_abi_version(void);
void btrx_bearing_config_default(btrx_bearing_config_t *cfg);
btrx_bearing_status_t btrx_bearing_create(const btrx_bearing_config_t *cfg,
        const uint8_t incident_key[16], btrx_bearing_t **out);
void btrx_bearing_destroy(btrx_bearing_t *h);
const char *btrx_bearing_status_string(btrx_bearing_status_t s);

/* Fade helpers (settings readout): bucket width and the age at half strength. */
float btrx_bearing_bucket_width_s(float total_s, float min_s, float max_s);
float btrx_bearing_half_strength_s(float total_s, float shape_p);
/* Presentation presence of a source last heard age_s ago: 1 up to fade_start_s, then linear
 * to 0 at hide_s; hide_s <= 0 (off) or hide_s <= fade_start_s with age < hide_s: 1.
 * The apps' panel and map use the same rule (test-fixtures/btrx/staleness.tsv). */
float btrx_bearing_presence(float fade_start_s, float hide_s, float age_s);

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
/* The app (re)connected to the Desk Pod: starts the "lost since connect" count. */
btrx_bearing_status_t btrx_bearing_note_connected(btrx_bearing_t *h, int64_t host_us);
/* T7: the overlay showing snapshot `seq` is on screen. */
btrx_bearing_status_t btrx_bearing_note_rendered(btrx_bearing_t *h, uint64_t seq, int64_t host_us);

/* Sources */
btrx_bearing_status_t btrx_bearing_source_key(const btrx_bearing_t *h,
        const uint8_t addr_msb_first[6], uint8_t addr_type, uint64_t *key_out);
void btrx_bearing_short_id(uint64_t key, char out[8]);    /* 6 chars + NUL */
enum {
    BTRX_BEARING_SRC_IGNORED = 1u << 0,  /* own aircraft: never accumulated */
    BTRX_BEARING_SRC_MUTED = 1u << 1,    /* hidden on the map; still accumulated */
    BTRX_BEARING_SRC_OWN_SUSPECT = 1u << 2, /* set by the layer: looks like it rides on the drone;
                                               its estimate is not drawn until decided */
    BTRX_BEARING_SRC_OWN_CLEARED = 1u << 3, /* set by the app: "not on the drone"; no suggestion */
    BTRX_BEARING_SRC_LAUNCH = 1u << 4,      /* set by the layer: strong at the launch point
                                               before takeoff (ABI 3); muted if launch_auto_mute */
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

/* Pod firmware 0x0106 and newer: src_id is a 16-bit hash of the address (1..65535, the same
 * device keeps it across gaps, table eviction and pod restarts). With stable ids on, an extra
 * source (no address) is keyed by its src_id alone (not by pod session) and is a real source,
 * not provisional; it takes the address key when the same src_id comes with an address.
 * Older firmware counts ids from 1 per pod session: leave off (the default). Any thread,
 * applied at the next tick. */
btrx_bearing_status_t btrx_bearing_set_stable_src_ids(btrx_bearing_t *h, bool stable);

/* Focus (any thread, next tick): the other sources' shading at focus_dim; the snapshot carries
 * the focused source's strength trail. key 0 clears. */
btrx_bearing_status_t btrx_bearing_set_focus(btrx_bearing_t *h, uint64_t key);

/* Focus filter (ABI 3; any thread, next tick): sources whose class bit (1u << class) is in
 * class_mask, or whose company id equals company_id (-1 = none), are focused along with the
 * focus key. hide_others draws the other sources not at all (alpha 0) instead of focus_dim.
 * Focused sources are never evicted while another slot can go. All zero/-1/false clears. */
btrx_bearing_status_t btrx_bearing_set_focus_filter(btrx_bearing_t *h, uint32_t class_mask,
        int32_t company_id, bool hide_others);

/* Launch-site auto mute on or off (ABI 3; any thread, next tick). Off does not unmute. */
btrx_bearing_status_t btrx_bearing_set_launch_auto_mute(btrx_bearing_t *h, bool on);

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
    float axis_major_1s_m, axis_minor_1s_m, axis_major_deg; /* 2 sigma = 2x; deg true of major axis */
    double second_lat_deg, second_lon_deg;
    float second_mass;
} btrx_bearing_estimate_t;

typedef struct {                     /* latest-ray outline, section 5.1 */
    bool valid;
    bool ambiguous;                  /* front and back lobes about equally likely: draw both alike */
    double apex_lat_deg, apex_lon_deg;
    float centre_true_deg, half_width_deg, length_m;
    float mirror_true_deg;           /* NaN when the mirror lobe is not significant */
    int64_t t_us;                    /* report time; the outline fades over 5 s from here */
    float range_m;                   /* RSSI range estimate (NaN unknown); length_m = reach from it */
} btrx_bearing_ray_t;

typedef struct {                     /* strength trail point (focused source) */
    double lat_deg, lon_deg;         /* drone position at the report */
    int64_t t_us;
    int8_t rssi_dbm;                 /* strongest patch mean */
} btrx_bearing_trail_point_t;

typedef struct {
    uint64_t key;                    /* address key, or provisional (pod session, src_id), or src_id key (stable ids) */
    bool provisional;                /* "unresolved": seen only as an extra so far (older firmware) */
    bool src_keyed;                  /* stable ids: seen only as an extra so far, keyed by src_id */
    uint16_t src_id;                 /* the pod's src_id in the latest report of it; 0 = none */
    char short_id[8];
    btrx_bearing_class_t dev_class;
    uint8_t rgb[3];
    uint32_t flags;                  /* IGNORED, MUTED, OWN_SUSPECT, OWN_CLEARED */
    int64_t first_heard_us, last_heard_us;
    uint32_t reports_in, reports_used, reports_in_window;
    float effective_reports;
    float peak_alpha;
    const uint8_t *alpha;            /* width*height, row 0 = north; muted sources too */
    btrx_bearing_estimate_t estimate;   /* drawn only when not muted */
    btrx_bearing_ray_t latest_ray;      /* drawn only when not muted */
    int8_t last_rssi_dbm;            /* strongest patch mean, latest report; -128 none */
    int8_t max_rssi_dbm;             /* strongest in the fade window; -128 none */
    bool strongest_valid;            /* "strongest here": where the drone heard it strongest */
    double strongest_lat_deg, strongest_lon_deg;
    int64_t strongest_t_us;
    bool label_valid;                /* label point inside the shaded area */
    double label_lat_deg, label_lon_deg;
    bool pinned;                     /* estimate valid and 2 sigma <= pin_max_axis_2s_m */
    uint16_t company_id;             /* Bluetooth SIG company id from the pod; 0 = unknown (ABI 3) */
    bool focus_match;                /* focus active and this source is focused (ABI 3) */
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
    uint64_t focus_key;              /* 0 = none; other sources at focus_dim alpha */
    uint32_t focus_class_mask;       /* ABI 3: focus filter in effect (see set_focus_filter) */
    int32_t focus_company_id;        /* -1 = none */
    bool focus_hide_others;
    bool focus_active;               /* key or filter set */
    bool launch_valid;               /* ABI 3: launch point known */
    double launch_lat_deg, launch_lon_deg;
    /* fine layer (fine_cell_m), only reports while low and slow or strong; draw over rgba */
    bool fine_valid;
    int32_t fine_width, fine_height;
    double fine_north_deg, fine_south_deg, fine_east_deg, fine_west_deg;
    const uint8_t *fine_rgba;        /* fine_width*fine_height*4, premultiplied, row 0 = north */
    int32_t trail_count;             /* focused source, oldest first */
    const btrx_bearing_trail_point_t *trail;
} btrx_bearing_snapshot_t;

/* Copy of the latest stats, refreshed by tick at the publish interval even
 * before the first snapshot (no drone position yet), so a platform can say why
 * nothing is drawn. Any thread. E_NO_SNAPSHOT before the first tick. */
btrx_bearing_status_t btrx_bearing_stats_copy(btrx_bearing_t *h, btrx_bearing_stats_t *out);

/* Valid until release; acquire again only after release. NULL before the first publish. */
const btrx_bearing_snapshot_t *btrx_bearing_snapshot_acquire(btrx_bearing_t *h);
void btrx_bearing_snapshot_release(btrx_bearing_t *h, const btrx_bearing_snapshot_t *s);

/* Deep copy of a snapshot into caller-owned buffers (rgba: width*height*4 bytes;
 * alpha: width*height*MAX_SOURCES bytes; fine: fine_width*fine_height*4 bytes, may be
 * NULL to drop the fine layer; trail: TRAIL_MAX points, may be NULL), so a platform
 * wrapper can keep the current snapshot for its UI thread while its worker is the one reader. */
btrx_bearing_status_t btrx_bearing_snapshot_copy(const btrx_bearing_snapshot_t *src,
        btrx_bearing_snapshot_t *dst, uint8_t *rgba, size_t rgba_size,
        uint8_t *alpha, size_t alpha_size, uint8_t *fine_rgba, size_t fine_size,
        btrx_bearing_trail_point_t *trail, int32_t trail_max);
/* Largest fine raster side for a config (0 = no fine layer), for buffer sizing. */
int32_t btrx_bearing_fine_cells(const btrx_bearing_config_t *cfg);

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

/* Tests and debugging: the pattern table in effect (v1 or measured), at azimuth
 * az_deg (from the nose, + starboard) and depression dep_deg. */
float btrx_bearing_pattern_delta_db(const btrx_bearing_t *h, float az_deg, float dep_deg);

#ifdef __cplusplus
}
#endif
#endif
