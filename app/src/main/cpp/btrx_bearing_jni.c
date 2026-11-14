/*
 * JNI boundary for the BTRX bearing layer (docs/btrx/BEARING-LAYER-spec.md section 7).
 * Flat primitive arguments, jlong handle. The library parses STEER and HEALTH itself.
 *
 * Threads: pushes from any thread; tick/copySnapshot on the BTRX worker (the one
 * snapshot reader); sourcesAt on the UI thread reads this wrapper's own copy of the
 * latest snapshot under `snap_lock`, so it never touches the library's buffers.
 *
 * Meta layout of copySnapshot (mirrored in BtrxBearingNative.kt):
 *   [0] seq [1] t_us [2] width [3] height [4] north [5] south [6] east [7] west [8] source_count
 *   [16..] stats (BBN_STATS_* below), [116 + 40*i ..] source i (BBN_SRC_* below).
 */
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "btrx_bearing.h"

#define BBN_META_STATS 16
#define BBN_META_SOURCES 116
#define BBN_META_PER_SOURCE 40
#define BBN_META_SIZE (BBN_META_SOURCES + BBN_META_PER_SOURCE * BTRX_BEARING_MAX_SOURCES)
#define BBN_LOG_LINES 256
#define BBN_LOG_LEN 320

typedef struct {
    btrx_bearing_t *h;
    int32_t cells;
    pthread_mutex_t snap_lock;
    bool have_snap;
    btrx_bearing_snapshot_t snap;      /* deep copy, pointers into the buffers below */
    uint8_t *rgba;
    uint8_t *alpha;
    pthread_mutex_t log_lock;
    char log_lines[BBN_LOG_LINES][BBN_LOG_LEN];
    int log_head, log_count;
    unsigned log_dropped;
} bbn_t;

static bbn_t *bbn(jlong handle) { return (bbn_t *)(intptr_t)handle; }

static void bbn_log_line(bbn_t *w, const char *fmt, ...) {
    pthread_mutex_lock(&w->log_lock);
    if (w->log_count == BBN_LOG_LINES) {           /* full: drop the oldest */
        w->log_head = (w->log_head + 1) % BBN_LOG_LINES;
        w->log_count--;
        w->log_dropped++;
    }
    int slot = (w->log_head + w->log_count) % BBN_LOG_LINES;
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(w->log_lines[slot], BBN_LOG_LEN, fmt, ap);
    va_end(ap);
    w->log_count++;
    pthread_mutex_unlock(&w->log_lock);
}

static void bbn_lib_log(void *ctx, int level, const char *msg) {
    (void)level;
    bbn_log_line((bbn_t *)ctx, "%s", msg);
}

#define JFN(name) Java_org_ncssar_rid2caltopo_btrx_BtrxBearingNative_##name

JNIEXPORT jint JNICALL JFN(nativeAbiVersion)(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return (jint)btrx_bearing_abi_version();
}

JNIEXPORT jlong JNICALL JFN(nativeCreate)(JNIEnv *env, jobject self, jbyteArray key,
        jfloat fade_total_s, jfloat fade_shape_p, jboolean lag_trace) {
    (void)self;
    if (!key || (*env)->GetArrayLength(env, key) != 16) return 0;
    uint8_t k[16];
    (*env)->GetByteArrayRegion(env, key, 0, 16, (jbyte *)k);
    bbn_t *w = calloc(1, sizeof *w);
    if (!w) return 0;
    btrx_bearing_config_t cfg;
    btrx_bearing_config_default(&cfg);
    cfg.fade_total_s = fade_total_s;
    cfg.fade_shape_p = fade_shape_p;
    cfg.lag_trace = lag_trace == JNI_TRUE;
    cfg.log = bbn_lib_log;
    cfg.callback_ctx = w;
    w->cells = cfg.grid_cells;
    size_t n = (size_t)cfg.grid_cells * (size_t)cfg.grid_cells;
    w->rgba = malloc(n * 4);
    w->alpha = malloc(n * BTRX_BEARING_MAX_SOURCES);
    pthread_mutex_init(&w->snap_lock, NULL);
    pthread_mutex_init(&w->log_lock, NULL);
    if (!w->rgba || !w->alpha || btrx_bearing_create(&cfg, k, &w->h) != BTRX_BEARING_OK) {
        pthread_mutex_destroy(&w->snap_lock);
        pthread_mutex_destroy(&w->log_lock);
        free(w->rgba); free(w->alpha); free(w);
        return 0;
    }
    return (jlong)(intptr_t)w;
}

JNIEXPORT void JNICALL JFN(nativeDestroy)(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    bbn_t *w = bbn(handle);
    if (!w) return;
    btrx_bearing_destroy(w->h);
    pthread_mutex_destroy(&w->snap_lock);
    pthread_mutex_destroy(&w->log_lock);
    free(w->rgba); free(w->alpha); free(w);
}

JNIEXPORT jint JNICALL JFN(nativePushPose)(JNIEnv *env, jobject self, jlong handle, jlong t_us, jlong push_us,
        jint mask, jdouble lat, jdouble lon, jfloat agl, jfloat yaw) {
    (void)env; (void)self;
    bbn_t *w = bbn(handle);
    if (!w) return BTRX_BEARING_E_INVALID;
    btrx_bearing_pose_t p;
    memset(&p, 0, sizeof p);
    p.t_us = t_us; p.push_us = push_us; p.mask = (uint32_t)mask;
    p.lat_deg = lat; p.lon_deg = lon; p.agl_m = agl; p.yaw_true_deg = yaw;
    return btrx_bearing_push_pose(w->h, &p);
}

static void bbn_log_report(bbn_t *w, const btrx_bearing_report_t *r) {
    char line[BBN_LOG_LEN];
    int o = snprintf(line, sizeof line, "BBv1 R %lld %u %u %u %d %d %d %u %d %u",
        (long long)r->arrival_us, (unsigned)r->window_end_ms, (unsigned)r->steer_seq, (unsigned)r->flags,
        (int)r->roll_x2, (int)r->pitch_x2, r->backlog ? 1 : 0, (unsigned)r->ext_format, r->truncated ? 1 : 0,
        (unsigned)r->source_count);
    for (int i = 0; i < r->source_count && o > 0 && o < (int)sizeof line; i++) {
        const btrx_bearing_report_source_t *s = &r->src[i];
        o += snprintf(line + o, sizeof line - (size_t)o, " %u %016llx %d %d %d %u %u %u %d %d %u %u",
            (unsigned)s->src_id, (unsigned long long)s->addr_key, (int)s->rssi_port, (int)s->rssi_stbd,
            (int)s->peak_rssi, (unsigned)s->hits_port, (unsigned)s->hits_stbd, (unsigned)s->age_ms,
            s->is_new ? 1 : 0, s->linked ? 1 : 0, (unsigned)s->pod_dev_class, (unsigned)s->company_id);
    }
    bbn_log_line(w, "%s", line);
}

JNIEXPORT jint JNICALL JFN(nativePushSteer)(JNIEnv *env, jobject self, jlong handle, jbyteArray payload,
        jlong arrival_us, jlong push_us, jboolean backlog, jboolean log_input) {
    (void)self;
    bbn_t *w = bbn(handle);
    if (!w || !payload) return BTRX_BEARING_E_INVALID;
    jsize len = (*env)->GetArrayLength(env, payload);
    if (len <= 0 || len > 255) return BTRX_BEARING_E_INVALID;
    uint8_t buf[256];
    (*env)->GetByteArrayRegion(env, payload, 0, len, (jbyte *)buf);
    btrx_bearing_report_t r;
    btrx_bearing_status_t st = btrx_bearing_parse_steer(w->h, buf, (size_t)len, arrival_us, backlog == JNI_TRUE, &r);
    if (st != BTRX_BEARING_OK) return st;
    r.push_us = push_us;
    if (log_input == JNI_TRUE) bbn_log_report(w, &r);
    return btrx_bearing_push_report(w->h, &r);
}

JNIEXPORT jint JNICALL JFN(nativePushTimeSync)(JNIEnv *env, jobject self, jlong handle, jlong t1, jlong t2,
        jlong t3, jlong t4) {
    (void)env; (void)self;
    bbn_t *w = bbn(handle);
    if (!w) return BTRX_BEARING_E_INVALID;
    return btrx_bearing_push_time_sync(w->h, t1, (uint32_t)t2, (uint32_t)t3, t4);
}

/* Returns the status; out[0] role, out[1] uptime_s, out[2] lost_known, out[3] phone_lost. */
JNIEXPORT jint JNICALL JFN(nativePushHealth)(JNIEnv *env, jobject self, jlong handle, jlong host_us,
        jbyteArray payload, jlongArray out) {
    (void)self;
    bbn_t *w = bbn(handle);
    if (!w || !payload) return BTRX_BEARING_E_INVALID;
    jsize len = (*env)->GetArrayLength(env, payload);
    if (len <= 0 || len > 255) return BTRX_BEARING_E_INVALID;
    uint8_t buf[256];
    (*env)->GetByteArrayRegion(env, payload, 0, len, (jbyte *)buf);
    btrx_bearing_health_t hl;
    btrx_bearing_status_t st = btrx_bearing_parse_health(buf, (size_t)len, &hl);
    if (st != BTRX_BEARING_OK) return st;
    if (out && (*env)->GetArrayLength(env, out) >= 4) {
        jlong v[4] = { hl.role, hl.uptime_s, hl.lost_known ? 1 : 0, hl.phone_lost };
        (*env)->SetLongArrayRegion(env, out, 0, 4, v);
    }
    return btrx_bearing_push_health(w->h, host_us, hl.role, hl.uptime_s, hl.lost_known, hl.phone_lost);
}

JNIEXPORT jint JNICALL JFN(nativeNoteConnected)(JNIEnv *env, jobject self, jlong handle, jlong host_us) {
    (void)env; (void)self;
    bbn_t *w = bbn(handle);
    return w ? btrx_bearing_note_connected(w->h, host_us) : BTRX_BEARING_E_INVALID;
}

JNIEXPORT jint JNICALL JFN(nativeNoteRendered)(JNIEnv *env, jobject self, jlong handle, jlong seq, jlong host_us) {
    (void)env; (void)self;
    bbn_t *w = bbn(handle);
    return w ? btrx_bearing_note_rendered(w->h, (uint64_t)seq, host_us) : BTRX_BEARING_E_INVALID;
}

JNIEXPORT jint JNICALL JFN(nativeSetSourceFlags)(JNIEnv *env, jobject self, jlong handle, jlongArray keys,
        jint mask, jint value) {
    (void)self;
    bbn_t *w = bbn(handle);
    if (!w || !keys) return BTRX_BEARING_E_INVALID;
    jsize n = (*env)->GetArrayLength(env, keys);
    if (n <= 0) return BTRX_BEARING_OK;
    if (n > 64) n = 64;
    uint64_t k[64];
    jlong tmp[64];
    (*env)->GetLongArrayRegion(env, keys, 0, n, tmp);
    for (jsize i = 0; i < n; i++) k[i] = (uint64_t)tmp[i];
    return btrx_bearing_set_source_flags(w->h, k, (size_t)n, (uint32_t)mask, (uint32_t)value);
}

JNIEXPORT jint JNICALL JFN(nativeSetFade)(JNIEnv *env, jobject self, jlong handle, jfloat total_s, jfloat shape_p) {
    (void)env; (void)self;
    bbn_t *w = bbn(handle);
    return w ? btrx_bearing_set_fade(w->h, total_s, shape_p) : BTRX_BEARING_E_INVALID;
}

JNIEXPORT jint JNICALL JFN(nativeReset)(JNIEnv *env, jobject self, jlong handle, jint what, jlong key) {
    (void)env; (void)self;
    bbn_t *w = bbn(handle);
    return w ? btrx_bearing_reset(w->h, what, (uint64_t)key) : BTRX_BEARING_E_INVALID;
}

/* Returns 1 when a new snapshot was published, 0 when not, a negative status on error. */
JNIEXPORT jint JNICALL JFN(nativeTick)(JNIEnv *env, jobject self, jlong handle, jlong now_us) {
    (void)env; (void)self;
    bbn_t *w = bbn(handle);
    if (!w) return BTRX_BEARING_E_INVALID;
    bool published = false;
    btrx_bearing_status_t st = btrx_bearing_tick(w->h, now_us, &published);
    if (st < 0) return st;
    return published ? 1 : 0;
}

/* Stats as doubles (BBN_STATS_COUNT); the same layout at meta[BBN_META_STATS] and in copyStats. */
#define BBN_STATS_COUNT (BBN_META_SOURCES - BBN_META_STATS)
static void bbn_stats(const btrx_bearing_stats_t *t, double *q) {
    int i = 0;
    q[i++] = t->reports_in; q[i++] = t->entries_in; q[i++] = t->used; q[i++] = t->gated;
    q[i++] = t->no_heading; q[i++] = t->no_position; q[i++] = t->late_dropped; q[i++] = t->ring_dropped;
    q[i++] = t->buckets_expired_early; q[i++] = t->ignored; q[i++] = t->backlog_history;
    q[i++] = t->steer_truncated; q[i++] = t->extras_unresolved; q[i++] = t->provisional_merges;
    q[i++] = t->desk_queue_lost_known ? 1 : 0; q[i++] = t->desk_queue_lost; q[i++] = t->desk_queue_lost_since_connect;
    q[i++] = t->pod_a_queue_lost; q[i++] = t->tiles_in_use; q[i++] = t->tiles_total;
    q[i++] = t->fade_total_s; q[i++] = t->fade_shape_p; q[i++] = t->pod_offset_ms; q[i++] = t->pod_offset_err_ms;
    q[i++] = t->offset_source; q[i++] = t->pod_session; q[i++] = t->display_lag_ms;
    for (int k = 0; k < BTRX_BEARING_STAGE_COUNT; k++) {        /* 27 + 6 * 10 = 87 < 100 */
        q[i++] = t->lag[k].count; q[i++] = t->lag[k].p50_ms; q[i++] = t->lag[k].p90_ms;
        q[i++] = t->lag[k].p99_ms; q[i++] = t->lag[k].max_ms; q[i++] = t->lag[k].late_drops;
    }
}

static void bbn_meta(const btrx_bearing_snapshot_t *s, double *m) {
    memset(m, 0, sizeof(double) * BBN_META_SIZE);
    m[0] = (double)s->seq; m[1] = (double)s->t_us; m[2] = s->width; m[3] = s->height;
    m[4] = s->north_deg; m[5] = s->south_deg; m[6] = s->east_deg; m[7] = s->west_deg;
    m[8] = s->source_count;
    bbn_stats(&s->stats, m + BBN_META_STATS);
    for (int k = 0; k < s->source_count && k < BTRX_BEARING_MAX_SOURCES; k++) {
        const btrx_bearing_source_t *src = &s->sources[k];
        double *o = m + BBN_META_SOURCES + BBN_META_PER_SOURCE * k;
        int j = 0;
        o[j++] = src->provisional ? 1 : 0; o[j++] = src->dev_class;
        o[j++] = src->rgb[0]; o[j++] = src->rgb[1]; o[j++] = src->rgb[2]; o[j++] = src->flags;
        o[j++] = (double)src->first_heard_us; o[j++] = (double)src->last_heard_us;
        o[j++] = src->reports_in; o[j++] = src->reports_used; o[j++] = src->reports_in_window;
        o[j++] = src->effective_reports; o[j++] = src->peak_alpha;
        const btrx_bearing_estimate_t *e = &src->estimate;
        o[j++] = e->valid ? 1 : 0; o[j++] = e->ambiguous ? 1 : 0; o[j++] = e->lat_deg; o[j++] = e->lon_deg;
        o[j++] = e->axis_major_1s_m; o[j++] = e->axis_minor_1s_m; o[j++] = e->axis_major_deg;
        o[j++] = e->second_lat_deg; o[j++] = e->second_lon_deg; o[j++] = e->second_mass;
        const btrx_bearing_ray_t *r = &src->latest_ray;
        o[j++] = r->valid ? 1 : 0; o[j++] = r->apex_lat_deg; o[j++] = r->apex_lon_deg;
        o[j++] = r->centre_true_deg; o[j++] = r->half_width_deg; o[j++] = r->length_m;
        o[j++] = r->mirror_true_deg; o[j++] = (double)r->t_us;
    }
}

/*
 * Worker thread. Copies the current snapshot into this wrapper (for sourcesAt) and into
 * the caller's direct RGBA buffer, keys (LongArray 8), short IDs (ByteArray 64, 8 per
 * source) and meta (DoubleArray BBN_META_SIZE). Returns source_count, or -6 before the
 * first publish.
 */
JNIEXPORT jint JNICALL JFN(nativeCopySnapshot)(JNIEnv *env, jobject self, jlong handle, jobject rgba_buffer,
        jlongArray keys, jbyteArray short_ids, jdoubleArray meta) {
    (void)self;
    bbn_t *w = bbn(handle);
    if (!w || !keys || !short_ids || !meta) return BTRX_BEARING_E_INVALID;
    if ((*env)->GetArrayLength(env, meta) < BBN_META_SIZE || (*env)->GetArrayLength(env, keys) < BTRX_BEARING_MAX_SOURCES
            || (*env)->GetArrayLength(env, short_ids) < 8 * BTRX_BEARING_MAX_SOURCES) return BTRX_BEARING_E_INVALID;
    const btrx_bearing_snapshot_t *s = btrx_bearing_snapshot_acquire(w->h);
    if (!s) return BTRX_BEARING_E_NO_SNAPSHOT;
    size_t n = (size_t)w->cells * (size_t)w->cells;
    pthread_mutex_lock(&w->snap_lock);
    btrx_bearing_status_t st = btrx_bearing_snapshot_copy(s, &w->snap, w->rgba, n * 4, w->alpha, n * BTRX_BEARING_MAX_SOURCES);
    w->have_snap = st == BTRX_BEARING_OK;
    pthread_mutex_unlock(&w->snap_lock);
    btrx_bearing_snapshot_release(w->h, s);
    if (st != BTRX_BEARING_OK) return st;
    /* w->snap is written only on this (worker) thread, so reading it here unlocked is safe. */
    const btrx_bearing_snapshot_t *c = &w->snap;
    if (rgba_buffer) {
        uint8_t *dst = (*env)->GetDirectBufferAddress(env, rgba_buffer);
        jlong cap = (*env)->GetDirectBufferCapacity(env, rgba_buffer);
        size_t need = (size_t)c->width * (size_t)c->height * 4;
        if (dst && cap >= (jlong)need) memcpy(dst, c->rgba, need);
    }
    jlong k[BTRX_BEARING_MAX_SOURCES] = {0};
    jbyte ids[8 * BTRX_BEARING_MAX_SOURCES];
    memset(ids, 0, sizeof ids);
    for (int i = 0; i < c->source_count; i++) {
        k[i] = (jlong)c->sources[i].key;
        memcpy(ids + 8 * i, c->sources[i].short_id, 8);
    }
    (*env)->SetLongArrayRegion(env, keys, 0, BTRX_BEARING_MAX_SOURCES, k);
    (*env)->SetByteArrayRegion(env, short_ids, 0, 8 * BTRX_BEARING_MAX_SOURCES, ids);
    double m[BBN_META_SIZE];
    bbn_meta(c, m);
    (*env)->SetDoubleArrayRegion(env, meta, 0, BBN_META_SIZE, m);
    return c->source_count;
}

/*
 * Any thread: the latest stats (refreshed by tick about once a second, also before the
 * first snapshot) into out (DoubleArray >= BBN_STATS_COUNT, same layout as meta[16..]).
 * 0, or -6 before the first tick.
 */
JNIEXPORT jint JNICALL JFN(nativeCopyStats)(JNIEnv *env, jobject self, jlong handle, jdoubleArray out) {
    (void)self;
    bbn_t *w = bbn(handle);
    if (!w || !out || (*env)->GetArrayLength(env, out) < BBN_STATS_COUNT) return BTRX_BEARING_E_INVALID;
    btrx_bearing_stats_t t;
    btrx_bearing_status_t st = btrx_bearing_stats_copy(w->h, &t);
    if (st != BTRX_BEARING_OK) return st;
    double q[BBN_STATS_COUNT];
    memset(q, 0, sizeof q);
    bbn_stats(&t, q);
    (*env)->SetDoubleArrayRegion(env, out, 0, BBN_STATS_COUNT, q);
    return BTRX_BEARING_OK;
}

/* UI thread: source indexes (into the last copied snapshot) by contribution; returns the count. */
JNIEXPORT jint JNICALL JFN(nativeSourcesAt)(JNIEnv *env, jobject self, jlong handle, jdouble lat, jdouble lon,
        jfloat radius_m, jintArray out_index) {
    (void)self;
    bbn_t *w = bbn(handle);
    if (!w || !out_index) return 0;
    jsize max = (*env)->GetArrayLength(env, out_index);
    if (max > BTRX_BEARING_MAX_SOURCES) max = BTRX_BEARING_MAX_SOURCES;
    btrx_bearing_hit_t hits[BTRX_BEARING_MAX_SOURCES];
    int32_t n = 0;
    pthread_mutex_lock(&w->snap_lock);
    if (w->have_snap) n = btrx_bearing_snapshot_sources_at(&w->snap, lat, lon, radius_m, hits, max);
    pthread_mutex_unlock(&w->snap_lock);
    jint idx[BTRX_BEARING_MAX_SOURCES];
    for (int32_t i = 0; i < n; i++) idx[i] = hits[i].source_index;
    if (n > 0) (*env)->SetIntArrayRegion(env, out_index, 0, n, idx);
    return n;
}

JNIEXPORT jfloat JNICALL JFN(nativeHalfStrengthS)(JNIEnv *env, jobject self, jfloat total_s, jfloat shape_p) {
    (void)env; (void)self;
    return btrx_bearing_half_strength_s(total_s, shape_p);
}

JNIEXPORT jstring JNICALL JFN(nativeShortId)(JNIEnv *env, jobject self, jlong key) {
    (void)self;
    char out[8];
    btrx_bearing_short_id((uint64_t)key, out);
    return (*env)->NewStringUTF(env, out);
}

/* Log lines since the last drain, joined with '\n', or null. Lines are ASCII. */
JNIEXPORT jstring JNICALL JFN(nativeDrainLog)(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    bbn_t *w = bbn(handle);
    if (!w) return NULL;
    static const size_t cap = (size_t)BBN_LOG_LINES * BBN_LOG_LEN + 64;
    char *out = malloc(cap);
    if (!out) return NULL;
    size_t o = 0;
    pthread_mutex_lock(&w->log_lock);
    if (w->log_dropped) o += (size_t)snprintf(out, cap, "BBLOG dropped=%u\n", w->log_dropped);
    for (int i = 0; i < w->log_count; i++) {
        const char *line = w->log_lines[(w->log_head + i) % BBN_LOG_LINES];
        size_t l = strnlen(line, BBN_LOG_LEN);
        memcpy(out + o, line, l);
        o += l;
        out[o++] = '\n';
    }
    w->log_head = 0; w->log_count = 0; w->log_dropped = 0;
    pthread_mutex_unlock(&w->log_lock);
    jstring s = NULL;
    if (o > 0) {
        out[o - 1] = '\0';
        s = (*env)->NewStringUTF(env, out);
    }
    free(out);
    return s;
}

/* BBv1 input lines for the boundary's own inputs (P, S, H, M, F) go through the same ring. */
JNIEXPORT void JNICALL JFN(nativeLogInput)(JNIEnv *env, jobject self, jlong handle, jstring line) {
    (void)self;
    bbn_t *w = bbn(handle);
    if (!w || !line) return;
    const char *c = (*env)->GetStringUTFChars(env, line, NULL);
    if (!c) return;
    bbn_log_line(w, "%s", c);
    (*env)->ReleaseStringUTFChars(env, line, c);
}
