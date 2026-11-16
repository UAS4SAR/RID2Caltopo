/* Private structs of btrx_bearing. Not part of the public API. */
#ifndef BB_INTERNAL_H
#define BB_INTERNAL_H
/* Determinism (spec 6.1): no fused multiply-add contraction, whatever the build flags.
 * Every .c includes this header first. Android CMake also passes -ffp-contract=off. */
#pragma STDC FP_CONTRACT OFF
#include <pthread.h>
#include <stdatomic.h>
#include "btrx_bearing.h"

#define BB_TILE 16
#define BB_TILE_CELLS (BB_TILE * BB_TILE)
#define BB_NONE (-1)
#define BB_PI 3.14159265358979323846
#define BB_DEG (BB_PI / 180.0)
#define BB_EARTH_R 6371008.8
#define BB_YAW_BINS 36
#define BB_MAX_PENDING_FLAGS 16
#define BB_SRCID_TABLE 64
#define BB_PONG_SAMPLES 16
#define BB_ARRIVAL_SAMPLES 256
#define BB_DIVERSITY_RING 256
#define BB_PENDING_CAP 32
#define BB_IN_POSES 1024
#define BB_IN_REPORTS 64
#define BB_IN_SYNCS 16
#define BB_IN_HEALTH 16
#define BB_IN_CMDS 128
#define BB_LOG_LINE 256
#define BB_T_NONE INT64_MIN
#define BB_PALETTE 12
#define BB_CAND_TABLE 64                 /* extras seen once, not tracked yet */
#define BB_COLOUR_MEMORY 64              /* key -> colour of evicted sources */

/* ---------- tiles and buckets (bb_tiles.c) ---------- */
typedef struct {
    int32_t tx, ty;                  /* world tile coordinate */
    int32_t next;                    /* next tile in the bucket list, or the free list */
    uint16_t e[BB_TILE_CELLS];       /* E' in 2^-8 nat, saturating */
    uint16_t s[BB_TILE_CELLS];       /* S in 2^-10 report units, saturating */
} bb_tile_t;

typedef struct {
    bool used;
    int64_t start_us, end_us;        /* [start, end) report-time range */
    int64_t sum_t_us;                /* sum of (t - start) for the mean time */
    int32_t n;
    float far_sum;                   /* sum of kappa * ln p_far (constant over cells) */
    float eff;                       /* diversity-discounted report count */
    uint64_t yaw_mask;               /* 36 x 10 deg bins of yaw at report time */
    float min_e, max_e, min_n, max_n;/* drone positions (local metres) */
    int32_t tile_head, tile_count;
} bb_bucket_t;

/* ---------- sources ---------- */
typedef struct {
    bool used;
    uint64_t key;
    uint64_t aliases[BTRX_BEARING_MAX_ALIASES];
    int32_t alias_count;
    bool provisional;
    bool src_keyed;                  /* stable ids: keyed by src_id (seen only as an extra so far) */
    uint16_t src_id;                 /* latest src_id reported for this source */
    uint8_t cls;
    uint8_t colour;
    uint32_t flags;
    int64_t first_us, last_us;
    uint32_t reports_in, reports_used;
    bb_bucket_t *buckets;            /* buckets_per_source */
    btrx_bearing_ray_t ray;
    bool est_valid;                  /* from the last publish (eviction preference) */
    uint64_t heard_order;            /* monotone counter of last heard (draw order) */
    /* strength trail: strongest patch mean at the drone position, every report heard */
    struct { int64_t t; float e, n; int8_t rssi; } trail[BTRX_BEARING_TRAIL_MAX];
    int32_t trail_head, trail_n;
    int8_t last_rssi;
    /* own aircraft suggestion: current run of strong reports */
    int64_t own_t0, own_last;
    float own_e0, own_n0, own_moved;
    /* the last STEER windows of it, for averaging (newest first) */
    struct { int64_t t; int8_t rp, rs; uint8_t hp, hs; float roll; } win[4];
    int32_t win_n;
} bb_source_t;

/* ---------- inputs ---------- */
typedef struct { int64_t t1, t4; uint32_t t2, t3; } bb_sync_in_t;
typedef struct { int64_t host_us; uint8_t role; uint32_t uptime_s; bool known; uint32_t lost; } bb_health_in_t;
enum { BB_CMD_FLAGS = 1, BB_CMD_CLASS, BB_CMD_FADE, BB_CMD_RESET, BB_CMD_RENDERED, BB_CMD_CONNECTED, BB_CMD_STABLE_IDS, BB_CMD_FOCUS };
typedef struct { int type; uint64_t key; uint32_t a, b; float f1, f2; uint64_t seq; int64_t us; } bb_cmd_t;

#define BB_RING(T, N) struct { T item[N]; int32_t head, count; }
typedef struct {
    BB_RING(btrx_bearing_pose_t, BB_IN_POSES) poses;
    BB_RING(btrx_bearing_report_t, BB_IN_REPORTS) reports;
    BB_RING(bb_sync_in_t, BB_IN_SYNCS) syncs;
    BB_RING(bb_health_in_t, BB_IN_HEALTH) health;
    BB_RING(bb_cmd_t, BB_IN_CMDS) cmds;
    uint32_t dropped;
} bb_inbox_t;

/* ---------- clock (bb_clock.c) ---------- */
typedef struct {
    bool have;
    int64_t off_us;                  /* host - pod */
    int32_t err_ms;
    uint8_t source;                  /* 0 none, 1 arrival minimum, 2 TIME_PONG */
    struct { int64_t t4, off, rtt; } pong[BB_PONG_SAMPLES];
    int32_t pong_n, pong_head;
    struct { int64_t arr, delta; } arr[BB_ARRIVAL_SAMPLES];
    int32_t arr_n, arr_head;
    bool have_last_we;
    uint32_t last_we;
    bool have_newest;
    uint32_t newest_we;
    uint32_t session;
    bool have_uptime_a;
    uint32_t last_uptime_a;
} bb_clock_t;

/* ---------- pose rings (bb_pose.c) ---------- */
typedef struct { int64_t t; float yaw; } bb_yaw_t;
typedef struct { int64_t t; double lat, lon; float agl; } bb_pos_t;
typedef struct {
    bb_yaw_t *yaw; int32_t yaw_cap, yaw_head, yaw_n;
    bb_pos_t *pos; int32_t pos_cap, pos_head, pos_n;
    int64_t last_yaw_push_us, last_pos_push_us;
    uint32_t out_of_order;
} bb_pose_t;

typedef struct {
    double e, n;                     /* local metres */
    float agl;
    float yaw;                       /* deg true at report time */
    float yaw_change, yaw_rate;
    bool agl_default;
} bb_pose_at_t;

/* ---------- pending reports and lag (bb_lag.c) ---------- */
enum { BB_WAIT_NONE = 0, BB_WAIT_YAW = 1, BB_WAIT_POS = 2 };
enum { BB_FATE_PENDING = 0, BB_FATE_USED, BB_FATE_GATED, BB_FATE_LATE, BB_FATE_HISTORY,
       BB_FATE_IGNORED, BB_FATE_NO_HEADING, BB_FATE_NO_POSITION, BB_FATE_EMPTY };
typedef struct {
    btrx_bearing_report_t r;
    int64_t t0, t2, t3;              /* T0 known once the offset is */
    int32_t wait;
    bool backlog;
} bb_pending_t;

typedef struct {
    uint8_t steer_seq;
    uint8_t fate;
    uint8_t wait;
    uint8_t n;
    uint8_t rank[BTRX_BEARING_MAX_REPORT_SOURCES];
    char sid[BTRX_BEARING_MAX_REPORT_SOURCES][8];
    uint8_t efate[BTRX_BEARING_MAX_REPORT_SOURCES];
    int64_t t0, t2, t3, t4, t5, t6, t7;
    uint64_t snap_seq;
    bool logged;
} bb_lagrec_t;

typedef struct {
    bb_lagrec_t *rec; int32_t cap, head, n;
    int32_t *yaw_in; int32_t *pos_in; int32_t in_cap, yaw_head, yaw_n, pos_head, pos_n;
    int32_t *scratch;
    uint32_t late_by_stage[BTRX_BEARING_STAGE_COUNT];
} bb_lag_t;

/* ---------- diversity (bb_diversity.c) ---------- */
typedef struct { int32_t slot; int64_t t; float e, n, yaw; } bb_div_t;
typedef struct { bb_div_t item[BB_DIVERSITY_RING]; int32_t head, n; } bb_diversity_t;

typedef struct { bool have; uint32_t last_uptime, last_lost; uint32_t total; bool known; } bb_health_state_t;

/* ---------- snapshot ---------- */
typedef struct {
    btrx_bearing_snapshot_t pub;
    uint8_t *rgba;
    uint8_t *alpha;
    uint8_t *fine_rgba;
    btrx_bearing_trail_point_t *trail;
} bb_snap_t;

/* ---------- per-entry geometry for the likelihood ---------- */
typedef struct {
    double de, dn;                   /* drone, local metres */
    float agl, yaw;
    bool censored;
    bool heard_port;                 /* censored: which patch heard it */
    float obs;                       /* both heard: delta obs; censored: heard rssi */
    float sigma2;
    float kappa, q;
    float div;                       /* 1 / (1 + k), diversity discount */
    float foot_m;                    /* footprint radius of this report (0 = cfg.footprint_m) */
    float reach_m;                   /* drawn wedge length (RSSI range * margin); 0 = footprint */
    float range_m;                   /* RSSI slant range estimate; NaN unknown */
    float roll_deg;                  /* pod roll (+ starboard down) for the correction; 0 = none */
} bb_geom_t;

struct btrx_bearing {
    btrx_bearing_config_t cfg;
    uint8_t key[16];
    float fade_total_s, fade_shape_p;

    pthread_mutex_t in_mu;
    pthread_cond_t in_cv;
    bb_inbox_t *inbox[2];
    int32_t inbox_active;

    pthread_mutex_t work_mu;
    pthread_mutex_t snap_mu;
    int32_t snap_latest, snap_reading;
    bb_snap_t snaps[3];
    uint64_t snap_seq;

    pthread_t worker;
    bool worker_running;
    atomic_bool worker_stop;

    /* worker-side state */
    float *pattern_delta, *pattern_sum;
    bb_clock_t clock;
    bb_pose_t pose;
    bb_pending_t pending[BB_PENDING_CAP];
    int32_t pending_head, pending_n;
    bb_lag_t lag;
    bb_diversity_t div;

    bb_source_t sources[BTRX_BEARING_MAX_SOURCES];
    bb_bucket_t *bucket_mem;
    uint64_t heard_counter;
    uint8_t next_colour;
    struct { uint16_t src_id; int32_t slot; } srcid[BB_SRCID_TABLE];
    int32_t srcid_n;
    bool stable_src_ids;             /* pod build 0x0106+: src_id = hash of the address */
    struct { uint64_t key; uint32_t mask, value; } pend_flags[BB_MAX_PENDING_FLAGS];
    int32_t pend_flags_n;

    bb_tile_t *tiles;
    int32_t tile_free, tiles_in_use;

    bool have_anchor;
    double lat0, lon0, coslat0;
    bool have_view;
    int32_t vtx0, vty0;              /* SW tile of the view */
    int32_t view_tiles;

    float *scr_e, *scr_s;
    int32_t *scr_stack;
    uint8_t *scr_mask;

    int64_t last_publish_us;
    int64_t newest_used_t_us;
    bool dirty, force_publish;

    bb_health_state_t health[4];
    uint32_t desk_connect_base; bool desk_connect_base_set;

    btrx_bearing_stats_t stats;
    /* Stats copy for btrx_bearing_stats_copy (under snap_mu), refreshed every
     * publish interval even while no snapshot can be published (no view yet). */
    btrx_bearing_stats_t stats_pub;
    bool stats_pub_valid;
    int64_t last_stats_us;
    /* Nov 16 additions */
    btrx_bearing_t *fine;            /* fine layer: accumulation only, slot-parallel sources */
    bool is_fine;
    uint64_t focus_key;
    struct { uint64_t key; int64_t t; int32_t n; } cand[BB_CAND_TABLE];
    int32_t cand_n;
    struct { uint64_t key; uint8_t colour; } colmem[BB_COLOUR_MEMORY];
    int32_t colmem_n;
    uint32_t extras_tentative;       /* entries of not-yet-tracked extras (log only) */
    uint32_t evictions;
};

/* helpers */
static inline int32_t bb_floordiv(int32_t a, int32_t b) { int32_t q = a / b; if ((a % b != 0) && ((a < 0) != (b < 0))) q--; return q; }
static inline uint16_t bb_sat_add16(uint16_t a, int32_t b) { int32_t v = (int32_t)a + b; return (uint16_t)(v > 65535 ? 65535 : (v < 0 ? 0 : v)); }
static inline uint32_t bb_sat_add32(uint32_t a, uint32_t b) { uint32_t v = a + b; return v < a ? 0xFFFFFFFFu : v; }
float bb_wrap180(float d);
float bb_wrap360(float d);

/* bb_siphash.c */
uint64_t bb_siphash24(const uint8_t key[16], const uint8_t *data, size_t len);

/* bb_pattern.c */
void bb_pattern_build_v1(float *delta, float *sum, float hpbw_deg);
float bb_pattern_lookup(const float *table, float az_deg, float dep_deg);

/* bb_clock.c */
void bb_clock_reset(bb_clock_t *c);
void bb_clock_add_pong(bb_clock_t *c, const bb_sync_in_t *s, int32_t window_ms);
/* Returns true if the frame started a new pod session. */
bool bb_clock_observe_frame(bb_clock_t *c, uint32_t we, int64_t arrival_us, bool flagged, bool *backlog_out);
bool bb_clock_uptime(bb_clock_t *c, uint32_t uptime_s);
void bb_clock_update(bb_clock_t *c, int64_t now_us, int32_t window_ms);

/* bb_pose.c */
void bb_pose_clear(bb_pose_t *p);
void bb_pose_push(btrx_bearing_t *h, const btrx_bearing_pose_t *in);
void bb_local(const btrx_bearing_t *h, double lat, double lon, double *e, double *n);
void bb_latlon(const btrx_bearing_t *h, double e, double n, double *lat, double *lon);
int bb_pose_ready(const btrx_bearing_t *h, int64_t t_end, int64_t now, int32_t *wait);
int bb_pose_at(btrx_bearing_t *h, int64_t t_end, bb_pose_at_t *out);
int bb_pose_pos(btrx_bearing_t *h, int64_t t_rep, bb_pose_at_t *out);   /* position and AGL only */
bool bb_drone_speed(btrx_bearing_t *h, int64_t t_rep, float *mps);
void bb_source_heard_at(btrx_bearing_t *h, bb_source_t *s, int64_t t, float e, float n, int8_t rssi);

/* bb_tiles.c */
void bb_tiles_init(btrx_bearing_t *h);
int32_t bb_tile_get(btrx_bearing_t *h, bb_source_t *src, bb_bucket_t *b, int32_t tx, int32_t ty);
bb_bucket_t *bb_bucket_for(btrx_bearing_t *h, bb_source_t *src, int64_t t_us);
void bb_bucket_free(btrx_bearing_t *h, bb_bucket_t *b);
void bb_source_free_buckets(btrx_bearing_t *h, bb_source_t *src);
float bb_fade_weight(const btrx_bearing_t *h, const bb_bucket_t *b, int64_t now_us);
int64_t bb_bucket_mean_us(const bb_bucket_t *b);
bool bb_expire(btrx_bearing_t *h, int64_t now_us);
void bb_merge_source(btrx_bearing_t *h, bb_source_t *dst, bb_source_t *src);
void bb_composite_source(btrx_bearing_t *h, bb_source_t *src, int64_t now_us, float *e, float *s, float *eff,
        float *yaw_span, float *baseline);

/* bb_grid.c */
float bb_loglik(const btrx_bearing_t *h, const bb_geom_t *g, float phi_deg, float dep_deg);
float bb_roll_k(float dep_deg);
void bb_grid_add(btrx_bearing_t *h, bb_source_t *src, bb_bucket_t *b, const bb_geom_t *g);
void bb_latest_ray(btrx_bearing_t *h, bb_source_t *src, const bb_geom_t *g, int64_t t_us);
float bb_range_m(const btrx_bearing_t *h, float rssi_dbm);   /* RSSI range estimate */
float bb_reach_m(const btrx_bearing_t *h, float rssi_dbm, float ceiling_m);

/* bb_diversity.c */
int32_t bb_diversity_count(btrx_bearing_t *h, int32_t slot, int64_t t, float e, float n, float yaw);
void bb_diversity_drop_slot(btrx_bearing_t *h, int32_t slot);

/* bb_estimate.c */
void bb_estimate(btrx_bearing_t *h, const float *e, float eff, float yaw_span, float baseline,
        btrx_bearing_estimate_t *out);

/* bb_render.c */
void bb_publish(btrx_bearing_t *h, int64_t now_us);
void bb_view_update(btrx_bearing_t *h, double e, double n);
const uint8_t *bb_palette(uint8_t colour);

/* bb_steer.c */
int32_t bb_resolve_entry(btrx_bearing_t *h, const btrx_bearing_report_source_t *rs, int64_t t_us);
int32_t bb_find_source(const btrx_bearing_t *h, uint64_t key);
void bb_srcid_clear(btrx_bearing_t *h);
void bb_source_evict(btrx_bearing_t *h, int32_t slot);
uint8_t bb_class_from_pod(uint8_t pod);

/* bb_lag.c */
bb_lagrec_t *bb_lag_new(btrx_bearing_t *h, const bb_pending_t *p);
void bb_lag_final(btrx_bearing_t *h, bb_lagrec_t *r);
void bb_lag_input(btrx_bearing_t *h, int which, int64_t lag_us);
void bb_lag_late(btrx_bearing_t *h, int stage);
void bb_lag_published(btrx_bearing_t *h, uint64_t seq, int64_t now_us);
void bb_lag_rendered(btrx_bearing_t *h, uint64_t seq, int64_t us);
void bb_lag_stats(btrx_bearing_t *h, btrx_bearing_stats_t *st);
void bb_fill_stats(btrx_bearing_t *h, int64_t now_us);
void bb_log(btrx_bearing_t *h, int level, const char *fmt, ...);

#endif
