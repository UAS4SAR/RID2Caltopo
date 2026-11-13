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

#define BTRX_BEARING_ABI_VERSION 1u
#define BTRX_BEARING_MAX_SOURCES 8
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
} btrx_bearing_class_t;

typedef int64_t (*btrx_bearing_now_us_fn)(void *ctx);           /* host monotonic */
typedef void (*btrx_bearing_log_fn)(void *ctx, int level, const char *msg); /* tick thread; must not block */

typedef struct {
    uint32_t struct_size;            /* sizeof(btrx_bearing_config_t) */
    uint32_t abi_version;            /* BTRX_BEARING_ABI_VERSION */
    /* grid */
    float cell_m;                    /* 10 */
    int32_t grid_cells;              /* 192 (square, multiple of 16) */
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
} btrx_bearing_report_t;

/* Parse a STEER payload by the section 4.1 rules. Pure apart from reading the
 * handle's incident key (to hash the primary's address). Any thread.
 * h may be NULL (addr_key is then 0). */
btrx_bearing_status_t btrx_bearing_parse_steer(const btrx_bearing_t *h,
        const uint8_t *payload, size_t len, int64_t arrival_us, bool backlog,
        btrx_bearing_report_t *out);

/* HEALTH (0x80) fields the layer uses: 30 bytes (older firmware, lost unknown) or 34. */
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
    float axis_major_1s_m, axis_minor_1s_m, axis_major_deg; /* 2 sigma = 2x; deg true of major axis */
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

/* Valid until release; acquire again only after release. NULL before the first publish. */
const btrx_bearing_snapshot_t *btrx_bearing_snapshot_acquire(btrx_bearing_t *h);
void btrx_bearing_snapshot_release(btrx_bearing_t *h, const btrx_bearing_snapshot_t *s);

/* Deep copy of a snapshot into caller-owned buffers (rgba: width*height*4 bytes;
 * alpha: width*height*MAX_SOURCES bytes), so a platform wrapper can keep the
 * current snapshot for its UI thread while its worker is the one reader. */
btrx_bearing_status_t btrx_bearing_snapshot_copy(const btrx_bearing_snapshot_t *src,
        btrx_bearing_snapshot_t *dst, uint8_t *rgba, size_t rgba_size,
        uint8_t *alpha, size_t alpha_size);

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
