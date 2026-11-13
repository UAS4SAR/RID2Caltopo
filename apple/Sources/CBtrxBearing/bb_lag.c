/* Lag instrumentation (section 3.5): stage stamps, percentiles, BBLAG/BBRND lines. */
#include <stdarg.h>
#include <stdio.h>     /* vsnprintf only: formatting, no I/O */
#include <stdlib.h>
#include <string.h>
#include "bb_internal.h"

void bb_log(btrx_bearing_t *h, int level, const char *fmt, ...) {
    if (!h->cfg.log) return;
    char line[BB_LOG_LINE];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    h->cfg.log(h->cfg.callback_ctx, level, line);
}

bb_lagrec_t *bb_lag_new(btrx_bearing_t *h, const bb_pending_t *p) {
    bb_lag_t *l = &h->lag;
    bb_lagrec_t *r = &l->rec[l->head];
    l->head = (l->head + 1) % l->cap;
    if (l->n < l->cap) l->n++;
    memset(r, 0, sizeof *r);
    r->steer_seq = p->r.steer_seq;
    r->wait = (uint8_t)p->wait;
    r->t0 = p->t0; r->t2 = p->t2; r->t3 = p->t3;
    r->t4 = r->t5 = r->t6 = r->t7 = BB_T_NONE;
    r->fate = BB_FATE_PENDING;
    return r;
}

static const char *fate_name(int f) {
    switch (f) {
    case BB_FATE_USED: return "used";
    case BB_FATE_GATED: return "gated";
    case BB_FATE_LATE: return "late";
    case BB_FATE_HISTORY: return "history";
    case BB_FATE_IGNORED: return "ignored";
    case BB_FATE_NO_HEADING: return "no_heading";
    case BB_FATE_NO_POSITION: return "no_position";
    case BB_FATE_EMPTY: return "empty";
    default: return "pending";
    }
}

static const char *wait_name(int w) { return w == BB_WAIT_YAW ? "yaw" : (w == BB_WAIT_POS ? "pos" : "none"); }

static void fmt_t(char *buf, size_t n, int64_t t) {
    if (t == BB_T_NONE) snprintf(buf, n, "-");
    else snprintf(buf, n, "%lld", (long long)t);
}

static void log_record(btrx_bearing_t *h, bb_lagrec_t *r) {
    if (!h->cfg.lag_trace || !h->cfg.log || r->logged) return;
    r->logged = true;
    char t0[24], t2[24], t3[24], t4[24], t5[24], t6[24];
    fmt_t(t0, sizeof t0, r->t0); fmt_t(t2, sizeof t2, r->t2); fmt_t(t3, sizeof t3, r->t3);
    fmt_t(t4, sizeof t4, r->t4); fmt_t(t5, sizeof t5, r->t5); fmt_t(t6, sizeof t6, r->t6);
    if (r->n == 0) {
        bb_log(h, 1, "BBLAG seq=%u src=- rank=0 fate=%s wait=%s t0=%s t2=%s t3=%s t4=%s t5=%s t6=%s",
            r->steer_seq, fate_name(r->fate), wait_name(r->wait), t0, t2, t3, t4, t5, t6);
        return;
    }
    for (int k = 0; k < r->n; k++) {
        int f = r->efate[k] ? r->efate[k] : r->fate;
        bb_log(h, 1, "BBLAG seq=%u src=%s rank=%u fate=%s wait=%s t0=%s t2=%s t3=%s t4=%s t5=%s t6=%s",
            r->steer_seq, r->sid[k][0] ? r->sid[k] : "-", r->rank[k], fate_name(f), wait_name(r->wait),
            t0, t2, t3, t4, t5, t6);
    }
}

void bb_lag_final(btrx_bearing_t *h, bb_lagrec_t *r) {
    if (r->fate != BB_FATE_USED) log_record(h, r);
}

void bb_lag_input(btrx_bearing_t *h, int which, int64_t lag_us) {
    bb_lag_t *l = &h->lag;
    int32_t v = (int32_t)(lag_us / 1000);
    if (which == 0) { l->yaw_in[l->yaw_head] = v; l->yaw_head = (l->yaw_head + 1) % l->in_cap; if (l->yaw_n < l->in_cap) l->yaw_n++; }
    else { l->pos_in[l->pos_head] = v; l->pos_head = (l->pos_head + 1) % l->in_cap; if (l->pos_n < l->in_cap) l->pos_n++; }
}

void bb_lag_late(btrx_bearing_t *h, int stage) {
    if (stage >= 0 && stage < BTRX_BEARING_STAGE_COUNT) h->lag.late_by_stage[stage]++;
}

void bb_lag_published(btrx_bearing_t *h, uint64_t seq, int64_t now_us) {
    bb_lag_t *l = &h->lag;
    for (int32_t i = 0; i < l->n; i++) {
        bb_lagrec_t *r = &l->rec[i];
        if (r->fate == BB_FATE_USED && r->t5 != BB_T_NONE && r->t6 == BB_T_NONE) {
            r->t6 = now_us;
            r->snap_seq = seq;
            log_record(h, r);
        }
    }
}

void bb_lag_rendered(btrx_bearing_t *h, uint64_t seq, int64_t us) {
    bb_lag_t *l = &h->lag;
    bool any = false;
    for (int32_t i = 0; i < l->n; i++) {
        bb_lagrec_t *r = &l->rec[i];
        if (r->snap_seq == seq && r->t6 != BB_T_NONE && r->t7 == BB_T_NONE) { r->t7 = us; any = true; }
    }
    if (h->cfg.lag_trace && (any || seq)) bb_log(h, 1, "BBRND snap=%llu t7=%lld", (unsigned long long)seq, (long long)us);
}

static int cmp_i32(const void *a, const void *b) {
    int32_t x = *(const int32_t *)a, y = *(const int32_t *)b;
    return (x > y) - (x < y);
}

static void pct(int32_t *v, int32_t n, btrx_bearing_lag_t *out) {
    out->count = (uint32_t)n;
    if (n == 0) { out->p50_ms = out->p90_ms = out->p99_ms = out->max_ms = 0; return; }
    qsort(v, (size_t)n, sizeof *v, cmp_i32);
    out->p50_ms = v[(n - 1) * 50 / 100];
    out->p90_ms = v[(n - 1) * 90 / 100];
    out->p99_ms = v[(n - 1) * 99 / 100];
    out->max_ms = v[n - 1];
}

static bool has(int64_t t) { return t != BB_T_NONE; }

void bb_lag_stats(btrx_bearing_t *h, btrx_bearing_stats_t *st) {
    bb_lag_t *l = &h->lag;
    int32_t *v = l->scratch;
    for (int s = 0; s < BTRX_BEARING_STAGE_COUNT; s++) {
        int32_t n = 0;
        if (s == BTRX_BEARING_STAGE_YAW_INPUT) { memcpy(v, l->yaw_in, sizeof(int32_t) * (size_t)l->yaw_n); n = l->yaw_n; }
        else if (s == BTRX_BEARING_STAGE_POS_INPUT) { memcpy(v, l->pos_in, sizeof(int32_t) * (size_t)l->pos_n); n = l->pos_n; }
        else for (int32_t i = 0; i < l->n; i++) {
            const bb_lagrec_t *r = &l->rec[i];
            if (r->fate == BB_FATE_HISTORY || r->fate == BB_FATE_PENDING) continue;
            int64_t a = BB_T_NONE, b = BB_T_NONE;
            switch (s) {
            case BTRX_BEARING_STAGE_POD_TO_ARRIVAL: a = r->t0; b = r->t2; break;
            case BTRX_BEARING_STAGE_ARRIVAL_TO_PUSH: a = r->t2; b = r->t3; break;
            case BTRX_BEARING_STAGE_POSE_WAIT_YAW: if (r->wait == BB_WAIT_YAW) { a = r->t3; b = r->t4; } break;
            case BTRX_BEARING_STAGE_POSE_WAIT_POS: if (r->wait == BB_WAIT_POS) { a = r->t3; b = r->t4; } break;
            case BTRX_BEARING_STAGE_TICK_WAIT: a = r->t4; b = r->t5; break;
            case BTRX_BEARING_STAGE_PUBLISH_WAIT: a = r->t5; b = r->t6; break;
            case BTRX_BEARING_STAGE_RENDER: a = r->t6; b = r->t7; break;
            case BTRX_BEARING_STAGE_TOTAL: a = r->t0; b = r->t7; break;
            default: break;
            }
            if (has(a) && has(b)) v[n++] = (int32_t)((b - a) / 1000);
        }
        pct(v, n, &st->lag[s]);
        st->lag[s].late_drops = l->late_by_stage[s];
    }
}
