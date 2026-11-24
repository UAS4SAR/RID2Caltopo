/* Tile pool, age buckets, expiry, fade weights, composite (section 2.4). */
#include <math.h>
#include <string.h>
#include "bb_internal.h"

float btrx_bearing_bucket_width_s(float total_s, float min_s, float max_s) {
    float w = total_s / 10.0f;
    if (w < min_s) w = min_s;
    if (w > max_s) w = max_s;
    return w;
}

float btrx_bearing_presence(float fade_start_s, float hide_s, float age_s) {
    if (!(hide_s > 0) || !(age_s > fade_start_s)) return 1.0f;
    if (age_s >= hide_s) return 0.0f;
    if (!(hide_s > fade_start_s)) return 1.0f;
    return (hide_s - age_s) / (hide_s - fade_start_s);
}

float btrx_bearing_half_strength_s(float total_s, float shape_p) {
    if (!(shape_p > 0)) shape_p = 1;
    return total_s * powf(0.5f, 1.0f / shape_p);
}

void bb_tiles_init(btrx_bearing_t *h) {
    int32_t n = h->cfg.tile_pool_tiles;
    for (int32_t i = 0; i < n; i++) h->tiles[i].next = i + 1 < n ? i + 1 : BB_NONE;
    h->tile_free = n > 0 ? 0 : BB_NONE;
    h->tiles_in_use = 0;
}

static void tile_release(btrx_bearing_t *h, int32_t t) {
    h->tiles[t].next = h->tile_free;
    h->tile_free = t;
    h->tiles_in_use--;
}

void bb_bucket_free(btrx_bearing_t *h, bb_bucket_t *b) {
    int32_t t = b->tile_head;
    while (t != BB_NONE) { int32_t nx = h->tiles[t].next; tile_release(h, t); t = nx; }
    memset(b, 0, sizeof *b);
    b->tile_head = BB_NONE;
}

void bb_source_free_buckets(btrx_bearing_t *h, bb_source_t *src) {
    for (int32_t i = 0; i < h->cfg.buckets_per_source; i++)
        if (src->buckets[i].used) bb_bucket_free(h, &src->buckets[i]);
}

int64_t bb_bucket_mean_us(const bb_bucket_t *b) {
    return b->n > 0 ? b->start_us + b->sum_t_us / b->n : b->start_us;
}

float bb_fade_weight(const btrx_bearing_t *h, const bb_bucket_t *b, int64_t now_us) {
    double age = (double)(now_us - bb_bucket_mean_us(b)) / 1e6;
    if (age <= 0) return 1.0f;
    double x = age / (double)h->fade_total_s;
    if (x >= 1) return 0.0f;
    return (float)(1.0 - pow(x, (double)h->fade_shape_p));
}

/* The oldest bucket of all sources (by mean time), excluding `keep`. */
static bool expire_oldest(btrx_bearing_t *h, const bb_bucket_t *keep) {
    bb_bucket_t *old = NULL;
    for (int32_t s = 0; s < h->cfg.max_sources; s++) {
        bb_source_t *src = &h->sources[s];
        if (!src->used) continue;
        for (int32_t i = 0; i < h->cfg.buckets_per_source; i++) {
            bb_bucket_t *b = &src->buckets[i];
            if (!b->used || b == keep || b->tile_head == BB_NONE) continue;
            if (!old || bb_bucket_mean_us(b) < bb_bucket_mean_us(old)) old = b;
        }
    }
    if (!old) return false;
    bb_bucket_free(h, old);
    h->stats.buckets_expired_early++;
    h->dirty = true;
    return true;
}

int32_t bb_tile_get(btrx_bearing_t *h, bb_source_t *src, bb_bucket_t *b, int32_t tx, int32_t ty) {
    (void)src;
    for (int32_t t = b->tile_head; t != BB_NONE; t = h->tiles[t].next)
        if (h->tiles[t].tx == tx && h->tiles[t].ty == ty) return t;
    if (h->tile_free == BB_NONE && !expire_oldest(h, b)) return BB_NONE;
    if (h->tile_free == BB_NONE) return BB_NONE;
    int32_t t = h->tile_free;
    bb_tile_t *tile = &h->tiles[t];
    h->tile_free = tile->next;
    h->tiles_in_use++;
    memset(tile->e, 0, sizeof tile->e);
    memset(tile->s, 0, sizeof tile->s);
    tile->tx = tx; tile->ty = ty;
    tile->next = b->tile_head;
    b->tile_head = t;
    b->tile_count++;
    return t;
}

bb_bucket_t *bb_bucket_for(btrx_bearing_t *h, bb_source_t *src, int64_t t_us) {
    int32_t nb = h->cfg.buckets_per_source;
    int64_t width = (int64_t)(btrx_bearing_bucket_width_s(h->fade_total_s, h->cfg.bucket_min_s,
            h->cfg.bucket_max_s) * 1e6);
    bb_bucket_t *latest = NULL, *nearest = NULL, *free_b = NULL, *oldest = NULL;
    int64_t nearest_d = 0;
    for (int32_t i = 0; i < nb; i++) {
        bb_bucket_t *b = &src->buckets[i];
        if (!b->used) { if (!free_b) free_b = b; continue; }
        if (t_us >= b->start_us && t_us < b->end_us) return b;
        if (!latest || b->end_us > latest->end_us) latest = b;
        if (!oldest || b->start_us < oldest->start_us) oldest = b;
        int64_t d = t_us < b->start_us ? b->start_us - t_us : t_us - b->end_us;
        if (!nearest || d < nearest_d) { nearest = b; nearest_d = d; }
    }
    if (latest && t_us < latest->end_us) return nearest;   /* older than the newest bucket: nearest */
    if (!free_b) {
        if (!oldest) return NULL;
        bb_bucket_free(h, oldest);
        h->stats.buckets_expired_early++;
        free_b = oldest;
    }
    int64_t start = t_us >= 0 ? (t_us / width) * width : -((-t_us + width - 1) / width) * width;
    if (latest && start < latest->end_us) start = latest->end_us;
    memset(free_b, 0, sizeof *free_b);
    free_b->used = true;
    free_b->start_us = start;
    free_b->end_us = start + width;
    if (free_b->end_us <= t_us) free_b->end_us = t_us + 1;
    free_b->tile_head = BB_NONE;
    free_b->min_e = free_b->min_n = INFINITY;
    free_b->max_e = free_b->max_n = -INFINITY;
    return free_b;
}

bool bb_expire(btrx_bearing_t *h, int64_t now_us) {
    bool any = false;
    int64_t n_us = (int64_t)((double)h->fade_total_s * 1e6);
    for (int32_t s = 0; s < h->cfg.max_sources; s++) {
        bb_source_t *src = &h->sources[s];
        if (!src->used) continue;
        for (int32_t i = 0; i < h->cfg.buckets_per_source; i++) {
            bb_bucket_t *b = &src->buckets[i];
            if (b->used && bb_bucket_mean_us(b) <= now_us - n_us) { bb_bucket_free(h, b); any = true; }
        }
    }
    return any;
}

void bb_merge_source(btrx_bearing_t *h, bb_source_t *dst, bb_source_t *src) {
    int32_t nb = h->cfg.buckets_per_source;
    for (int32_t i = 0; i < nb; i++) {
        bb_bucket_t *sb = &src->buckets[i];
        if (!sb->used) continue;
        bb_bucket_t *db = NULL, *free_b = NULL;
        for (int32_t j = 0; j < nb; j++) {
            bb_bucket_t *b = &dst->buckets[j];
            if (b->used && b->start_us == sb->start_us) { db = b; break; }
            if (!b->used && !free_b) free_b = b;
        }
        if (!db) {
            if (!free_b) { bb_bucket_free(h, sb); h->stats.buckets_expired_early++; continue; }
            *free_b = *sb;
            memset(sb, 0, sizeof *sb);
            sb->tile_head = BB_NONE;
            continue;
        }
        int32_t t = sb->tile_head;
        while (t != BB_NONE) {
            int32_t nx = h->tiles[t].next;
            int32_t d = BB_NONE;
            for (int32_t k = db->tile_head; k != BB_NONE; k = h->tiles[k].next)
                if (h->tiles[k].tx == h->tiles[t].tx && h->tiles[k].ty == h->tiles[t].ty) { d = k; break; }
            if (d == BB_NONE) {
                h->tiles[t].next = db->tile_head;
                db->tile_head = t;
                db->tile_count++;
            } else {
                for (int c = 0; c < BB_TILE_CELLS; c++) {
                    h->tiles[d].e[c] = bb_sat_add16(h->tiles[d].e[c], h->tiles[t].e[c]);
                    h->tiles[d].s[c] = bb_sat_add16(h->tiles[d].s[c], h->tiles[t].s[c]);
                }
                tile_release(h, t);
            }
            t = nx;
        }
        db->sum_t_us += sb->sum_t_us + (int64_t)sb->n * (sb->start_us - db->start_us);
        db->n += sb->n;
        db->far_sum += sb->far_sum;
        db->eff += sb->eff;
        db->yaw_mask |= sb->yaw_mask;
        if (sb->min_e < db->min_e) db->min_e = sb->min_e;
        if (sb->max_e > db->max_e) db->max_e = sb->max_e;
        if (sb->min_n < db->min_n) db->min_n = sb->min_n;
        if (sb->max_n > db->max_n) db->max_n = sb->max_n;
        if (sb->end_us > db->end_us) db->end_us = sb->end_us;
        memset(sb, 0, sizeof *sb);
        sb->tile_head = BB_NONE;
    }
}

void bb_composite_source(btrx_bearing_t *h, bb_source_t *src, int64_t now_us, float *e, float *s, float *eff,
        float *yaw_span, float *baseline) {
    int32_t W = h->cfg.grid_cells;
    memset(e, 0, sizeof(float) * (size_t)W * (size_t)W);
    memset(s, 0, sizeof(float) * (size_t)W * (size_t)W);
    float effs = 0;
    uint64_t mask = 0;
    float mine = INFINITY, maxe = -INFINITY, minn = INFINITY, maxn = -INFINITY;
    for (int32_t i = 0; i < h->cfg.buckets_per_source; i++) {
        bb_bucket_t *b = &src->buckets[i];
        if (!b->used) continue;
        float w = bb_fade_weight(h, b, now_us);
        if (w <= 0) continue;
        effs += w * b->eff;
        mask |= b->yaw_mask;
        if (b->min_e < mine) mine = b->min_e;
        if (b->max_e > maxe) maxe = b->max_e;
        if (b->min_n < minn) minn = b->min_n;
        if (b->max_n > maxn) maxn = b->max_n;
        float we = w / 256.0f, ws = w / 1024.0f;
        for (int32_t t = b->tile_head; t != BB_NONE; t = h->tiles[t].next) {
            const bb_tile_t *tile = &h->tiles[t];
            int32_t ox = tile->tx - h->vtx0, oy = tile->ty - h->vty0;
            if (ox < 0 || oy < 0 || ox >= h->view_tiles || oy >= h->view_tiles) continue;
            for (int32_t j = 0; j < BB_TILE; j++) {
                int32_t row = W - 1 - (oy * BB_TILE + j);
                float *er = e + (size_t)row * W + ox * BB_TILE;
                float *sr = s + (size_t)row * W + ox * BB_TILE;
                const uint16_t *te = tile->e + j * BB_TILE, *ts = tile->s + j * BB_TILE;
                for (int32_t c = 0; c < BB_TILE; c++) { er[c] += we * te[c]; sr[c] += ws * ts[c]; }
            }
        }
    }
    *eff = effs;
    /* yaw span: 360 minus the largest empty run of 10 deg bins */
    int occupied = 0;
    for (int k = 0; k < BB_YAW_BINS; k++) if (mask & (1ULL << k)) occupied++;
    if (occupied == 0) *yaw_span = 0;
    else {
        int best = 0;
        for (int k = 0; k < BB_YAW_BINS; k++) {
            if (mask & (1ULL << k)) continue;
            int run = 0;
            while (run < BB_YAW_BINS && !(mask & (1ULL << ((k + run) % BB_YAW_BINS)))) run++;
            if (run > best) best = run;
        }
        *yaw_span = (float)((BB_YAW_BINS - best - 1) * 10);
    }
    *baseline = (mine <= maxe) ? hypotf(maxe - mine, maxn - minn) : 0.0f;
}
