/* Best estimate and confidence ellipse (section 2.7). */
#include <math.h>
#include <string.h>
#include "bb_internal.h"

/* Flood fill of cells with p > thr connected to `seed`, not already in `mask`.
 * Marks them with `label`; returns mass, and sums for the mean and covariance. */
static double flood(btrx_bearing_t *h, const float *e, float emax, int32_t seed, uint8_t label,
        double *sx, double *sy, double *sxx, double *syy, double *sxy) {
    int32_t W = h->cfg.grid_cells;
    const float thr = logf(0.01f);
    int32_t *st = h->scr_stack;
    uint8_t *mask = h->scr_mask;
    int32_t top = 0;
    st[top++] = seed;
    mask[seed] = label;
    double mass = 0;
    *sx = *sy = *sxx = *syy = *sxy = 0;
    while (top > 0) {
        int32_t c = st[--top];
        int32_t row = c / W, col = c % W;
        double p = exp((double)(e[c] - emax));
        double x = col + 0.5, y = (W - 1 - row) + 0.5;   /* cells, east and north */
        mass += p; *sx += p * x; *sy += p * y; *sxx += p * x * x; *syy += p * y * y; *sxy += p * x * y;
        const int32_t nb[4] = { c - 1, c + 1, c - W, c + W };
        const bool ok[4] = { col > 0, col < W - 1, row > 0, row < W - 1 };
        for (int k = 0; k < 4; k++) {
            if (!ok[k]) continue;
            int32_t q = nb[k];
            if (mask[q] || e[q] - emax <= thr) continue;
            mask[q] = label;
            st[top++] = q;
        }
    }
    return mass;
}

void bb_estimate(btrx_bearing_t *h, const float *e, float eff, float yaw_span, float baseline,
        btrx_bearing_estimate_t *out) {
    int32_t W = h->cfg.grid_cells, N = W * W;
    float cell = h->cfg.cell_m;
    memset(out, 0, sizeof *out);
    out->second_lat_deg = out->second_lon_deg = NAN;
    int32_t arg = 0;
    float emax = e[0];
    for (int32_t c = 1; c < N; c++) if (e[c] > emax) { emax = e[c]; arg = c; }
    if (!(emax > 0)) return;
    memset(h->scr_mask, 0, (size_t)N);
    double total = 0;
    for (int32_t c = 0; c < N; c++) total += exp((double)(e[c] - emax));
    double sx, sy, sxx, syy, sxy;
    double m1 = flood(h, e, emax, arg, 1, &sx, &sy, &sxx, &syy, &sxy);
    double mx = sx / m1, my = sy / m1;
    double cxx = sxx / m1 - mx * mx, cyy = syy / m1 - my * my, cxy = sxy / m1 - mx * my;
    /* cells -> metres, plus a cell's own variance */
    cxx = (cxx + 1.0 / 12.0) * cell * cell;
    cyy = (cyy + 1.0 / 12.0) * cell * cell;
    cxy = cxy * cell * cell;
    double tr = cxx + cyy, det = cxx * cyy - cxy * cxy;
    double disc = sqrt(fmax(0.0, tr * tr / 4 - det));
    double l1 = tr / 2 + disc, l2 = tr / 2 - disc;
    if (l2 < 0) l2 = 0;
    double ang = 0.5 * atan2(2 * cxy, cxx - cyy);      /* from east, counter-clockwise */
    double e_m = (h->vtx0 * BB_TILE) * (double)cell + mx * cell;
    double n_m = (h->vty0 * BB_TILE) * (double)cell + my * cell;
    bb_latlon(h, e_m, n_m, &out->lat_deg, &out->lon_deg);
    out->axis_major_1s_m = (float)sqrt(l1);
    out->axis_minor_1s_m = (float)sqrt(l2);
    out->axis_major_deg = bb_wrap360((float)(90.0 - ang / BB_DEG));
    if (out->axis_major_deg >= 180.0f) out->axis_major_deg -= 180.0f;
    /* second peak: the strongest cell outside the main region */
    int32_t arg2 = -1;
    const float thr = logf(0.01f);
    for (int32_t c = 0; c < N; c++)
        if (!h->scr_mask[c] && e[c] - emax > thr && (arg2 < 0 || e[c] > e[arg2])) arg2 = c;
    if (arg2 >= 0) {
        double m2 = flood(h, e, emax, arg2, 2, &sx, &sy, &sxx, &syy, &sxy);
        out->second_mass = (float)(m2 / total);
        double e2 = (h->vtx0 * BB_TILE) * (double)cell + sx / m2 * cell;
        double n2 = (h->vty0 * BB_TILE) * (double)cell + sy / m2 * cell;
        bb_latlon(h, e2, n2, &out->second_lat_deg, &out->second_lon_deg);
        out->ambiguous = out->second_mass >= h->cfg.second_peak_mass;
    }
    out->valid = eff >= (float)h->cfg.est_min_reports
        && (yaw_span >= h->cfg.est_min_yaw_span_deg || baseline >= h->cfg.est_min_baseline_m)
        && 2.0f * out->axis_major_1s_m <= h->cfg.est_max_axis_2s_m;
}
