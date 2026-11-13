/* Diversity discount (section 2.2): repeated geometry within 30 s counts once more slowly. */
#include <math.h>
#include "bb_internal.h"

int32_t bb_diversity_count(btrx_bearing_t *h, int32_t slot, int64_t t, float e, float n, float yaw) {
    bb_diversity_t *d = &h->div;
    int64_t win = (int64_t)h->cfg.diversity_window_ms * 1000;
    float dm2 = h->cfg.diversity_m * h->cfg.diversity_m;
    int32_t k = 0;
    for (int32_t i = 0; i < d->n; i++) {
        const bb_div_t *x = &d->item[i];
        if (x->slot != slot) continue;
        int64_t dt = t - x->t;
        if (dt < 0) dt = -dt;
        if (dt > win) continue;
        float de = e - x->e, dn = n - x->n;
        if (de * de + dn * dn > dm2) continue;
        if (fabsf(bb_wrap180(yaw - x->yaw)) > h->cfg.diversity_deg) continue;
        k++;
    }
    if (k > h->cfg.diversity_cap) k = h->cfg.diversity_cap;
    bb_div_t *w = &d->item[d->head];
    w->slot = slot; w->t = t; w->e = e; w->n = n; w->yaw = yaw;
    d->head = (d->head + 1) % BB_DIVERSITY_RING;
    if (d->n < BB_DIVERSITY_RING) d->n++;
    return k;
}

void bb_diversity_drop_slot(btrx_bearing_t *h, int32_t slot) {
    for (int32_t i = 0; i < h->div.n; i++) if (h->div.item[i].slot == slot) h->div.item[i].slot = -1;
}
