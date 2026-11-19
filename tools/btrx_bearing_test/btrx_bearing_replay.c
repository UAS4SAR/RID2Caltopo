/* btrx_bearing_replay: feeds a BBv1 input log (spec section 9.2) through the
 * library, ticking every 200 ms of log time, and writes a CSV of snapshots and
 * optionally the last snapshot as a PNG.
 *
 *   btrx_bearing_replay input.bbv1 [--csv out.csv] [--png out.png] [--lag-trace]
 */
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <inttypes.h>
#include "btrx_bearing.h"

static FILE *g_csv;
static bool g_trace;

static void log_cb(void *ctx, int level, const char *msg) { (void)ctx; (void)level; if (g_trace) printf("%s\n", msg); }

static void csv_snapshot(btrx_bearing_t *h) {
    const btrx_bearing_snapshot_t *s = btrx_bearing_snapshot_acquire(h);
    if (!s) return;
    if (g_csv) {
        const btrx_bearing_stats_t *st = &s->stats;
        fprintf(g_csv, "%" PRIu64 ",%" PRId64 ",%d,%u,%u,%u,%u,%u,%u,%u,%u,%d,%u,%u",
            s->seq, s->t_us, s->source_count, st->reports_in, st->used, st->gated, st->no_heading, st->no_position,
            st->late_dropped, st->backlog_history, st->ring_dropped, st->pod_offset_ms, st->offset_source, st->pod_session);
        for (int k = 0; k < 2; k++) {
            if (k < s->source_count) {
                const btrx_bearing_source_t *src = &s->sources[k];
                fprintf(g_csv, ",%s,%u,%d,%.7f,%.7f,%.1f,%.2f,%.1f", src->short_id, src->reports_used, src->estimate.valid,
                    src->estimate.lat_deg, src->estimate.lon_deg, 2 * src->estimate.axis_major_1s_m, src->estimate.second_mass,
                    src->latest_ray.centre_true_deg);
            } else fprintf(g_csv, ",,,,,,,,");
        }
        fprintf(g_csv, "\n");
    }
    btrx_bearing_snapshot_release(h, s);
}

/* ---- minimal PNG (stored deflate blocks) ---- */
static uint32_t crc_table[256];
static void crc_init(void) {
    for (uint32_t n = 0; n < 256; n++) { uint32_t c = n; for (int k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320u ^ (c >> 1) : c >> 1; crc_table[n] = c; }
}
static uint32_t crc(uint32_t c, const uint8_t *b, size_t n) { c ^= 0xFFFFFFFFu; for (size_t i = 0; i < n; i++) c = crc_table[(c ^ b[i]) & 0xFF] ^ (c >> 8); return c ^ 0xFFFFFFFFu; }
static void be32(uint8_t *p, uint32_t v) { p[0] = (uint8_t)(v >> 24); p[1] = (uint8_t)(v >> 16); p[2] = (uint8_t)(v >> 8); p[3] = (uint8_t)v; }
static void chunk(FILE *f, const char *type, const uint8_t *data, uint32_t len) {
    uint8_t h[8]; be32(h, len); memcpy(h + 4, type, 4);
    fwrite(h, 1, 8, f); if (len) fwrite(data, 1, len, f);
    uint8_t *tmp = malloc(len + 4); memcpy(tmp, type, 4); if (len) memcpy(tmp + 4, data, len);
    uint8_t cb[4]; be32(cb, crc(0, tmp, len + 4)); fwrite(cb, 1, 4, f); free(tmp);
}
static int write_png(const char *path, const uint8_t *rgba, int w, int h) {
    FILE *f = fopen(path, "wb");
    if (!f) return -1;
    static const uint8_t sig[8] = { 137, 80, 78, 71, 13, 10, 26, 10 };
    fwrite(sig, 1, 8, f);
    uint8_t ihdr[13]; be32(ihdr, (uint32_t)w); be32(ihdr + 4, (uint32_t)h);
    ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
    chunk(f, "IHDR", ihdr, 13);
    size_t raw_n = (size_t)h * (1 + (size_t)w * 4);
    uint8_t *raw = malloc(raw_n);
    for (int y = 0; y < h; y++) {
        raw[(size_t)y * (1 + w * 4)] = 0;
        for (int x = 0; x < w * 4; x++) {
            /* un-premultiply for PNG */
            const uint8_t *px = rgba + ((size_t)y * w + x / 4) * 4;
            uint8_t a = px[3], v = px[x % 4];
            raw[(size_t)y * (1 + w * 4) + 1 + x] = (x % 4 == 3 || a == 0) ? v : (uint8_t)((v * 255 + a / 2) / a);
        }
    }
    size_t nblocks = (raw_n + 65534) / 65535;
    size_t z_n = 2 + raw_n + nblocks * 5 + 4;
    uint8_t *z = malloc(z_n), *q = z;
    *q++ = 0x78; *q++ = 0x01;
    uint32_t s1 = 1, s2 = 0;
    for (size_t i = 0; i < raw_n; i++) { s1 = (s1 + raw[i]) % 65521; s2 = (s2 + s1) % 65521; }
    for (size_t off = 0; off < raw_n; off += 65535) {
        size_t len = raw_n - off < 65535 ? raw_n - off : 65535;
        *q++ = off + len >= raw_n ? 1 : 0;
        *q++ = (uint8_t)len; *q++ = (uint8_t)(len >> 8); *q++ = (uint8_t)~len; *q++ = (uint8_t)(~len >> 8);
        memcpy(q, raw + off, len); q += len;
    }
    be32(q, (s2 << 16) | s1); q += 4;
    chunk(f, "IDAT", z, (uint32_t)(q - z));
    chunk(f, "IEND", NULL, 0);
    fclose(f);
    free(raw); free(z);
    return 0;
}

static uint64_t hex64(const char *s) { return strcmp(s, "0") == 0 ? 0 : strtoull(s, NULL, 16); }

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s input.bbv1 [--csv out.csv] [--png out.png] [--lag-trace]\n", argv[0]); return 2; }
    const char *png = NULL;
    for (int i = 2; i < argc; i++) {
        if (!strcmp(argv[i], "--csv") && i + 1 < argc) g_csv = fopen(argv[++i], "w");
        else if (!strcmp(argv[i], "--png") && i + 1 < argc) png = argv[++i];
        else if (!strcmp(argv[i], "--lag-trace")) g_trace = true;
    }
    FILE *in = fopen(argv[1], "r");
    if (!in) { perror(argv[1]); return 2; }
    if (g_csv) fprintf(g_csv, "seq,t_us,sources,reports_in,used,gated,no_heading,no_position,late,history,ring_dropped,pod_offset_ms,offset_source,pod_session,"
        "s0_id,s0_used,s0_valid,s0_lat,s0_lon,s0_2sigma_m,s0_second,s0_ray_deg,s1_id,s1_used,s1_valid,s1_lat,s1_lon,s1_2sigma_m,s1_second,s1_ray_deg\n");
    btrx_bearing_config_t cfg;
    btrx_bearing_config_default(&cfg);
    cfg.lag_trace = g_trace;
    cfg.log = log_cb;
    uint8_t key[16] = { 'r', 'e', 'p', 'l', 'a', 'y' };
    btrx_bearing_t *h = NULL;
    if (btrx_bearing_create(&cfg, key, &h) != BTRX_BEARING_OK) { fprintf(stderr, "create failed\n"); return 1; }
    char line[2048];
    int64_t next_tick = INT64_MIN;
    long lines = 0, bad = 0;
    while (fgets(line, sizeof line, in)) {
        if (strncmp(line, "BBv1 ", 5) != 0) continue;
        char kind = line[5];
        char *p = line + 7;
        int64_t t = strtoll(p, NULL, 10);
        if (next_tick == INT64_MIN) next_tick = t;
        while (next_tick <= t) {
            bool pub = false;
            btrx_bearing_tick(h, next_tick, &pub);
            if (pub) csv_snapshot(h);
            next_tick += 200000;
        }
        lines++;
        if (kind == 'P') {
            btrx_bearing_pose_t ps; memset(&ps, 0, sizeof ps);
            unsigned mask;
            if (sscanf(p, "%" SCNd64 " %u %lf %lf %f %f %f %f", &ps.t_us, &mask, &ps.lat_deg, &ps.lon_deg, &ps.agl_m,
                    &ps.yaw_true_deg, &ps.pitch_deg, &ps.roll_deg) != 8) { bad++; continue; }
            ps.mask = mask; ps.push_us = ps.t_us;
            btrx_bearing_push_pose(h, &ps);
        } else if (kind == 'R') {
            /* ext_format (fmt) is stored as logged; format 2 (gyro yaw) is allowed.
             * Yaw itself is analysed from the session log, not from BBv1. */
            btrx_bearing_report_t r; memset(&r, 0, sizeof r);
            int seq, flags, roll, pitch, backlog, fmt, trunc, n, used = 0;
            if (sscanf(p, "%" SCNd64 " %u %d %d %d %d %d %d %d %d%n", &r.arrival_us, &r.window_end_ms, &seq, &flags, &roll, &pitch,
                    &backlog, &fmt, &trunc, &n, &used) != 10 || n < 0 || n > 3) { bad++; continue; }
            r.steer_seq = (uint8_t)seq; r.flags = (uint8_t)flags; r.roll_x2 = (int8_t)roll; r.pitch_x2 = (int8_t)pitch;
            r.backlog = backlog; r.ext_format = (uint8_t)fmt; r.truncated = trunc; r.source_count = (uint8_t)n;
            r.push_us = r.arrival_us;
            char *q = p + used;
            for (int k = 0; k < n; k++) {
                btrx_bearing_report_source_t *e = &r.src[k];
                char keyhex[32];
                int id, port, stbd, peak, hp, hs, age, nw, lk, cls, comp, u2 = 0;
                if (sscanf(q, "%d %31s %d %d %d %d %d %d %d %d %d %d%n", &id, keyhex, &port, &stbd, &peak, &hp, &hs, &age,
                        &nw, &lk, &cls, &comp, &u2) != 12) { bad++; n = k; break; }
                q += u2;
                e->src_id = (uint16_t)id; e->addr_key = hex64(keyhex); e->rssi_port = (int8_t)port; e->rssi_stbd = (int8_t)stbd;
                e->peak_rssi = (int8_t)peak; e->hits_port = (uint8_t)hp; e->hits_stbd = (uint8_t)hs; e->age_ms = (uint16_t)age;
                e->is_new = nw; e->linked = lk; e->pod_dev_class = (uint8_t)cls; e->company_id = (uint16_t)comp; e->rank = (uint8_t)k;
            }
            r.source_count = (uint8_t)n;
            btrx_bearing_push_report(h, &r);
        } else if (kind == 'S') {
            int64_t t1, t4; unsigned t2, t3;
            if (sscanf(p, "%" SCNd64 " %u %u %" SCNd64, &t1, &t2, &t3, &t4) != 4) { bad++; continue; }
            btrx_bearing_push_time_sync(h, t1, t2, t3, t4);
        } else if (kind == 'H') {
            int64_t tu; int role; unsigned up; char lost[32];
            if (sscanf(p, "%" SCNd64 " %d %u %31s", &tu, &role, &up, lost) != 4) { bad++; continue; }
            bool known = strcmp(lost, "unknown") != 0;
            btrx_bearing_push_health(h, tu, (uint8_t)role, up, known, known ? (uint32_t)strtoul(lost, NULL, 10) : 0);
        } else if (kind == 'M') {
            int64_t tu; char keyhex[32]; int muted;
            if (sscanf(p, "%" SCNd64 " %31s %d", &tu, keyhex, &muted) != 3) { bad++; continue; }
            uint64_t k = hex64(keyhex);
            btrx_bearing_set_source_flags(h, &k, 1, BTRX_BEARING_SRC_MUTED, muted ? BTRX_BEARING_SRC_MUTED : 0);
        } else if (kind == 'F') {
            int64_t tu; float tot, shp;
            if (sscanf(p, "%" SCNd64 " %f %f", &tu, &tot, &shp) != 3) { bad++; continue; }
            btrx_bearing_set_fade(h, tot, shp);
        } else bad++;
    }
    fclose(in);
    for (int k = 0; k < 100; k++) {   /* drain: 20 s more */
        bool pub = false;
        btrx_bearing_tick(h, next_tick, &pub);
        if (pub) csv_snapshot(h);
        next_tick += 200000;
    }
    const btrx_bearing_snapshot_t *s = btrx_bearing_snapshot_acquire(h);
    if (s) {
        const btrx_bearing_stats_t *st = &s->stats;
        printf("BBREPLAY lines=%ld bad=%ld reports_in=%u entries_in=%u used=%u gated=%u no_heading=%u no_position=%u late=%u history=%u "
               "ring_dropped=%u sources=%d offset_ms=%d offset_source=%u sessions=%u\n",
            lines, bad, st->reports_in, st->entries_in, st->used, st->gated, st->no_heading, st->no_position, st->late_dropped,
            st->backlog_history, st->ring_dropped, s->source_count, st->pod_offset_ms, st->offset_source, st->pod_session);
        for (int i = 0; i < BTRX_BEARING_STAGE_COUNT; i++)
            printf("BBREPLAY stage=%d count=%u p50=%d p90=%d p99=%d max=%d late=%u\n", i, st->lag[i].count, st->lag[i].p50_ms,
                st->lag[i].p90_ms, st->lag[i].p99_ms, st->lag[i].max_ms, st->lag[i].late_drops);
        if (png) { crc_init(); write_png(png, s->rgba, s->width, s->height); }
        btrx_bearing_snapshot_release(h, s);
    } else {
        printf("BBREPLAY lines=%ld bad=%ld no snapshot (no position input?)\n", lines, bad);
    }
    if (g_csv) fclose(g_csv);
    btrx_bearing_destroy(h);
    return bad ? 1 : 0;
}
