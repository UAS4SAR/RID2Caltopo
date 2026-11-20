/* Pose rings, interpolation, gates (sections 2.8, 3.3). */
#include <math.h>
#include "bb_internal.h"

float bb_wrap180(float d) {
    float r = fmodf(d + 180.0f, 360.0f);
    if (r < 0) r += 360.0f;
    return r - 180.0f;
}

float bb_wrap360(float d) {
    float r = fmodf(d, 360.0f);
    if (r < 0) r += 360.0f;
    if (r >= 360.0f) r -= 360.0f;
    return r;
}

void bb_pose_clear(bb_pose_t *p) {
    p->yaw_head = p->yaw_n = 0;
    p->pos_head = p->pos_n = 0;
    p->last_yaw_push_us = p->last_pos_push_us = INT64_MIN / 2;
}

static const bb_yaw_t *yaw_at(const bb_pose_t *p, int32_t k) {
    return &p->yaw[(p->yaw_head - p->yaw_n + k + 2 * p->yaw_cap) % p->yaw_cap];
}

static const bb_pos_t *pos_at(const bb_pose_t *p, int32_t k) {
    return &p->pos[(p->pos_head - p->pos_n + k + 2 * p->pos_cap) % p->pos_cap];
}

void bb_local(const btrx_bearing_t *h, double lat, double lon, double *e, double *n) {
    *n = (lat - h->lat0) * BB_DEG * BB_EARTH_R;
    *e = (lon - h->lon0) * BB_DEG * BB_EARTH_R * h->coslat0;
}

void bb_latlon(const btrx_bearing_t *h, double e, double n, double *lat, double *lon) {
    *lat = h->lat0 + n / (BB_DEG * BB_EARTH_R);
    *lon = h->lon0 + e / (BB_DEG * BB_EARTH_R * h->coslat0);
}

void bb_pose_push(btrx_bearing_t *h, const btrx_bearing_pose_t *in) {
    bb_pose_t *p = &h->pose;
    if ((in->mask & BTRX_BEARING_POSE_YAW) && isfinite(in->yaw_true_deg)) {
        int64_t t = in->t_us - (int64_t)h->cfg.yaw_latency_ms * 1000;
        if (p->yaw_n > 0 && t <= yaw_at(p, p->yaw_n - 1)->t) {
            p->out_of_order++;
        } else {
            bb_yaw_t *y = &p->yaw[p->yaw_head];
            y->t = t; y->yaw = bb_wrap360(in->yaw_true_deg);
            p->yaw_head = (p->yaw_head + 1) % p->yaw_cap;
            if (p->yaw_n < p->yaw_cap) p->yaw_n++;
        }
        p->last_yaw_push_us = in->push_us ? in->push_us : in->t_us;
        if (in->push_us) bb_lag_input(h, 0, in->push_us - in->t_us);
    }
    if ((in->mask & BTRX_BEARING_POSE_POSITION) && isfinite(in->lat_deg) && isfinite(in->lon_deg)) {
        if (!h->have_anchor) {
            h->have_anchor = true;
            h->lat0 = in->lat_deg; h->lon0 = in->lon_deg;
            h->coslat0 = cos(in->lat_deg * BB_DEG);
            if (h->coslat0 < 0.01) h->coslat0 = 0.01;
        }
        if (p->pos_n > 0 && in->t_us <= pos_at(p, p->pos_n - 1)->t) {
            p->out_of_order++;
        } else {
            bb_pos_t *q = &p->pos[p->pos_head];
            q->t = in->t_us; q->lat = in->lat_deg; q->lon = in->lon_deg;
            q->agl = (in->mask & BTRX_BEARING_POSE_AGL) ? in->agl_m : NAN;
            p->pos_head = (p->pos_head + 1) % p->pos_cap;
            if (p->pos_n < p->pos_cap) p->pos_n++;
        }
        if (!h->is_fine) {
            double le, ln;
            bb_local(h, in->lat_deg, in->lon_deg, &le, &ln);
            bb_cover_add(h, (float)le, (float)ln);
            /* launch point: the first position with a known AGL, if the drone is on the ground */
            if (!h->launch_done && (in->mask & BTRX_BEARING_POSE_AGL) && isfinite(in->agl_m)) {
                h->launch_done = true;
                if (in->agl_m <= h->cfg.launch_max_agl_m) {
                    h->launch_set = true;
                    h->launch_e = (float)le; h->launch_n = (float)ln;
                    h->launch_t = in->t_us;
                }
            }
        }
        p->last_pos_push_us = in->push_us ? in->push_us : in->t_us;
        if (in->push_us) bb_lag_input(h, 1, in->push_us - in->t_us);
    }
}

/* 1 = ready to evaluate, 0 = keep waiting (wait says which ring). */
int bb_pose_ready(const btrx_bearing_t *h, int64_t t_end, int64_t now, int32_t *wait) {
    const bb_pose_t *p = &h->pose;
    int64_t yaw_stale = 2000000 + (int64_t)h->cfg.max_yaw_gap_ms * 1000;
    if (p->yaw_n > 0 && now - p->last_yaw_push_us <= yaw_stale) {
        if (yaw_at(p, p->yaw_n - 1)->t < t_end) { *wait = BB_WAIT_YAW; return 0; }
    }
    int64_t t_rep = t_end - 500000;
    if (p->pos_n > 0 && now - p->last_pos_push_us <= (int64_t)h->cfg.max_pos_extrapolate_ms * 1000) {
        if (pos_at(p, p->pos_n - 1)->t < t_rep) { *wait = BB_WAIT_POS; return 0; }
    }
    return 1;
}

static int pose_yaw(const btrx_bearing_t *h, int64_t t_end, bb_pose_at_t *out) {
    const bb_pose_t *p = &h->pose;
    int64_t gap = (int64_t)h->cfg.max_yaw_gap_ms * 1000;
    int64_t t0 = t_end - 1000000, t_rep = t_end - 500000;
    if (p->yaw_n == 0) return BB_FATE_NO_HEADING;
    /* first sample with t >= t0 - gap (binary search) */
    int32_t lo = 0, hi = p->yaw_n;
    while (lo < hi) { int32_t m = (lo + hi) / 2; if (yaw_at(p, m)->t < t0 - gap) lo = m + 1; else hi = m; }
    int32_t first = lo;
    if (first >= p->yaw_n) return BB_FATE_NO_HEADING;
    const bb_yaw_t *f = yaw_at(p, first);
    if (f->t > t0 + gap) return BB_FATE_NO_HEADING;
    double u = f->yaw, umin = u, umax = u;
    double prev_y = f->yaw; int64_t prev_t = f->t;
    double u_start = NAN, u_end = NAN, u_rep = NAN;
    double prev_u = u;
    if (f->t >= t0) u_start = u;
    if (f->t >= t_rep) u_rep = u;
    if (f->t >= t_end) u_end = u;
    int32_t k = first + 1;
    for (; k < p->yaw_n; k++) {
        const bb_yaw_t *s = yaw_at(p, k);
        if (s->t > t_end + gap) break;
        if (s->t - prev_t > gap) return BB_FATE_NO_HEADING;
        double nu = prev_u + bb_wrap180((float)(s->yaw - prev_y));
        double span = (double)(s->t - prev_t);
        if (isnan(u_start) && s->t >= t0) u_start = prev_u + (nu - prev_u) * (double)(t0 - prev_t) / span;
        if (isnan(u_rep) && s->t >= t_rep) u_rep = prev_u + (nu - prev_u) * (double)(t_rep - prev_t) / span;
        if (isnan(u_end) && s->t >= t_end) u_end = prev_u + (nu - prev_u) * (double)(t_end - prev_t) / span;
        if (s->t >= t0 && s->t <= t_end) { if (nu < umin) umin = nu; if (nu > umax) umax = nu; }
        prev_u = nu; prev_y = s->yaw; prev_t = s->t;
    }
    if (prev_t < t_end - gap) return BB_FATE_NO_HEADING;
    if (isnan(u_start)) u_start = prev_u;
    if (isnan(u_rep)) u_rep = prev_u;
    if (isnan(u_end)) u_end = prev_u;
    if (u_start < umin) umin = u_start;
    if (u_start > umax) umax = u_start;
    if (u_end < umin) umin = u_end;
    if (u_end > umax) umax = u_end;
    out->yaw = bb_wrap360((float)u_rep);
    out->yaw_change = (float)(umax - umin);
    out->yaw_rate = (float)fabs(u_end - u_start);
    return 0;
}

int bb_pose_pos(btrx_bearing_t *h, int64_t t_rep, bb_pose_at_t *out) {
    const bb_pose_t *p = &h->pose;
    if (p->pos_n == 0 || !h->have_anchor) return BB_FATE_NO_POSITION;
    int64_t gap = (int64_t)h->cfg.max_pos_gap_ms * 1000;
    int64_t ext = (int64_t)h->cfg.max_pos_extrapolate_ms * 1000;
    int32_t lo = 0, hi = p->pos_n;
    while (lo < hi) { int32_t m = (lo + hi) / 2; if (pos_at(p, m)->t < t_rep) lo = m + 1; else hi = m; }
    const bb_pos_t *a = lo > 0 ? pos_at(p, lo - 1) : NULL;
    const bb_pos_t *b = lo < p->pos_n ? pos_at(p, lo) : NULL;
    double ea, na, eb, nb;
    float agl = NAN;
    if (a && b && b->t - a->t <= gap) {
        bb_local(h, a->lat, a->lon, &ea, &na);
        bb_local(h, b->lat, b->lon, &eb, &nb);
        double f = b->t == a->t ? 0.0 : (double)(t_rep - a->t) / (double)(b->t - a->t);
        out->e = ea + (eb - ea) * f; out->n = na + (nb - na) * f;
        if (isfinite(a->agl) && isfinite(b->agl)) agl = (float)(a->agl + (b->agl - a->agl) * f);
        else agl = isfinite(a->agl) ? a->agl : b->agl;
    } else if (b && b->t == t_rep) {
        bb_local(h, b->lat, b->lon, &out->e, &out->n);
        agl = b->agl;
    } else if (a && t_rep - a->t <= ext) {
        bb_local(h, a->lat, a->lon, &ea, &na);
        out->e = ea; out->n = na;
        if (lo >= 2) {
            const bb_pos_t *z = pos_at(p, lo - 2);
            if (a->t - z->t > 0 && a->t - z->t <= gap) {
                double ez, nz;
                bb_local(h, z->lat, z->lon, &ez, &nz);
                double f = (double)(t_rep - a->t) / (double)(a->t - z->t);
                out->e = ea + (ea - ez) * f; out->n = na + (na - nz) * f;
            }
        }
        agl = a->agl;
    } else if (b && b->t - t_rep <= ext) {
        bb_local(h, b->lat, b->lon, &out->e, &out->n);
        agl = b->agl;
    } else {
        return BB_FATE_NO_POSITION;
    }
    out->agl_default = false;
    if (!isfinite(agl)) {
        if (!isfinite(h->cfg.default_agl_m)) return BB_FATE_NO_POSITION;
        agl = h->cfg.default_agl_m;
        out->agl_default = true;
    }
    if (agl < 1.0f) agl = 1.0f;
    out->agl = agl;
    return 0;
}

/* 0 = ok, else BB_FATE_NO_HEADING / BB_FATE_NO_POSITION. */
int bb_pose_at(btrx_bearing_t *h, int64_t t_end, bb_pose_at_t *out) {
    int f = pose_yaw(h, t_end, out);
    if (f) return f;
    return bb_pose_pos(h, t_end - 500000, out);
}

/* Horizontal speed from the two position fixes around t_rep (false when unknown). */
bool bb_drone_speed(btrx_bearing_t *h, int64_t t_rep, float *mps) {
    const bb_pose_t *p = &h->pose;
    if (p->pos_n < 2 || !h->have_anchor) return false;
    int32_t lo = 0, hi = p->pos_n;
    while (lo < hi) { int32_t m = (lo + hi) / 2; if (pos_at(p, m)->t < t_rep) lo = m + 1; else hi = m; }
    if (lo < 1) lo = 1;
    if (lo >= p->pos_n) lo = p->pos_n - 1;
    const bb_pos_t *a = pos_at(p, lo - 1), *b = pos_at(p, lo);
    if (b->t <= a->t || b->t - a->t > (int64_t)h->cfg.max_pos_gap_ms * 1000) return false;
    double ea, na, eb, nb;
    bb_local(h, a->lat, a->lon, &ea, &na);
    bb_local(h, b->lat, b->lon, &eb, &nb);
    *mps = (float)(hypot(eb - ea, nb - na) / ((double)(b->t - a->t) / 1e6));
    return true;
}

/* ---------- coverage (Nov 20) ---------- */
void bb_cover_add(btrx_bearing_t *h, float e, float n) {
    if (!h->cover_e) return;
    if (h->cover_n_pts > 0) {
        int32_t k = h->cover_n_pts - 1;
        if (hypotf(e - h->cover_e[k], n - h->cover_n[k]) < BB_COVER_STEP_M) return;
    }
    if (h->cover_n_pts == h->cover_cap) {          /* full: keep every other point */
        int32_t j = 0;
        for (int32_t i = 0; i < h->cover_n_pts; i += 2, j++) { h->cover_e[j] = h->cover_e[i]; h->cover_n[j] = h->cover_n[i]; }
        h->cover_n_pts = j;
    }
    h->cover_e[h->cover_n_pts] = e;
    h->cover_n[h->cover_n_pts] = n;
    h->cover_n_pts++;
    h->cover_dist_pts = -1;
}

/* Distance (m) from every coarse view cell to the nearest track point: two-pass chamfer
 * (3-4) over the view, seeded with the track points inside it. False: no point inside. */
static bool cover_build(btrx_bearing_t *h) {
    int32_t W = h->cfg.grid_cells, N = W * W;
    if (h->cover_dist_pts == h->cover_n_pts && h->cover_vtx0 == h->vtx0 && h->cover_vty0 == h->vty0)
        return h->cover_dist_pts > 0;
    const float big = 1e9f, cell = h->cfg.cell_m;
    float *d = h->cover_dist;
    for (int32_t c = 0; c < N; c++) d[c] = big;
    double west = h->vtx0 * BB_TILE * (double)cell, south = h->vty0 * BB_TILE * (double)cell;
    int32_t seeds = 0;
    for (int32_t i = 0; i < h->cover_n_pts; i++) {
        int32_t col = (int32_t)floor((h->cover_e[i] - west) / cell);
        int32_t rowS = (int32_t)floor((h->cover_n[i] - south) / cell);
        if (col < 0 || rowS < 0 || col >= W || rowS >= W) continue;
        d[(size_t)(W - 1 - rowS) * W + col] = 0;   /* row 0 = north */
        seeds++;
    }
    h->cover_dist_pts = seeds > 0 ? h->cover_n_pts : 0;
    h->cover_vtx0 = h->vtx0; h->cover_vty0 = h->vty0;
    if (!seeds) return false;
    const float a = 1.0f, b = 1.41421356f;          /* in cells */
    for (int32_t r = 0; r < W; r++)
        for (int32_t c = 0; c < W; c++) {
            float v = d[r * W + c];
            if (c > 0 && d[r * W + c - 1] + a < v) v = d[r * W + c - 1] + a;
            if (r > 0) {
                if (d[(r - 1) * W + c] + a < v) v = d[(r - 1) * W + c] + a;
                if (c > 0 && d[(r - 1) * W + c - 1] + b < v) v = d[(r - 1) * W + c - 1] + b;
                if (c < W - 1 && d[(r - 1) * W + c + 1] + b < v) v = d[(r - 1) * W + c + 1] + b;
            }
            d[r * W + c] = v;
        }
    for (int32_t r = W - 1; r >= 0; r--)
        for (int32_t c = W - 1; c >= 0; c--) {
            float v = d[r * W + c];
            if (c < W - 1 && d[r * W + c + 1] + a < v) v = d[r * W + c + 1] + a;
            if (r < W - 1) {
                if (d[(r + 1) * W + c] + a < v) v = d[(r + 1) * W + c] + a;
                if (c < W - 1 && d[(r + 1) * W + c + 1] + b < v) v = d[(r + 1) * W + c + 1] + b;
                if (c > 0 && d[(r + 1) * W + c - 1] + b < v) v = d[(r + 1) * W + c - 1] + b;
            }
            d[r * W + c] = v;
        }
    for (int32_t c = 0; c < N; c++) d[c] *= cell;
    return true;
}

/* Lower the evidence in cells far from the track: cover_nats_per_100m per 100 m beyond
 * cover_free_m. Used for the estimate only (the shading and the latest ray are unchanged). */
void bb_cover_penalty(btrx_bearing_t *h, const bb_source_t *s, int64_t now_us, float *e) {
    int32_t W = h->cfg.grid_cells, N = W * W;
    if (h->cfg.cover_nats_per_100m > 0 && h->cover_dist && cover_build(h)) {
        const float k = h->cfg.cover_nats_per_100m / 100.0f, free_m = h->cfg.cover_free_m;
        for (int32_t c = 0; c < N; c++) {
            float over = h->cover_dist[c] - free_m;
            if (over > 0) e[c] -= k * over;
        }
    }
    /* range cap: heard strong while low bounds the range from that point */
    if (s && s->near_valid && h->cfg.range_cap_nats_per_100m > 0
            && now_us - s->near_t <= (int64_t)((double)h->fade_total_s * 1e6)) {
        float r = bb_range_m(h, (float)s->near_rssi) * h->cfg.range_cap_margin;
        if (r < h->cfg.range_cap_min_m) r = h->cfg.range_cap_min_m;
        if (r > h->cfg.footprint_m) r = h->cfg.footprint_m;
        const float k = h->cfg.range_cap_nats_per_100m / 100.0f, cell = h->cfg.cell_m;
        double west = h->vtx0 * BB_TILE * (double)cell, south = h->vty0 * BB_TILE * (double)cell;
        for (int32_t row = 0; row < W; row++) {
            float y = (float)(south + ((double)(W - 1 - row) + 0.5) * cell) - s->near_n;
            for (int32_t col = 0; col < W; col++) {
                float x = (float)(west + ((double)col + 0.5) * cell) - s->near_e;
                float over = sqrtf(x * x + y * y) - r;
                if (over > 0) e[(size_t)row * W + col] -= k * over;
            }
        }
    }
}
