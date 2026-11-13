/* Pod clock offset (section 3.2): min-RTT TIME_PONG, arrival-minimum fallback, sessions. */
#include <string.h>
#include "bb_internal.h"

void bb_clock_reset(bb_clock_t *c) {
    uint32_t session = c->session;
    memset(c, 0, sizeof *c);
    c->session = session;
}

void bb_clock_add_pong(bb_clock_t *c, const bb_sync_in_t *s, int32_t window_ms) {
    (void)window_ms;
    /* times: t1/t4 host us, t2/t3 pod ms. off = host - pod. */
    int64_t t2 = (int64_t)s->t2 * 1000, t3 = (int64_t)s->t3 * 1000;
    int64_t rtt = (s->t4 - s->t1) - (t3 - t2);
    if (rtt < 0) rtt = 0;
    int64_t off = ((s->t1 - t2) + (s->t4 - t3)) / 2;
    int32_t i = c->pong_head;
    c->pong[i].t4 = s->t4; c->pong[i].off = off; c->pong[i].rtt = rtt;
    c->pong_head = (i + 1) % BB_PONG_SAMPLES;
    if (c->pong_n < BB_PONG_SAMPLES) c->pong_n++;
}

static int64_t arrival_min(const bb_clock_t *c, bool *have) {
    int64_t m = 0;
    *have = false;
    for (int32_t k = 0; k < c->arr_n; k++) {
        int64_t d = c->arr[k].delta;
        if (!*have || d < m) { m = d; *have = true; }
    }
    return m;
}

bool bb_clock_observe_frame(bb_clock_t *c, uint32_t we, int64_t arrival_us, bool flagged, bool *backlog_out) {
    bool restart = false;
    int64_t delta = arrival_us - (int64_t)we * 1000;
    if (!flagged && c->have_last_we && (int64_t)we + 2000 < (int64_t)c->last_we) restart = true;
    if (!restart && !flagged) {
        bool have;
        int64_t m = arrival_min(c, &have);
        /* A delay only raises delta; a drop of more than 2 s means a different pod clock. */
        if (have && delta < m - 2000000) restart = true;
    }
    if (restart) {
        uint32_t s = c->session + 1;
        bool hu = c->have_uptime_a; uint32_t lu = c->last_uptime_a;
        memset(c, 0, sizeof *c);
        c->session = s; c->have_uptime_a = hu; c->last_uptime_a = lu;
    }
    /* Backlog (section 3.4): marked by BtrxClient, or > 5 s older than the newest window. */
    bool backlog = flagged || (c->have_newest && (int64_t)we + 5000 < (int64_t)c->newest_we);
    if (!backlog || !c->have_last_we || we > c->last_we) { c->have_last_we = true; c->last_we = we; }
    if (!c->have_newest || we > c->newest_we) { c->newest_we = we; c->have_newest = true; }
    if (!backlog) {
        int32_t i = c->arr_head;
        c->arr[i].arr = arrival_us; c->arr[i].delta = delta;
        c->arr_head = (i + 1) % BB_ARRIVAL_SAMPLES;
        if (c->arr_n < BB_ARRIVAL_SAMPLES) c->arr_n++;
    }
    if (backlog_out) *backlog_out = backlog;
    return restart;
}

bool bb_clock_uptime(bb_clock_t *c, uint32_t uptime_s) {
    bool restart = c->have_uptime_a && uptime_s < c->last_uptime_a;
    if (restart) {
        uint32_t s = c->session + 1;
        memset(c, 0, sizeof *c);
        c->session = s;
    }
    c->have_uptime_a = true;
    c->last_uptime_a = uptime_s;
    return restart;
}

void bb_clock_update(bb_clock_t *c, int64_t now_us, int32_t window_ms) {
    int64_t win = (int64_t)window_ms * 1000;
    /* expire old samples by compaction (bounded: fixed small arrays) */
    int32_t best = -1;
    for (int32_t k = 0; k < c->pong_n; k++) {
        if (now_us - c->pong[k].t4 > win) continue;
        if (best < 0 || c->pong[k].rtt < c->pong[best].rtt) best = k;
    }
    if (best >= 0) {
        c->have = true;
        c->off_us = c->pong[best].off;
        c->err_ms = (int32_t)(c->pong[best].rtt / 2000);
        c->source = 2;
        return;
    }
    bool have = false;
    int64_t m = 0;
    for (int32_t k = 0; k < c->arr_n; k++) {
        if (now_us - c->arr[k].arr > win) continue;
        if (!have || c->arr[k].delta < m) { m = c->arr[k].delta; have = true; }
    }
    if (have) {
        c->have = true;
        c->off_us = m;
        c->err_ms = -1;
        c->source = 1;
    } else if (c->arr_n == 0 && c->pong_n == 0) {
        c->have = false;
        c->source = 0;
    }
    /* otherwise keep the last offset (samples aged out, no new ones) */
}
