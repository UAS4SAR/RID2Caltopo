/* btrx_bearing: handle, config, input rings, tick, worker (sections 6.1-6.3). */
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>      /* timespec_get for the own-worker wait only */
#include "bb_internal.h"

uint32_t btrx_bearing_abi_version(void) { return BTRX_BEARING_ABI_VERSION; }

void btrx_bearing_config_default(btrx_bearing_config_t *c) {
    if (!c) return;
    memset(c, 0, sizeof *c);
    c->struct_size = (uint32_t)sizeof *c;
    c->abi_version = BTRX_BEARING_ABI_VERSION;
    c->cell_m = 10; c->grid_cells = 192; c->max_sources = 32;
    c->footprint_m = 400; c->recentre_margin_m = 300;
    c->sigma_sample_db = 5.5f; c->sigma_c0_db = 0.5f; c->c0_db = 0; c->sigma_model_db = 2;
    c->student_nu = 4; c->p_far = 0.05f; c->scan_floor_dbm = -100; c->hpbw_deg = 90;
    c->level_term_weight = 0;
    c->tempering = 0.5f; c->info_ref_db = 2;
    c->diversity_m = 5; c->diversity_deg = 5; c->diversity_window_ms = 30000; c->diversity_cap = 9;
    c->fade_total_s = 600; c->fade_shape_p = 1; c->bucket_min_s = 6; c->bucket_max_s = 60;
    c->buckets_per_source = 64; c->tile_pool_tiles = 8192;
    c->alpha_max = 0.7f; c->support_sat = 8; c->alpha_visible = 0.02f; c->ray_halfwidth_ll = -0.5f;
    c->max_yaw_rate_dps = 10; c->max_yaw_change_deg = 10;
    c->max_yaw_gap_ms = 500; c->max_pos_gap_ms = 3000; c->max_pos_extrapolate_ms = 1500;
    c->max_report_lag_ms = 15000;
    c->yaw_ring_capacity = 1024; c->pos_ring_capacity = 64;
    c->max_yaw_rate_hz = 60; c->max_pos_rate_hz = 2;
    c->yaw_latency_ms = 0; c->default_agl_m = 30; c->sync_window_ms = 120000;
    c->lag_trace = false; c->lag_history = 600;
    c->est_min_reports = 10; c->est_min_yaw_span_deg = 60; c->est_min_baseline_m = 50;
    c->est_max_axis_2s_m = 100; c->second_peak_mass = 0.25f;
    c->max_reports_per_tick = 4; c->publish_interval_ms = 1000;
    c->extra_min_sightings = 2; c->extra_sighting_window_ms = 60000;
    c->range_ref_dbm = -38; c->range_exponent = 2.2f; c->range_margin = 3; c->range_min_m = 30;
    c->range_cap_dbm = -55; c->range_cap_max_agl_m = 40; c->range_cap_margin = 4; c->range_cap_min_m = 25;
    c->range_cap_nats_per_100m = 20;
    c->cover_free_m = 60; c->cover_nats_per_100m = 3;
    c->launch_rssi_dbm = -70; c->launch_radius_m = 15; c->launch_max_agl_m = 5; c->launch_window_s = 300;
    c->launch_auto_mute = true;
    c->own_rssi_dbm = -40; c->own_hold_ms = 60000; c->own_moved_m = 30;
    c->fine_cell_m = 2.5f; c->fine_grid_cells = 160; c->fine_footprint_m = 100;
    c->fine_max_agl_m = 30; c->fine_max_speed_mps = 4; c->fine_strong_dbm = -65; c->fine_tile_pool_tiles = 3072;
    c->ray_ambiguous_ll = 0.5f; c->focus_dim = 0.25f; c->pin_max_axis_2s_m = 30;
    c->gate_on_attitude_flag = false; c->roll_correct = true;
    c->roll_full_weight_deg = 10; c->roll_max_deg = 45;
    c->avg_windows = 3; c->avg_span_ms = 2600;
}

int32_t btrx_bearing_fine_cells(const btrx_bearing_config_t *c) {
    return c && c->fine_cell_m > 0 ? c->fine_grid_cells : 0;
}

const char *btrx_bearing_status_string(btrx_bearing_status_t s) {
    switch (s) {
    case BTRX_BEARING_OK: return "ok";
    case BTRX_BEARING_W_DROPPED: return "dropped oldest";
    case BTRX_BEARING_E_INVALID: return "invalid";
    case BTRX_BEARING_E_NOMEM: return "out of memory";
    case BTRX_BEARING_E_ABI: return "ABI mismatch";
    case BTRX_BEARING_E_BUSY: return "busy";
    case BTRX_BEARING_E_LATE: return "late";
    case BTRX_BEARING_E_NO_SNAPSHOT: return "no snapshot";
    default: return "unknown";
    }
}

static bool fin(float v) { return isfinite(v); }

static bool config_ok(const btrx_bearing_config_t *c) {
    if (!(c->cell_m > 0) || c->grid_cells < BB_TILE || c->grid_cells % BB_TILE != 0 || c->grid_cells > 1024) return false;
    if (c->max_sources < 1 || c->max_sources > BTRX_BEARING_MAX_SOURCES) return false;
    if (!(c->footprint_m > 0) || !fin(c->recentre_margin_m)) return false;
    if (!(c->sigma_sample_db > 0) || !(c->student_nu > 0) || !(c->p_far > 0 && c->p_far < 1)) return false;
    if (!(c->hpbw_deg > 0) || !(c->tempering > 0) || !(c->info_ref_db > 0)) return false;
    if (!(c->fade_total_s >= 1) || !(c->fade_shape_p >= 1 && c->fade_shape_p <= 4)) return false;
    if (!(c->bucket_min_s > 0) || !(c->bucket_max_s >= c->bucket_min_s)) return false;
    if (c->buckets_per_source < 1 || c->tile_pool_tiles < 1) return false;
    if (!(c->alpha_max > 0 && c->alpha_max <= 1) || !(c->support_sat > 0)) return false;
    if (c->max_report_lag_ms < 0 || c->yaw_ring_capacity < 2 || c->pos_ring_capacity < 2) return false;
    if (!(c->max_yaw_rate_hz > 0) || !(c->max_pos_rate_hz > 0)) return false;
    double need_s = (c->max_report_lag_ms + 2000) / 1000.0;
    if (c->yaw_ring_capacity / c->max_yaw_rate_hz < need_s) return false;
    if (c->pos_ring_capacity / c->max_pos_rate_hz < need_s) return false;
    if (c->lag_history < 1 || c->max_reports_per_tick < 1 || c->publish_interval_ms < 0) return false;
    if (c->diversity_cap < 0 || c->sync_window_ms <= 0) return false;
    if (c->fine_cell_m < 0 || !fin(c->fine_cell_m)) return false;
    if (c->fine_cell_m > 0 && (c->fine_grid_cells < BB_TILE || c->fine_grid_cells % BB_TILE != 0
            || c->fine_grid_cells > 1024 || !(c->fine_footprint_m > 0) || c->fine_tile_pool_tiles < 1)) return false;
    if (!(c->range_exponent > 0) || !fin(c->range_ref_dbm) || !(c->range_margin >= 0)) return false;
    if (!(c->focus_dim >= 0 && c->focus_dim <= 1) || !(c->roll_max_deg > 0)) return false;
    if (!fin(c->range_cap_dbm) || !(c->range_cap_margin > 0) || !(c->range_cap_min_m > 0) || !(c->range_cap_nats_per_100m >= 0) || !fin(c->range_cap_max_agl_m)) return false;
    if (!(c->cover_free_m >= 0) || !(c->cover_nats_per_100m >= 0)) return false;
    if (!fin(c->launch_rssi_dbm) || !(c->launch_radius_m >= 0) || !fin(c->launch_max_agl_m) || !(c->launch_window_s >= 0)) return false;
    return true;
}

btrx_bearing_status_t btrx_bearing_create(const btrx_bearing_config_t *cfg,
        const uint8_t incident_key[16], btrx_bearing_t **out) {
    if (!cfg || !incident_key || !out) return BTRX_BEARING_E_INVALID;
    *out = NULL;
    if (cfg->struct_size != sizeof(btrx_bearing_config_t) || cfg->abi_version != BTRX_BEARING_ABI_VERSION)
        return BTRX_BEARING_E_ABI;
    if (!config_ok(cfg)) return BTRX_BEARING_E_INVALID;
    btrx_bearing_t *h = calloc(1, sizeof *h);
    if (!h) return BTRX_BEARING_E_NOMEM;
    h->cfg = *cfg;
    memcpy(h->key, incident_key, 16);
    h->fade_total_s = cfg->fade_total_s;
    h->fade_shape_p = cfg->fade_shape_p;
    size_t W = (size_t)cfg->grid_cells, N = W * W;
    size_t P = (size_t)BTRX_BEARING_PATTERN_AZ * BTRX_BEARING_PATTERN_DEP;
    h->inbox[0] = calloc(1, sizeof(bb_inbox_t));
    h->inbox[1] = calloc(1, sizeof(bb_inbox_t));
    h->pattern_delta = malloc(P * sizeof(float));
    h->pattern_sum = malloc(P * sizeof(float));
    h->pose.yaw = calloc((size_t)cfg->yaw_ring_capacity, sizeof(bb_yaw_t));
    h->pose.pos = calloc((size_t)cfg->pos_ring_capacity, sizeof(bb_pos_t));
    h->lag.rec = calloc((size_t)cfg->lag_history, sizeof(bb_lagrec_t));
    h->lag.yaw_in = calloc((size_t)cfg->lag_history, sizeof(int32_t));
    h->lag.pos_in = calloc((size_t)cfg->lag_history, sizeof(int32_t));
    h->lag.scratch = calloc((size_t)cfg->lag_history, sizeof(int32_t));
    h->bucket_mem = calloc((size_t)cfg->buckets_per_source * (size_t)cfg->max_sources, sizeof(bb_bucket_t));
    h->tiles = malloc((size_t)cfg->tile_pool_tiles * sizeof(bb_tile_t));
    h->scr_e = malloc(N * sizeof(float));
    h->scr_s = malloc(N * sizeof(float));
    h->scr_stack = malloc(N * sizeof(int32_t));
    h->scr_mask = malloc(N);
    h->cover_cap = BB_COVER_CAP;
    h->cover_e = malloc((size_t)h->cover_cap * sizeof(float));
    h->cover_n = malloc((size_t)h->cover_cap * sizeof(float));
    h->cover_dist = malloc(N * sizeof(float));
    h->cover_dist_pts = -1;
    h->focus_company = -1;
    h->launch_auto_mute = cfg->launch_auto_mute;
    bool ok = h->cover_e && h->cover_n && h->cover_dist && h->inbox[0] && h->inbox[1] && h->pattern_delta && h->pattern_sum && h->pose.yaw && h->pose.pos
        && h->lag.rec && h->lag.yaw_in && h->lag.pos_in && h->lag.scratch && h->bucket_mem && h->tiles
        && h->scr_e && h->scr_s && h->scr_stack && h->scr_mask;
    for (int i = 0; i < 3 && ok; i++) {
        h->snaps[i].rgba = calloc(N * 4, 1);
        h->snaps[i].alpha = calloc(N * BTRX_BEARING_MAX_SOURCES, 1);
        ok = h->snaps[i].rgba && h->snaps[i].alpha;
    }
    if (!ok) { btrx_bearing_destroy(h); return BTRX_BEARING_E_NOMEM; }
    if (cfg->pattern_delta_db && cfg->pattern_sum_db) {
        memcpy(h->pattern_delta, cfg->pattern_delta_db, P * sizeof(float));
        memcpy(h->pattern_sum, cfg->pattern_sum_db, P * sizeof(float));
    } else {
        bb_pattern_build_v1(h->pattern_delta, h->pattern_sum, cfg->hpbw_deg);
    }
    pthread_mutex_init(&h->in_mu, NULL);
    pthread_cond_init(&h->in_cv, NULL);
    pthread_mutex_init(&h->work_mu, NULL);
    pthread_mutex_init(&h->snap_mu, NULL);
    h->snap_latest = h->snap_reading = -1;
    h->pose.yaw_cap = cfg->yaw_ring_capacity;
    h->pose.pos_cap = cfg->pos_ring_capacity;
    bb_pose_clear(&h->pose);
    h->lag.cap = cfg->lag_history;
    h->lag.in_cap = cfg->lag_history;
    for (int32_t i = 0; i < cfg->max_sources; i++) h->sources[i].buckets = h->bucket_mem + (size_t)i * cfg->buckets_per_source;
    bb_tiles_init(h);
    h->view_tiles = cfg->grid_cells / BB_TILE;
    h->clock.err_ms = -1;
    atomic_init(&h->worker_stop, false);
    if (cfg->fine_cell_m > 0) {
        /* Fine layer: accumulation only (no inputs, snapshots or worker of its own); its
         * sources are slot-parallel to this handle's; shares the pattern tables. */
        btrx_bearing_t *f = calloc(1, sizeof *f);
        if (!f) { btrx_bearing_destroy(h); return BTRX_BEARING_E_NOMEM; }
        h->fine = f;
        f->is_fine = true;
        f->cfg = *cfg;
        f->cfg.cell_m = cfg->fine_cell_m;
        f->cfg.grid_cells = cfg->fine_grid_cells;
        f->cfg.footprint_m = cfg->fine_footprint_m;
        f->cfg.recentre_margin_m = 0.3f * cfg->fine_grid_cells * cfg->fine_cell_m;
        f->cfg.tile_pool_tiles = cfg->fine_tile_pool_tiles;
        f->cfg.fine_cell_m = 0;
        memcpy(f->key, incident_key, 16);
        f->fade_total_s = h->fade_total_s; f->fade_shape_p = h->fade_shape_p;
        size_t FN = (size_t)cfg->fine_grid_cells * (size_t)cfg->fine_grid_cells;
        f->pattern_delta = h->pattern_delta; f->pattern_sum = h->pattern_sum;
        f->bucket_mem = calloc((size_t)cfg->buckets_per_source * (size_t)cfg->max_sources, sizeof(bb_bucket_t));
        f->tiles = malloc((size_t)cfg->fine_tile_pool_tiles * sizeof(bb_tile_t));
        f->scr_e = malloc(FN * sizeof(float));
        f->scr_s = malloc(FN * sizeof(float));
        for (int i = 0; i < 3; i++) h->snaps[i].fine_rgba = calloc(FN * 4, 1);
        if (!f->bucket_mem || !f->tiles || !f->scr_e || !f->scr_s || !h->snaps[0].fine_rgba
                || !h->snaps[1].fine_rgba || !h->snaps[2].fine_rgba) { btrx_bearing_destroy(h); return BTRX_BEARING_E_NOMEM; }
        for (int32_t i = 0; i < cfg->max_sources; i++) {
            f->sources[i].buckets = f->bucket_mem + (size_t)i * cfg->buckets_per_source;
            for (int32_t j = 0; j < cfg->buckets_per_source; j++) f->sources[i].buckets[j].tile_head = BB_NONE;
        }
        bb_tiles_init(f);
        f->view_tiles = f->cfg.grid_cells / BB_TILE;
    }
    for (int i = 0; i < 3; i++) {
        h->snaps[i].trail = calloc(BTRX_BEARING_TRAIL_MAX, sizeof(btrx_bearing_trail_point_t));
        if (!h->snaps[i].trail) { btrx_bearing_destroy(h); return BTRX_BEARING_E_NOMEM; }
    }
    *out = h;
    return BTRX_BEARING_OK;
}

void btrx_bearing_destroy(btrx_bearing_t *h) {
    if (!h) return;
    if (h->worker_running) btrx_bearing_stop_worker(h);
    if (h->tiles) {   /* fully constructed */
        pthread_mutex_destroy(&h->in_mu);
        pthread_cond_destroy(&h->in_cv);
        pthread_mutex_destroy(&h->work_mu);
        pthread_mutex_destroy(&h->snap_mu);
    }
    free(h->inbox[0]); free(h->inbox[1]);
    free(h->pattern_delta); free(h->pattern_sum);
    free(h->pose.yaw); free(h->pose.pos);
    free(h->lag.rec); free(h->lag.yaw_in); free(h->lag.pos_in); free(h->lag.scratch);
    free(h->bucket_mem); free(h->tiles);
    free(h->scr_e); free(h->scr_s); free(h->scr_stack); free(h->scr_mask);
    free(h->cover_e); free(h->cover_n); free(h->cover_dist);
    for (int i = 0; i < 3; i++) {
        free(h->snaps[i].rgba); free(h->snaps[i].alpha); free(h->snaps[i].fine_rgba); free(h->snaps[i].trail);
    }
    if (h->fine) {
        btrx_bearing_t *f = h->fine;
        free(f->bucket_mem); free(f->tiles); free(f->scr_e); free(f->scr_s);
        free(f);
    }
    free(h);
}

/* ---------- inputs ---------- */
#define RING_PUSH(ring, N, value, dropped) do { \
    if ((ring).count == (N)) { (ring).item[(ring).head] = (value); (ring).head = ((ring).head + 1) % (N); (dropped) = true; } \
    else { (ring).item[((ring).head + (ring).count) % (N)] = (value); (ring).count++; } } while (0)

static btrx_bearing_status_t finish_push(btrx_bearing_t *h, bool dropped) {
    if (dropped) h->inbox[h->inbox_active]->dropped++;
    pthread_cond_signal(&h->in_cv);
    pthread_mutex_unlock(&h->in_mu);
    return dropped ? BTRX_BEARING_W_DROPPED : BTRX_BEARING_OK;
}

btrx_bearing_status_t btrx_bearing_push_pose(btrx_bearing_t *h, const btrx_bearing_pose_t *p) {
    if (!h || !p || !p->mask) return BTRX_BEARING_E_INVALID;
    if ((p->mask & BTRX_BEARING_POSE_POSITION) && (!isfinite(p->lat_deg) || !isfinite(p->lon_deg)
            || fabs(p->lat_deg) > 90 || fabs(p->lon_deg) > 180)) return BTRX_BEARING_E_INVALID;
    if ((p->mask & BTRX_BEARING_POSE_YAW) && !isfinite(p->yaw_true_deg)) return BTRX_BEARING_E_INVALID;
    bool dropped = false;
    pthread_mutex_lock(&h->in_mu);
    RING_PUSH(h->inbox[h->inbox_active]->poses, BB_IN_POSES, *p, dropped);
    return finish_push(h, dropped);
}

btrx_bearing_status_t btrx_bearing_push_report(btrx_bearing_t *h, const btrx_bearing_report_t *r) {
    if (!h || !r || r->source_count > BTRX_BEARING_MAX_REPORT_SOURCES) return BTRX_BEARING_E_INVALID;
    bool dropped = false;
    pthread_mutex_lock(&h->in_mu);
    RING_PUSH(h->inbox[h->inbox_active]->reports, BB_IN_REPORTS, *r, dropped);
    return finish_push(h, dropped);
}

btrx_bearing_status_t btrx_bearing_push_time_sync(btrx_bearing_t *h, int64_t t1, uint32_t t2, uint32_t t3, int64_t t4) {
    if (!h || t4 < t1) return BTRX_BEARING_E_INVALID;
    bb_sync_in_t s = { t1, t4, t2, t3 };
    bool dropped = false;
    pthread_mutex_lock(&h->in_mu);
    RING_PUSH(h->inbox[h->inbox_active]->syncs, BB_IN_SYNCS, s, dropped);
    return finish_push(h, dropped);
}

btrx_bearing_status_t btrx_bearing_push_health(btrx_bearing_t *h, int64_t host_us, uint8_t role,
        uint32_t uptime_s, bool lost_known, uint32_t phone_lost) {
    if (!h || role > 3) return BTRX_BEARING_E_INVALID;
    bb_health_in_t v = { host_us, role, uptime_s, lost_known, phone_lost };
    bool dropped = false;
    pthread_mutex_lock(&h->in_mu);
    RING_PUSH(h->inbox[h->inbox_active]->health, BB_IN_HEALTH, v, dropped);
    return finish_push(h, dropped);
}

static btrx_bearing_status_t push_cmd(btrx_bearing_t *h, const bb_cmd_t *c) {
    bool dropped = false;
    pthread_mutex_lock(&h->in_mu);
    RING_PUSH(h->inbox[h->inbox_active]->cmds, BB_IN_CMDS, *c, dropped);
    return finish_push(h, dropped);
}

btrx_bearing_status_t btrx_bearing_note_rendered(btrx_bearing_t *h, uint64_t seq, int64_t host_us) {
    if (!h) return BTRX_BEARING_E_INVALID;
    bb_cmd_t c = { .type = BB_CMD_RENDERED, .seq = seq, .us = host_us };
    return push_cmd(h, &c);
}

btrx_bearing_status_t btrx_bearing_note_connected(btrx_bearing_t *h, int64_t host_us) {
    if (!h) return BTRX_BEARING_E_INVALID;
    bb_cmd_t c = { .type = BB_CMD_CONNECTED, .us = host_us };
    return push_cmd(h, &c);
}

btrx_bearing_status_t btrx_bearing_set_source_flags(btrx_bearing_t *h, const uint64_t *keys,
        size_t key_count, uint32_t mask, uint32_t value) {
    if (!h || (key_count && !keys)) return BTRX_BEARING_E_INVALID;
    btrx_bearing_status_t st = BTRX_BEARING_OK;
    for (size_t i = 0; i < key_count; i++) {
        bb_cmd_t c = { .type = BB_CMD_FLAGS, .key = keys[i], .a = mask, .b = value };
        btrx_bearing_status_t s = push_cmd(h, &c);
        if (s != BTRX_BEARING_OK) st = s;
    }
    return st;
}

btrx_bearing_status_t btrx_bearing_set_source_class(btrx_bearing_t *h, uint64_t key, btrx_bearing_class_t cls) {
    if (!h || cls < BTRX_BEARING_CLASS_UNKNOWN || cls > BTRX_BEARING_CLASS_COMPUTER) return BTRX_BEARING_E_INVALID;
    bb_cmd_t c = { .type = BB_CMD_CLASS, .key = key, .a = (uint32_t)cls };
    return push_cmd(h, &c);
}

btrx_bearing_status_t btrx_bearing_set_fade(btrx_bearing_t *h, float total_s, float shape_p) {
    if (!h || !(total_s >= 60 && total_s <= 1800) || !(shape_p >= 1 && shape_p <= 4)) return BTRX_BEARING_E_INVALID;
    bb_cmd_t c = { .type = BB_CMD_FADE, .f1 = total_s, .f2 = shape_p };
    return push_cmd(h, &c);
}

btrx_bearing_status_t btrx_bearing_set_stable_src_ids(btrx_bearing_t *h, bool stable) {
    if (!h) return BTRX_BEARING_E_INVALID;
    bb_cmd_t c = { .type = BB_CMD_STABLE_IDS, .a = stable ? 1u : 0u };
    return push_cmd(h, &c);
}

btrx_bearing_status_t btrx_bearing_set_focus(btrx_bearing_t *h, uint64_t key) {
    if (!h) return BTRX_BEARING_E_INVALID;
    bb_cmd_t c = { .type = BB_CMD_FOCUS, .key = key };
    return push_cmd(h, &c);
}

btrx_bearing_status_t btrx_bearing_set_focus_filter(btrx_bearing_t *h, uint32_t class_mask,
        int32_t company_id, bool hide_others) {
    if (!h || company_id < -1 || company_id > 0xFFFF) return BTRX_BEARING_E_INVALID;
    bb_cmd_t c = { .type = BB_CMD_FOCUS_FILTER, .a = class_mask, .b = (uint32_t)(company_id + 1), .seq = hide_others ? 1u : 0u };
    return push_cmd(h, &c);
}

btrx_bearing_status_t btrx_bearing_set_launch_auto_mute(btrx_bearing_t *h, bool on) {
    if (!h) return BTRX_BEARING_E_INVALID;
    bb_cmd_t c = { .type = BB_CMD_LAUNCH_MUTE, .a = on ? 1u : 0u };
    return push_cmd(h, &c);
}

btrx_bearing_status_t btrx_bearing_reset(btrx_bearing_t *h, int what, uint64_t key) {
    if (!h || what < BTRX_BEARING_RESET_ALL || what > BTRX_BEARING_RESET_CLOCK) return BTRX_BEARING_E_INVALID;
    bb_cmd_t c = { .type = BB_CMD_RESET, .a = (uint32_t)what, .key = key };
    return push_cmd(h, &c);
}

/* ---------- tick ---------- */
static void apply_cmd(btrx_bearing_t *h, const bb_cmd_t *c) {
    switch (c->type) {
    case BB_CMD_FLAGS: {
        int32_t slot = bb_find_source(h, c->key);
        if (slot != BB_NONE) {
            bb_source_t *s = &h->sources[slot];
            uint32_t before = s->flags;
            s->flags = (s->flags & ~c->a) | (c->b & c->a);
            /* decided (own aircraft saved = IGNORED, or "not on the drone"): no suggestion */
            if (s->flags & (BTRX_BEARING_SRC_OWN_CLEARED | BTRX_BEARING_SRC_IGNORED)) s->flags &= ~BTRX_BEARING_SRC_OWN_SUSPECT;
            if (s->flags & BTRX_BEARING_SRC_OWN_CLEARED) s->own_t0 = 0;
            if ((s->flags & BTRX_BEARING_SRC_IGNORED) && !(before & BTRX_BEARING_SRC_IGNORED)) {
                bb_source_free_buckets(h, s);
                s->ray.valid = false;
            }
        }
        int32_t i;
        for (i = 0; i < h->pend_flags_n; i++) if (h->pend_flags[i].key == c->key) break;
        if (i == h->pend_flags_n) {
            if (h->pend_flags_n == BB_MAX_PENDING_FLAGS) {
                memmove(&h->pend_flags[0], &h->pend_flags[1], sizeof h->pend_flags[0] * (BB_MAX_PENDING_FLAGS - 1));
                i = BB_MAX_PENDING_FLAGS - 1;
            } else h->pend_flags_n++;
            h->pend_flags[i].key = c->key;
            h->pend_flags[i].mask = 0;
            h->pend_flags[i].value = 0;
        }
        h->pend_flags[i].value = (h->pend_flags[i].value & ~c->a) | (c->b & c->a);
        h->pend_flags[i].mask |= c->a;
        h->force_publish = true;
        break;
    }
    case BB_CMD_CLASS: {
        int32_t slot = bb_find_source(h, c->key);
        if (slot != BB_NONE) { h->sources[slot].cls = (uint8_t)c->a; h->force_publish = true; }
        break;
    }
    case BB_CMD_STABLE_IDS:
        h->stable_src_ids = c->a != 0;
        break;
    case BB_CMD_FOCUS: {
        int32_t slot = bb_find_source(h, c->key);
        h->focus_key = slot != BB_NONE ? h->sources[slot].key : c->key;
        h->force_publish = true;
        break;
    }
    case BB_CMD_FOCUS_FILTER:
        h->focus_class_mask = c->a;
        h->focus_company = (int32_t)c->b - 1;
        h->focus_hide = c->seq != 0;
        h->force_publish = true;
        break;
    case BB_CMD_LAUNCH_MUTE:
        h->launch_auto_mute = c->a != 0;
        break;
    case BB_CMD_FADE:
        h->fade_total_s = c->f1;
        h->fade_shape_p = c->f2;
        if (h->fine) { h->fine->fade_total_s = c->f1; h->fine->fade_shape_p = c->f2; }
        h->force_publish = true;
        break;
    case BB_CMD_RESET:
        if (c->a == BTRX_BEARING_RESET_ALL) {
            for (int32_t i = 0; i < h->cfg.max_sources; i++) if (h->sources[i].used) bb_source_evict(h, i);
            h->div.n = h->div.head = 0;
            bb_srcid_clear(h);
            h->pend_flags_n = 0;
            h->cand_n = 0;
            h->focus_key = 0;
            h->focus_class_mask = 0; h->focus_company = -1; h->focus_hide = false;
            h->cover_n_pts = 0; h->cover_dist_pts = -1;
        } else if (c->a == BTRX_BEARING_RESET_SOURCE) {
            int32_t slot = bb_find_source(h, c->key);
            if (slot != BB_NONE) bb_source_evict(h, slot);
        } else {
            bb_clock_reset(&h->clock);
            h->clock.session++;
            bb_srcid_clear(h);
        }
        h->force_publish = true;
        break;
    case BB_CMD_RENDERED:
        bb_lag_rendered(h, c->seq, c->us);
        break;
    case BB_CMD_CONNECTED:
        h->desk_connect_base = h->health[3].total;
        h->desk_connect_base_set = true;
        h->stats.desk_queue_lost_since_connect = 0;
        break;
    default: break;
    }
}

static void apply_health(btrx_bearing_t *h, const bb_health_in_t *v) {
    if (v->role == 2 && bb_clock_uptime(&h->clock, v->uptime_s)) bb_srcid_clear(h);
    bb_health_state_t *st = &h->health[v->role];
    bool restarted = st->have && v->uptime_s < st->last_uptime;
    if (v->known) {
        uint32_t delta;
        if (!st->have || !st->known || restarted) delta = v->lost;
        else delta = v->lost >= st->last_lost ? v->lost - st->last_lost : v->lost;
        st->total = bb_sat_add32(st->total, delta);
        st->last_lost = v->lost;
    }
    st->known = v->known;
    st->have = true;
    st->last_uptime = v->uptime_s;
    if (v->role == 3) {
        h->stats.desk_queue_lost_known = v->known;
        h->stats.desk_queue_lost = st->total;
        h->stats.desk_queue_lost_since_connect = h->desk_connect_base_set ? st->total - h->desk_connect_base : st->total;
    } else if (v->role == 2) {
        h->stats.pod_a_queue_lost = st->total;
    }
}

static void pending_push(btrx_bearing_t *h, const bb_pending_t *p) {
    if (h->pending_n == BB_PENDING_CAP) {
        h->pending_head = (h->pending_head + 1) % BB_PENDING_CAP;
        h->pending_n--;
        h->stats.ring_dropped++;
        bb_lag_late(h, BTRX_BEARING_STAGE_TICK_WAIT);
    }
    h->pending[(h->pending_head + h->pending_n) % BB_PENDING_CAP] = *p;
    h->pending_n++;
}

/* Every report that heard a source with the drone position known (gated or not): the
 * strength trail, and the own-aircraft suggestion (strong for own_hold_ms while the
 * drone moved own_moved_m: a source on the airframe; Nov 16, the ..148C at -30 dBm). */
void bb_source_heard_at(btrx_bearing_t *h, bb_source_t *s, int64_t t, float e, float n, int8_t rssi) {
    int32_t cap = BTRX_BEARING_TRAIL_MAX;
    int32_t at = (s->trail_head + s->trail_n) % cap;
    if (s->trail_n == cap) { s->trail_head = (s->trail_head + 1) % cap; at = (s->trail_head + cap - 1) % cap; }
    else s->trail_n++;
    s->trail[at].t = t; s->trail[at].e = e; s->trail[at].n = n; s->trail[at].rssi = rssi;
    if (rssi >= h->cfg.own_rssi_dbm) {
        if (s->own_t0 == 0 || t - s->own_last > 10000000) {
            s->own_t0 = t; s->own_e0 = e; s->own_n0 = n; s->own_moved = 0;
        }
        s->own_last = t;
        float d = hypotf(e - s->own_e0, n - s->own_n0);
        if (d > s->own_moved) s->own_moved = d;
        if (!(s->flags & (BTRX_BEARING_SRC_OWN_SUSPECT | BTRX_BEARING_SRC_OWN_CLEARED | BTRX_BEARING_SRC_IGNORED))
                && t - s->own_t0 >= (int64_t)h->cfg.own_hold_ms * 1000 && s->own_moved >= h->cfg.own_moved_m) {
            s->flags |= BTRX_BEARING_SRC_OWN_SUSPECT;
            h->force_publish = true;
            char sid[8];
            btrx_bearing_short_id(s->key, sid);
            bb_log(h, 1, "BBOWN %s suggest own aircraft: >= %.0f dBm for %.0f s, drone moved %.0f m",
                    sid, (double)h->cfg.own_rssi_dbm, (double)(t - s->own_t0) / 1e6, (double)s->own_moved);
        }
    } else if (rssi < h->cfg.own_rssi_dbm - 6) {
        s->own_t0 = 0;                                  /* clearly weaker: the run ends */
    }
}

bool bb_launch_key(const btrx_bearing_t *h, uint64_t key) {
    for (int32_t i = 0; i < h->launch_keys_n; i++) if (h->launch_keys[i] == key) return true;
    return false;
}

/* Nov 20: strong at the launch point before takeoff (Ken's back deck: phones and a laptop at
 * the pod read -18 dBm, then smeared 200 m along the rays). */
static void launch_check(btrx_bearing_t *h, bb_source_t *s, int64_t t, const bb_pose_at_t *pp, int8_t best) {
    if (!h->launch_set || (s->flags & (BTRX_BEARING_SRC_LAUNCH | BTRX_BEARING_SRC_IGNORED))) return;
    if (best < h->cfg.launch_rssi_dbm || pp->agl_default || !(pp->agl <= h->cfg.launch_max_agl_m)) return;
    if (t - h->launch_t > (int64_t)((double)h->cfg.launch_window_s * 1e6)) return;
    if (hypotf((float)pp->e - h->launch_e, (float)pp->n - h->launch_n) > h->cfg.launch_radius_m) return;
    if (h->focus_key && s->key == h->focus_key) return;
    s->flags |= BTRX_BEARING_SRC_LAUNCH;
    if (h->launch_auto_mute) s->flags |= BTRX_BEARING_SRC_MUTED;
    if (h->launch_keys_n < BB_LAUNCH_KEYS) h->launch_keys[h->launch_keys_n++] = s->key;
    h->force_publish = true;
    char sid[8];
    btrx_bearing_short_id(s->key, sid);
    bb_log(h, 1, "BBLAUNCH %s heard %d dBm at the launch point%s", sid, (int)best, h->launch_auto_mute ? ": muted" : "");
}

/* Nov 20 range cap: where the drone heard the source strongest while low (strong only). An
 * older point outside the fade window gives way to any new strong one. */
static void near_note(btrx_bearing_t *h, bb_source_t *s, int64_t t, const bb_pose_at_t *pp, int8_t best) {
    if (!(h->cfg.range_cap_dbm < 0) || best < h->cfg.range_cap_dbm || pp->agl_default
            || !(pp->agl <= h->cfg.range_cap_max_agl_m)) return;
    bool stale = s->near_valid && t - s->near_t > (int64_t)((double)h->fade_total_s * 1e6);
    if (s->near_valid && !stale && best < s->near_rssi) return;
    s->near_valid = true;
    s->near_e = (float)pp->e; s->near_n = (float)pp->n;
    s->near_rssi = best; s->near_t = t;
}

static void evaluate(btrx_bearing_t *h, bb_pending_t *p, int64_t now) {
    const btrx_bearing_report_t *r = &p->r;
    bb_lagrec_t *rec = bb_lag_new(h, p);
    rec->t4 = now;
    int64_t t_end = p->t0, t_rep = t_end - 500000;
    bb_pose_at_t pose;
    int f = bb_pose_at(h, t_end, &pose);
    rec->n = 0;
    if (f == BB_FATE_NO_HEADING) { h->stats.no_heading++; rec->fate = (uint8_t)f; }
    else if (f == BB_FATE_NO_POSITION) { h->stats.no_position++; rec->fate = (uint8_t)f; }
    /* Attitude (pod 0x0109 and 0x010A): flags bit 3 is the pod's advice (0x0109: roll > 10 or
     * pitch > 15 deg; 0x010A: roll > 15, pitch > 35, roll rate > 15 deg/s). The pod sends every
     * report, so the layer decides: roll from bytes 25/26 corrects port - stbd and lowers the
     * weight beyond roll_full_weight_deg; pitch is ignored (no left/right bias on track). */
    const bool imu_ok = (r->flags & 0x10) != 0;
    const float roll = imu_ok ? 0.5f * (float)r->roll_x2 : 0.0f;
    bool frame_gated;
    float att_w = 1.0f;
    if (h->cfg.gate_on_attitude_flag) {
        frame_gated = !imu_ok || (r->flags & 0x08);
    } else {
        frame_gated = false;
        if (!imu_ok) att_w = 0.5f;                      /* roll unknown: no correction */
        else {
            float a = fabsf(roll), full = h->cfg.roll_full_weight_deg, mx = h->cfg.roll_max_deg;
            if (a > mx) frame_gated = true;
            else if (a > full && mx > full) att_w = 1.0f - 0.75f * (a - full) / (mx - full);
        }
    }
    if (!f && (pose.yaw_rate > h->cfg.max_yaw_rate_dps || pose.yaw_change > h->cfg.max_yaw_change_deg)) frame_gated = true;
    if (!f && frame_gated) { h->stats.gated++; rec->fate = BB_FATE_GATED; }
    if (r->source_count == 0 && !rec->fate) rec->fate = BB_FATE_EMPTY;
    /* drone position for the strength trail and the own-aircraft check, also when gated */
    bb_pose_at_t pp;
    bool have_pos = f == 0 ? true : bb_pose_pos(h, t_rep, &pp) == 0;
    if (f == 0) pp = pose;
    float speed = NAN;
    bool have_speed = have_pos && bb_drone_speed(h, t_rep, &speed);
    bool any_used = false;
    const float lnpf = logf(h->cfg.p_far);
    const float ss2 = h->cfg.sigma_sample_db * h->cfg.sigma_sample_db;
    const float fixed2 = h->cfg.sigma_c0_db * h->cfg.sigma_c0_db + h->cfg.sigma_model_db * h->cfg.sigma_model_db;
    for (int k = 0; k < r->source_count; k++) {
        const btrx_bearing_report_source_t *rs = &r->src[k];
        int32_t slot = bb_resolve_entry(h, rs, t_rep);
        rec->rank[rec->n] = rs->rank;
        uint8_t *ef = &rec->efate[rec->n];
        if (slot == BB_NONE) {                          /* extra seen once: not tracked yet */
            memcpy(rec->sid[rec->n], "new?", 5);
            rec->n++;
            *ef = BB_FATE_GATED;
            h->extras_tentative++;
            continue;
        }
        bb_source_t *s = &h->sources[slot];
        s->reports_in++;
        if (t_rep > s->last_us) s->last_us = t_rep;
        btrx_bearing_short_id(s->key, rec->sid[rec->n]);
        rec->n++;
        bool hp0 = rs->rssi_port != BTRX_BEARING_RSSI_NOT_HEARD && rs->hits_port > 0;
        bool hs0 = rs->rssi_stbd != BTRX_BEARING_RSSI_NOT_HEARD && rs->hits_stbd > 0;
        int8_t best = BTRX_BEARING_RSSI_NOT_HEARD;
        if (hp0) best = rs->rssi_port;
        if (hs0 && rs->rssi_stbd > best) best = rs->rssi_stbd;
        if (best != BTRX_BEARING_RSSI_NOT_HEARD) {
            s->last_rssi = best;
            if (have_pos) bb_source_heard_at(h, s, t_rep, (float)pp.e, (float)pp.n, best);
            if (have_pos) launch_check(h, s, t_rep, &pp, best);
            /* range cap level: mean of both sides (the antenna pattern's port - stbd split
             * would read a side lobe several dB hot), one side alone 3 dB down */
            if (have_pos) near_note(h, s, t_rep, &pp, hp0 && hs0
                    ? (int8_t)lrintf(0.5f * ((float)rs->rssi_port + (float)rs->rssi_stbd)) : (int8_t)(best - 3));
        }
        if (f || frame_gated) continue;
        if (s->flags & BTRX_BEARING_SRC_IGNORED) { h->stats.ignored++; *ef = BB_FATE_IGNORED; continue; }
        /* Average over the last avg_windows windows (a ~1 Hz advertiser gives about one
         * sample per patch per window): hit-weighted means, hits summed. Each window then
         * counts 1/n in the weights, so the evidence is not counted n times. */
        if (s->win_n > 0 && s->win[0].t == t_rep) s->win_n = 0;   /* same window again: restart */
        memmove(&s->win[1], &s->win[0], sizeof s->win[0] * 3);
        s->win[0].t = t_rep; s->win[0].rp = rs->rssi_port; s->win[0].rs = rs->rssi_stbd;
        s->win[0].hp = hp0 ? rs->hits_port : 0; s->win[0].hs = hs0 ? rs->hits_stbd : 0; s->win[0].roll = roll;
        if (s->win_n < 4) s->win_n++;
        int nwin = 0, hits_p = 0, hits_s = 0;
        float sum_p = 0, sum_s = 0, sum_roll = 0;
        int max_w = h->cfg.avg_windows < 1 ? 1 : (h->cfg.avg_windows > 4 ? 4 : h->cfg.avg_windows);
        for (int w = 0; w < s->win_n && w < max_w; w++) {
            if (t_rep - s->win[w].t > (int64_t)h->cfg.avg_span_ms * 1000) break;
            nwin++;
            hits_p += s->win[w].hp; sum_p += (float)s->win[w].hp * s->win[w].rp;
            hits_s += s->win[w].hs; sum_s += (float)s->win[w].hs * s->win[w].rs;
            sum_roll += (float)(s->win[w].hp + s->win[w].hs) * s->win[w].roll;
        }
        bool hp = hits_p > 0, hs = hits_s > 0;
        float mp = hp ? sum_p / hits_p : 0, msb = hs ? sum_s / hits_s : 0;
        bb_geom_t g;
        memset(&g, 0, sizeof g);
        g.de = pose.e; g.dn = pose.n; g.agl = pose.agl; g.yaw = pose.yaw;
        g.roll_deg = h->cfg.roll_correct && hits_p + hits_s > 0 ? sum_roll / (float)(hits_p + hits_s) : 0.0f;
        float sd2;
        if (hp && hs) {
            g.censored = false;
            g.obs = mp - msb;
            sd2 = ss2 * (1.0f / hits_p + 1.0f / hits_s);
        } else if (hp != hs && (hp ? hits_p : hits_s) >= 2) {
            g.censored = true;
            g.heard_port = hp;
            g.obs = hp ? mp : msb;
            sd2 = ss2 * (1.0f / (hp ? hits_p : hits_s) + 1.0f);
        } else {
            h->stats.gated++;
            *ef = BB_FATE_GATED;
            continue;
        }
        float level = hp && hs ? fmaxf(mp, msb) : (hp ? mp : msb);
        /* The coarse footprint stays footprint_m: a range-limited footprint would mark a
         * strong-transmitting phone's true cells "far" (Tx power varies by 10 dB and more).
         * The range estimate sets the drawn wedge (reach) and the fine layer only. */
        g.foot_m = h->cfg.footprint_m;
        /* Nov 20 range cap: strong and low bounds the footprint (Garmin watch 16 m from the
         * takeoff point slid ~200 m along the ray). The margin (x4, about +13 dB at n 2.2) and
         * the floor keep a hot phone's true cells inside. */
        if (level >= h->cfg.range_cap_dbm && h->cfg.range_cap_dbm < 0 && !pose.agl_default
                && pose.agl <= h->cfg.range_cap_max_agl_m) {
            float cap = bb_range_m(h, level) * h->cfg.range_cap_margin;
            if (cap < h->cfg.range_cap_min_m) cap = h->cfg.range_cap_min_m;
            if (cap < g.foot_m) g.foot_m = cap;
        }
        g.reach_m = bb_reach_m(h, level, h->cfg.footprint_m);
        g.range_m = bb_range_m(h, level);
        g.sigma2 = sd2 + fixed2;
        float qi = h->cfg.info_ref_db * h->cfg.info_ref_db / sd2;
        if (qi > 1) qi = 1;
        int32_t kd = bb_diversity_count(h, slot, t_rep, (float)pose.e, (float)pose.n, pose.yaw);
        g.div = 1.0f / (1.0f + (float)kd);
        float wgt = att_w / (float)nwin;
        g.kappa = h->cfg.tempering * g.div * wgt;
        g.q = qi * g.div * wgt;
        bb_bucket_t *b = bb_bucket_for(h, s, t_rep);
        if (!b) { *ef = BB_FATE_GATED; continue; }
        b->n++;
        b->sum_t_us += t_rep - b->start_us;
        b->far_sum += g.kappa * lnpf;
        b->eff += g.div * att_w;                  /* one new window per report */
        int bin = (int)(pose.yaw / 10.0f);
        if (bin < 0) bin = 0;
        if (bin >= BB_YAW_BINS) bin = BB_YAW_BINS - 1;
        b->yaw_mask |= 1ULL << bin;
        float fe = (float)pose.e, fn = (float)pose.n;
        if (fe < b->min_e) b->min_e = fe;
        if (fe > b->max_e) b->max_e = fe;
        if (fn < b->min_n) b->min_n = fn;
        if (fn > b->max_n) b->max_n = fn;
        bb_grid_add(h, s, b, &g);
        bb_latest_ray(h, s, &g, t_rep);
        /* fine layer: low and slow, or strong */
        if (h->fine && ((pose.agl <= h->cfg.fine_max_agl_m && have_speed && speed <= h->cfg.fine_max_speed_mps)
                || level >= h->cfg.fine_strong_dbm)) {
            bb_source_t *cs = &h->fine->sources[slot];
            if (!cs->used) { cs->used = true; cs->key = s->key; }
            bb_bucket_t *fb = bb_bucket_for(h->fine, cs, t_rep);
            if (fb) {
                bb_geom_t gf = g;
                gf.foot_m = bb_reach_m(h, level, h->fine->cfg.footprint_m);
                fb->n++;
                fb->sum_t_us += t_rep - fb->start_us;
                fb->eff += g.div * att_w;
                bb_grid_add(h->fine, cs, fb, &gf);
            }
        }
        s->reports_used++;
        s->heard_order = ++h->heard_counter;
        h->stats.used++;
        *ef = BB_FATE_USED;
        any_used = true;
    }
    if (any_used) {
        rec->fate = BB_FATE_USED;
        rec->t5 = now;
        if (t_rep > h->newest_used_t_us) h->newest_used_t_us = t_rep;
        h->dirty = true;
    } else if (!rec->fate) {
        rec->fate = (rec->n > 0 && rec->efate[0] == BB_FATE_IGNORED) ? BB_FATE_IGNORED : BB_FATE_GATED;
    }
    bb_lag_final(h, rec);
}

static void final_drop(btrx_bearing_t *h, bb_pending_t *p, int fate, int stage) {
    bb_lagrec_t *rec = bb_lag_new(h, p);
    rec->fate = (uint8_t)fate;
    if (fate == BB_FATE_LATE) { h->stats.late_dropped++; bb_lag_late(h, stage); }
    else h->stats.backlog_history++;
    bb_lag_final(h, rec);
}

static void process_pending(btrx_bearing_t *h, int64_t now) {
    int64_t budget = (int64_t)h->cfg.max_report_lag_ms * 1000;
    int32_t done = 0;
    while (h->pending_n > 0) {
        bb_pending_t *p = &h->pending[h->pending_head];
        if (p->t0 == BB_T_NONE) {
            if (!h->clock.have) {
                if (now - p->t2 > budget) { final_drop(h, p, p->backlog ? BB_FATE_HISTORY : BB_FATE_LATE, BTRX_BEARING_STAGE_POD_TO_ARRIVAL); goto pop; }
                break;
            }
            p->t0 = (int64_t)p->r.window_end_ms * 1000 + h->clock.off_us;
        }
        if (now - p->t0 > budget) {
            int stage;
            if (p->t2 - p->t0 > budget) stage = BTRX_BEARING_STAGE_POD_TO_ARRIVAL;
            else if (p->t3 - p->t0 > budget) stage = BTRX_BEARING_STAGE_ARRIVAL_TO_PUSH;
            else if (p->wait == BB_WAIT_YAW) stage = BTRX_BEARING_STAGE_POSE_WAIT_YAW;
            else if (p->wait == BB_WAIT_POS) stage = BTRX_BEARING_STAGE_POSE_WAIT_POS;
            else stage = BTRX_BEARING_STAGE_TICK_WAIT;
            final_drop(h, p, p->backlog ? BB_FATE_HISTORY : BB_FATE_LATE, stage);
            goto pop;
        }
        {
            int32_t wait = BB_WAIT_NONE;
            if (!bb_pose_ready(h, p->t0, now, &wait)) { p->wait = wait; break; }
            if (done >= h->cfg.max_reports_per_tick) break;
            evaluate(h, p, now);
            done++;
        }
    pop:
        h->pending_head = (h->pending_head + 1) % BB_PENDING_CAP;
        h->pending_n--;
    }
}

btrx_bearing_status_t btrx_bearing_tick(btrx_bearing_t *h, int64_t now, bool *published) {
    if (published) *published = false;
    if (!h) return BTRX_BEARING_E_INVALID;
    if (pthread_mutex_trylock(&h->work_mu) != 0) return BTRX_BEARING_E_BUSY;
    pthread_mutex_lock(&h->in_mu);
    bb_inbox_t *in = h->inbox[h->inbox_active];
    h->inbox_active ^= 1;
    pthread_mutex_unlock(&h->in_mu);

    h->stats.ring_dropped += in->dropped;
    for (int32_t i = 0; i < in->cmds.count; i++) apply_cmd(h, &in->cmds.item[(in->cmds.head + i) % BB_IN_CMDS]);
    for (int32_t i = 0; i < in->syncs.count; i++)
        bb_clock_add_pong(&h->clock, &in->syncs.item[(in->syncs.head + i) % BB_IN_SYNCS], h->cfg.sync_window_ms);
    for (int32_t i = 0; i < in->health.count; i++) apply_health(h, &in->health.item[(in->health.head + i) % BB_IN_HEALTH]);
    for (int32_t i = 0; i < in->poses.count; i++) bb_pose_push(h, &in->poses.item[(in->poses.head + i) % BB_IN_POSES]);
    for (int32_t i = 0; i < in->reports.count; i++) {
        const btrx_bearing_report_t *r = &in->reports.item[(in->reports.head + i) % BB_IN_REPORTS];
        bb_pending_t p;
        memset(&p, 0, sizeof p);
        p.r = *r;
        p.t2 = r->arrival_us;
        p.t3 = r->push_us ? r->push_us : r->arrival_us;
        p.t0 = BB_T_NONE;
        bool backlog = false;
        if (bb_clock_observe_frame(&h->clock, r->window_end_ms, r->arrival_us, r->backlog, &backlog)) {
            bb_srcid_clear(h);
            /* frames of the old session can no longer be timed: history */
            while (h->pending_n > 0) {
                final_drop(h, &h->pending[h->pending_head], BB_FATE_HISTORY, 0);
                h->pending_head = (h->pending_head + 1) % BB_PENDING_CAP;
                h->pending_n--;
            }
        }
        p.backlog = backlog;
        h->stats.reports_in++;
        h->stats.entries_in += r->source_count;
        if (r->truncated) h->stats.steer_truncated++;
        pending_push(h, &p);
    }
    in->poses.count = in->poses.head = 0;
    in->reports.count = in->reports.head = 0;
    in->syncs.count = in->syncs.head = 0;
    in->health.count = in->health.head = 0;
    in->cmds.count = in->cmds.head = 0;
    in->dropped = 0;

    bb_clock_update(&h->clock, now, h->cfg.sync_window_ms);
    process_pending(h, now);
    if (bb_expire(h, now)) h->dirty = true;
    if (h->fine && bb_expire(h->fine, now)) h->dirty = true;
    if (h->pose.pos_n > 0 && h->have_anchor) {
        const bb_pos_t *q = &h->pose.pos[(h->pose.pos_head - 1 + h->pose.pos_cap) % h->pose.pos_cap];
        double e, n;
        bb_local(h, q->lat, q->lon, &e, &n);
        bb_view_update(h, e, n);
        if (h->fine) {
            h->fine->have_anchor = true;
            h->fine->lat0 = h->lat0; h->fine->lon0 = h->lon0; h->fine->coslat0 = h->coslat0;
            bb_view_update(h->fine, e, n);
        }
    }
    bool any_data = false;
    for (int32_t i = 0; i < h->cfg.max_sources && !any_data; i++) if (h->sources[i].used) any_data = true;
    bool due = now - h->last_publish_us >= (int64_t)h->cfg.publish_interval_ms * 1000 || h->snap_seq == 0;
    bool did_publish = false;
    if (h->have_view && (h->force_publish || (due && (h->dirty || any_data)))) {
        bb_publish(h, now);
        did_publish = true;
        if (published) *published = true;
    }
    /* Stats for the platform's status line, also while no view exists yet (no
     * drone position), when nothing can be published (field test, Nov 13). */
    if (did_publish || !h->stats_pub_valid
            || now - h->last_stats_us >= (int64_t)h->cfg.publish_interval_ms * 1000) {
        if (!did_publish) bb_fill_stats(h, now);
        pthread_mutex_lock(&h->snap_mu);
        h->stats_pub = h->stats;
        h->stats_pub_valid = true;
        pthread_mutex_unlock(&h->snap_mu);
        h->last_stats_us = now;
    }
    pthread_mutex_unlock(&h->work_mu);
    return BTRX_BEARING_OK;
}

btrx_bearing_status_t btrx_bearing_stats_copy(btrx_bearing_t *h, btrx_bearing_stats_t *out) {
    if (!h || !out) return BTRX_BEARING_E_INVALID;
    pthread_mutex_lock(&h->snap_mu);
    bool ok = h->stats_pub_valid;
    if (ok) *out = h->stats_pub;
    pthread_mutex_unlock(&h->snap_mu);
    return ok ? BTRX_BEARING_OK : BTRX_BEARING_E_NO_SNAPSHOT;
}

/* ---------- own worker ---------- */
static void *worker_main(void *arg) {
    btrx_bearing_t *h = arg;
    while (!atomic_load(&h->worker_stop)) {
        pthread_mutex_lock(&h->in_mu);
        struct timespec ts;
        timespec_get(&ts, TIME_UTC);
        ts.tv_nsec += 200 * 1000 * 1000;
        if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
        if (!atomic_load(&h->worker_stop)) pthread_cond_timedwait(&h->in_cv, &h->in_mu, &ts);
        pthread_mutex_unlock(&h->in_mu);
        if (atomic_load(&h->worker_stop)) break;
        btrx_bearing_tick(h, h->cfg.now_us(h->cfg.callback_ctx), NULL);
    }
    return NULL;
}

btrx_bearing_status_t btrx_bearing_start_worker(btrx_bearing_t *h) {
    if (!h || !h->cfg.now_us || h->worker_running) return BTRX_BEARING_E_INVALID;
    atomic_store(&h->worker_stop, false);
    if (pthread_create(&h->worker, NULL, worker_main, h) != 0) return BTRX_BEARING_E_NOMEM;
    h->worker_running = true;
    return BTRX_BEARING_OK;
}

btrx_bearing_status_t btrx_bearing_stop_worker(btrx_bearing_t *h) {
    if (!h || !h->worker_running) return BTRX_BEARING_E_INVALID;
    atomic_store(&h->worker_stop, true);
    pthread_mutex_lock(&h->in_mu);
    pthread_cond_broadcast(&h->in_cv);
    pthread_mutex_unlock(&h->in_mu);
    pthread_join(h->worker, NULL);
    h->worker_running = false;
    return BTRX_BEARING_OK;
}

btrx_bearing_status_t btrx_bearing_copy_grid(btrx_bearing_t *h, uint64_t key, int which,
        int64_t now_us, float *out, size_t out_count, int32_t *width, int32_t *height) {
    if (!h || !out) return BTRX_BEARING_E_INVALID;
    size_t N = (size_t)h->cfg.grid_cells * (size_t)h->cfg.grid_cells;
    if (out_count < N) return BTRX_BEARING_E_INVALID;
    pthread_mutex_lock(&h->work_mu);
    int32_t slot = bb_find_source(h, key);
    btrx_bearing_status_t st = BTRX_BEARING_OK;
    if (slot == BB_NONE || !h->have_view) st = BTRX_BEARING_E_INVALID;
    else {
        float eff, ys, bl;
        bb_composite_source(h, &h->sources[slot], now_us, h->scr_e, h->scr_s, &eff, &ys, &bl);
        memcpy(out, which == BTRX_BEARING_GRID_SUPPORT ? h->scr_s : h->scr_e, N * sizeof(float));
        if (width) *width = h->cfg.grid_cells;
        if (height) *height = h->cfg.grid_cells;
    }
    pthread_mutex_unlock(&h->work_mu);
    return st;
}
