/* Per-report likelihood (section 2.1) and footprint accumulation (2.2), latest ray (5.1). */
#include <math.h>
#include "bb_internal.h"

/* Roll bias of port - stbd per degree of roll (+ starboard down), by depression of the
 * source: 0.10 at 30 deg down or shallower, 0.13 at 45, 0.22 straight below
 * (PROTOCOL.md "Attitude and the gate", geometric estimates). */
float bb_roll_k(float dep_deg) {
    if (dep_deg <= 30.0f) return 0.10f;
    if (dep_deg <= 45.0f) return 0.10f + 0.03f * (dep_deg - 30.0f) / 15.0f;
    if (dep_deg >= 90.0f) return 0.22f;
    return 0.13f + 0.09f * (dep_deg - 45.0f) / 45.0f;
}

float bb_loglik(const btrx_bearing_t *h, const bb_geom_t *g, float phi_deg, float dep_deg) {
    float dpred = bb_pattern_lookup(h->pattern_delta, phi_deg, dep_deg);
    if (!g->censored) {
        float obs = g->obs;
        if (g->roll_deg != 0.0f) obs += bb_roll_k(dep_deg) * g->roll_deg;
        float r = obs - dpred - h->cfg.c0_db;
        float nu = h->cfg.student_nu;
        return -0.5f * (nu + 1.0f) * log1pf(r * r / (nu * g->sigma2));
    }
    float silent_pred = g->heard_port ? g->obs - dpred : g->obs + dpred;
    float z = (h->cfg.scan_floor_dbm - silent_pred) / sqrtf(g->sigma2);
    double phi = 0.5 * erfc(-(double)z * 0.70710678118654752);
    if (phi < 1e-30) return -69.0f;
    return (float)log(phi);
}

/* Slant range from the strongest patch mean (log-distance model); NaN when not heard. */
float bb_range_m(const btrx_bearing_t *h, float rssi_dbm) {
    if (!(rssi_dbm > BTRX_BEARING_RSSI_NOT_HEARD)) return NAN;
    float d = powf(10.0f, (h->cfg.range_ref_dbm - rssi_dbm) / (10.0f * h->cfg.range_exponent));
    return d < 1.0f ? 1.0f : d;
}

/* How far out a report can say anything: range * margin, clamped to [range_min_m, ceiling]. */
float bb_reach_m(const btrx_bearing_t *h, float rssi_dbm, float ceiling_m) {
    float d = bb_range_m(h, rssi_dbm);
    if (!(d > 0) || !(h->cfg.range_margin > 0)) return ceiling_m;
    float r = d * h->cfg.range_margin;
    if (r < h->cfg.range_min_m) r = h->cfg.range_min_m;
    if (r > ceiling_m) r = ceiling_m;
    return r;
}

void bb_grid_add(btrx_bearing_t *h, bb_source_t *src, bb_bucket_t *b, const bb_geom_t *g) {
    const float cell = h->cfg.cell_m;
    const float fp = g->foot_m > 0 && g->foot_m < h->cfg.footprint_m ? g->foot_m : h->cfg.footprint_m, fp2 = fp * fp;
    const float lnpf = logf(h->cfg.p_far);
    int32_t R = (int32_t)ceilf(fp / cell);
    int32_t cx = (int32_t)floor(g->de / cell), cy = (int32_t)floor(g->dn / cell);
    int32_t tx0 = bb_floordiv(cx - R, BB_TILE), tx1 = bb_floordiv(cx + R, BB_TILE);
    int32_t ty0 = bb_floordiv(cy - R, BB_TILE), ty1 = bb_floordiv(cy + R, BB_TILE);
    const float tile_m = cell * BB_TILE;
    for (int32_t ty = ty0; ty <= ty1; ty++) {
        for (int32_t tx = tx0; tx <= tx1; tx++) {
            /* closest point of the tile to the drone */
            double x0 = tx * (double)tile_m, y0 = ty * (double)tile_m;
            double qx = g->de < x0 ? x0 : (g->de > x0 + tile_m ? x0 + tile_m : g->de);
            double qy = g->dn < y0 ? y0 : (g->dn > y0 + tile_m ? y0 + tile_m : g->dn);
            if ((qx - g->de) * (qx - g->de) + (qy - g->dn) * (qy - g->dn) > fp2) continue;
            int32_t t = bb_tile_get(h, src, b, tx, ty);
            if (t == BB_NONE) continue;
            bb_tile_t *tile = &h->tiles[t];
            for (int32_t j = 0; j < BB_TILE; j++) {
                float dy = (float)((y0 + (j + 0.5) * cell) - g->dn);
                for (int32_t i = 0; i < BB_TILE; i++) {
                    float dx = (float)((x0 + (i + 0.5) * cell) - g->de);
                    float r2 = dx * dx + dy * dy;
                    if (r2 > fp2) continue;
                    float r = sqrtf(r2);
                    float beta = atan2f(dx, dy) * (float)(180.0 / BB_PI);
                    float rr = r < 0.5f * cell ? 0.5f * cell : r;
                    float dep = atan2f(g->agl, rr) * (float)(180.0 / BB_PI);
                    float l = bb_loglik(h, g, beta - g->yaw, dep);
                    float lc = l > lnpf ? l : lnpf;
                    int32_t qe = (int32_t)lrintf(g->kappa * (lc - lnpf) * 256.0f);
                    int32_t qs = (int32_t)lrintf(g->q * expf(l) * 1024.0f);
                    int c = j * BB_TILE + i;
                    tile->e[c] = bb_sat_add16(tile->e[c], qe);
                    tile->s[c] = bb_sat_add16(tile->s[c], qs);
                }
            }
        }
    }
}

void bb_latest_ray(btrx_bearing_t *h, bb_source_t *src, const bb_geom_t *g, int64_t t_us) {
    float ll[360];
    const float reach = g->reach_m > 0 && g->reach_m < h->cfg.footprint_m ? g->reach_m : h->cfg.footprint_m;
    /* depression as before (half the footprint): the RSSI range is too uncertain to steer it */
    float r0 = h->cfg.footprint_m * 0.5f;
    float dep = atan2f(g->agl, r0) * (float)(180.0 / BB_PI);
    int kmax = 0;
    for (int k = 0; k < 360; k++) {
        ll[k] = bb_loglik(h, g, (float)k - g->yaw, dep);
        if (ll[k] > ll[kmax]) kmax = k;
    }
    float thr = h->cfg.ray_halfwidth_ll;
    btrx_bearing_ray_t *ray = &src->ray;
    ray->valid = true;
    ray->ambiguous = false;
    ray->t_us = t_us;
    ray->length_m = reach;
    ray->range_m = g->range_m > 0 ? g->range_m : NAN;
    bb_latlon(h, g->de, g->dn, &ray->apex_lat_deg, &ray->apex_lon_deg);
    uint8_t in_main[360] = { 0 };
    int left = 0, right = 0;
    while (left < 359 && ll[(kmax - left - 1 + 360) % 360] > thr) left++;
    while (right < 359 - left && ll[(kmax + right + 1) % 360] > thr) right++;
    for (int k = -left; k <= right; k++) in_main[(kmax + k + 360) % 360] = 1;
    int len = left + right + 1;
    if (len >= 359) {
        ray->centre_true_deg = (float)kmax;
        ray->half_width_deg = 180.0f;
        ray->mirror_true_deg = NAN;
        return;
    }
    ray->centre_true_deg = bb_wrap360((float)kmax + 0.5f * (float)(right - left));
    ray->half_width_deg = 0.5f * (float)len;
    int m = -1;
    for (int k = 0; k < 360; k++) if (!in_main[k] && (m < 0 || ll[k] > ll[m])) m = k;
    if (m < 0 || ll[m] <= thr) { ray->mirror_true_deg = NAN; return; }
    int ml = 0, mr = 0;
    while (ml < 359 && !in_main[(m - ml - 1 + 360) % 360] && ll[(m - ml - 1 + 360) % 360] > thr) ml++;
    while (mr < 359 && !in_main[(m + mr + 1) % 360] && ll[(m + mr + 1) % 360] > thr) mr++;
    ray->mirror_true_deg = bb_wrap360((float)m + 0.5f * (float)(mr - ml));
    /* Lobes about equally likely: ambiguous, drawn alike. The patches look forward, so the
     * in-front lobe is the main one then (Nov 16: rays picked the rear lobe). */
    if (ll[kmax] - ll[m] < h->cfg.ray_ambiguous_ll) {
        ray->ambiguous = true;
        bool main_front = fabsf(bb_wrap180(ray->centre_true_deg - g->yaw)) < 90.0f;
        bool mirror_front = fabsf(bb_wrap180(ray->mirror_true_deg - g->yaw)) < 90.0f;
        if (!main_front && mirror_front) {
            float c = ray->centre_true_deg;
            ray->centre_true_deg = ray->mirror_true_deg;
            ray->mirror_true_deg = c;
            float hw = 0.5f * (float)(ml + mr + 1);
            if (hw > ray->half_width_deg) ray->half_width_deg = hw;
        }
    }
}
