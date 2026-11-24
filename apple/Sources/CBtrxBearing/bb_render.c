/* View, publish (alpha rasters, RGBA composite, estimates), triple buffer, sources_at (section 5). */
#include <math.h>
#include <string.h>
#include "bb_internal.h"

static const uint8_t k_palette[BB_PALETTE][3] = {   /* Okabe-Ito, then 4 more distinct */
    { 230, 159, 0 }, { 86, 180, 233 }, { 0, 158, 115 }, { 240, 228, 66 },
    { 0, 114, 178 }, { 213, 94, 0 }, { 204, 121, 167 }, { 0, 0, 0 },
    { 148, 103, 189 }, { 140, 86, 75 }, { 23, 190, 207 }, { 188, 189, 34 },
};

const uint8_t *bb_palette(uint8_t colour) { return k_palette[colour % BB_PALETTE]; }

/* "Strongest here" from the trail within the fade window: the strongest point, averaged
 * with points within 2 dB of it and 30 m of it (steadies a run of equal readings). */
static void strongest_here(btrx_bearing_t *h, const bb_source_t *s, int64_t now_us, btrx_bearing_source_t *o) {
    o->strongest_valid = false;
    o->max_rssi_dbm = BTRX_BEARING_RSSI_NOT_HEARD;
    int64_t oldest = now_us - (int64_t)((double)h->fade_total_s * 1e6);
    int32_t best = -1;
    for (int32_t i = 0; i < s->trail_n; i++) {
        int32_t k = (s->trail_head + i) % BTRX_BEARING_TRAIL_MAX;
        if (s->trail[k].t < oldest) continue;
        if (best < 0 || s->trail[k].rssi >= s->trail[best].rssi) best = k;
    }
    if (best < 0) return;
    o->max_rssi_dbm = s->trail[best].rssi;
    double se = 0, sn = 0; int cnt = 0;
    for (int32_t i = 0; i < s->trail_n; i++) {
        int32_t k = (s->trail_head + i) % BTRX_BEARING_TRAIL_MAX;
        if (s->trail[k].t < oldest || s->trail[k].rssi < s->trail[best].rssi - 2) continue;
        if (hypotf(s->trail[k].e - s->trail[best].e, s->trail[k].n - s->trail[best].n) > 30) continue;
        se += s->trail[k].e; sn += s->trail[k].n; cnt++;
    }
    o->strongest_valid = true;
    o->strongest_t_us = s->trail[best].t;
    bb_latlon(h, se / cnt, sn / cnt, &o->strongest_lat_deg, &o->strongest_lon_deg);
}

static void composite_rgba(uint8_t *rgba, size_t N, const uint8_t *alpha, const uint8_t rgb[3], float dim) {
    for (size_t c = 0; c < N; c++) {
        uint32_t a = alpha[c];
        if (!a) continue;
        if (dim < 1.0f) { a = (uint32_t)lrintf((float)a * dim); if (!a) continue; }
        uint8_t *px = rgba + c * 4;
        uint32_t ia = 255 - a;
        px[0] = (uint8_t)((rgb[0] * a + px[0] * ia + 127) / 255);
        px[1] = (uint8_t)((rgb[1] * a + px[1] * ia + 127) / 255);
        px[2] = (uint8_t)((rgb[2] * a + px[2] * ia + 127) / 255);
        px[3] = (uint8_t)((255 * a + px[3] * ia + 127) / 255);
    }
}

/* Fine layer raster: the same sources, colours, mute and focus as the coarse one. */
static void publish_fine(btrx_bearing_t *h, bb_snap_t *sn, const int32_t *draw_slot, int32_t nd, int64_t now_us) {
    btrx_bearing_t *f = h->fine;
    btrx_bearing_snapshot_t *pub = &sn->pub;
    if (!f || !f->have_view || !sn->fine_rgba) return;
    int32_t W = f->cfg.grid_cells;
    size_t N = (size_t)W * (size_t)W;
    memset(sn->fine_rgba, 0, N * 4);
    bool any = false;
    uint8_t *alpha = (uint8_t *)f->scr_s;       /* reuse: alpha bytes written after S is read */
    for (int32_t d = 0; d < nd; d++) {
        int32_t slot = draw_slot[d];
        bb_source_t *cs = &f->sources[slot];
        if (!cs->used) continue;
        bool has = false;
        for (int32_t i = 0; i < f->cfg.buckets_per_source && !has; i++) has = cs->buckets[i].used && cs->buckets[i].tile_head != BB_NONE;
        if (!has) continue;
        float eff, ys, bl;
        bb_composite_source(f, cs, now_us, f->scr_e, f->scr_s, &eff, &ys, &bl);
        bool lit = false;
        for (size_t c = 0; c < N; c++) {
            float a = h->cfg.alpha_max * fminf(1.0f, f->scr_s[c] / h->cfg.support_sat);
            uint8_t v = a < h->cfg.alpha_visible ? 0 : (uint8_t)lrintf(a * 255.0f);
            alpha[c] = v;                       /* c bytes in, 4c floats read: never overtakes */
            if (v) lit = true;
        }
        if (!lit) continue;
        any = true;
        const bb_source_t *s = &h->sources[slot];
        float dim = 1.0f;
        if (bb_focus_active(h) && !bb_focus_match(h, s)) dim = h->focus_hide ? 0.0f : h->cfg.focus_dim;
        dim *= btrx_bearing_presence(h->cfg.stale_fade_start_s, h->cfg.stale_hide_s, (float)((now_us - s->last_us) / 1e6));
        if (!(dim > 0)) continue;
        composite_rgba(sn->fine_rgba, N, alpha, bb_palette(s->colour), dim);
    }
    if (!any) return;
    double cell = f->cfg.cell_m;
    double west = f->vtx0 * BB_TILE * cell, south = f->vty0 * BB_TILE * cell;
    pub->fine_valid = true;
    pub->fine_width = W; pub->fine_height = W;
    bb_latlon(h, west, south, &pub->fine_south_deg, &pub->fine_west_deg);
    bb_latlon(h, west + W * cell, south + W * cell, &pub->fine_north_deg, &pub->fine_east_deg);
    pub->fine_rgba = sn->fine_rgba;
}

void bb_view_update(btrx_bearing_t *h, double e, double n) {
    const double cell = h->cfg.cell_m, span = (double)h->cfg.grid_cells * cell;
    int32_t cx = (int32_t)floor(e / cell), cy = (int32_t)floor(n / cell);
    if (h->have_view) {
        double w = h->vtx0 * BB_TILE * cell, s = h->vty0 * BB_TILE * cell;
        double m = h->cfg.recentre_margin_m;
        if (e - w >= m && w + span - e >= m && n - s >= m && s + span - n >= m) return;
    }
    int32_t ntx = bb_floordiv(cx, BB_TILE) - h->view_tiles / 2;
    int32_t nty = bb_floordiv(cy, BB_TILE) - h->view_tiles / 2;
    if (!h->have_view || ntx != h->vtx0 || nty != h->vty0) h->dirty = true;
    h->vtx0 = ntx; h->vty0 = nty;
    h->have_view = true;
}

/* Derived stats fields and lag percentiles (worker, under work_mu). */
void bb_fill_stats(btrx_bearing_t *h, int64_t now_us) {
    h->stats.tiles_in_use = (uint32_t)h->tiles_in_use;
    h->stats.tiles_total = (uint32_t)h->cfg.tile_pool_tiles;
    h->stats.fade_total_s = h->fade_total_s;
    h->stats.fade_shape_p = h->fade_shape_p;
    h->stats.pod_offset_ms = h->clock.have ? (int32_t)(h->clock.off_us / 1000) : 0;
    h->stats.pod_offset_err_ms = h->clock.err_ms;
    h->stats.offset_source = h->clock.source;
    h->stats.pod_session = h->clock.session;
    h->stats.display_lag_ms = h->newest_used_t_us ? (int32_t)((now_us - h->newest_used_t_us) / 1000) : -1;
    bb_lag_stats(h, &h->stats);
}

void bb_publish(btrx_bearing_t *h, int64_t now_us) {
    int32_t W = h->cfg.grid_cells;
    size_t N = (size_t)W * (size_t)W;
    pthread_mutex_lock(&h->snap_mu);
    int32_t w = 0;
    while (w == h->snap_latest || w == h->snap_reading) w++;
    pthread_mutex_unlock(&h->snap_mu);
    bb_snap_t *sn = &h->snaps[w];
    btrx_bearing_snapshot_t *pub = &sn->pub;
    memset(pub, 0, sizeof *pub);
    pub->seq = h->snap_seq + 1;
    pub->t_us = now_us;
    pub->width = W; pub->height = W;
    pub->rgba = sn->rgba;
    double cell = h->cfg.cell_m;
    double west = h->vtx0 * BB_TILE * cell, south = h->vty0 * BB_TILE * cell;
    bb_latlon(h, west, south, &pub->south_deg, &pub->west_deg);
    bb_latlon(h, west + W * cell, south + W * cell, &pub->north_deg, &pub->east_deg);

    /* order: first heard (stable panel/list order) */
    int32_t order[BTRX_BEARING_MAX_SOURCES], n_src = 0;
    for (int32_t i = 0; i < h->cfg.max_sources; i++) if (h->sources[i].used) order[n_src++] = i;
    for (int32_t i = 1; i < n_src; i++)
        for (int32_t j = i; j > 0 && h->sources[order[j]].first_us < h->sources[order[j - 1]].first_us; j--) {
            int32_t t = order[j]; order[j] = order[j - 1]; order[j - 1] = t;
        }
    const float vis = h->cfg.alpha_visible;
    for (int32_t k = 0; k < n_src; k++) {
        bb_source_t *s = &h->sources[order[k]];
        btrx_bearing_source_t *o = &pub->sources[k];
        uint8_t *alpha = sn->alpha + (size_t)k * N;
        float eff = 0, yaw_span = 0, baseline = 0;
        bb_composite_source(h, s, now_us, h->scr_e, h->scr_s, &eff, &yaw_span, &baseline);
        float peak = 0;
        for (size_t c = 0; c < N; c++) {
            float a = h->cfg.alpha_max * fminf(1.0f, h->scr_s[c] / h->cfg.support_sat);
            if (a < vis) { alpha[c] = 0; continue; }
            if (a > peak) peak = a;
            alpha[c] = (uint8_t)lrintf(a * 255.0f);
        }
        /* label point inside the shading: centroid of the cells within 90% of the peak */
        o->label_valid = false;
        if (peak > 0) {
            uint8_t thr = (uint8_t)lrintf(peak * 0.9f * 255.0f);
            double sx = 0, sy = 0, sw = 0;
            for (size_t c = 0; c < N; c++) {
                if (alpha[c] < thr) continue;
                double wgt = alpha[c];
                sx += wgt * ((double)(c % (size_t)W) + 0.5); sy += wgt * ((double)(W - 1 - (int32_t)(c / (size_t)W)) + 0.5);
                sw += wgt;
            }
            if (sw > 0) {
                o->label_valid = true;
                bb_latlon(h, west + sx / sw * cell, south + sy / sw * cell, &o->label_lat_deg, &o->label_lon_deg);
            }
        }
        uint32_t in_window = 0;
        for (int32_t i = 0; i < h->cfg.buckets_per_source; i++)
            if (s->buckets[i].used && bb_fade_weight(h, &s->buckets[i], now_us) > 0) in_window += (uint32_t)s->buckets[i].n;
        o->key = s->key;
        o->provisional = s->provisional;
        o->src_keyed = s->src_keyed;
        o->src_id = s->src_id;
        btrx_bearing_short_id(s->key, o->short_id);
        o->dev_class = (btrx_bearing_class_t)s->cls;
        memcpy(o->rgb, bb_palette(s->colour), 3);
        o->flags = s->flags;
        o->first_heard_us = s->first_us;
        o->last_heard_us = s->last_us;
        o->reports_in = s->reports_in;
        o->reports_used = s->reports_used;
        o->reports_in_window = in_window;
        o->effective_reports = eff;
        o->peak_alpha = peak;
        o->alpha = alpha;
        if (in_window > 0) {
            bb_cover_penalty(h, s, now_us, h->scr_e);
            bb_estimate(h, h->scr_e, eff, yaw_span, baseline, &o->estimate);
        }
        else { memset(&o->estimate, 0, sizeof o->estimate); o->estimate.second_lat_deg = o->estimate.second_lon_deg = NAN; }
        /* suggested own aircraft: no estimate until the user decides (Nov 16) */
        if (s->flags & BTRX_BEARING_SRC_OWN_SUSPECT) o->estimate.valid = false;
        s->est_valid = o->estimate.valid;
        o->company_id = s->company_id;
        o->focus_match = bb_focus_active(h) && bb_focus_match(h, s);
        o->pinned = o->estimate.valid && 2.0f * o->estimate.axis_major_1s_m <= h->cfg.pin_max_axis_2s_m;
        o->latest_ray = s->ray;
        o->last_rssi_dbm = s->last_rssi;
        strongest_here(h, s, now_us, o);
        if (h->focus_key && s->key == h->focus_key) {
            int64_t oldest = now_us - (int64_t)((double)h->fade_total_s * 1e6);
            int32_t nt = 0;
            for (int32_t i = 0; i < s->trail_n; i++) {
                int32_t q = (s->trail_head + i) % BTRX_BEARING_TRAIL_MAX;
                if (s->trail[q].t < oldest) continue;
                btrx_bearing_trail_point_t *tp = &sn->trail[nt++];
                bb_latlon(h, s->trail[q].e, s->trail[q].n, &tp->lat_deg, &tp->lon_deg);
                tp->t_us = s->trail[q].t;
                tp->rssi_dbm = s->trail[q].rssi;
            }
            pub->trail = sn->trail;
            pub->trail_count = nt;
        }
    }
    pub->focus_key = h->focus_key;
    pub->focus_class_mask = h->focus_class_mask;
    pub->focus_company_id = h->focus_company;
    pub->focus_hide_others = h->focus_hide;
    pub->focus_active = bb_focus_active(h);
    pub->launch_valid = h->launch_set;
    if (h->launch_set) bb_latlon(h, h->launch_e, h->launch_n, &pub->launch_lat_deg, &pub->launch_lon_deg);
    else pub->launch_lat_deg = pub->launch_lon_deg = NAN;
    pub->source_count = n_src;

    /* RGBA: unmuted, unignored sources "over" in order of last heard (most recent on top) */
    memset(sn->rgba, 0, N * 4);
    int32_t draw[BTRX_BEARING_MAX_SOURCES], nd = 0;
    for (int32_t k = 0; k < n_src; k++)
        if (!(pub->sources[k].flags & (BTRX_BEARING_SRC_MUTED | BTRX_BEARING_SRC_IGNORED))) draw[nd++] = k;
    for (int32_t i = 1; i < nd; i++)
        for (int32_t j = i; j > 0 && h->sources[order[draw[j]]].heard_order < h->sources[order[draw[j - 1]]].heard_order; j--) {
            int32_t t = draw[j]; draw[j] = draw[j - 1]; draw[j - 1] = t;
        }
    int32_t draw_slot[BTRX_BEARING_MAX_SOURCES];
    for (int32_t d = 0; d < nd; d++) {
        const btrx_bearing_source_t *o = &pub->sources[draw[d]];
        draw_slot[d] = order[draw[d]];
        float dim = 1.0f;
        if (bb_focus_active(h) && !o->focus_match) dim = h->focus_hide ? 0.0f : h->cfg.focus_dim;
        dim *= btrx_bearing_presence(h->cfg.stale_fade_start_s, h->cfg.stale_hide_s, (float)((now_us - o->last_heard_us) / 1e6));
        composite_rgba(sn->rgba, N, o->alpha, o->rgb, dim);
    }
    publish_fine(h, sn, draw_slot, nd, now_us);

    /* stats */
    h->snap_seq = pub->seq;
    bb_lag_published(h, pub->seq, now_us);
    bb_fill_stats(h, now_us);
    pub->stats = h->stats;

    pthread_mutex_lock(&h->snap_mu);
    h->snap_latest = w;
    pthread_mutex_unlock(&h->snap_mu);
    h->last_publish_us = now_us;
    h->dirty = false;
    h->force_publish = false;
}

const btrx_bearing_snapshot_t *btrx_bearing_snapshot_acquire(btrx_bearing_t *h) {
    if (!h) return NULL;
    pthread_mutex_lock(&h->snap_mu);
    const btrx_bearing_snapshot_t *s = NULL;
    if (h->snap_latest >= 0 && h->snap_reading < 0) {
        h->snap_reading = h->snap_latest;
        s = &h->snaps[h->snap_reading].pub;
    }
    pthread_mutex_unlock(&h->snap_mu);
    return s;
}

void btrx_bearing_snapshot_release(btrx_bearing_t *h, const btrx_bearing_snapshot_t *s) {
    if (!h || !s) return;
    pthread_mutex_lock(&h->snap_mu);
    if (h->snap_reading >= 0 && &h->snaps[h->snap_reading].pub == s) h->snap_reading = -1;
    pthread_mutex_unlock(&h->snap_mu);
}

btrx_bearing_status_t btrx_bearing_snapshot_copy(const btrx_bearing_snapshot_t *src,
        btrx_bearing_snapshot_t *dst, uint8_t *rgba, size_t rgba_size,
        uint8_t *alpha, size_t alpha_size, uint8_t *fine_rgba, size_t fine_size,
        btrx_bearing_trail_point_t *trail, int32_t trail_max) {
    if (!src || !dst || !rgba || !alpha) return BTRX_BEARING_E_INVALID;
    size_t N = (size_t)src->width * (size_t)src->height;
    if (rgba_size < N * 4 || alpha_size < N * BTRX_BEARING_MAX_SOURCES) return BTRX_BEARING_E_INVALID;
    *dst = *src;
    size_t FN = (size_t)src->fine_width * (size_t)src->fine_height * 4;
    if (src->fine_valid && fine_rgba && fine_size >= FN) {
        memcpy(fine_rgba, src->fine_rgba, FN);
        dst->fine_rgba = fine_rgba;
    } else {
        dst->fine_valid = false;
        dst->fine_rgba = NULL;
    }
    int32_t nt = src->trail_count;
    if (!trail || nt > trail_max) nt = trail ? trail_max : 0;
    if (nt > 0) memcpy(trail, src->trail + (src->trail_count - nt), sizeof *trail * (size_t)nt);
    dst->trail = trail;
    dst->trail_count = nt;
    memcpy(rgba, src->rgba, N * 4);
    dst->rgba = rgba;
    for (int32_t k = 0; k < src->source_count; k++) {
        memcpy(alpha + (size_t)k * N, src->sources[k].alpha, N);
        dst->sources[k].alpha = alpha + (size_t)k * N;
    }
    return BTRX_BEARING_OK;
}

int32_t btrx_bearing_snapshot_sources_at(const btrx_bearing_snapshot_t *s,
        double lat_deg, double lon_deg, float radius_m,
        btrx_bearing_hit_t *out, int32_t max_out) {
    if (!s || !out || max_out <= 0 || s->width <= 0 || s->north_deg <= s->south_deg) return 0;
    int32_t W = s->width, H = s->height;
    double col = (lon_deg - s->west_deg) / (s->east_deg - s->west_deg) * W;
    double row = (s->north_deg - lat_deg) / (s->north_deg - s->south_deg) * H;
    double cell_m = (s->north_deg - s->south_deg) * BB_DEG * BB_EARTH_R / H;
    double rc = radius_m / cell_m;
    if (rc < 0.5) rc = 0.5;
    int32_t c0 = (int32_t)floor(col - rc), c1 = (int32_t)floor(col + rc);
    int32_t r0 = (int32_t)floor(row - rc), r1 = (int32_t)floor(row + rc);
    if (c0 < 0) c0 = 0;
    if (r0 < 0) r0 = 0;
    if (c1 >= W) c1 = W - 1;
    if (r1 >= H) r1 = H - 1;
    int32_t n = 0;
    for (int32_t k = 0; k < s->source_count; k++) {
        const uint8_t *a = s->sources[k].alpha;
        if (!a) continue;
        float sum = 0, mx = 0;
        for (int32_t r = r0; r <= r1; r++) {
            for (int32_t c = c0; c <= c1; c++) {
                double dx = c + 0.5 - col, dy = r + 0.5 - row;
                bool inside = dx * dx + dy * dy <= rc * rc || (c == (int32_t)floor(col) && r == (int32_t)floor(row));
                if (!inside) continue;
                float v = a[(size_t)r * W + c] / 255.0f;
                sum += v;
                if (v > mx) mx = v;
            }
        }
        if (mx <= 0) continue;
        btrx_bearing_hit_t hit = { k, sum, mx };
        int32_t pos = n < max_out ? n : max_out;
        while (pos > 0 && out[pos - 1].contribution < sum) {
            if (pos < max_out) out[pos] = out[pos - 1];
            pos--;
        }
        if (pos < max_out) { out[pos] = hit; if (n < max_out) n++; }
    }
    return n;
}
