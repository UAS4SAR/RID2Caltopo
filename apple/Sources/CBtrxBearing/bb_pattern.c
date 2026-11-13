/* v1 antenna pattern table (section 1/2.1): patches at +-45 deg azimuth, 45 deg down.
 * Body frame: x forward, y starboard, z down. Azimuth phi from the nose, + starboard. */
#include <math.h>
#include "bb_internal.h"

static float gain_db(float theta_deg, float hpbw) {
    float g = -12.0f * (theta_deg / hpbw) * (theta_deg / hpbw);
    return g < -20.0f ? -20.0f : g;
}

void bb_pattern_build_v1(float *delta, float *sum, float hpbw_deg) {
    const double bp[3] = { 0.5, -0.5, 0.70710678118654752 };
    const double bs[3] = { 0.5, 0.5, 0.70710678118654752 };
    for (int d = 0; d < BTRX_BEARING_PATTERN_DEP; d++) {
        double eps = (double)(2 * d) * BB_DEG;
        for (int a = 0; a < BTRX_BEARING_PATTERN_AZ; a++) {
            double phi = (double)a * BB_DEG;
            double u[3] = { cos(eps) * cos(phi), cos(eps) * sin(phi), sin(eps) };
            double cp = u[0] * bp[0] + u[1] * bp[1] + u[2] * bp[2];
            double cs = u[0] * bs[0] + u[1] * bs[1] + u[2] * bs[2];
            if (cp > 1) cp = 1;
            if (cp < -1) cp = -1;
            if (cs > 1) cs = 1;
            if (cs < -1) cs = -1;
            float gp = gain_db((float)(acos(cp) / BB_DEG), hpbw_deg);
            float gs = gain_db((float)(acos(cs) / BB_DEG), hpbw_deg);
            delta[d * BTRX_BEARING_PATTERN_AZ + a] = gp - gs;
            sum[d * BTRX_BEARING_PATTERN_AZ + a] = gp + gs;
        }
    }
}

float bb_pattern_lookup(const float *table, float az_deg, float dep_deg) {
    int a = (int)floorf(bb_wrap360(az_deg) + 0.5f);
    if (a >= BTRX_BEARING_PATTERN_AZ) a -= BTRX_BEARING_PATTERN_AZ;
    int d = (int)floorf(dep_deg * 0.5f + 0.5f);
    if (d < 0) d = 0;
    if (d >= BTRX_BEARING_PATTERN_DEP) d = BTRX_BEARING_PATTERN_DEP - 1;
    return table[d * BTRX_BEARING_PATTERN_AZ + a];
}

float btrx_bearing_pattern_delta_db(const btrx_bearing_t *h, float az_deg, float dep_deg) {
    if (!h) return NAN;
    return bb_pattern_lookup(h->pattern_delta, az_deg, dep_deg);
}
