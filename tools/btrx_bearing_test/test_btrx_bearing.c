/* Host tests for btrx_bearing (docs/btrx/BEARING-LAYER-spec.md section 9.1, 9.3).
 * Build: cmake -S tools/btrx_bearing_test -B /tmp/bbt && cmake --build /tmp/bbt && /tmp/bbt/btrx_bearing_test */
#define _POSIX_C_SOURCE 200809L
#include <inttypes.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "btrx_bearing.h"
#include "bb_internal.h"

extern atomic_long bbt_alloc_count;

static int g_fail, g_checks;
#define CHECK(cond, ...) do { g_checks++; if (!(cond)) { g_fail++; printf("  FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
#define NEAR(a, b, tol, what) CHECK(fabs((double)(a) - (double)(b)) <= (tol), "%s: %g vs %g (tol %g)", what, (double)(a), (double)(b), (double)(tol))

static const uint8_t KEY[16] = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };
#define LAT0 37.0
#define LON0 (-122.0)

static double now_s(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec + ts.tv_nsec * 1e-9; }

static void m2ll(double e, double n, double *lat, double *lon) {
    *lat = LAT0 + n / (BB_DEG * BB_EARTH_R);
    *lon = LON0 + e / (BB_DEG * BB_EARTH_R * cos(LAT0 * BB_DEG));
}

static btrx_bearing_t *mk(btrx_bearing_config_t *over) {
    btrx_bearing_config_t c;
    /* The older tests resolve extras on their first sighting (their subject); the
     * 2-sighting rule of the defaults has its own tests (test_watch_fixes). */
    if (over) c = *over; else { btrx_bearing_config_default(&c); c.extra_min_sightings = 1; }
    btrx_bearing_t *h = NULL;
    btrx_bearing_status_t st = btrx_bearing_create(&c, KEY, &h);
    if (st != BTRX_BEARING_OK) { printf("create failed %d\n", st); exit(2); }
    return h;
}

/* ---------- deterministic RNG ---------- */
static uint64_t rng_state = 88172645463325252ULL;
static uint64_t rnd(void) { rng_state ^= rng_state << 13; rng_state ^= rng_state >> 7; rng_state ^= rng_state << 17; return rng_state; }
static double urand(void) { return ((rnd() >> 11) + 0.5) / 9007199254740992.0; }
static double gauss(void) { return sqrt(-2 * log(urand())) * cos(2 * BB_PI * urand()); }

/* ---------- simulator ---------- */
typedef struct { double e, n; uint64_t key; uint16_t src_id; int hits; bool as_extra; } sim_src_t;
typedef struct { double e, n, agl, yaw; } sim_pose_t;
typedef sim_pose_t (*traj_fn)(double t, void *ctx);

typedef struct {
    btrx_bearing_t *h;
    int64_t t0_us;            /* host time at sim t = 0 */
    int64_t pod_off_us;       /* host - pod */
    double delay_s;           /* arrival delay after window end */
    double delay_jitter_s;
    double noise_db;
    uint8_t seq;
    uint8_t flags;
    sim_src_t src[3];
    int n_src;
    int64_t last_tick;
    int64_t next_we, next_arr;
    traj_fn traj; void *ctx;
} sim_t;

static void sim_init(sim_t *s, btrx_bearing_t *h) {
    memset(s, 0, sizeof *s);
    s->h = h;
    s->t0_us = 1000000000LL;
    s->pod_off_us = 999000000LL;
    s->delay_s = 0.07;
    s->noise_db = 5.5;
    s->flags = 0x11 | 0x40;   /* target, imu ok, cal */
    s->next_we = 0;
}

static int8_t clamp8(double v) { if (v > 20) v = 20; if (v < -127) v = -127; return (int8_t)lrint(v); }

static void sim_fill_entry(sim_t *s, const sim_src_t *src, const sim_pose_t *p, btrx_bearing_report_source_t *o, int rank) {
    double dx = src->e - p->e, dy = src->n - p->n;
    double r = hypot(dx, dy); if (r < 1) r = 1;
    double beta = atan2(dx, dy) / BB_DEG;
    double dep = atan2(p->agl, r) / BB_DEG;
    float dpred = btrx_bearing_pattern_delta_db(s->h, (float)(beta - p->yaw), (float)dep);
    double level = -45 - 20 * log10(r / 10);
    int hits = src->hits ? src->hits : 5;
    double sd = s->noise_db / sqrt(hits);
    memset(o, 0, sizeof *o);
    o->src_id = src->src_id;
    o->addr_key = src->as_extra ? 0 : src->key;
    o->rssi_port = clamp8(level + dpred / 2 + sd * gauss());
    o->rssi_stbd = clamp8(level - dpred / 2 + sd * gauss());
    o->hits_port = (uint8_t)hits; o->hits_stbd = (uint8_t)hits;
    if (src->as_extra) { if (o->hits_port > 15) o->hits_port = 15; if (o->hits_stbd > 15) o->hits_stbd = 15; }
    o->peak_rssi = src->as_extra ? -128 : (int8_t)(o->rssi_port > o->rssi_stbd ? o->rssi_port : o->rssi_stbd);
    o->rank = (uint8_t)rank;
    o->pod_dev_class = 1;
    o->age_ms = src->as_extra ? 0xFFFF : 50;
}

/* Runs the sim from t_from to t_to seconds. Yaw 30 Hz, position 1 Hz, STEER 1 Hz, tick 5 Hz. */
static void sim_run(sim_t *s, double t_from, double t_to) {
    int64_t step = 33333;
    int64_t a = s->t0_us + (int64_t)(t_from * 1e6), b = s->t0_us + (int64_t)(t_to * 1e6);
    for (int64_t t = a; t < b; t += step) {
        double ts = (t - s->t0_us) / 1e6;
        sim_pose_t p = s->traj(ts, s->ctx);
        btrx_bearing_pose_t y = { .t_us = t, .push_us = t + 20000, .mask = BTRX_BEARING_POSE_YAW, .yaw_true_deg = (float)bb_wrap360((float)p.yaw) };
        btrx_bearing_push_pose(s->h, &y);
        /* 1 Hz position at whole seconds */
        if ((t - s->t0_us) % 1000000 < step) {
            double lat, lon; m2ll(p.e, p.n, &lat, &lon);
            btrx_bearing_pose_t q = { .t_us = t, .push_us = t + 100000, .mask = BTRX_BEARING_POSE_POSITION | BTRX_BEARING_POSE_AGL,
                .lat_deg = lat, .lon_deg = lon, .agl_m = (float)p.agl };
            btrx_bearing_push_pose(s->h, &q);
        }
        /* STEER: 1 s windows ending at whole seconds + 0.25 s; arrival after the delay */
        if (s->next_we == 0) {
            s->next_we = s->t0_us + ((t - s->t0_us) / 1000000 + 1) * 1000000 + 250000;
            s->next_arr = s->next_we + (int64_t)((s->delay_s + s->delay_jitter_s * urand()) * 1e6);
        }
        while (t >= s->next_arr) {
            int64_t we = s->next_we;
            sim_pose_t pr = s->traj((we - 500000 - s->t0_us) / 1e6, s->ctx);
            btrx_bearing_report_t r;
            memset(&r, 0, sizeof r);
            r.arrival_us = t; r.push_us = t + 1000;
            r.window_end_ms = (uint32_t)((we - s->pod_off_us) / 1000);
            r.steer_seq = s->seq++;
            r.flags = s->flags;
            for (int k = 0; k < s->n_src; k++) sim_fill_entry(s, &s->src[k], &pr, &r.src[r.source_count++], k);
            btrx_bearing_push_report(s->h, &r);
            s->next_we += 1000000;
            s->next_arr = s->next_we + (int64_t)((s->delay_s + s->delay_jitter_s * urand()) * 1e6);
        }
        if (t - s->last_tick >= 200000) { btrx_bearing_tick(s->h, t, NULL); s->last_tick = t; }
    }
}

typedef struct { double e, n, agl, centre, amp, rate; } hover_ctx_t;
static sim_pose_t traj_hover(double t, void *c) {
    hover_ctx_t *h = c;
    /* triangle wave yaw, +-amp around centre at `rate` deg/s */
    double period = 4 * h->amp / h->rate;
    double ph = fmod(t, period) / period;
    double y = ph < 0.25 ? ph * 4 * h->amp : (ph < 0.75 ? h->amp - (ph - 0.25) * 4 * h->amp : -h->amp + (ph - 0.75) * 4 * h->amp);
    sim_pose_t p = { h->e, h->n, h->agl, h->centre + y };
    return p;
}

/* bearing (deg true) from (e,n) to the argmax of E for key */
static float *g_grid;
static double grid_argmax(btrx_bearing_t *h, uint64_t key, int which, int64_t now, double *pe, double *pn) {
    int32_t w, hh;
    if (btrx_bearing_copy_grid(h, key, which, now, g_grid, 192 * 192, &w, &hh) != BTRX_BEARING_OK) return NAN;
    int best = 0;
    for (int c = 1; c < w * hh; c++) if (g_grid[c] > g_grid[best]) best = c;
    int row = best / w, col = best % w;
    *pe = (h->vtx0 * 16 + col + 0.5) * 10.0;
    *pn = (h->vty0 * 16 + (w - 1 - row) + 0.5) * 10.0;
    /* to sim metres (anchor = first position pushed) */
    double la, lo; bb_latlon(h, *pe, *pn, &la, &lo);
    *pn = (la - LAT0) * BB_DEG * BB_EARTH_R;
    *pe = (lo - LON0) * BB_DEG * BB_EARTH_R * cos(LAT0 * BB_DEG);
    return g_grid[best];
}

static const btrx_bearing_source_t *snap_src(const btrx_bearing_snapshot_t *s, uint64_t key) {
    for (int i = 0; s && i < s->source_count; i++) if (s->sources[i].key == key) return &s->sources[i];
    return NULL;
}

static void est_to_m(const btrx_bearing_estimate_t *e, double *x, double *y) {
    *y = (e->lat_deg - LAT0) * BB_DEG * BB_EARTH_R;
    *x = (e->lon_deg - LON0) * BB_DEG * BB_EARTH_R * cos(LAT0 * BB_DEG);
}

/* ================= 1. SipHash and short ID ================= */
static void test_siphash(void) {
    printf("siphash\n");
    uint8_t msg[64];
    for (int i = 0; i < 64; i++) msg[i] = (uint8_t)i;
    CHECK(bb_siphash24(KEY, msg, 0) == 0x726fdb47dd0e0e31ULL, "empty vector");
    CHECK(bb_siphash24(KEY, msg, 15) == 0xa129ca6149be45e5ULL, "15-byte vector %llx", (unsigned long long)bb_siphash24(KEY, msg, 15));
    CHECK(bb_siphash24(KEY, msg, 8) == 0x93f5f5799a932462ULL, "8-byte vector %llx", (unsigned long long)bb_siphash24(KEY, msg, 8));
    btrx_bearing_t *h = mk(NULL);
    uint8_t addr[6] = { 0xC0, 0x11, 0x22, 0x33, 0x44, 0x55 };
    uint64_t k1, k2, k3;
    btrx_bearing_source_key(h, addr, 1, &k1);
    btrx_bearing_source_key(h, addr, 1, &k2);
    btrx_bearing_source_key(h, addr, 0, &k3);
    CHECK(k1 == k2 && k1 != k3, "key stable, type matters");
    char id[8];
    btrx_bearing_short_id(k1, id);
    CHECK(strlen(id) == 6, "short id length");
    btrx_bearing_short_id(0xFC00000000000000ULL, id);
    CHECK(strcmp(id, "_AAAAA") == 0, "short id top bits: %s", id);
    uint8_t key2[16] = { 1 };
    btrx_bearing_config_t c; btrx_bearing_config_default(&c);
    btrx_bearing_t *h2 = NULL; btrx_bearing_create(&c, key2, &h2);
    uint64_t k4; btrx_bearing_source_key(h2, addr, 1, &k4);
    CHECK(k4 != k1, "different incident keys give different keys");
    btrx_bearing_destroy(h); btrx_bearing_destroy(h2);
}

/* ================= parsers ================= */
static size_t steer_bytes(uint8_t *p, uint8_t flags, int n_extra, int fmt, int rec_len, int count_override) {
    memset(p, 0, 64);
    p[0] = 7; p[1] = flags;
    uint32_t we = 123456; memcpy(p + 2, &we, 4);
    p[6] = 50; p[8] = 0x34; p[9] = 0x12;            /* age 50, src 0x1234 */
    p[10] = (uint8_t)-60; p[11] = (uint8_t)-63; p[12] = (uint8_t)-55; p[13] = 9; p[14] = 8;
    p[15] = 0x4C; p[16] = 0; p[17] = 1; p[18] = 1;
    for (int i = 0; i < 6; i++) p[19 + i] = (uint8_t)(0xA0 + i);
    p[25] = 4; p[26] = (uint8_t)-2;
    if (fmt < 0) return 27;
    int count = count_override >= 0 ? count_override : n_extra;
    p[27] = (uint8_t)((fmt << 4) | (count & 0x0F));
    for (int k = 0; k < n_extra; k++) {
        uint8_t *r = p + 28 + k * rec_len;
        r[0] = (uint8_t)(0x20 + k); r[1] = 0;
        r[2] = (uint8_t)(-70 - k); r[3] = (uint8_t)(-72 - k);
        r[4] = k == 0 ? 0xF1 : 0x00;                 /* port 1, stbd 15 ; then 0/0 */
        r[5] = (uint8_t)(0x01 | 0x02 | (4 << 2));     /* new, linked, audio */
    }
    return (size_t)(28 + n_extra * rec_len);
}

static void test_parsers(void) {
    printf("parsers\n");
    btrx_bearing_t *h = mk(NULL);
    uint8_t p[64]; btrx_bearing_report_t r;
    size_t n = steer_bytes(p, 0x11, 0, -1, 6, -1);
    CHECK(btrx_bearing_parse_steer(h, p, n, 5, false, &r) == 0 && r.source_count == 1 && r.ext_format == 0 && !r.truncated, "27 bytes");
    CHECK(r.window_end_ms == 123456 && r.src[0].src_id == 0x1234 && r.src[0].rssi_port == -60 && r.src[0].rssi_stbd == -63
        && r.src[0].hits_port == 9 && r.src[0].company_id == 0x4C && r.roll_x2 == 4 && r.pitch_x2 == -2, "27 fields");
    uint8_t msb[6] = { 0xA5, 0xA4, 0xA3, 0xA2, 0xA1, 0xA0 }; uint64_t k;
    btrx_bearing_source_key(h, msb, 1, &k);
    CHECK(r.src[0].addr_key == k, "addr key from LSB-first mac");
    CHECK(btrx_bearing_parse_steer(h, p, 26, 0, false, &r) == BTRX_BEARING_E_INVALID, "26 bytes invalid");
    n = steer_bytes(p, 0x11, 0, 1, 6, 0);
    CHECK(btrx_bearing_parse_steer(h, p, n, 0, false, &r) == 0 && n == 28 && r.source_count == 1 && r.ext_format == 1, "28 count 0");
    n = steer_bytes(p, 0x11, 1, 1, 6, -1);
    btrx_bearing_parse_steer(h, p, n, 0, false, &r);
    CHECK(n == 34 && r.source_count == 2 && r.src[1].src_id == 0x20 && r.src[1].rssi_port == -70 && r.src[1].hits_port == 1
        && r.src[1].hits_stbd == 15 && r.src[1].is_new && r.src[1].linked && r.src[1].pod_dev_class == 4 && r.src[1].addr_key == 0
        && r.src[1].peak_rssi == -128 && r.src[1].age_ms == 0xFFFF && r.src[1].rank == 1, "34 bytes");
    n = steer_bytes(p, 0x11, 2, 1, 6, -1);
    btrx_bearing_parse_steer(h, p, n, 0, false, &r);
    CHECK(n == 40 && r.source_count == 3 && r.src[2].src_id == 0x21 && r.src[2].hits_port == 0 && r.src[2].hits_stbd == 0 && r.src[2].rank == 2, "40 bytes");
    n = steer_bytes(p, 0x11, 1, 0, 6, -1);
    btrx_bearing_parse_steer(h, p, n, 0, false, &r);
    CHECK(r.source_count == 1 && !r.truncated, "format 0 with count: no extras");
    n = steer_bytes(p, 0x11, 2, 1, 6, 3);
    btrx_bearing_parse_steer(h, p, n, 0, false, &r);
    CHECK(r.source_count == 1 && r.truncated, "count 3: first 27 only");
    n = steer_bytes(p, 0x11, 0, 1, 6, 1);
    btrx_bearing_parse_steer(h, p, 30, 0, false, &r);
    CHECK(r.source_count == 1 && r.truncated, "30 bytes count 1: truncated");
    n = steer_bytes(p, 0x11, 2, 1, 8, -1);
    btrx_bearing_parse_steer(h, p, n, 0, false, &r);
    CHECK(n == 44 && r.source_count == 3 && r.src[2].src_id == 0x21 && !r.truncated, "8-byte records, first 6 read");
    n = steer_bytes(p, 0x10, 2, 1, 6, 0);
    btrx_bearing_parse_steer(h, p, n, 0, false, &r);
    CHECK(r.source_count == 0, "bit 0 clear count 0: nothing heard");
    n = steer_bytes(p, 0x10, 2, 1, 6, -1);
    btrx_bearing_parse_steer(h, p, n, 0, false, &r);
    CHECK(r.source_count == 0, "bit 0 clear: no entries");
    /* HEALTH */
    uint8_t hp[34]; memset(hp, 0, sizeof hp);
    uint32_t up = 777, lost = 0xFFFFFFFFu; memcpy(hp, &up, 4); hp[6] = 3; memcpy(hp + 30, &lost, 4);
    btrx_bearing_health_t he;
    CHECK(btrx_bearing_parse_health(hp, 30, &he) == 0 && he.uptime_s == 777 && he.role == 3 && !he.lost_known, "health 30");
    CHECK(btrx_bearing_parse_health(hp, 34, &he) == 0 && he.lost_known && he.phone_lost == 0xFFFFFFFFu, "health 34");
    CHECK(btrx_bearing_parse_health(hp, 29, &he) == BTRX_BEARING_E_INVALID, "health 29");
    btrx_bearing_destroy(h);
}

/* ================= model ================= */
static void test_model(void) {
    printf("model\n");
    btrx_bearing_t *h = mk(NULL);
    CHECK(btrx_bearing_pattern_delta_db(h, -45, 44) > 4, "source to port: delta > 0 (%g)", btrx_bearing_pattern_delta_db(h, -45, 44));
    CHECK(btrx_bearing_pattern_delta_db(h, 45, 44) < -4, "source to starboard: delta < 0");
    NEAR(btrx_bearing_pattern_delta_db(h, 0, 20), 0, 1e-4, "nose");
    NEAR(btrx_bearing_pattern_delta_db(h, 180, 20), 0, 1e-4, "tail");
    /* the mirror (front/back) lobe is only approximate: patches look forward as well as down */
    CHECK(btrx_bearing_pattern_delta_db(h, -30, 10) > 3 && btrx_bearing_pattern_delta_db(h, -150, 10) > 3, "front/back mirror same sign");
    bb_geom_t g; memset(&g, 0, sizeof g);
    g.sigma2 = 30; g.obs = 5; g.censored = false;
    float best = bb_loglik(h, &g, -45, 44);
    CHECK(best > bb_loglik(h, &g, 45, 44), "agreeing direction more likely");
    CHECK(bb_loglik(h, &g, -45, 44) <= 0, "normalised <= 0");
    g.censored = true; g.heard_port = true; g.obs = -80;
    float lp = bb_loglik(h, &g, -60, 30), ls = bb_loglik(h, &g, 60, 30);
    CHECK(lp > ls, "censored: heard on port favours port (%g vs %g)", lp, ls);
    g.obs = -40;   /* strong on port, nothing on starboard: starboard side very unlikely */
    CHECK(bb_loglik(h, &g, 80, 10) < logf(0.05f), "censored far below p_far before clamp");
    btrx_bearing_destroy(h);
}

/* ================= clock ================= */
static void test_clock(void) {
    printf("clock\n");
    bb_clock_t c; memset(&c, 0, sizeof c);
    bb_sync_in_t s1 = { 1000000, 1300000, 600, 600 };      /* rtt 300 ms */
    bb_sync_in_t s2 = { 2000000, 2040000, 1520, 1520 };     /* rtt 40 ms */
    bb_clock_add_pong(&c, &s1, 120000);
    bb_clock_add_pong(&c, &s2, 120000);
    bb_clock_update(&c, 2100000, 120000);
    CHECK(c.source == 2 && c.err_ms == 20, "min RTT chosen (err %d)", c.err_ms);
    NEAR(c.off_us, 500000, 1, "offset from min-RTT sample");
    bb_clock_update(&c, 2040000 + 121000000, 120000);
    CHECK(c.source == 2 && c.off_us == 500000, "old pong kept until replaced (no arrival samples)");
    memset(&c, 0, sizeof c);
    bool bl;
    bb_clock_observe_frame(&c, 1000, 1200000, false, &bl);
    bb_clock_observe_frame(&c, 2000, 2080000, false, &bl);
    bb_clock_observe_frame(&c, 3000, 3300000, false, &bl);
    bb_clock_update(&c, 3300000, 120000);
    CHECK(c.source == 1 && c.off_us == 80000 && c.err_ms == -1, "arrival minimum fallback (%lld)", (long long)c.off_us);
    bb_clock_observe_frame(&c, 9000, 9080000, true, &bl);
    CHECK(bl, "flagged backlog");
    bb_clock_observe_frame(&c, 1500, 9500000, false, &bl);
    CHECK(c.session == 1, "window_end backward: new session");
    uint32_t s = c.session;
    bb_clock_observe_frame(&c, 2500, 9600000, false, &bl);
    bb_clock_observe_frame(&c, 6000, 9700000, false, &bl);  /* delta drops by > 2 s */
    CHECK(c.session == s + 1, "offset jump: new session");
    bb_clock_observe_frame(&c, 6100, 9800000, false, &bl);
    bb_clock_observe_frame(&c, 500, 9900000 + 6000000, true, &bl);
    CHECK(bl, "flagged old backlog does not restart");
    bb_clock_uptime(&c, 100); s = c.session;
    bb_clock_uptime(&c, 50);
    CHECK(c.session == s + 1, "uptime drop: new session");
    memset(&c, 0, sizeof c);
    bb_clock_observe_frame(&c, 20000, 20100000, false, &bl);
    bb_clock_observe_frame(&c, 13000, 20200000, false, &bl);
    CHECK(c.session == 1, "7 s backward unflagged = restart (spec 3.2)");
}

/* ================= pose ================= */
static void push_yaw(btrx_bearing_t *h, int64_t t, float yaw) {
    btrx_bearing_pose_t p = { .t_us = t, .mask = BTRX_BEARING_POSE_YAW, .yaw_true_deg = yaw };
    btrx_bearing_push_pose(h, &p);
}
static void push_pos(btrx_bearing_t *h, int64_t t, double e, double n, float agl) {
    double la, lo; m2ll(e, n, &la, &lo);
    btrx_bearing_pose_t p = { .t_us = t, .mask = BTRX_BEARING_POSE_POSITION | (isfinite(agl) ? BTRX_BEARING_POSE_AGL : 0),
        .lat_deg = la, .lon_deg = lo, .agl_m = agl };
    btrx_bearing_push_pose(h, &p);
}

static void test_pose(void) {
    printf("pose\n");
    btrx_bearing_t *h = mk(NULL);
    /* yaw 355 -> 5 over 1 s at 30 Hz (crosses 0/360), position 0..10 m east */
    for (int i = 0; i <= 45; i++) push_yaw(h, 1000000 + i * 33333, bb_wrap360(355.0f + 10.0f * i / 30.0f));
    push_pos(h, 1000000, 0, 0, 30);
    push_pos(h, 2000000, 10, 0, 40);
    btrx_bearing_tick(h, 2600000, NULL);
    bb_pose_at_t a;
    CHECK(bb_pose_at(h, 2000000, &a) == 0, "pose ok");
    NEAR(bb_wrap180(a.yaw - 0.0f), 0, 0.3, "yaw across 0/360 at report time");
    NEAR(a.yaw_change, 10, 0.5, "yaw change over window");
    NEAR(a.yaw_rate, 10, 0.5, "yaw rate");
    NEAR(a.e, 5, 0.01, "position interpolation");
    NEAR(a.agl, 35, 0.01, "agl interpolation");
    btrx_bearing_destroy(h);
    h = mk(NULL);
    for (int i = 0; i <= 45; i++) push_yaw(h, 1000000 + i * 33333, bb_wrap180(175.0f + 10.0f * i / 30.0f));
    push_pos(h, 1500000, 0, 0, NAN);
    btrx_bearing_tick(h, 2600000, NULL);
    CHECK(bb_pose_at(h, 2000000, &a) == 0, "pose ok 2");
    NEAR(bb_wrap180(a.yaw - 180.0f), 0, 0.3, "yaw across +-180");
    CHECK(a.agl_default && a.agl == 30, "default AGL");
    btrx_bearing_destroy(h);
    /* gap: yaw stops 0.6 s before window end */
    h = mk(NULL);
    for (int i = 0; i <= 12; i++) push_yaw(h, 1000000 + i * 33333, 10);
    push_pos(h, 1500000, 0, 0, 30);
    btrx_bearing_tick(h, 2600000, NULL);
    CHECK(bb_pose_at(h, 2000000, &a) == BB_FATE_NO_HEADING, "yaw gap > 0.5 s: no heading");
    /* extrapolation limit: position 1.4 s before report time ok, 1.6 s not */
    for (int i = 13; i <= 200; i++) push_yaw(h, 1000000 + i * 33333, 10);
    btrx_bearing_tick(h, 8000000, NULL);
    CHECK(bb_pose_at(h, 3400000, &a) == 0, "extrapolate 1.4 s");
    CHECK(bb_pose_at(h, 3600000, &a) == BB_FATE_NO_POSITION, "extrapolate 1.6 s refused");
    btrx_bearing_destroy(h);
    /* ring capacity check at create */
    btrx_bearing_config_t c; btrx_bearing_config_default(&c);
    c.yaw_ring_capacity = 600; btrx_bearing_t *x = NULL;
    CHECK(btrx_bearing_create(&c, KEY, &x) == BTRX_BEARING_E_INVALID, "yaw ring too small for 15 s at 60 Hz");
}

/* ================= direct injection helper ================= */
/* One STEER whose window ends at t_end (host), drone hovering at (e,n) with constant yaw.
 * Pushes yaw over the window, position at the report time, the report, then ticks. */
typedef struct { int64_t last_yaw_t; int64_t pod_off; uint8_t seq; } inj_t;
static void inject(btrx_bearing_t *h, inj_t *s, int64_t t_end, double e, double n, float yaw, float agl,
        const btrx_bearing_report_source_t *ent, int n_ent, bool tick) {
    int64_t t = s->last_yaw_t ? s->last_yaw_t + 33333 : t_end - 1100000;
    if (t < t_end - 1100000) t = t_end - 1100000;
    for (; t <= t_end + 100000; t += 33333) { push_yaw(h, t, yaw); s->last_yaw_t = t; }
    push_pos(h, t_end - 500000, e, n, agl);
    btrx_bearing_report_t r; memset(&r, 0, sizeof r);
    /* zero arrival delay: the arrival-minimum offset is exact, report time = t_end - 0.5 s */
    r.arrival_us = t_end; r.push_us = r.arrival_us + 1000;
    r.window_end_ms = (uint32_t)((t_end - s->pod_off) / 1000);
    r.steer_seq = s->seq++; r.flags = 0x51;
    for (int k = 0; k < n_ent; k++) r.src[k] = ent[k];
    r.source_count = (uint8_t)n_ent;
    btrx_bearing_push_report(h, &r);
    if (tick) btrx_bearing_tick(h, t_end + 200000, NULL);
}

static btrx_bearing_report_source_t entry(uint64_t key, uint16_t id, int port, int stbd, int hits) {
    btrx_bearing_report_source_t s; memset(&s, 0, sizeof s);
    s.addr_key = key; s.src_id = id; s.rssi_port = (int8_t)port; s.rssi_stbd = (int8_t)stbd;
    s.hits_port = s.hits_stbd = (uint8_t)hits; s.pod_dev_class = 1; s.peak_rssi = (int8_t)port;
    return s;
}

static double grid_sum(btrx_bearing_t *h, uint64_t key, int which, int64_t now) {
    int32_t w, hh;
    if (btrx_bearing_copy_grid(h, key, which, now, g_grid, 192 * 192, &w, &hh) != BTRX_BEARING_OK) return 0;
    double s = 0; for (int i = 0; i < w * hh; i++) s += g_grid[i];
    return s;
}

static bb_source_t *src_of(btrx_bearing_t *h, uint64_t key) {
    int32_t i = bb_find_source(h, key);
    return i == BB_NONE ? NULL : &h->sources[i];
}

/* ================= accumulation ================= */
static void test_accumulation(void) {
    printf("accumulation\n");
    btrx_bearing_t *h = mk(NULL);
    inj_t s = { 0, 0, 0 };
    btrx_bearing_report_source_t e1 = entry(0x1111, 1, -60, -66, 5);
    inject(h, &s, 10000000, 0, 0, 0, 30, &e1, 1, true);
    btrx_bearing_tick(h, 11500000, NULL);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    CHECK(sn && sn->source_count == 1, "one source");
    if (sn) {
        CHECK(sn->sources[0].peak_alpha < 0.1f, "one report stays faint (%g)", sn->sources[0].peak_alpha);
        CHECK(sn->stats.used == 1, "used 1");
        CHECK(sn->sources[0].latest_ray.valid, "ray");
        NEAR(bb_wrap180(sn->sources[0].latest_ray.centre_true_deg - 315), 0, 20, "ray to port (heading 0 => ~315)");
        CHECK(!isnan(sn->sources[0].latest_ray.mirror_true_deg), "mirror lobe present for one report");
        btrx_bearing_snapshot_release(h, sn);
    }
    /* diversity: the same geometry again within 30 s counts half */
    bb_source_t *src = src_of(h, 0x1111);
    float eff0 = 0; for (int i = 0; i < 64; i++) if (src->buckets[i].used) eff0 += src->buckets[i].eff;
    inject(h, &s, 11000000, 1, 1, 2, 30, &e1, 1, true);
    float eff1 = 0; for (int i = 0; i < 64; i++) if (src->buckets[i].used) eff1 += src->buckets[i].eff;
    NEAR(eff1 - eff0, 0.5, 1e-6, "diversity discount 1/(1+k)");
    /* view re-centres by whole tiles; cells without tiles hold the far value (0) */
    int32_t vx = h->vtx0;
    push_pos(h, 12000000, 900, 0, 30);
    btrx_bearing_tick(h, 12100000, NULL);
    CHECK(h->vtx0 != vx, "recentred");
    double e_m = 900, cx = floor(e_m / 10);
    CHECK(h->vtx0 == bb_floordiv((int32_t)cx, 16) - 6, "whole tiles");
    btrx_bearing_destroy(h);
}

/* ================= fade ================= */
static void test_fade(void) {
    printf("fade\n");
    const float Ns[3] = { 60, 600, 1800 }, ps[3] = { 1, 2, 4 };
    for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
        btrx_bearing_t *h = mk(NULL);
        btrx_bearing_set_fade(h, Ns[a], ps[b]);
        btrx_bearing_tick(h, 1000, NULL);
        inj_t s = { 0, 0, 0 };
        btrx_bearing_report_source_t e1 = entry(0x2222, 2, -60, -62, 9);
        inject(h, &s, 10000000, 0, 0, 0, 30, &e1, 1, true);
        bb_source_t *src = src_of(h, 0x2222);
        int64_t mean = 0; float width = 0;
        for (int i = 0; i < 64; i++) if (src->buckets[i].used) { mean = bb_bucket_mean_us(&src->buckets[i]); width = (src->buckets[i].end_us - src->buckets[i].start_us) / 1e6f; }
        float want_w = btrx_bearing_bucket_width_s(Ns[a], 6, 60);
        NEAR(width, want_w, 1e-3, "bucket width");
        double s0 = grid_sum(h, 0x2222, 1, mean);
        for (int k = 1; k <= 4; k++) {
            double age = Ns[a] * k / 5.0;
            double got = grid_sum(h, 0x2222, 1, mean + (int64_t)(age * 1e6)) / s0;
            NEAR(got, 1 - pow(age / Ns[a], ps[b]), 1e-4, "weight 1-(a/N)^p");
        }
        int64_t t_gone = mean + (int64_t)((Ns[a] + want_w) * 1e6);
        btrx_bearing_tick(h, t_gone, NULL);
        CHECK(h->tiles_in_use == 0, "N=%g p=%g: zero after N + bucket, pool full again (%d)", Ns[a], ps[b], h->tiles_in_use);
        btrx_bearing_destroy(h);
    }
    /* N change: 30 min -> 1 min keeps each bucket's own width; shortening drops old buckets */
    btrx_bearing_t *h = mk(NULL);
    btrx_bearing_set_fade(h, 1800, 1);
    btrx_bearing_tick(h, 1000, NULL);
    inj_t s = { 0, 0, 0 };
    btrx_bearing_report_source_t e1 = entry(0x3333, 3, -60, -62, 9);
    inject(h, &s, 10000000, 0, 0, 0, 30, &e1, 1, true);
    inject(h, &s, 200000000, 0, 0, 0, 30, &e1, 1, true);
    bb_source_t *src = src_of(h, 0x3333);
    int nb = 0; for (int i = 0; i < 64; i++) nb += src->buckets[i].used;
    CHECK(nb == 2, "two buckets");
    btrx_bearing_set_fade(h, 900, 3);  /* lengthen-ish / p change: nothing dropped */
    btrx_bearing_tick(h, 200300000, NULL);
    nb = 0; for (int i = 0; i < 64; i++) nb += src->buckets[i].used;
    CHECK(nb == 2, "p change drops nothing");
    btrx_bearing_set_fade(h, 60, 1);
    btrx_bearing_tick(h, 200400000, NULL);
    nb = 0; for (int i = 0; i < 64; i++) nb += src->buckets[i].used;
    CHECK(nb == 1, "shortening drops the old bucket at the next tick");
    inject(h, &s, 250000000, 0, 0, 0, 30, &e1, 1, true);
    float widths[2]; int k = 0;
    for (int i = 0; i < 64; i++) if (src->buckets[i].used && k < 2) widths[k++] = (src->buckets[i].end_us - src->buckets[i].start_us) / 1e6f;
    CHECK(k == 2 && ((fabsf(widths[0] - 60) < 1e-3 && fabsf(widths[1] - 6) < 1e-3) || (fabsf(widths[1] - 60) < 1e-3 && fabsf(widths[0] - 6) < 1e-3)),
        "each bucket keeps its own width");
    btrx_bearing_destroy(h);
    /* pool exhaustion expires the oldest bucket early */
    btrx_bearing_config_t c; btrx_bearing_config_default(&c);
    c.tile_pool_tiles = 40;
    h = mk(&c);
    s = (inj_t){ 0, 0, 0 };
    inject(h, &s, 10000000, 0, 0, 0, 30, &e1, 1, true);
    inject(h, &s, 100000000, 600, 0, 0, 30, &e1, 1, true);
    btrx_bearing_tick(h, 101000000, NULL);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    CHECK(sn && sn->stats.buckets_expired_early >= 1, "pool exhaustion counted");
    if (sn) btrx_bearing_snapshot_release(h, sn);
    btrx_bearing_destroy(h);
    /* order independence within a bucket */
    btrx_bearing_t *h1 = mk(NULL), *h2 = mk(NULL);
    inj_t s1 = { 0, 0, 0 }, s2 = { 0, 0, 0 };
    push_pos(h1, 1000000, 0, 0, 30); push_pos(h2, 1000000, 0, 0, 30);
    btrx_bearing_report_source_t ea = entry(0x4444, 4, -60, -66, 5), eb = entry(0x4444, 4, -64, -61, 7);
    inject(h1, &s1, 10000000, 0, 0, 10, 30, &ea, 1, true);
    inject(h1, &s1, 13000000, 40, 0, 80, 30, &eb, 1, true);
    inject(h2, &s2, 10000000, 40, 0, 80, 30, &eb, 1, true);
    inject(h2, &s2, 13000000, 0, 0, 10, 30, &ea, 1, true);
    static float g1[192 * 192];
    int32_t w, hh;
    btrx_bearing_copy_grid(h1, 0x4444, 0, 14000000, g1, 192 * 192, &w, &hh);
    btrx_bearing_copy_grid(h2, 0x4444, 0, 14000000, g_grid, 192 * 192, &w, &hh);
    CHECK(memcmp(g1, g_grid, sizeof g1) == 0, "tile sums identical for any order");
    btrx_bearing_destroy(h1); btrx_bearing_destroy(h2);
    NEAR(btrx_bearing_half_strength_s(600, 1), 300, 1e-3, "half strength linear");
    NEAR(btrx_bearing_half_strength_s(600, 2), 424.264, 1e-2, "half strength p=2");
}

/* ================= mute and tap query ================= */
static void feed_two(btrx_bearing_t *h, inj_t *s, int64_t t0, int n) {
    for (int i = 0; i < n; i++) {
        btrx_bearing_report_source_t e[2] = { entry(0xA1, 10, -55, -63, 9), entry(0xB2, 11, -64, -57, 9) };
        inject(h, s, t0 + i * 1000000LL, i * 3.0, 0, (float)(i * 7 % 90), 30, e, 1, false);
        inject(h, s, t0 + i * 1000000LL + 500000, i * 3.0 + 1, 0, (float)(i * 7 % 90), 30, &e[1], 1, true);
    }
}

static void test_mute(void) {
    printf("mute\n");
    btrx_bearing_t *h1 = mk(NULL), *h2 = mk(NULL);
    inj_t s1 = { 0, 0, 0 }, s2 = { 0, 0, 0 };
    feed_two(h1, &s1, 10000000, 20);
    feed_two(h2, &s2, 10000000, 20);
    uint64_t kb = 0xB2;
    btrx_bearing_set_source_flags(h1, &kb, 1, BTRX_BEARING_SRC_MUTED, BTRX_BEARING_SRC_MUTED);
    bool pub = false;
    btrx_bearing_tick(h1, 31000000, &pub);
    CHECK(pub, "mute publishes at the next tick");
    const btrx_bearing_snapshot_t *a = btrx_bearing_snapshot_acquire(h1);
    const btrx_bearing_source_t *sb = snap_src(a, kb), *sa = snap_src(a, 0xA1);
    CHECK(sb && (sb->flags & BTRX_BEARING_SRC_MUTED) && sb->peak_alpha > 0, "muted still has alpha raster");
    /* composite has no B colour: every lit pixel matches A's colour at A's alpha */
    int bad = 0;
    for (int c = 0; a && c < a->width * a->height; c++) {
        uint8_t al = sa->alpha[c];
        if (a->rgba[c * 4 + 3] != al) bad++;
    }
    CHECK(bad == 0, "muted source absent from RGBA (%d px)", bad);
    uint32_t used_before = a ? a->stats.used : 0, b_before = sb ? sb->reports_used : 0;
    btrx_bearing_snapshot_release(h1, a);
    feed_two(h1, &s1, 31000000, 5);
    feed_two(h2, &s2, 31000000, 5);
    a = btrx_bearing_snapshot_acquire(h1);
    CHECK(a && a->stats.used > used_before && snap_src(a, kb)->reports_used > b_before, "muted keeps accumulating");
    btrx_bearing_snapshot_release(h1, a);
    btrx_bearing_set_source_flags(h1, &kb, 1, BTRX_BEARING_SRC_MUTED, 0);
    btrx_bearing_tick(h1, 37000000, NULL);
    btrx_bearing_tick(h2, 37000000, NULL);
    a = btrx_bearing_snapshot_acquire(h1);
    const btrx_bearing_snapshot_t *b = btrx_bearing_snapshot_acquire(h2);
    CHECK(a && b && memcmp(a->rgba, b->rgba, (size_t)a->width * a->height * 4) == 0, "unmute == never muted");
    /* tap query on the real snapshot: at the drone, both sources; far away, none */
    if (a) {
        double lat, lon; m2ll(30, 0, &lat, &lon);
        btrx_bearing_hit_t hits[8];
        int n = btrx_bearing_snapshot_sources_at(a, lat, lon, 60, hits, 8);
        CHECK(n == 2, "both sources near the drone (%d)", n);
        m2ll(5000, 5000, &lat, &lon);
        CHECK(btrx_bearing_snapshot_sources_at(a, lat, lon, 60, hits, 8) == 0, "off the shading: empty");
    }
    btrx_bearing_snapshot_release(h1, a);
    btrx_bearing_snapshot_release(h2, b);
    btrx_bearing_destroy(h1); btrx_bearing_destroy(h2);
}

static void test_sources_at(void) {
    printf("sources_at\n");
    static uint8_t al[3][64 * 64];
    memset(al, 0, sizeof al);
    btrx_bearing_snapshot_t s; memset(&s, 0, sizeof s);
    s.width = s.height = 64;
    s.south_deg = 37.0; s.north_deg = 37.0 + 640 / (BB_DEG * BB_EARTH_R);
    s.west_deg = -122.0; s.east_deg = -122.0 + 640 / (BB_DEG * BB_EARTH_R * cos(37.0 * BB_DEG));
    s.source_count = 3;
    for (int k = 0; k < 3; k++) s.sources[k].alpha = al[k];
    for (int r = 0; r < 64; r++) for (int c = 0; c < 64; c++) {
        al[0][r * 64 + c] = 40;                              /* everywhere, faint */
        if (c < 32) al[1][r * 64 + c] = 200;                 /* west half, strong */
        if (r == 0 && c == 63) al[2][r * 64 + c] = 255;      /* NE corner only */
    }
    btrx_bearing_hit_t h[8];
    double lat = 37.0 + 320 / (BB_DEG * BB_EARTH_R), lon = -122.0 + 100 / (BB_DEG * BB_EARTH_R * cos(37.0 * BB_DEG));
    int n = btrx_bearing_snapshot_sources_at(&s, lat, lon, 30, h, 8);
    CHECK(n == 2 && h[0].source_index == 1 && h[1].source_index == 0, "ordered by contribution");
    n = btrx_bearing_snapshot_sources_at(&s, s.north_deg, s.east_deg, 50, h, 8);
    CHECK(n == 2 && (h[0].source_index == 2 || h[1].source_index == 2), "radius clipped at the grid edge, corner source found (%d)", n);
    n = btrx_bearing_snapshot_sources_at(&s, lat, lon, 30, h, 1);
    CHECK(n == 1 && h[0].source_index == 1, "max_out respected");
    n = btrx_bearing_snapshot_sources_at(&s, 38.0, -121.0, 30, h, 8);
    CHECK(n == 0, "outside the grid");
    n = btrx_bearing_snapshot_sources_at(&s, lat, lon, 0, h, 8);
    CHECK(n == 2, "zero radius still checks the cell");
}

/* ================= lag ================= */
static char g_log[64][BB_LOG_LINE];
static int g_log_n;
static void cap_log(void *ctx, int level, const char *msg) {
    (void)ctx; (void)level;
    if (g_log_n < 64) { strncpy(g_log[g_log_n], msg, BB_LOG_LINE - 1); g_log_n++; }
}

static void test_lag(void) {
    printf("lag\n");
    btrx_bearing_config_t c; btrx_bearing_config_default(&c);
    c.lag_trace = true; c.log = cap_log;
    btrx_bearing_t *h = mk(&c);
    g_log_n = 0;
    /* exact offset: host - pod = 5 s, zero RTT */
    btrx_bearing_push_time_sync(h, 6000000, 1000, 1000, 6000000);
    btrx_bearing_report_source_t e1 = entry(0x5555, 5, -60, -64, 9);
    /* report: arrival +70 ms, push +1 ms; position arrives only at +600 ms (pos wait) */
    int64_t te = 20000000;
    for (int64_t t = te - 1100000; t <= te + 100000; t += 33333) push_yaw(h, t, 0);
    push_pos(h, te - 1000000, 0, 0, 30);
    btrx_bearing_report_t r; memset(&r, 0, sizeof r);
    r.arrival_us = te + 70000; r.push_us = te + 71000; r.window_end_ms = (uint32_t)((te - 5000000) / 1000);
    r.flags = 0x51; r.src[0] = e1; r.source_count = 1; r.steer_seq = 9;
    btrx_bearing_push_report(h, &r);
    btrx_bearing_tick(h, te + 100000, NULL);   /* waits for position (stream live) */
    push_pos(h, te - 500000 + 1, 0, 0, 30);
    btrx_bearing_tick(h, te + 600000, NULL);
    btrx_bearing_tick(h, te + 1700000, NULL);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    uint64_t seq = sn ? sn->seq : 0;
    btrx_bearing_snapshot_release(h, sn);
    btrx_bearing_note_rendered(h, seq, te + 1750000);
    btrx_bearing_tick(h, te + 2800000, NULL);
    sn = btrx_bearing_snapshot_acquire(h);
    CHECK(sn != NULL, "snapshot");
    if (sn) {
        const btrx_bearing_lag_t *L = sn->stats.lag;
        CHECK(L[BTRX_BEARING_STAGE_POD_TO_ARRIVAL].count == 1 && L[BTRX_BEARING_STAGE_POD_TO_ARRIVAL].p50_ms == 70, "T2-T0 70 ms (%d)", L[0].p50_ms);
        CHECK(L[BTRX_BEARING_STAGE_ARRIVAL_TO_PUSH].p50_ms == 1, "T3-T2 1 ms");
        CHECK(L[BTRX_BEARING_STAGE_POSE_WAIT_POS].count == 1 && L[BTRX_BEARING_STAGE_POSE_WAIT_POS].p50_ms == 529, "pose wait pos (%d)", L[BTRX_BEARING_STAGE_POSE_WAIT_POS].p50_ms);
        CHECK(L[BTRX_BEARING_STAGE_POSE_WAIT_YAW].count == 0, "no yaw wait");
        CHECK(L[BTRX_BEARING_STAGE_TICK_WAIT].p50_ms == 0, "tick wait 0");
        CHECK(L[BTRX_BEARING_STAGE_PUBLISH_WAIT].count == 1, "publish wait counted");
        CHECK(L[BTRX_BEARING_STAGE_RENDER].count == 1 && L[BTRX_BEARING_STAGE_TOTAL].count == 1, "render and total");
        CHECK(sn->stats.offset_source == 2 && sn->stats.pod_offset_ms == 5000, "offset from TIME_PONG");
    }
    btrx_bearing_snapshot_release(h, sn);
    bool saw_lag = false, saw_rnd = false;
    for (int i = 0; i < g_log_n; i++) {
        if (strncmp(g_log[i], "BBLAG seq=9 src=", 16) == 0 && strstr(g_log[i], "rank=0 fate=used wait=pos t0=20000000 t2=20070000 t3=20071000")) saw_lag = true;
        if (strncmp(g_log[i], "BBRND snap=", 11) == 0 && strstr(g_log[i], " t7=21750000")) saw_rnd = true;
    }
    CHECK(saw_lag, "BBLAG line format (%s)", g_log_n ? g_log[0] : "none");
    CHECK(saw_rnd, "BBRND line format");
    /* a report 16 s late on arrival is dropped against pod->arrival */
    memset(&r, 0, sizeof r);
    te = 40000000;
    for (int64_t t = te - 1100000; t <= te + 17000000; t += 33333) push_yaw(h, t, 0);
    r.arrival_us = te + 16000000; r.push_us = r.arrival_us; r.window_end_ms = (uint32_t)((te - 5000000) / 1000);
    r.flags = 0x51; r.src[0] = e1; r.source_count = 1;
    btrx_bearing_push_report(h, &r);
    btrx_bearing_tick(h, te + 16100000, NULL);
    btrx_bearing_tick(h, te + 17200000, NULL);
    sn = btrx_bearing_snapshot_acquire(h);
    CHECK(sn && sn->stats.late_dropped == 1 && sn->stats.lag[BTRX_BEARING_STAGE_POD_TO_ARRIVAL].late_drops == 1, "late drop counted on pod->arrival");
    btrx_bearing_snapshot_release(h, sn);
    /* pending-ring overflow drops the oldest: 40 frames waiting for a position that never comes */
    btrx_bearing_destroy(h);
    h = mk(NULL);
    te = 60000000;
    push_pos(h, te - 5000000, 0, 0, 30);
    for (int i = 0; i < 40; i++) {
        memset(&r, 0, sizeof r);
        r.arrival_us = te + i * 1000; r.window_end_ms = (uint32_t)(te / 1000 - 1000 + i); r.flags = 0x51; r.src[0] = e1; r.source_count = 1;
        btrx_bearing_push_report(h, &r);
        push_pos(h, te - 5000000 + i + 1, 0, 0, 30);
    }
    for (int64_t t = te - 1100000; t <= te; t += 33333) push_yaw(h, t, 0);
    btrx_bearing_tick(h, te + 50000, NULL);
    CHECK(h->stats.ring_dropped == 8 && h->pending_n == 32, "pending ring overflow drops the oldest (%u, %d)", h->stats.ring_dropped, h->pending_n);
    btrx_bearing_destroy(h);
}

/* ================= multi-source and source resolution ================= */
static void test_multi(void) {
    printf("multi-source\n");
    btrx_bearing_t *h = mk(NULL);
    inj_t s = { 0, 0, 0 };
    btrx_bearing_report_source_t e[3] = { entry(0x6661, 21, -55, -60, 9), entry(0, 22, -70, -66, 9), entry(0, 23, -75, -72, 4) };
    e[1].rank = 1; e[2].rank = 2;
    inject(h, &s, 10000000, 0, 0, 0, 30, e, 3, true);
    btrx_bearing_tick(h, 11500000, NULL);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    CHECK(sn && sn->source_count == 3, "3-entry report feeds three sources");
    int prov = 0; bool colours_differ = true;
    for (int i = 0; sn && i < sn->source_count; i++) {
        prov += sn->sources[i].provisional;
        CHECK(sn->sources[i].reports_used == 1 && sn->sources[i].peak_alpha > 0, "grid %d fed", i);
        for (int j = 0; j < i; j++) if (memcmp(sn->sources[i].rgb, sn->sources[j].rgb, 3) == 0) colours_differ = false;
    }
    CHECK(prov == 2 && colours_differ, "two unresolved extras, separate colours");
    CHECK(sn && sn->stats.extras_unresolved == 2, "extras_unresolved");
    btrx_bearing_snapshot_release(h, sn);
    btrx_bearing_destroy(h);

    /* 1-entry report: 27-byte and 28-byte (count 0) payloads give identical grids */
    uint8_t p[64]; btrx_bearing_report_t r27, r28;
    btrx_bearing_t *ha = mk(NULL), *hb = mk(NULL);
    size_t n = steer_bytes(p, 0x51, 0, -1, 6, -1);
    btrx_bearing_parse_steer(ha, p, n, 0, false, &r27);
    n = steer_bytes(p, 0x51, 0, 1, 6, 0);
    btrx_bearing_parse_steer(hb, p, n, 0, false, &r28);
    inj_t sa = { 0, 0, 0 }, sb = { 0, 0, 0 };
    inject(ha, &sa, 10000000, 0, 0, 30, 30, &r27.src[0], 1, true);
    inject(hb, &sb, 10000000, 0, 0, 30, 30, &r28.src[0], 1, true);
    static float g1[192 * 192]; int32_t w, hh;
    btrx_bearing_copy_grid(ha, r27.src[0].addr_key, 0, 11000000, g1, 192 * 192, &w, &hh);
    btrx_bearing_copy_grid(hb, r28.src[0].addr_key, 0, 11000000, g_grid, 192 * 192, &w, &hh);
    CHECK(memcmp(g1, g_grid, sizeof g1) == 0, "single-source path bit for bit");
    btrx_bearing_destroy(ha); btrx_bearing_destroy(hb);
}

/* Pod 0x0106+: src_id is a hash of the address, stable across pod restarts (btrx 74ed238). */
static void test_stable_ids(void) {
    printf("stable src ids\n");
    btrx_bearing_t *h = mk(NULL);
    inj_t s = { 0, 0, 0 };
    CHECK(btrx_bearing_set_stable_src_ids(NULL, true) == BTRX_BEARING_E_INVALID, "null handle");
    CHECK(btrx_bearing_set_stable_src_ids(h, true) == BTRX_BEARING_OK, "set stable");
    btrx_bearing_tick(h, 9000000, NULL);
    CHECK(h->stable_src_ids, "applied at the tick");
    const uint64_t K = 0x5151;
    const uint16_t W = 0xF1A3;                    /* large, not sequential */
    btrx_bearing_report_source_t prim = entry(0x7000, 0x9C41, -50, -55, 9);
    btrx_bearing_report_source_t ex = entry(0, W, -70, -64, 9); ex.rank = 1;
    btrx_bearing_report_source_t ex1 = entry(0, 1, -72, -66, 9); ex1.rank = 2;
    btrx_bearing_report_source_t e[3] = { prim, ex, ex1 };
    inject(h, &s, 10000000, 0, 0, 0, 30, e, 3, true);
    btrx_bearing_tick(h, 11300000, NULL);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    int keyed = 0, prov = 0; bool saw_w = false, saw_1 = false;
    for (int i = 0; sn && i < sn->source_count; i++) {
        keyed += sn->sources[i].src_keyed; prov += sn->sources[i].provisional;
        if (sn->sources[i].src_id == W) saw_w = true;
        if (sn->sources[i].src_id == 1) saw_1 = true;
    }
    CHECK(sn && sn->source_count == 3 && keyed == 2 && prov == 0, "extras are real sources keyed by src_id (%d keyed, %d provisional)", keyed, prov);
    CHECK(saw_w && saw_1, "src ids 0xF1A3 and 1 reported in the snapshot");
    if (sn) btrx_bearing_snapshot_release(h, sn);
    int32_t slot_w = BB_NONE;
    for (int i = 0; i < BTRX_BEARING_MAX_SOURCES; i++) if (h->sources[i].used && h->sources[i].src_id == W) slot_w = i;
    CHECK(slot_w != BB_NONE, "slot for 0xF1A3");
    uint64_t key_w = slot_w != BB_NONE ? h->sources[slot_w].key : 0;
    /* pod restart: the id table is cleared, the src_id key still finds the same source */
    int before = 0; for (int i = 0; i < BTRX_BEARING_MAX_SOURCES; i++) before += h->sources[i].used;
    uint32_t sess = h->clock.session;
    s.pod_off += 17000000;
    inject(h, &s, 12000000, 50, 0, 20, 30, e, 3, true);
    CHECK(h->clock.session == sess + 1, "restart detected");
    int after = 0; for (int i = 0; i < BTRX_BEARING_MAX_SOURCES; i++) after += h->sources[i].used;
    CHECK(after == before, "same sources after a pod restart (%d -> %d)", before, after);
    CHECK(slot_w != BB_NONE && h->sources[slot_w].reports_in == 2 && h->sources[slot_w].key == key_w, "0xF1A3 kept its source (%u reports)",
          slot_w != BB_NONE ? h->sources[slot_w].reports_in : 0);
    /* the same src_id with an address: the src-keyed source takes the address key, no new source */
    uint32_t merges = h->stats.provisional_merges;
    btrx_bearing_report_source_t pw = entry(K, W, -58, -61, 9);
    inject(h, &s, 13000000, 100, 0, 40, 30, &pw, 1, true);
    CHECK(src_of(h, K) == &h->sources[slot_w] && !h->sources[slot_w].src_keyed && h->stats.provisional_merges == merges + 1,
          "src-keyed source takes the address key");
    int after2 = 0; for (int i = 0; i < BTRX_BEARING_MAX_SOURCES; i++) after2 += h->sources[i].used;
    CHECK(after2 == after, "no new source for the address");
    /* another restart, then 0xF1A3 as an extra again: found by its src_id alias */
    s.pod_off += 17000000;
    btrx_bearing_report_source_t e2[2] = { prim, ex };
    inject(h, &s, 14000000, 150, 0, 60, 30, e2, 2, true);
    CHECK(src_of(h, K) == &h->sources[slot_w] && h->sources[slot_w].reports_in == 4, "extra finds the address source by src_id after a restart (%u)",
          h->sources[slot_w].reports_in);
    int after3 = 0; for (int i = 0; i < BTRX_BEARING_MAX_SOURCES; i++) after3 += h->sources[i].used;
    CHECK(after3 == after2, "still no new source");
    btrx_bearing_destroy(h);

    /* older firmware (default): the same extra after a pod restart is a new provisional source */
    h = mk(NULL);
    inj_t s2 = { 0, 0, 0 };
    inject(h, &s2, 10000000, 0, 0, 0, 30, e, 3, true);
    int b0 = 0; for (int i = 0; i < BTRX_BEARING_MAX_SOURCES; i++) b0 += h->sources[i].used;
    s2.pod_off += 17000000;
    inject(h, &s2, 12000000, 50, 0, 20, 30, e, 3, true);
    int b1 = 0; for (int i = 0; i < BTRX_BEARING_MAX_SOURCES; i++) b1 += h->sources[i].used;
    CHECK(b1 == b0 + 2, "older firmware: extras are per pod session (%d -> %d)", b0, b1);
    btrx_bearing_destroy(h);

    /* more distinct ids than the id table holds (64): every extra still resolves */
    h = mk(NULL);
    inj_t s3 = { 0, 0, 0 };
    btrx_bearing_set_stable_src_ids(h, true);
    btrx_bearing_tick(h, 9000000, NULL);
    for (int i = 0; i < 80; i++) {
        btrx_bearing_report_source_t a = entry(0x7000, 0x9C41, -50, -55, 9);
        btrx_bearing_report_source_t b = entry(0, (uint16_t)(65535 - i * 811), -70, -66, 9); b.rank = 1;
        btrx_bearing_report_source_t ab[2] = { a, b };
        inject(h, &s3, 10000000 + (int64_t)i * 1000000, 0, 0, (float)(i % 36) * 10, 30, ab, 2, true);
    }
    CHECK(h->srcid_n <= 64, "id table bounded (%d)", h->srcid_n);
    int used = 0; for (int i = 0; i < BTRX_BEARING_MAX_SOURCES; i++) used += h->sources[i].used;
    CHECK(used == h->cfg.max_sources, "eviction keeps %d sources (%d)", h->cfg.max_sources, used);
    btrx_bearing_destroy(h);

    uint8_t hp[44]; memset(hp, 0, sizeof hp);
    hp[0] = 9; hp[6] = 2; hp[30] = 5; hp[34] = 0x04; hp[38] = 2; hp[39] = 3; hp[43] = 0x0F;
    btrx_bearing_health_t he;
    CHECK(btrx_bearing_parse_health(hp, 44, &he) == 0 && he.uptime_s == 9 && he.role == 2 && he.lost_known && he.phone_lost == 5, "health 44");
}

static void test_resolution(void) {
    printf("source resolution\n");
    /* A: extra (src 31) first, then primary with address K and src 31: merge.
       B: both entries attributed to K from the start. Grids must match exactly. */
    btrx_bearing_t *ha = mk(NULL), *hb = mk(NULL);
    inj_t sa = { 0, 0, 0 }, sb = { 0, 0, 0 };
    const uint64_t K = 0x7777;
    btrx_bearing_report_source_t prim0 = entry(0x7000, 30, -50, -55, 9);
    btrx_bearing_report_source_t ex = entry(0, 31, -70, -64, 9); ex.rank = 1;
    btrx_bearing_report_source_t exk = entry(K, 31, -70, -64, 9);
    btrx_bearing_report_source_t ea[2] = { prim0, ex }, eb[2] = { prim0, exk };
    inject(ha, &sa, 10000000, 0, 0, 0, 30, ea, 2, true);
    inject(hb, &sb, 10000000, 0, 0, 0, 30, eb, 2, true);
    btrx_bearing_tick(ha, 11300000, NULL);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(ha);
    CHECK(sn && sn->source_count == 2 && (sn->sources[0].provisional || sn->sources[1].provisional), "provisional source");
    btrx_bearing_snapshot_release(ha, sn);
    btrx_bearing_report_source_t pk = entry(K, 31, -58, -61, 9);
    inject(ha, &sa, 12000000, 50, 0, 40, 30, &pk, 1, true);
    inject(hb, &sb, 12000000, 50, 0, 40, 30, &pk, 1, true);
    btrx_bearing_tick(ha, 13300000, NULL);
    sn = btrx_bearing_snapshot_acquire(ha);
    const btrx_bearing_source_t *s = snap_src(sn, K);
    CHECK(sn && sn->source_count == 2 && s && !s->provisional && s->reports_used == 2, "provisional takes the address key");
    btrx_bearing_snapshot_release(ha, sn);
    static float g1[192 * 192]; int32_t w, hh;
    btrx_bearing_copy_grid(ha, K, 0, 13000000, g1, 192 * 192, &w, &hh);
    btrx_bearing_copy_grid(hb, K, 0, 13000000, g_grid, 192 * 192, &w, &hh);
    CHECK(memcmp(g1, g_grid, sizeof g1) == 0, "exact merge");
    /* merge into an existing address source: extra src 41 provisional, then K2 (already known) as primary with src 41 */
    btrx_bearing_report_source_t k2 = entry(0x8888, 40, -55, -60, 9);
    inject(ha, &sa, 14000000, 100, 0, 0, 30, &k2, 1, true);
    btrx_bearing_report_source_t ex41 = entry(0, 41, -66, -62, 9); ex41.rank = 1;
    btrx_bearing_report_source_t e2[2] = { k2, ex41 };
    e2[0].src_id = 40;
    inject(ha, &sa, 15000000, 150, 0, 10, 30, e2, 2, true);
    uint32_t merges = ha->stats.provisional_merges;
    btrx_bearing_report_source_t k2b = k2; k2b.src_id = 41;
    inject(ha, &sa, 16000000, 200, 0, 20, 30, &k2b, 1, true);
    CHECK(ha->stats.provisional_merges == merges + 1, "provisional merged into the existing source");
    bb_source_t *sk2 = src_of(ha, 0x8888);
    CHECK(sk2 && sk2->reports_used == 4, "merged counts (%u)", sk2 ? sk2->reports_used : 0);
    /* linked address change: alias; mute by alias */
    btrx_bearing_report_source_t lk = entry(0x9999, 40, -55, -60, 9); lk.linked = true;
    inject(ha, &sa, 17000000, 250, 0, 30, 30, &lk, 1, true);
    CHECK(src_of(ha, 0x9999) == sk2, "linked change adds an alias");
    uint64_t alias = 0x9999;
    btrx_bearing_set_source_flags(ha, &alias, 1, BTRX_BEARING_SRC_MUTED, BTRX_BEARING_SRC_MUTED);
    btrx_bearing_tick(ha, 17500000, NULL);
    CHECK(sk2->flags & BTRX_BEARING_SRC_MUTED, "mute by alias");
    /* pod restart clears the id table: same src 31 as extra is a new provisional source */
    int before = 0; for (int i = 0; i < BTRX_BEARING_MAX_SOURCES; i++) before += ha->sources[i].used;
    uint32_t sess = ha->clock.session;
    sa.pod_off += 17000000;   /* pod clock restarted: window_end_ms goes backward */
    btrx_bearing_report_source_t e3[2] = { prim0, ex };
    inject(ha, &sa, 18000000, 300, 0, 40, 30, e3, 2, true);
    CHECK(ha->clock.session == sess + 1, "restart detected");
    int after = 0; for (int i = 0; i < BTRX_BEARING_MAX_SOURCES; i++) after += ha->sources[i].used;
    CHECK(after == before + 1, "id table cleared: new provisional (%d -> %d)", before, after);
    btrx_bearing_destroy(ha); btrx_bearing_destroy(hb);
    /* ignored sources are not accumulated; flags set before first sight apply when it appears */
    btrx_bearing_t *h = mk(NULL);
    inj_t s0 = { 0, 0, 0 };
    uint64_t own = 0xDD;
    btrx_bearing_set_source_flags(h, &own, 1, BTRX_BEARING_SRC_IGNORED, BTRX_BEARING_SRC_IGNORED);
    btrx_bearing_report_source_t eo = entry(own, 50, -40, -42, 30);
    inject(h, &s0, 10000000, 0, 0, 0, 30, &eo, 1, true);
    CHECK(h->stats.ignored == 1 && src_of(h, own)->reports_used == 0 && h->tiles_in_use == 0, "ignored not accumulated");
    btrx_bearing_destroy(h);
}

/* ================= backlog and HEALTH ================= */
static void test_backlog_health(void) {
    printf("backlog and health\n");
    btrx_bearing_t *h = mk(NULL);
    btrx_bearing_push_time_sync(h, 1000000, 0, 0, 1000000);   /* host - pod = 1 s */
    /* 60 s of poses, live */
    for (int64_t t = 1000000; t <= 61000000; t += 33333) push_yaw(h, t, (float)fmod(t / 1e6 * 6, 360));
    for (int64_t t = 1000000; t <= 61000000; t += 1000000) push_pos(h, t, (t / 1e6) * 2, 0, 30);
    btrx_bearing_tick(h, 61000000, NULL);
    /* reconnect at 61 s: the 32 newest queued frames, windows ending 60 s .. 29 s */
    btrx_bearing_report_source_t e1 = entry(0xEE, 60, -60, -62, 9);
    for (int i = 31; i >= 0; i--) {
        btrx_bearing_report_t r; memset(&r, 0, sizeof r);
        int64_t we = 60000000 - i * 1000000LL;
        r.arrival_us = 61000000 + (31 - i) * 2000; r.push_us = r.arrival_us;
        r.window_end_ms = (uint32_t)((we - 1000000) / 1000);
        r.flags = 0x51; r.backlog = true; r.src[0] = e1; r.source_count = 1;
        btrx_bearing_push_report(h, &r);
    }
    for (int k = 0; k < 12; k++) btrx_bearing_tick(h, 61100000 + k * 200000, NULL);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    CHECK(sn != NULL, "snapshot");
    if (sn) {
        /* used: report age (now - t0) <= 15 s at evaluation time */
        CHECK(sn->stats.backlog_history >= 16 && sn->stats.backlog_history <= 18, "older backlog is history (%u)", sn->stats.backlog_history);
        CHECK(sn->stats.used + sn->stats.gated == 32 - sn->stats.backlog_history, "the rest used (%u used %u gated)", sn->stats.used, sn->stats.gated);
        CHECK(sn->stats.late_dropped == 0, "history is not late");
        CHECK(sn->stats.lag[BTRX_BEARING_STAGE_POD_TO_ARRIVAL].count == 32 - sn->stats.backlog_history, "history kept out of the histograms");
    }
    btrx_bearing_snapshot_release(h, sn);
    /* HEALTH lost counts */
    btrx_bearing_push_health(h, 0, 3, 100, false, 0);
    btrx_bearing_tick(h, 64000000, NULL);
    CHECK(!h->stats.desk_queue_lost_known, "30-byte: unknown");
    btrx_bearing_push_health(h, 0, 3, 130, true, 5);
    btrx_bearing_tick(h, 64200000, NULL);
    CHECK(h->stats.desk_queue_lost_known && h->stats.desk_queue_lost == 5, "first 34-byte value counts");
    btrx_bearing_note_connected(h, 0);
    btrx_bearing_push_health(h, 0, 3, 160, true, 8);
    btrx_bearing_tick(h, 64400000, NULL);
    CHECK(h->stats.desk_queue_lost == 8 && h->stats.desk_queue_lost_since_connect == 3, "difference; since connect");
    btrx_bearing_push_health(h, 0, 3, 10, true, 2);
    btrx_bearing_tick(h, 64600000, NULL);
    CHECK(h->stats.desk_queue_lost == 10 && h->stats.desk_queue_lost_since_connect == 5, "restart: new value is the delta");
    btrx_bearing_push_health(h, 0, 3, 40, true, 0xFFFFFFFFu);
    btrx_bearing_push_health(h, 0, 3, 70, true, 0xFFFFFFFFu);
    btrx_bearing_tick(h, 64800000, NULL);
    CHECK(h->stats.desk_queue_lost == 0xFFFFFFFFu, "saturates");
    uint32_t sess = h->clock.session;
    btrx_bearing_push_health(h, 0, 2, 500, true, 0);
    btrx_bearing_push_health(h, 0, 2, 3, true, 0);
    btrx_bearing_tick(h, 65000000, NULL);
    CHECK(h->clock.session == sess + 1, "pod A uptime drop: new session");
    btrx_bearing_destroy(h);
}

/* ================= threading, allocation, ABI ================= */
static atomic_bool g_stop;
static void *push_thread(void *arg) {
    btrx_bearing_t *h = arg;
    int64_t t = 1000000;
    uint64_t k = 0xAB;
    while (!atomic_load(&g_stop)) {
        btrx_bearing_pose_t p = { .t_us = t, .mask = BTRX_BEARING_POSE_YAW, .yaw_true_deg = 1 };
        btrx_bearing_push_pose(h, &p);
        btrx_bearing_set_source_flags(h, &k, 1, BTRX_BEARING_SRC_MUTED, (t / 1000) & 2);
        btrx_bearing_report_t r; memset(&r, 0, sizeof r);
        r.arrival_us = t; r.window_end_ms = (uint32_t)(t / 1000); r.flags = 0x51; r.source_count = 1;
        r.src[0] = entry(k, 1, -60, -62, 5);
        btrx_bearing_push_report(h, &r);
        t += 1000;
    }
    return NULL;
}

static void test_threads(void) {
    printf("threads\n");
    btrx_bearing_t *h = mk(NULL);
    pthread_t th[3];
    atomic_store(&g_stop, false);
    for (int i = 0; i < 3; i++) pthread_create(&th[i], NULL, push_thread, h);
    double t0 = now_s();
    int ticks = 0, snaps = 0;
    int64_t t = 1000000;
    while (now_s() - t0 < 1.5) {
        btrx_bearing_tick(h, t, NULL);
        const btrx_bearing_snapshot_t *s = btrx_bearing_snapshot_acquire(h);
        if (s) { snaps++; btrx_bearing_snapshot_release(h, s); }
        t += 200000; ticks++;
    }
    atomic_store(&g_stop, true);
    for (int i = 0; i < 3; i++) pthread_join(th[i], NULL);
    CHECK(ticks > 10, "ticked %d times", ticks);
    /* concurrent tick gets BUSY */
    pthread_mutex_lock(&h->work_mu);
    CHECK(btrx_bearing_tick(h, t, NULL) == BTRX_BEARING_E_BUSY, "second tick busy");
    pthread_mutex_unlock(&h->work_mu);
    (void)snaps;
    btrx_bearing_destroy(h);
}

static int64_t wnow(void *ctx) { (void)ctx; return (int64_t)(now_s() * 1e6); }

static void test_worker_and_abi(void) {
    printf("worker, allocation, ABI\n");
    btrx_bearing_config_t c; btrx_bearing_config_default(&c);
    c.now_us = wnow;
    btrx_bearing_t *h = mk(&c);
    CHECK(btrx_bearing_start_worker(h) == 0, "worker starts");
    for (int i = 0; i < 20; i++) push_yaw(h, wnow(NULL) + i, 3);
    struct timespec ts = { 0, 300000000 };
    nanosleep(&ts, NULL);
    CHECK(btrx_bearing_stop_worker(h) == 0, "worker stops");
    CHECK(h->pose.yaw_n == 20, "worker drained inputs (%d)", h->pose.yaw_n);
    btrx_bearing_destroy(h);
    /* no allocation after create */
    h = mk(NULL);
    long before = atomic_load(&bbt_alloc_count);
    sim_t s; sim_init(&s, h);
    hover_ctx_t hc = { 0, 0, 30, 60, 45, 6 };
    s.traj = traj_hover; s.ctx = &hc;
    s.src[0] = (sim_src_t){ 75, 130, 0xC1, 7, 5, false };
    s.src[1] = (sim_src_t){ -100, 50, 0, 8, 5, true };
    s.n_src = 2;
    sim_run(&s, 0, 60);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    btrx_bearing_hit_t hits[8];
    if (sn) { btrx_bearing_snapshot_sources_at(sn, LAT0, LON0, 50, hits, 8); btrx_bearing_snapshot_release(h, sn); }
    btrx_bearing_set_fade(h, 120, 2);
    btrx_bearing_tick(h, s.t0_us + 61000000, NULL);
    CHECK(atomic_load(&bbt_alloc_count) == before, "no malloc after create (%ld)", atomic_load(&bbt_alloc_count) - before);
    btrx_bearing_destroy(h);
    btrx_bearing_config_default(&c);
    btrx_bearing_t *x = NULL;
    c.struct_size -= 4;
    CHECK(btrx_bearing_create(&c, KEY, &x) == BTRX_BEARING_E_ABI && !x, "wrong struct_size");
    btrx_bearing_config_default(&c);
    c.abi_version = 99;
    CHECK(btrx_bearing_create(&c, KEY, &x) == BTRX_BEARING_E_ABI, "wrong abi_version");
    CHECK(btrx_bearing_abi_version() == BTRX_BEARING_ABI_VERSION, "abi version");
    btrx_bearing_config_default(&c);
    c.grid_cells = 100;
    CHECK(btrx_bearing_create(&c, KEY, &x) == BTRX_BEARING_E_INVALID, "grid not a multiple of 16");
    CHECK(btrx_bearing_set_fade((btrx_bearing_t *)1, 30, 1) == BTRX_BEARING_E_INVALID, "fade range");
}

/* ================= synthetic scenarios (section 9.3) ================= */
typedef struct { int n; double t[6]; hover_ctx_t leg[6]; } multi_hover_t;
static sim_pose_t traj_multi_hover(double t, void *c) {
    multi_hover_t *m = c;
    int k = 0;
    while (k + 1 < m->n && t >= m->t[k + 1]) k++;
    return traj_hover(t - m->t[k], &m->leg[k]);
}

typedef struct { int n; double t[6]; double e0[6], n0[6], ve[6], vn[6]; double agl; } legs_t;
static sim_pose_t traj_legs(double t, void *c) {
    legs_t *L = c;
    int k = 0;
    while (k + 1 < L->n && t >= L->t[k + 1]) k++;
    double dt = t - L->t[k];
    sim_pose_t p = { L->e0[k] + L->ve[k] * dt, L->n0[k] + L->vn[k] * dt, L->agl, atan2(L->ve[k], L->vn[k]) / BB_DEG };
    return p;
}

static double first_valid_s;
static const btrx_bearing_source_t *run_until_valid(sim_t *s, double t_end, uint64_t key) {
    first_valid_s = -1;
    for (double t = 0; t < t_end; t += 5) {
        sim_run(s, t, t + 5);
        const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(s->h);
        const btrx_bearing_source_t *src = snap_src(sn, key);
        bool v = src && src->estimate.valid;
        btrx_bearing_snapshot_release(s->h, sn);
        if (v && first_valid_s < 0) first_valid_s = t + 5;
    }
    return NULL;
}

static void scenario_report(const char *name, btrx_bearing_t *h, uint64_t key, double te, double tn, double tol_m, bool need_valid) {
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    const btrx_bearing_source_t *src = snap_src(sn, key);
    if (!src) { CHECK(false, "%s: no source", name); btrx_bearing_snapshot_release(h, sn); return; }
    double x, y; est_to_m(&src->estimate, &x, &y);
    double err = hypot(x - te, y - tn);
    printf("  %-34s valid=%d err=%.1f m 2sigma=%.0fx%.0f m second=%.2f eff=%.1f used=%u/%u first_valid=%.0f s\n", name,
        src->estimate.valid, err, 2 * src->estimate.axis_major_1s_m, 2 * src->estimate.axis_minor_1s_m,
        src->estimate.second_mass, src->effective_reports, src->reports_used, src->reports_in, first_valid_s);
    if (need_valid) {
        CHECK(src->estimate.valid, "%s: estimate valid", name);
        CHECK(err <= tol_m, "%s: within %.0f m (%.1f)", name, tol_m, err);
        CHECK(!src->estimate.ambiguous, "%s: no mirror solution left", name);
        CHECK(first_valid_s > 0 && first_valid_s < 600, "%s: first valid estimate well under 10 min", name);
    }
    btrx_bearing_snapshot_release(h, sn);
}

static void scenarios(void) {
    printf("scenarios\n");
    /* 1. hover-and-yaw +-45 deg at 6 deg/s at one spot: the ridge points at the source */
    {
        rng_state = 1234567;
        btrx_bearing_t *h = mk(NULL);
        sim_t s; sim_init(&s, h);
        hover_ctx_t hc = { 0, 0, 30, 60, 45, 6 };
        s.traj = traj_hover; s.ctx = &hc;
        double se = 150 * sin(60 * BB_DEG), sn_ = 150 * cos(60 * BB_DEG);
        s.src[0] = (sim_src_t){ se, sn_, 0x51, 1, 5, false }; s.n_src = 1;
        run_until_valid(&s, 180, 0x51);
        double pe, pn;
        grid_argmax(h, 0x51, 0, s.t0_us + 180000000, &pe, &pn);
        double brg = atan2(pe, pn) / BB_DEG;
        printf("  hover-yaw ridge bearing %.1f deg (true 60)\n", brg);
        CHECK(fabs(bb_wrap180((float)(brg - 60))) <= 20, "hover-yaw: ridge within 20 deg of the source (%.1f)", brg);
        const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
        const btrx_bearing_source_t *src = snap_src(sn, 0x51);
        CHECK(src && src->estimate.second_mass < 0.25f, "hover-yaw: mirror lobe below 25%% mass after a 90 deg span (%.2f)", src ? src->estimate.second_mass : -1);
        btrx_bearing_snapshot_release(h, sn);
        btrx_bearing_destroy(h);
    }
    /* 2. two hover spots on adjacent segment edges, ~90 deg apart as seen from the source */
    {
        rng_state = 7654321;
        btrx_bearing_t *h = mk(NULL);
        sim_t s; sim_init(&s, h);
        multi_hover_t m = { 2, { 0, 90 }, { { 0, 0, 30, 45, 45, 6 }, { 200, 0, 30, 315, 45, 6 } } };
        s.traj = traj_multi_hover; s.ctx = &m;
        s.src[0] = (sim_src_t){ 100, 100, 0x52, 2, 5, false }; s.n_src = 1;
        run_until_valid(&s, 180, 0x52);
        scenario_report("two-station hover-yaw", h, 0x52, 100, 100, 40, true);
        btrx_bearing_destroy(h);
    }
    /* 3. straight transects: two crossing legs at 6 m/s */
    {
        rng_state = 99;
        btrx_bearing_t *h = mk(NULL);
        sim_t s; sim_init(&s, h);
        legs_t L = { 2, { 0, 100 }, { -300, 0 }, { 0, -300 }, { 6, 0 }, { 0, 6 }, 30 };
        s.traj = traj_legs; s.ctx = &L;
        s.src[0] = (sim_src_t){ 60, 80, 0x53, 3, 5, false }; s.n_src = 1;
        run_until_valid(&s, 200, 0x53);
        scenario_report("two crossing transects", h, 0x53, 60, 80, 30, true);
        btrx_bearing_destroy(h);
    }
    /* 3b. lawnmower: three parallel legs 100 m apart */
    {
        rng_state = 4242;
        btrx_bearing_t *h = mk(NULL);
        sim_t s; sim_init(&s, h);
        legs_t L = { 3, { 0, 100, 200 }, { -300, 300, -300 }, { -100, 0, 100 }, { 6, -6, 6 }, { 0, 0, 0 }, 30 };
        s.traj = traj_legs; s.ctx = &L;
        s.src[0] = (sim_src_t){ 40, 40, 0x54, 4, 5, false }; s.n_src = 1;
        run_until_valid(&s, 300, 0x54);
        scenario_report("lawnmower 3 legs", h, 0x54, 40, 40, 30, true);
        btrx_bearing_destroy(h);
    }
    /* 4. turns above 10 deg/s are gated */
    {
        btrx_bearing_t *h = mk(NULL);
        sim_t s; sim_init(&s, h);
        hover_ctx_t hc = { 0, 0, 30, 0, 90, 15 };
        s.traj = traj_hover; s.ctx = &hc;
        s.src[0] = (sim_src_t){ 100, 0, 0x55, 5, 5, false }; s.n_src = 1;
        sim_run(&s, 0, 30);
        CHECK(h->stats.gated >= 25 && h->stats.used <= 3, "15 deg/s yaw gated (%u gated, %u used)", h->stats.gated, h->stats.used);
        btrx_bearing_destroy(h);
        h = mk(NULL);
        sim_init(&s, h);
        hover_ctx_t hc2 = { 0, 0, 30, 0, 45, 6 };
        s.traj = traj_hover; s.ctx = &hc2; s.flags = 0x01;   /* IMU not ok */
        s.src[0] = (sim_src_t){ 100, 0, 0x56, 6, 5, false }; s.n_src = 1;
        sim_run(&s, 0, 20);
        CHECK(h->stats.used > 0, "bit 4 clear: used at half weight, no roll correction (%u)", h->stats.used);
        btrx_bearing_destroy(h);
        btrx_bearing_config_t cg; btrx_bearing_config_default(&cg); cg.gate_on_attitude_flag = true;
        h = mk(&cg);
        sim_init(&s, h);
        s.traj = traj_hover; s.ctx = &hc2; s.flags = 0x01;
        s.src[0] = (sim_src_t){ 100, 0, 0x56, 6, 5, false }; s.n_src = 1;
        sim_run(&s, 0, 20);
        CHECK(h->stats.used == 0, "gate_on_attitude_flag: bit 4 clear gated");
        btrx_bearing_destroy(h);
    }
    /* 6. two sources as primary + extra, then three entries: separate colours, no merging */
    {
        rng_state = 31337;
        btrx_bearing_t *h = mk(NULL);
        sim_t s; sim_init(&s, h);
        legs_t L = { 2, { 0, 100 }, { -300, 0 }, { 0, -300 }, { 6, 0 }, { 0, 6 }, 30 };
        s.traj = traj_legs; s.ctx = &L;
        s.src[0] = (sim_src_t){ 60, 80, 0x61, 11, 5, false };
        s.src[1] = (sim_src_t){ -120, -60, 0x62, 12, 5, false };
        s.src[2] = (sim_src_t){ 150, -100, 0, 13, 5, true };
        s.n_src = 3;
        run_until_valid(&s, 200, 0x61);
        const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
        CHECK(sn && sn->source_count == 3, "three sources (%d)", sn ? sn->source_count : -1);
        btrx_bearing_snapshot_release(h, sn);
        scenario_report("multi: source 1 of 3", h, 0x61, 60, 80, 35, true);
        scenario_report("multi: source 2 of 3", h, 0x62, -120, -60, 35, true);
        btrx_bearing_destroy(h);
    }
    /* 8. delayed inputs: 0-14 s jitter all used; 16 s all late */
    {
        rng_state = 5;
        btrx_bearing_t *h = mk(NULL);
        sim_t s; sim_init(&s, h);
        hover_ctx_t hc = { 0, 0, 30, 0, 45, 6 };
        s.traj = traj_hover; s.ctx = &hc;
        s.delay_s = 0.07; s.delay_jitter_s = 0;
        s.src[0] = (sim_src_t){ 100, 50, 0x57, 7, 5, false }; s.n_src = 1;
        sim_run(&s, 0, 10);
        s.delay_s = 12.0; s.delay_jitter_s = 2.0;   /* 12-14 s */
        s.next_arr = s.next_we + 12000000;
        sim_run(&s, 10, 80);
        CHECK(h->stats.late_dropped == 0 && h->stats.used + h->stats.gated + h->stats.no_heading >= h->stats.reports_in - 1,
            "12-14 s delays used (%u in, %u used, %u late)", h->stats.reports_in, h->stats.used, h->stats.late_dropped);
        btrx_bearing_destroy(h);
        h = mk(NULL);
        sim_init(&s, h);
        s.traj = traj_hover; s.ctx = &hc; s.delay_s = 16.0;
        s.src[0] = (sim_src_t){ 100, 50, 0x58, 8, 5, false }; s.n_src = 1;
        btrx_bearing_push_time_sync(h, s.t0_us, (uint32_t)((s.t0_us - s.pod_off_us) / 1000), (uint32_t)((s.t0_us - s.pod_off_us) / 1000), s.t0_us);
        sim_run(&s, 0, 60);
        CHECK(h->stats.used == 0 && h->stats.late_dropped >= 40, "16 s delays late (%u)", h->stats.late_dropped);
        btrx_bearing_destroy(h);
    }
}

/* ================= timing (section 10) ================= */
static void bench_run(int entries, const char *label) {
    btrx_bearing_config_t c; btrx_bearing_config_default(&c);
    c.publish_interval_ms = 1000000000;   /* time evaluation and publish separately */
    btrx_bearing_t *h = mk(&c);
    inj_t s = { 0, 0, 0 };
    btrx_bearing_tick(h, 1, NULL);        /* the first tick publishes */
    double tot = 0, worst = 0; int n = 0;
    uint32_t used0 = 0;
    for (int i = 0; i < 300; i++) {
        btrx_bearing_report_source_t e[3] = { entry(0xF1 + (entries > 1 ? i % 8 : 0), (uint16_t)(1 + (entries > 1 ? i % 8 : 0)), -60, -64, 9),
            entry(0, (uint16_t)(20 + i % 3), -66, -62, 9), entry(0, (uint16_t)(30 + i % 3), -70, -71, 9) };
        e[1].rank = 1; e[2].rank = 2;
        int64_t te = 10000000 + i * 2000000LL;   /* 2 s apart: each window sees one yaw */
        inject(h, &s, te, (i % 20) * 10.0, (i / 20) * 10.0, (float)(i * 13 % 360), 30, e, entries, false);
        used0 = h->stats.used;
        double t0 = now_s();
        btrx_bearing_tick(h, te + 200000, NULL);
        double dt = now_s() - t0;
        if (h->stats.used > used0) { tot += dt; n++; if (dt > worst) worst = dt; }
    }
    printf("  per report, %s: mean %.3f ms, max %.3f ms (%d timed, tick includes the pose drain)\n", label, tot / (n ? n : 1) * 1e3, worst * 1e3, n);
    int ns = 0; for (int i = 0; i < BTRX_BEARING_MAX_SOURCES; i++) ns += h->sources[i].used;
    double t0 = now_s();
    for (int i = 0; i < 20; i++) bb_publish(h, 610000000 + i * 1000);
    printf("  per redraw (publish: composite, alpha, estimate, RGBA), %d sources, %d tiles: %.3f ms\n", ns, h->tiles_in_use, (now_s() - t0) / 20 * 1e3);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    btrx_bearing_hit_t hits[8];
    t0 = now_s();
    for (int i = 0; i < 1000; i++) btrx_bearing_snapshot_sources_at(sn, LAT0 + 0.0005, LON0 + 0.0005, 60, hits, 8);
    printf("  sources_at (60 m radius): %.1f us\n", (now_s() - t0) / 1000 * 1e6);
    btrx_bearing_snapshot_release(h, sn);
    t0 = now_s();
    for (int i = 0; i < 100000; i++) push_yaw(h, 900000000 + i, 1);
    printf("  push_pose: %.3f us\n", (now_s() - t0) / 100000 * 1e6);
    btrx_bearing_destroy(h);
}

static void bench(void) {
    printf("timing (Mac host, -O2)\n");
    bench_run(1, "1 entry");
    bench_run(3, "3 entries");
}


/* ================= stats without a view (field test, Nov 13) ================= */
/* Bench: Drone Pod reports arrive but the tablet has no drone pose. No snapshot can be
 * published, yet stats_copy must say why nothing is drawn; a drone sitting on the
 * table (AGL 0) must still be placed once yaw and position arrive. */
static void push_report_only(btrx_bearing_t *h, int64_t t_end, uint8_t seq, const btrx_bearing_report_source_t *e) {
    btrx_bearing_report_t r; memset(&r, 0, sizeof r);
    r.arrival_us = t_end; r.push_us = t_end + 1000;
    r.window_end_ms = (uint32_t)(t_end / 1000);
    r.steer_seq = seq; r.flags = 0x51;
    r.src[0] = *e; r.source_count = 1;
    btrx_bearing_push_report(h, &r);
}


/* ================= Nov 16 watch-test fixes ================= */
static void inject_r(btrx_bearing_t *h, inj_t *s, int64_t t_end, double e, double n, float yaw, float agl,
        const btrx_bearing_report_source_t *ent, int n_ent, uint8_t flags, int8_t roll_x2) {
    int64_t t = s->last_yaw_t ? s->last_yaw_t + 33333 : t_end - 1100000;
    if (t < t_end - 1100000) t = t_end - 1100000;
    for (; t <= t_end + 100000; t += 33333) { push_yaw(h, t, yaw); s->last_yaw_t = t; }
    push_pos(h, t_end - 500000, e, n, agl);
    btrx_bearing_report_t r; memset(&r, 0, sizeof r);
    r.arrival_us = t_end; r.push_us = r.arrival_us + 1000;
    r.window_end_ms = (uint32_t)((t_end - s->pod_off) / 1000);
    r.steer_seq = s->seq++; r.flags = flags; r.roll_x2 = roll_x2;
    for (int k = 0; k < n_ent; k++) r.src[k] = ent[k];
    r.source_count = (uint8_t)n_ent;
    btrx_bearing_push_report(h, &r);
    btrx_bearing_tick(h, t_end + 200000, NULL);
}

static int count_used(const btrx_bearing_t *h) {
    int n = 0; for (int i = 0; i < BTRX_BEARING_MAX_SOURCES; i++) n += h->sources[i].used; return n;
}


static void test_watch_fixes(void) {
    printf("watch-test fixes (Nov 16)\n");
    btrx_bearing_config_t c;
    btrx_bearing_config_default(&c);
    CHECK(c.max_sources >= 16 && c.max_sources <= BTRX_BEARING_MAX_SOURCES && BTRX_BEARING_MAX_SOURCES == 32, "16-32 sources (%d)", c.max_sources);
    CHECK(btrx_bearing_abi_version() == 3, "ABI 3");

    /* 1. eviction by least evidence, not least recent; colour follows the device */
    btrx_bearing_config_t c3 = c; c3.max_sources = 3; c3.extra_min_sightings = 1;
    btrx_bearing_t *h = mk(&c3);
    inj_t s = { 0, 0, 0 };
    btrx_bearing_report_source_t wa = entry(0xA, 1, -62, -70, 5);
    for (int i = 0; i < 20; i++) inject(h, &s, 10000000 + (int64_t)i * 1000000, i * 6.0, 0, 90, 30, &wa, 1, true);
    uint8_t col_a = src_of(h, 0xA)->colour;
    btrx_bearing_report_source_t wb = entry(0xB, 2, -80, -78, 5), wc = entry(0xC, 3, -80, -79, 5), wd = entry(0xD, 4, -81, -80, 5);
    inject(h, &s, 70000000, 0, 0, 0, 30, &wb, 1, true);       /* A last heard 41 s ago, B and C newer */
    inject(h, &s, 71000000, 0, 0, 0, 30, &wc, 1, true);
    inject(h, &s, 72000000, 0, 0, 0, 30, &wd, 1, true);
    CHECK(src_of(h, 0xA) != NULL, "least-recent source with 20 reports kept");
    CHECK(src_of(h, 0xD) != NULL && count_used(h) == 3, "newcomer took a weak source's slot");
    CHECK(h->evictions == 1, "one eviction (%u)", h->evictions);
    uint64_t gone = src_of(h, 0xB) ? 0xC : 0xB;
    uint8_t col_gone = 255;
    for (int i = 0; i < h->colmem_n; i++) if (h->colmem[i].key == gone) col_gone = h->colmem[i].colour;
    CHECK(col_gone != 255, "evicted source's colour remembered");
    btrx_bearing_report_source_t wg = entry(gone, 9, -80, -79, 5);
    inject(h, &s, 73000000, 0, 0, 0, 30, &wg, 1, true);
    CHECK(src_of(h, gone) && (src_of(h, gone)->colour == col_gone || src_of(h, 0xA)->colour == col_gone), "returning device gets its colour back");
    CHECK(src_of(h, 0xA)->colour == col_a, "kept source's colour unchanged");
    btrx_bearing_destroy(h);

    /* 2. extras start a source on the 2nd sighting within 60 s */
    h = mk(&c);
    s = (inj_t){ 0, 0, 0 };
    btrx_bearing_set_stable_src_ids(h, true);
    btrx_bearing_report_source_t p0 = entry(0x7000, 0x9C41, -50, -55, 9);
    btrx_bearing_report_source_t x = entry(0, 0x1234, -70, -66, 3); x.rank = 1;
    btrx_bearing_report_source_t px[2] = { p0, x };
    inject(h, &s, 10000000, 0, 0, 0, 30, px, 2, true);
    CHECK(count_used(h) == 1, "extra seen once: not tracked (%d)", count_used(h));
    inject(h, &s, 11000000, 0, 0, 0, 30, px, 2, true);
    CHECK(count_used(h) == 2, "extra seen twice: tracked (%d)", count_used(h));
    btrx_bearing_report_source_t y = entry(0, 0x4321, -70, -66, 3); y.rank = 1;
    btrx_bearing_report_source_t py[2] = { p0, y };
    inject(h, &s, 12000000, 0, 0, 0, 30, py, 2, true);
    inject(h, &s, 80000000, 0, 0, 0, 30, py, 2, true);
    CHECK(count_used(h) == 2, "two sightings 68 s apart: not tracked (%d)", count_used(h));
    CHECK(h->extras_tentative == 3, "tentative entries counted (%u)", h->extras_tentative);
    btrx_bearing_destroy(h);

    /* 3. own aircraft suggestion: -32 dBm for 70 s while the drone flies 140 m */
    h = mk(&c);
    s = (inj_t){ 0, 0, 0 };
    btrx_bearing_report_source_t own = entry(0x0BAD, 7, -32, -33, 15);
    btrx_bearing_report_source_t gnd = entry(0x600D, 8, -70, -76, 5);
    for (int i = 0; i <= 70; i++) {
        btrx_bearing_report_source_t two[2] = { own, gnd }; two[1].rank = 1;
        inject(h, &s, 10000000 + (int64_t)i * 1000000, i * 2.0, 0, 90, 30, two, 2, true);
    }
    btrx_bearing_tick(h, 82000000, NULL);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    const btrx_bearing_source_t *so = snap_src(sn, 0x0BAD), *sg = snap_src(sn, 0x600D);
    CHECK(so && (so->flags & BTRX_BEARING_SRC_OWN_SUSPECT), "strong for 70 s while moving: suggested own aircraft");
    CHECK(so && !so->estimate.valid && !so->pinned, "suggested: no estimate drawn");
    CHECK(so && !(so->flags & (BTRX_BEARING_SRC_IGNORED | BTRX_BEARING_SRC_MUTED)), "a suggestion only: not muted or ignored");
    CHECK(sg && !(sg->flags & BTRX_BEARING_SRC_OWN_SUSPECT), "weaker ground source not suggested");
    if (sn) btrx_bearing_snapshot_release(h, sn);
    uint64_t kown = 0x0BAD;
    btrx_bearing_set_source_flags(h, &kown, 1, BTRX_BEARING_SRC_OWN_CLEARED, BTRX_BEARING_SRC_OWN_CLEARED);
    for (int i = 71; i <= 150; i++) inject(h, &s, 10000000 + (int64_t)i * 1000000, i * 2.0, 0, 90, 30, &own, 1, true);
    CHECK(!(src_of(h, 0x0BAD)->flags & BTRX_BEARING_SRC_OWN_SUSPECT), "'not on the drone': no new suggestion");
    btrx_bearing_destroy(h);
    h = mk(&c);
    s = (inj_t){ 0, 0, 0 };
    for (int i = 0; i <= 90; i++) inject(h, &s, 10000000 + (int64_t)i * 1000000, 0, 0, (float)(i * 4 % 360), 30, &own, 1, true);
    CHECK(!(src_of(h, 0x0BAD)->flags & BTRX_BEARING_SRC_OWN_SUSPECT), "hovering over a strong ground source: no suggestion");
    btrx_bearing_destroy(h);

    /* 4. roll: k by depression; the correction moves the likelihood peak back; weighting */
    NEAR(bb_roll_k(10), 0.10, 1e-6, "k shallow");
    NEAR(bb_roll_k(45), 0.13, 1e-6, "k 45 deg down");
    NEAR(bb_roll_k(90), 0.22, 1e-6, "k straight below");
    h = mk(&c);
    {
        bb_geom_t g; memset(&g, 0, sizeof g);
        g.sigma2 = 4; g.roll_deg = 10;
        float dpred = btrx_bearing_pattern_delta_db(h, -40, 45);
        g.obs = dpred - bb_roll_k(45) * 10;          /* measured, biased by 10 deg starboard-down roll */
        NEAR(bb_loglik(h, &g, -40, 45), 0, 1e-5, "roll-corrected residual zero");
        g.roll_deg = 0;
        CHECK(bb_loglik(h, &g, -40, 45) < -0.05f, "uncorrected residual not zero");
    }
    btrx_bearing_destroy(h);
    h = mk(&c);
    s = (inj_t){ 0, 0, 0 };
    btrx_bearing_report_source_t er = entry(0x1, 1, -60, -66, 5);
    inject_r(h, &s, 10000000, 0, 0, 0, 30, &er, 1, 0x19, 24);     /* bit 3 set, roll 12 (0x0109 gate is 10) */
    inject_r(h, &s, 11000000, 5, 0, 0, 30, &er, 1, 0x19, 60);     /* roll 30 */
    inject_r(h, &s, 12000000, 10, 0, 0, 30, &er, 1, 0x19, 100);   /* roll 50: gated */
    CHECK(h->stats.used == 2 && h->stats.gated == 1, "bit 3 is advice: rolls 12 and 30 used, 50 gated (%u used, %u gated)", h->stats.used, h->stats.gated);
    btrx_bearing_destroy(h);

    /* 5. averaging: one sample per patch per window, alternating patches */
    h = mk(&c);
    s = (inj_t){ 0, 0, 0 };
    btrx_bearing_report_source_t ap = entry(0x2, 2, -70, BTRX_BEARING_RSSI_NOT_HEARD, 1); ap.hits_stbd = 0;
    btrx_bearing_report_source_t as = entry(0x2, 2, BTRX_BEARING_RSSI_NOT_HEARD, -74, 1); as.hits_port = 0;
    inject(h, &s, 10000000, 0, 0, 0, 30, &ap, 1, true);
    CHECK(h->stats.used == 0, "one sample on one patch: not enough alone");
    inject(h, &s, 11000000, 3, 0, 0, 30, &as, 1, true);
    CHECK(h->stats.used == 1, "averaged with the previous window: both patches, used (%u)", h->stats.used);
    inject(h, &s, 16000000, 6, 0, 0, 30, &ap, 1, true);
    CHECK(h->stats.used == 1, "windows older than avg_span_ms are not averaged");
    btrx_bearing_destroy(h);

    /* 6. wedge length from the range estimate; front lobe when ambiguous */
    h = mk(&c);
    s = (inj_t){ 0, 0, 0 };
    btrx_bearing_report_source_t near = entry(0x3, 3, -52, -56, 9), far = entry(0x4, 4, -92, -95, 9);
    btrx_bearing_report_source_t nf[2] = { near, far }; nf[1].rank = 1;
    inject(h, &s, 10000000, 0, 0, 0, 30, nf, 2, true);
    btrx_bearing_report_source_t ahead = entry(0x5, 5, -70, -70, 15);
    inject(h, &s, 11000000, 0, 0, 0, 30, &ahead, 1, true);
    btrx_bearing_tick(h, 12500000, NULL);
    sn = btrx_bearing_snapshot_acquire(h);
    const btrx_bearing_source_t *sns = snap_src(sn, 0x3), *sfs = snap_src(sn, 0x4), *sah = snap_src(sn, 0x5);
    CHECK(sns && sns->latest_ray.length_m >= 30 && sns->latest_ray.length_m < 100 && isfinite(sns->latest_ray.range_m),
            "strong: short wedge (%g m, range %g m)", sns ? sns->latest_ray.length_m : -1, sns ? sns->latest_ray.range_m : -1);
    CHECK(sfs && sfs->latest_ray.length_m == 400, "weak: wedge to the footprint (%g)", sfs ? sfs->latest_ray.length_m : -1);
    CHECK(sah && sah->latest_ray.ambiguous && fabsf(bb_wrap180(sah->latest_ray.centre_true_deg)) < 90,
            "equal patches: ambiguous, front lobe first (%g)", sah ? sah->latest_ray.centre_true_deg : -1);
    if (sn) btrx_bearing_snapshot_release(h, sn);
    btrx_bearing_destroy(h);

    /* 7. close pass, low and slow: trail, strongest here, label, fine layer, focus, copy */
    h = mk(&c);
    s = (inj_t){ 0, 0, 0 };
    const double se = 40, sn_ = 10;                  /* source; drone flies east along n = 0 at 2 m/s, 20 m AGL */
    for (int i = 0; i <= 60; i++) {
        double de = i * 2.0, dn = 0;
        double r = hypot(hypot(se - de, sn_ - dn), 20);
        int lvl = (int)lrint(-38 - 22 * log10(r));
        btrx_bearing_report_source_t ec = entry(0x6, 6, lvl + 2, lvl - 2, 3);
        btrx_bearing_report_source_t eo = entry(0x7, 7, -80, -78, 3);
        btrx_bearing_report_source_t two[2] = { ec, eo }; two[1].rank = 1;
        inject(h, &s, 10000000 + (int64_t)i * 1000000, de, dn, 90, 20, two, 2, true);
    }
    btrx_bearing_set_focus(h, 0x6);
    btrx_bearing_tick(h, 72000000, NULL);
    sn = btrx_bearing_snapshot_acquire(h);
    const btrx_bearing_source_t *sc = snap_src(sn, 0x6);
    CHECK(sn && sn->focus_key == 0x6 && sn->trail_count == 61, "focused source's strength trail (%d)", sn ? sn->trail_count : -1);
    if (sc && sc->strongest_valid) {
        double lat, lon; m2ll(se, 0, &lat, &lon);
        double d = hypot((sc->strongest_lat_deg - lat) * BB_DEG * BB_EARTH_R, (sc->strongest_lon_deg - lon) * BB_DEG * BB_EARTH_R * cos(LAT0 * BB_DEG));
        CHECK(d < 10, "strongest here at the closest approach (%.1f m off)", d);
        CHECK(sc->max_rssi_dbm >= -70 && sc->last_rssi_dbm < sc->max_rssi_dbm, "max %d, last %d dBm", sc->max_rssi_dbm, sc->last_rssi_dbm);
    } else CHECK(false, "strongest here");
    CHECK(sc && sc->label_valid, "label point inside the shading");
    CHECK(sn && sn->fine_valid && sn->fine_width == 160 && sn->fine_rgba, "fine layer published while low and slow");
    if (sn && sn->fine_valid) {
        double span = (sn->fine_north_deg - sn->fine_south_deg) * BB_DEG * BB_EARTH_R;
        NEAR(span, 400, 1, "fine raster 160 x 2.5 m");
        int lit = 0; for (int i = 0; i < 160 * 160; i++) lit += sn->fine_rgba[i * 4 + 3] > 0;
        CHECK(lit > 100, "fine raster shaded (%d px)", lit);
    }
    uint8_t *rgba = malloc(192 * 192 * 4), *alpha = malloc(192 * 192 * BTRX_BEARING_MAX_SOURCES), *fine = malloc(160 * 160 * 4);
    btrx_bearing_trail_point_t *trail = malloc(sizeof *trail * BTRX_BEARING_TRAIL_MAX);
    btrx_bearing_snapshot_t cp;
    CHECK(sn && btrx_bearing_snapshot_copy(sn, &cp, rgba, 192 * 192 * 4, alpha, 192 * 192 * BTRX_BEARING_MAX_SOURCES,
            fine, 160 * 160 * 4, trail, BTRX_BEARING_TRAIL_MAX) == BTRX_BEARING_OK && cp.fine_rgba == fine && cp.trail == trail
            && cp.trail_count == sn->trail_count && memcmp(fine, sn->fine_rgba, 160 * 160 * 4) == 0, "deep copy with fine layer and trail");
    CHECK(sn && btrx_bearing_snapshot_copy(sn, &cp, rgba, 192 * 192 * 4, alpha, 192 * 192 * BTRX_BEARING_MAX_SOURCES,
            NULL, 0, NULL, 0) == BTRX_BEARING_OK && !cp.fine_valid && cp.trail_count == 0, "copy without fine layer and trail");
    double sum_focus = 0;
    if (sn) for (int i = 0; i < 192 * 192; i++) sum_focus += sn->rgba[i * 4 + 3];
    if (sn) btrx_bearing_snapshot_release(h, sn);
    btrx_bearing_set_focus(h, 0);
    btrx_bearing_tick(h, 72100000, NULL);
    sn = btrx_bearing_snapshot_acquire(h);
    double sum_all = 0;
    if (sn) for (int i = 0; i < 192 * 192; i++) sum_all += sn->rgba[i * 4 + 3];
    CHECK(sn && sn->focus_key == 0 && sn->trail_count == 0 && sum_all > sum_focus, "focus cleared: others full strength (%.0f > %.0f)", sum_all, sum_focus);
    if (sn) btrx_bearing_snapshot_release(h, sn);
    btrx_bearing_destroy(h);
    free(rgba); free(alpha); free(fine); free(trail);

    /* high and fast, weak: no fine layer */
    h = mk(&c);
    s = (inj_t){ 0, 0, 0 };
    btrx_bearing_report_source_t ew = entry(0x8, 8, -84, -88, 5);
    for (int i = 0; i <= 20; i++) inject(h, &s, 10000000 + (int64_t)i * 1000000, i * 12.0, 0, 90, 100, &ew, 1, true);
    btrx_bearing_tick(h, 32000000, NULL);
    sn = btrx_bearing_snapshot_acquire(h);
    CHECK(sn && !sn->fine_valid, "high, fast and weak: coarse only");
    if (sn) btrx_bearing_snapshot_release(h, sn);
    btrx_bearing_destroy(h);
}

static void test_stats_without_view(void) {
    printf("stats without a view\n");
    btrx_bearing_t *h = mk(NULL);
    btrx_bearing_stats_t st;
    CHECK(btrx_bearing_stats_copy(h, &st) == BTRX_BEARING_E_NO_SNAPSHOT, "no stats before the first tick");
    CHECK(btrx_bearing_stats_copy(NULL, &st) == BTRX_BEARING_E_INVALID && btrx_bearing_stats_copy(h, NULL) == BTRX_BEARING_E_INVALID, "null args");
    btrx_bearing_report_source_t e1 = entry(0x2222, 2, -60, -66, 5);
    uint8_t seq = 0;
    int64_t t = 10000000;
    for (int i = 0; i < 6; i++, t += 1000000) {
        push_report_only(h, t, seq++, &e1);
        btrx_bearing_tick(h, t + 100000, NULL);
    }
    btrx_bearing_tick(h, t + 4000000, NULL);
    CHECK(btrx_bearing_snapshot_acquire(h) == NULL, "no pose: no snapshot");
    CHECK(btrx_bearing_stats_copy(h, &st) == BTRX_BEARING_OK, "stats without a snapshot");
    CHECK(st.reports_in == 6, "reports_in 6 (%u)", st.reports_in);
    CHECK(st.used == 0, "none used");
    CHECK(st.no_heading + st.no_position == 6, "all dropped for pose (%u heading, %u position)", st.no_heading, st.no_position);
    CHECK(st.offset_source == 1, "arrival-minimum clock (%u)", st.offset_source);
    btrx_bearing_destroy(h);

    /* drone on the table: AGL 0, yaw steady, RID position at 1 Hz */
    h = mk(NULL);
    inj_t s = { 0, 0, 0 };
    for (int i = 0; i < 8; i++) inject(h, &s, 20000000 + (int64_t)i * 1000000, 0, 0, 90, 0, &e1, 1, true);
    btrx_bearing_tick(h, 30000000, NULL);
    CHECK(btrx_bearing_stats_copy(h, &st) == BTRX_BEARING_OK, "stats");
    CHECK(st.used >= 7, "grounded drone: reports placed (%u of %u)", st.used, st.reports_in);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    CHECK(sn && sn->source_count == 1 && sn->sources[0].peak_alpha > 0, "grounded drone: shading");
    if (sn) btrx_bearing_snapshot_release(h, sn);
    btrx_bearing_destroy(h);
}

/* Shared test-fixtures/btrx/steer-format2.tsv: format 2 yaw and format 1 unchanged. */
#ifndef BTRX_STEER_FORMAT2_FIXTURE
#define BTRX_STEER_FORMAT2_FIXTURE "test-fixtures/btrx/steer-format2.tsv"
#endif

static int hex_nibble(int c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static int parse_hex(const char *s, uint8_t *out, int cap) {
    int n = 0;
    for (const char *p = s; *p; p += 2, n++) {
        int a = hex_nibble((unsigned char)p[0]), b = hex_nibble((unsigned char)p[1]);
        if (a < 0 || b < 0 || n >= cap) return -1;
        out[n] = (uint8_t)((a << 4) | b);
    }
    return n;
}

static FILE *open_steer_format2(void) {
    const char *cands[] = {
        BTRX_STEER_FORMAT2_FIXTURE,
        "test-fixtures/btrx/steer-format2.tsv",
        "../test-fixtures/btrx/steer-format2.tsv",
        "../../test-fixtures/btrx/steer-format2.tsv",
        "../../../test-fixtures/btrx/steer-format2.tsv",
        NULL
    };
    for (int i = 0; cands[i]; i++) {
        FILE *f = fopen(cands[i], "r");
        if (f) return f;
    }
    return NULL;
}

static void test_steer_format2(void) {
    printf("steer format 2\n");
    FILE *f = open_steer_format2();
    CHECK(f != NULL, "open steer-format2.tsv");
    if (!f) return;
    btrx_bearing_t *h = mk(NULL);
    char line[2048];
    int rows = 0;
    while (fgets(line, sizeof line, f)) {
        if (line[0] == '#' || line[0] == '\n' || line[0] == '\0') continue;
        char *nl = strchr(line, '\n'); if (nl) *nl = 0;
        char *save = NULL;
        char *name = strtok_r(line, "\t", &save);
        char *hex = strtok_r(NULL, "\t", &save);
        char *extras_s = strtok_r(NULL, "\t", &save);
        char *fmt_s = strtok_r(NULL, "\t", &save);
        char *trunc_s = strtok_r(NULL, "\t", &save);
        char *has_s = strtok_r(NULL, "\t", &save);
        char *yx_s = strtok_r(NULL, "\t", &save);
        char *yf_s = strtok_r(NULL, "\t", &save);
        char *bias_s = strtok_r(NULL, "\t", &save);
        char *toff_s = strtok_r(NULL, "\t", &save);
        char *turns_s = strtok_r(NULL, "\t", &save);
        char *yflags_s = strtok_r(NULL, "\t", &save);
        CHECK(name && hex && extras_s && fmt_s && trunc_s && has_s, "columns %s", name ? name : "?");
        if (!hex) continue;
        uint8_t buf[128];
        int n = parse_hex(hex, buf, (int)sizeof buf);
        CHECK(n > 0, "%s: hex", name);
        btrx_bearing_report_t r;
        CHECK(btrx_bearing_parse_steer(h, buf, (size_t)n, 0, false, &r) == 0, "%s: parse", name);
        int extras = atoi(extras_s), fmt = atoi(fmt_s), trunc = atoi(trunc_s), has = atoi(has_s);
        int expect_sources = extras + ((buf[1] & 0x01) ? 1 : 0);
        if (trunc) expect_sources = (buf[1] & 0x01) ? 1 : 0;
        CHECK(r.ext_format == (uint8_t)fmt, "%s: ext_format %u vs %d", name, r.ext_format, fmt);
        CHECK(r.truncated == (trunc != 0), "%s: truncated", name);
        CHECK(r.source_count == (uint8_t)expect_sources, "%s: source_count %u vs %d", name, r.source_count, expect_sources);
        CHECK(r.has_yaw == (has != 0), "%s: has_yaw", name);
        if (has) {
            CHECK(r.yaw_x10 == (uint16_t)atoi(yx_s), "%s: yaw_x10", name);
            CHECK(r.yaw_fixed_x10 == (uint16_t)atoi(yf_s), "%s: yaw_fixed_x10", name);
            CHECK(r.bias_z_mdps == (int16_t)atoi(bias_s), "%s: bias", name);
            CHECK(r.t_off_ms == (int16_t)atoi(toff_s), "%s: t_off", name);
            CHECK(r.turns == (int8_t)atoi(turns_s), "%s: turns", name);
            CHECK(r.yflags == (uint8_t)strtol(yflags_s, NULL, 0), "%s: yflags", name);
            int64_t sample = 0;
            bool ready = btrx_bearing_yaw_sample_ms(r.window_end_ms, r.t_off_ms, &sample);
            if (r.t_off_ms == BTRX_BEARING_YAW_T_OFF_UNKNOWN) {
                CHECK(!ready, "%s: sample not ready", name);
            } else {
                CHECK(ready && sample == (int64_t)r.window_end_ms + (int64_t)r.t_off_ms, "%s: sample_ms", name);
            }
        }
        rows++;
    }
    fclose(f);
    CHECK(rows == 16, "16 format-2 vectors (%d)", rows);
    btrx_bearing_destroy(h);
}


/* ================= Nov 20 back-deck flight fixes ================= */
#ifndef BTRX_FIXTURE_DIR
#define BTRX_FIXTURE_DIR "test-fixtures/btrx"
#endif
static FILE *open_fixture(const char *name) {
    const char *dirs[] = { BTRX_FIXTURE_DIR, "test-fixtures/btrx", "../test-fixtures/btrx", "../../test-fixtures/btrx",
        "../../../test-fixtures/btrx", NULL };
    char path[1024];
    for (int i = 0; dirs[i]; i++) {
        snprintf(path, sizeof path, "%s/%s", dirs[i], name);
        FILE *f = fopen(path, "r");
        if (f) return f;
    }
    return NULL;
}

static uint64_t hex64s(const char *s) { return strtoull(s, NULL, 16); }

/* Feeds a BBv1 file (spec 9.2; same parsing as btrx_bearing_replay.c), ticking every 200 ms of
 * host time, then 20 s more. Returns the number of lines fed, -1 if the file is missing. */
static long feed_bbv1(btrx_bearing_t *h, const char *name, int64_t *end_us) {
    FILE *in = open_fixture(name);
    if (!in) return -1;
    char line[2048];
    int64_t next_tick = INT64_MIN;
    long lines = 0;
    while (fgets(line, sizeof line, in)) {
        if (strncmp(line, "BBv1 ", 5) != 0) continue;
        char kind = line[5];
        char *p = line + 7;
        int64_t t = strtoll(p, NULL, 10);
        if (next_tick == INT64_MIN) next_tick = t;
        while (next_tick <= t) { btrx_bearing_tick(h, next_tick, NULL); next_tick += 200000; }
        lines++;
        if (kind == 'P') {
            btrx_bearing_pose_t ps; memset(&ps, 0, sizeof ps);
            unsigned mask;
            if (sscanf(p, "%" SCNd64 " %u %lf %lf %f %f %f %f", &ps.t_us, &mask, &ps.lat_deg, &ps.lon_deg, &ps.agl_m,
                    &ps.yaw_true_deg, &ps.pitch_deg, &ps.roll_deg) != 8) continue;
            ps.mask = mask; ps.push_us = ps.t_us;
            btrx_bearing_push_pose(h, &ps);
        } else if (kind == 'R') {
            btrx_bearing_report_t r; memset(&r, 0, sizeof r);
            int seq, flags, roll, pitch, backlog, fmt, trunc, n, used = 0;
            if (sscanf(p, "%" SCNd64 " %u %d %d %d %d %d %d %d %d%n", &r.arrival_us, &r.window_end_ms, &seq, &flags, &roll, &pitch,
                    &backlog, &fmt, &trunc, &n, &used) != 10 || n < 0 || n > 3) continue;
            r.steer_seq = (uint8_t)seq; r.flags = (uint8_t)flags; r.roll_x2 = (int8_t)roll; r.pitch_x2 = (int8_t)pitch;
            r.backlog = backlog; r.ext_format = (uint8_t)fmt; r.truncated = trunc;
            r.push_us = r.arrival_us;
            char *q = p + used;
            int k;
            for (k = 0; k < n; k++) {
                btrx_bearing_report_source_t *e = &r.src[k];
                char keyhex[32];
                int id, port, stbd, peak, hp, hs, age, nw, lk, cls, comp, u2 = 0;
                if (sscanf(q, "%d %31s %d %d %d %d %d %d %d %d %d %d%n", &id, keyhex, &port, &stbd, &peak, &hp, &hs, &age,
                        &nw, &lk, &cls, &comp, &u2) != 12) break;
                q += u2;
                e->src_id = (uint16_t)id; e->addr_key = hex64s(keyhex); e->rssi_port = (int8_t)port; e->rssi_stbd = (int8_t)stbd;
                e->peak_rssi = (int8_t)peak; e->hits_port = (uint8_t)hp; e->hits_stbd = (uint8_t)hs; e->age_ms = (uint16_t)age;
                e->is_new = nw; e->linked = lk; e->pod_dev_class = (uint8_t)cls; e->company_id = (uint16_t)comp; e->rank = (uint8_t)k;
            }
            r.source_count = (uint8_t)k;
            btrx_bearing_push_report(h, &r);
        } else if (kind == 'S') {
            int64_t t1, t4; unsigned t2, t3;
            if (sscanf(p, "%" SCNd64 " %u %u %" SCNd64, &t1, &t2, &t3, &t4) == 4) btrx_bearing_push_time_sync(h, t1, t2, t3, t4);
        } else if (kind == 'H') {
            int64_t tu; int role; unsigned up; char lost[32];
            if (sscanf(p, "%" SCNd64 " %d %u %31s", &tu, &role, &up, lost) == 4) {
                bool known = strcmp(lost, "unknown") != 0;
                btrx_bearing_push_health(h, tu, (uint8_t)role, up, known, known ? (uint32_t)strtoul(lost, NULL, 10) : 0);
            }
        } else if (kind == 'M') {
            int64_t tu; char keyhex[32]; int muted;
            if (sscanf(p, "%" SCNd64 " %31s %d", &tu, keyhex, &muted) == 3) {
                uint64_t key = hex64s(keyhex);
                btrx_bearing_set_source_flags(h, &key, 1, BTRX_BEARING_SRC_MUTED, muted ? BTRX_BEARING_SRC_MUTED : 0);
            }
        } else if (kind == 'F') {
            int64_t tu; float tot, shp;
            if (sscanf(p, "%" SCNd64 " %f %f", &tu, &tot, &shp) == 3) btrx_bearing_set_fade(h, tot, shp);
        }
    }
    fclose(in);
    for (int k = 0; k < 100; k++) { btrx_bearing_tick(h, next_tick, NULL); next_tick += 200000; }
    if (end_us) *end_us = next_tick;
    return lines;
}

static double ll_dist_m(double la1, double lo1, double la2, double lo2) {
    double dn = (la2 - la1) * BB_DEG * BB_EARTH_R, de = (lo2 - lo1) * BB_DEG * BB_EARTH_R * cos(la1 * BB_DEG);
    return hypot(de, dn);
}

/* The Nov 20 flight through the engine: the Garmin watch 16 m N of takeoff, launch-point
 * devices flagged, the laptop's class. Expectations from nov20-backdeck-expect.tsv. */
static void test_nov20_fixture(void) {
    printf("Nov 20 back-deck fixture\n");
    FILE *ex = open_fixture("nov20-backdeck-expect.tsv");
    CHECK(ex != NULL, "nov20-backdeck-expect.tsv found");
    if (!ex) return;
    btrx_bearing_config_t c; btrx_bearing_config_default(&c);
    btrx_bearing_t *h = mk(&c);
    long n = feed_bbv1(h, "nov20-backdeck.bbv1", NULL);
    CHECK(n > 17000, "nov20-backdeck.bbv1 fed (%ld lines)", n);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    CHECK(sn != NULL, "snapshot");
    /* the old settings (no range cap, no coverage term, 2 sigma 200 m) put the watch ~200 m SSW */
    btrx_bearing_config_t o = c;
    o.range_cap_dbm = 0; o.cover_nats_per_100m = 0; o.est_max_axis_2s_m = 200; o.max_sources = 24;
    o.launch_rssi_dbm = 100;
    btrx_bearing_t *ho = mk(&o);
    feed_bbv1(ho, "nov20-backdeck.bbv1", NULL);
    const btrx_bearing_snapshot_t *so = btrx_bearing_snapshot_acquire(ho);
    char line[512];
    while (sn && fgets(line, sizeof line, ex)) {
        if (line[0] == '#' || line[0] == '\n') continue;
        char sid[16], keyhex[32], kind[16], la[32], lo[32], tol[16], cls[8], comp[8];
        if (sscanf(line, "%15s %31s %15s %31s %31s %15s %7s %7s", sid, keyhex, kind, la, lo, tol, cls, comp) != 8) continue;
        uint64_t key = hex64s(keyhex);
        const btrx_bearing_source_t *s = snap_src(sn, key);
        CHECK(s != NULL, "%s tracked", sid);
        if (!s) continue;
        if (!strcmp(kind, "estimate")) {
            double d = s->estimate.valid ? ll_dist_m(atof(la), atof(lo), s->estimate.lat_deg, s->estimate.lon_deg) : 1e9;
            printf("  %s estimate %s: %.1f m from the expected spot, 2 sigma %.0f m\n", sid, s->estimate.valid ? "valid" : "invalid",
                d, 2.0 * s->estimate.axis_major_1s_m);
            CHECK(s->estimate.valid && d <= atof(tol), "%s within %s m (%.1f)", sid, tol, d);
            CHECK((int)s->dev_class == atoi(cls) && (int)s->company_id == atoi(comp), "%s class %d company %u", sid, s->dev_class, s->company_id);
            const btrx_bearing_source_t *old = so ? snap_src(so, key) : NULL;
            double dold = old && old->estimate.valid ? ll_dist_m(atof(la), atof(lo), old->estimate.lat_deg, old->estimate.lon_deg) : -1;
            printf("  %s with the old settings: %s %.1f m\n", sid, old && old->estimate.valid ? "valid" : "invalid", dold);
        } else if (!strcmp(kind, "launch")) {
            CHECK((s->flags & BTRX_BEARING_SRC_LAUNCH) && (s->flags & BTRX_BEARING_SRC_MUTED), "%s flagged at the launch point (flags %u)", sid, s->flags);
        } else if (!strcmp(kind, "class")) {
            CHECK((int)s->dev_class == atoi(cls) && (int)s->company_id == atoi(comp), "%s class %d company %u", sid, s->dev_class, s->company_id);
            CHECK(!(s->flags & BTRX_BEARING_SRC_LAUNCH), "%s not at the launch point", sid);
        }
    }
    fclose(ex);
    CHECK(sn && sn->launch_valid && ll_dist_m(39.153087, -121.132797, sn->launch_lat_deg, sn->launch_lon_deg) < 15,
        "launch point near the takeoff point");
    int far_valid = 0;
    for (int k = 0; sn && k < sn->source_count; k++) {
        const btrx_bearing_source_t *s = &sn->sources[k];
        if (s->estimate.valid && ll_dist_m(39.153087, -121.132797, s->estimate.lat_deg, s->estimate.lon_deg) > 150) far_valid++;
        CHECK(!s->estimate.valid || 2.0f * s->estimate.axis_major_1s_m <= c.est_max_axis_2s_m, "valid within est_max_axis_2s_m");
    }
    CHECK(far_valid == 0, "no valid estimate slid beyond 150 m (%d)", far_valid);
    btrx_bearing_snapshot_release(ho, so);
    btrx_bearing_snapshot_release(h, sn);
    btrx_bearing_destroy(ho);
    btrx_bearing_destroy(h);
}

static const btrx_bearing_source_t *snap_src_now(btrx_bearing_t *h, const btrx_bearing_snapshot_t **sn, uint64_t key) {
    *sn = btrx_bearing_snapshot_acquire(h);
    return *sn ? snap_src(*sn, key) : NULL;
}

/* Focus filter, eviction of focused sources, the computer class, launch auto mute, range cap. */
static void test_nov20_units(void) {
    printf("Nov 20 focus filter, launch, range cap\n");
    btrx_bearing_config_t c; btrx_bearing_config_default(&c);
    CHECK(c.max_sources == 32 && c.est_max_axis_2s_m == 100, "defaults: 32 sources, 2 sigma 100 m");
    CHECK(c.range_cap_dbm == -55 && c.launch_auto_mute, "defaults: range cap -55 dBm, launch auto mute on");
    CHECK(bb_class_from_pod(5) == BTRX_BEARING_CLASS_COMPUTER, "pod class 5 = computer");
    btrx_bearing_t *hx = mk(NULL);
    CHECK(btrx_bearing_set_source_class(hx, 1, BTRX_BEARING_CLASS_COMPUTER) == BTRX_BEARING_OK, "computer class settable");
    CHECK(btrx_bearing_set_focus_filter(hx, 0, 70000, false) == BTRX_BEARING_E_INVALID, "company id range");
    btrx_bearing_destroy(hx);

    /* focus filter by class and by company; hide others draws nothing of them */
    {
        btrx_bearing_t *h = mk(NULL);
        inj_t s = { 0, 0, 0 };
        for (int i = 0; i < 20; i++) {
            btrx_bearing_report_source_t e[2] = { entry(0xA1, 10, -55, -63, 9), entry(0xB2, 11, -64, -57, 9) };
            e[0].pod_dev_class = 2; e[0].company_id = 0x0087;      /* Garmin watch */
            e[1].pod_dev_class = 5; e[1].company_id = 0x0006;      /* Microsoft computer */
            inject(h, &s, 10000000 + i * 1000000LL, i * 3.0, 0, (float)(i * 7 % 90), 30, e, 2, true);
        }
        const btrx_bearing_snapshot_t *sn;
        const btrx_bearing_source_t *a = snap_src_now(h, &sn, 0xA1), *b = sn ? snap_src(sn, 0xB2) : NULL;
        CHECK(a && b && a->company_id == 0x0087 && b->company_id == 0x0006 && b->dev_class == BTRX_BEARING_CLASS_COMPUTER,
            "company ids and the computer class in the snapshot");
        CHECK(sn && !sn->focus_active && a && !a->focus_match, "no focus: nothing matches");
        btrx_bearing_snapshot_release(h, sn);
        btrx_bearing_set_focus_filter(h, 1u << BTRX_BEARING_CLASS_WATCH, -1, true);
        btrx_bearing_tick(h, 31000000, NULL);
        a = snap_src_now(h, &sn, 0xA1); b = sn ? snap_src(sn, 0xB2) : NULL;
        CHECK(sn && sn->focus_active && sn->focus_hide_others && a && a->focus_match && b && !b->focus_match, "class filter: watches");
        int other = 0;
        for (int c2 = 0; sn && a && c2 < sn->width * sn->height; c2++) if (sn->rgba[c2 * 4 + 3] != a->alpha[c2]) other++;
        CHECK(other == 0, "hide others: only the focused colour in the RGBA (%d px)", other);
        btrx_bearing_snapshot_release(h, sn);
        btrx_bearing_set_focus_filter(h, 0, 0x0006, false);
        btrx_bearing_tick(h, 32100000, NULL);
        a = snap_src_now(h, &sn, 0xA1); b = sn ? snap_src(sn, 0xB2) : NULL;
        CHECK(sn && b && b->focus_match && a && !a->focus_match && sn->focus_company_id == 6, "company filter: Microsoft");
        btrx_bearing_snapshot_release(h, sn);
        btrx_bearing_set_focus_filter(h, 0, -1, false);
        btrx_bearing_tick(h, 33200000, NULL);
        sn = btrx_bearing_snapshot_acquire(h);
        CHECK(sn && !sn->focus_active, "filter cleared");
        btrx_bearing_snapshot_release(h, sn);
        btrx_bearing_destroy(h);
    }
    /* a focused (filtered) source is never evicted, even with the least evidence */
    {
        btrx_bearing_config_t c3 = c; c3.max_sources = 3; c3.extra_min_sightings = 1;
        btrx_bearing_t *h = mk(&c3);
        inj_t s = { 0, 0, 0 };
        btrx_bearing_report_source_t w = entry(0xF0, 1, -70, -72, 2);
        w.pod_dev_class = 2; w.company_id = 0x0087;
        inject(h, &s, 10000000, 0, 0, 90, 30, &w, 1, true);
        btrx_bearing_set_focus_filter(h, 0, 0x0087, false);
        for (int i = 0; i < 12; i++) {
            btrx_bearing_report_source_t e = entry(0x100 + (uint64_t)i, (uint16_t)(10 + i), -60, -65, 9);
            for (int j = 0; j < 3; j++) inject(h, &s, 11000000 + (i * 3 + j) * 1000000LL, i * 5.0, j * 5.0, (float)(j * 30), 30, &e, 1, true);
        }
        CHECK(src_of(h, 0xF0) != NULL, "focused Garmin kept through 12 newcomers in 3 slots");
        btrx_bearing_destroy(h);
    }
    /* launch point: strong while on the ground at the first position -> LAUNCH | MUTED; heard
       strong later away from it -> not flagged; auto mute off flags without muting */
    for (int mute_on = 0; mute_on < 2; mute_on++) {
        btrx_bearing_t *h = mk(NULL);
        btrx_bearing_set_launch_auto_mute(h, mute_on != 0);
        inj_t s = { 0, 0, 0 };
        btrx_bearing_report_source_t g = entry(0xC1, 1, -30, -32, 9);
        for (int i = 0; i < 5; i++) inject(h, &s, 10000000 + i * 1000000LL, 0, 0, (float)(i * 20), 0.5f, &g, 1, true);
        btrx_bearing_report_source_t far = entry(0xC2, 2, -40, -42, 9);
        for (int i = 0; i < 5; i++) inject(h, &s, 20000000 + i * 1000000LL, 200, 0, (float)(i * 20), 30, &far, 1, true);
        const btrx_bearing_snapshot_t *sn;
        const btrx_bearing_source_t *a = snap_src_now(h, &sn, 0xC1), *b = sn ? snap_src(sn, 0xC2) : NULL;
        CHECK(sn && sn->launch_valid, "launch point set from the first ground position");
        CHECK(a && (a->flags & BTRX_BEARING_SRC_LAUNCH) && (((a->flags & BTRX_BEARING_SRC_MUTED) != 0) == (mute_on != 0)),
            "launch source flagged, muted only with auto mute (%d, flags %u)", mute_on, a ? a->flags : 0);
        CHECK(b && !(b->flags & BTRX_BEARING_SRC_LAUNCH), "away from the launch point: not flagged");
        btrx_bearing_snapshot_release(h, sn);
        if (mute_on) {   /* the user unmutes: stays unmuted, also after an eviction and return */
            uint64_t k = 0xC1;
            btrx_bearing_set_source_flags(h, &k, 1, BTRX_BEARING_SRC_MUTED, 0);
            btrx_bearing_reset(h, BTRX_BEARING_RESET_SOURCE, 0xC1);
            for (int i = 0; i < 3; i++) inject(h, &s, 30000000 + i * 1000000LL, 200, 10, 0, 30, &g, 1, true);
            a = snap_src_now(h, &sn, 0xC1);
            CHECK(a && (a->flags & BTRX_BEARING_SRC_LAUNCH) && !(a->flags & BTRX_BEARING_SRC_MUTED), "unmuted launch source stays unmuted (%u)", a ? a->flags : 0);
            btrx_bearing_snapshot_release(h, sn);
        }
        btrx_bearing_destroy(h);
    }
    /* first position already airborne: no launch point, nothing flagged */
    {
        btrx_bearing_t *h = mk(NULL);
        inj_t s = { 0, 0, 0 };
        btrx_bearing_report_source_t g = entry(0xD1, 1, -30, -32, 9);
        for (int i = 0; i < 5; i++) inject(h, &s, 10000000 + i * 1000000LL, 0, 0, 0, 40, &g, 1, true);
        for (int i = 0; i < 5; i++) inject(h, &s, 15000000 + i * 1000000LL, 0, 0, 0, 1, &g, 1, true);
        const btrx_bearing_snapshot_t *sn;
        const btrx_bearing_source_t *a = snap_src_now(h, &sn, 0xD1);
        CHECK(sn && !sn->launch_valid && a && !(a->flags & BTRX_BEARING_SRC_LAUNCH), "airborne start: no launch flags");
        btrx_bearing_snapshot_release(h, sn);
        btrx_bearing_destroy(h);
    }
    /* range cap: a source heard -50 dBm (both sides) at 8 m AGL is bounded within the capped
       range of that spot even when most reports point it far along a ray */
    {
        btrx_bearing_t *h = mk(NULL);
        inj_t s = { 0, 0, 0 };
        btrx_bearing_report_source_t e;
        /* 40 weak reports from 0..195 m east, heading north, port side louder: ray to the west */
        for (int i = 0; i < 40; i++) {
            e = entry(0xE1, 1, -70, -76, 9);
            inject(h, &s, 10000000 + i * 1000000LL, i * 5.0, 0, (float)(i % 2 ? 0 : 20), 30, &e, 1, true);
        }
        e = entry(0xE1, 1, -50, -51, 9);
        for (int i = 0; i < 3; i++) inject(h, &s, 50000000 + i * 1000000LL, 0, 0, (float)(i * 30), 8, &e, 1, true);
        bb_source_t *bs = src_of(h, 0xE1);
        CHECK(bs && bs->near_valid && bs->near_rssi == -50, "strong low report noted (%d)", bs ? bs->near_rssi : 0);
        const btrx_bearing_snapshot_t *sn;
        const btrx_bearing_source_t *a = snap_src_now(h, &sn, 0xE1);
        double x = 0, y = 0;
        if (a && a->estimate.lat_deg == a->estimate.lat_deg) est_to_m(&a->estimate, &x, &y);
        CHECK(a && hypot(x, y) <= 40, "estimate within the capped range of the strong spot (%.1f m)", hypot(x, y));
        btrx_bearing_snapshot_release(h, sn);
        btrx_bearing_destroy(h);
    }
}


/* ================= Nov 23 staleness: fade, hide and focus loss (presentation only) ================= */
static uint64_t rgba_alpha_sum(const btrx_bearing_snapshot_t *sn) {
    uint64_t sum = 0;
    for (int c = 0; sn && c < sn->width * sn->height; c++) sum += sn->rgba[c * 4 + 3];
    return sum;
}

static void test_staleness(void) {
    printf("staleness\n");
    NEAR(btrx_bearing_presence(25, 60, 0), 1, 1e-6, "fresh: full");
    NEAR(btrx_bearing_presence(25, 60, 25), 1, 1e-6, "fade starts at 25 s");
    NEAR(btrx_bearing_presence(25, 60, 42.5f), 0.5, 1e-6, "half way");
    NEAR(btrx_bearing_presence(25, 60, 60), 0, 1e-6, "gone at 60 s");
    NEAR(btrx_bearing_presence(25, 60, 600), 0, 1e-6, "stays gone");
    NEAR(btrx_bearing_presence(0, 0, 600), 1, 1e-6, "off: always full");
    btrx_bearing_config_t c; btrx_bearing_config_default(&c);
    CHECK(c.stale_fade_start_s == 0 && c.stale_hide_s == 0, "off by default (engine tests keep the 10 min fade)");
    c.extra_min_sightings = 1;
    c.stale_fade_start_s = BTRX_BEARING_STALE_FADE_START_S;
    c.stale_hide_s = BTRX_BEARING_STALE_HIDE_S;
    btrx_bearing_config_t off; btrx_bearing_config_default(&off); off.extra_min_sightings = 1;
    btrx_bearing_t *h = mk(&c), *ho = mk(&off);
    inj_t s = { 0, 0, 0 }, so = { 0, 0, 0 };
    feed_two(h, &s, 10000000, 20);
    feed_two(ho, &so, 10000000, 20);
    btrx_bearing_set_focus(h, 0xA1);
    btrx_bearing_set_focus(ho, 0xA1);
    btrx_bearing_tick(h, 31000000, NULL);
    btrx_bearing_tick(ho, 31000000, NULL);
    const btrx_bearing_snapshot_t *sn = btrx_bearing_snapshot_acquire(h);
    const btrx_bearing_source_t *a = snap_src(sn, 0xA1);
    int64_t last = a ? a->last_heard_us : 0;
    uint64_t full = rgba_alpha_sum(sn);
    CHECK(sn && sn->focus_key == 0xA1 && full > 0, "fresh: focused and drawn");
    btrx_bearing_snapshot_release(h, sn);
    /* both fading: 45 s after A1's last report */
    btrx_bearing_tick(h, last + 45000000, NULL);
    sn = btrx_bearing_snapshot_acquire(h);
    uint64_t fading = rgba_alpha_sum(sn);
    CHECK(sn && sn->focus_key == 0xA1 && fading > 0 && fading < full, "fading: dimmer (%llu < %llu), still focused",
        (unsigned long long)fading, (unsigned long long)full);
    btrx_bearing_snapshot_release(h, sn);
    /* 61 s: nothing drawn, focus gone, the sources and their evidence kept */
    btrx_bearing_tick(h, last + 61000000, NULL);
    btrx_bearing_tick(ho, last + 61000000, NULL);
    sn = btrx_bearing_snapshot_acquire(h);
    a = snap_src(sn, 0xA1);
    CHECK(sn && rgba_alpha_sum(sn) == 0, "stale: nothing in the RGBA");
    CHECK(sn && sn->focus_key == 0 && sn->trail_count == 0, "stale: focus lost");
    CHECK(sn && sn->source_count == 2 && a && a->reports_used > 0 && a->peak_alpha > 0, "stale: sources and evidence kept");
    btrx_bearing_snapshot_release(h, sn);
    sn = btrx_bearing_snapshot_acquire(ho);
    CHECK(sn && sn->focus_key == 0xA1 && rgba_alpha_sum(sn) > 0, "off: focused and drawn at 61 s");
    btrx_bearing_snapshot_release(ho, sn);
    /* heard again: drawn again (focus stays off until chosen again) */
    feed_two(h, &s, last + 62000000, 3);
    btrx_bearing_tick(h, last + 66000000, NULL);
    sn = btrx_bearing_snapshot_acquire(h);
    CHECK(sn && rgba_alpha_sum(sn) > 0 && sn->focus_key == 0, "heard again: drawn, not refocused");
    btrx_bearing_snapshot_release(h, sn);
    btrx_bearing_destroy(h); btrx_bearing_destroy(ho);
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    g_grid = malloc(sizeof(float) * 192 * 192);
    bool quick = argc > 1 && strcmp(argv[1], "--quick") == 0;
    test_siphash();
    test_parsers();
    test_steer_format2();
    test_model();
    test_clock();
    test_pose();
    test_accumulation();
    test_fade();
    test_mute();
    test_sources_at();
    test_lag();
    test_multi();
    test_resolution();
    test_stable_ids();
    test_backlog_health();
    test_stats_without_view();
    test_watch_fixes();
    test_nov20_units();
    test_staleness();
    test_nov20_fixture();
    test_threads();
    test_worker_and_abi();
    scenarios();
    if (!quick) bench();
    printf("%d checks, %d failures\n", g_checks, g_fail);
    free(g_grid);
    return g_fail ? 1 : 0;
}
