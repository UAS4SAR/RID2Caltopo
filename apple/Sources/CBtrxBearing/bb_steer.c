/* STEER and HEALTH parsers (sections 3.4, 4.1), src_id table, provisional merge. */
#include <math.h>
#include <string.h>
#include "bb_internal.h"

static uint16_t u16le(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t u32le(const uint8_t *p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }

btrx_bearing_status_t btrx_bearing_parse_steer(const btrx_bearing_t *h,
        const uint8_t *p, size_t len, int64_t arrival_us, bool backlog,
        btrx_bearing_report_t *out) {
    if (!p || !out || len < 27) return BTRX_BEARING_E_INVALID;
    memset(out, 0, sizeof *out);
    out->arrival_us = arrival_us;
    out->backlog = backlog;
    out->steer_seq = p[0];
    out->flags = p[1];
    out->window_end_ms = u32le(p + 2);
    out->roll_x2 = (int8_t)p[25];
    out->pitch_x2 = (int8_t)p[26];
    bool target = (p[1] & 0x01) != 0;
    if (target) {
        btrx_bearing_report_source_t *s = &out->src[0];
        s->rank = 0;
        s->age_ms = u16le(p + 6);
        s->src_id = u16le(p + 8);
        s->rssi_port = (int8_t)p[10];
        s->rssi_stbd = (int8_t)p[11];
        s->peak_rssi = (int8_t)p[12];
        s->hits_port = p[13];
        s->hits_stbd = p[14];
        s->company_id = u16le(p + 15);
        s->pod_dev_class = p[17];
        s->addr_type = p[18];
        s->is_new = (p[1] & 0x02) != 0;
        s->linked = (p[1] & 0x04) != 0;
        uint8_t msb[6];
        for (int i = 0; i < 6; i++) msb[i] = p[24 - i];   /* payload is LSB first */
        if (h) btrx_bearing_source_key(h, msb, s->addr_type, &s->addr_key);
        out->source_count = 1;
    }
    if (len < 28) return BTRX_BEARING_OK;
    uint8_t ext = p[27];
    uint8_t count = ext & 0x0F, format = (uint8_t)(ext >> 4);
    out->ext_format = format;
    if (!target || format == 0 || count == 0) return BTRX_BEARING_OK;
    size_t rec = count > 0 ? (len - 28) / count : 0;
    if (count > 2 || rec < 6) { out->truncated = true; return BTRX_BEARING_OK; }
    for (uint8_t k = 0; k < count; k++) {
        const uint8_t *r = p + 28 + (size_t)k * rec;
        btrx_bearing_report_source_t *s = &out->src[out->source_count++];
        s->rank = (uint8_t)(k + 1);
        s->src_id = u16le(r);
        s->addr_key = 0;
        s->rssi_port = (int8_t)r[2];
        s->rssi_stbd = (int8_t)r[3];
        s->hits_port = r[4] & 0x0F;
        s->hits_stbd = (uint8_t)(r[4] >> 4);
        s->is_new = (r[5] & 0x01) != 0;
        s->linked = (r[5] & 0x02) != 0;
        s->pod_dev_class = (uint8_t)((r[5] >> 2) & 0x07);
        s->peak_rssi = BTRX_BEARING_RSSI_NOT_HEARD;
        s->age_ms = 0xFFFF;
        s->company_id = 0;
        s->addr_type = 0;
    }
    return BTRX_BEARING_OK;
}

btrx_bearing_status_t btrx_bearing_parse_health(const uint8_t *p, size_t len, btrx_bearing_health_t *out) {
    if (!p || !out || len < 30) return BTRX_BEARING_E_INVALID;
    memset(out, 0, sizeof *out);
    out->uptime_s = u32le(p);
    out->role = p[6];
    if (len >= 34) { out->lost_known = true; out->phone_lost = u32le(p + 30); }
    return BTRX_BEARING_OK;
}

uint8_t bb_class_from_pod(uint8_t pod) {
    switch (pod) {
    case 1: return BTRX_BEARING_CLASS_PHONE;
    case 2: return BTRX_BEARING_CLASS_WATCH;
    case 3: return BTRX_BEARING_CLASS_TAG;
    case 4: return BTRX_BEARING_CLASS_AUDIO;
    default: return BTRX_BEARING_CLASS_UNKNOWN;
    }
}

/* Specificity: unknown < audio < phone/watch/tag < hearing aid. */
static int class_rank(uint8_t c) {
    switch (c) {
    case BTRX_BEARING_CLASS_UNKNOWN: return 0;
    case BTRX_BEARING_CLASS_AUDIO: return 1;
    case BTRX_BEARING_CLASS_HEARING_AID: return 3;
    default: return 2;
    }
}

static void merge_class(bb_source_t *s, uint8_t c) {
    if (class_rank(c) > class_rank(s->cls)) s->cls = c;
}

int32_t bb_find_source(const btrx_bearing_t *h, uint64_t key) {
    if (!key) return BB_NONE;
    for (int32_t i = 0; i < h->cfg.max_sources; i++) {
        const bb_source_t *s = &h->sources[i];
        if (!s->used) continue;
        if (s->key == key) return i;
        for (int32_t a = 0; a < s->alias_count; a++) if (s->aliases[a] == key) return i;
    }
    return BB_NONE;
}

void bb_srcid_clear(btrx_bearing_t *h) { h->srcid_n = 0; }

static int32_t srcid_lookup(const btrx_bearing_t *h, uint16_t id) {
    for (int32_t i = 0; i < h->srcid_n; i++) if (h->srcid[i].src_id == id) return h->srcid[i].slot;
    return BB_NONE;
}

static void srcid_set(btrx_bearing_t *h, uint16_t id, int32_t slot) {
    if (id == 0) return;
    for (int32_t i = 0; i < h->srcid_n; i++) if (h->srcid[i].src_id == id) { h->srcid[i].slot = slot; return; }
    if (h->srcid_n == BB_SRCID_TABLE) {          /* drop the oldest mapping */
        memmove(&h->srcid[0], &h->srcid[1], sizeof h->srcid[0] * (BB_SRCID_TABLE - 1));
        h->srcid_n--;
    }
    h->srcid[h->srcid_n].src_id = id;
    h->srcid[h->srcid_n].slot = slot;
    h->srcid_n++;
}

static void srcid_remap(btrx_bearing_t *h, int32_t from, int32_t to) {
    for (int32_t i = 0; i < h->srcid_n; i++) if (h->srcid[i].slot == from) h->srcid[i].slot = to;
}

void bb_source_evict(btrx_bearing_t *h, int32_t slot) {
    bb_source_t *s = &h->sources[slot];
    bb_source_free_buckets(h, s);
    bb_diversity_drop_slot(h, slot);
    bb_bucket_t *b = s->buckets;
    memset(s, 0, sizeof *s);
    s->buckets = b;
    for (int32_t i = 0; i < h->srcid_n; ) {
        if (h->srcid[i].slot == slot) {
            memmove(&h->srcid[i], &h->srcid[i + 1], sizeof h->srcid[0] * (size_t)(h->srcid_n - i - 1));
            h->srcid_n--;
        } else i++;
    }
    h->dirty = true;
}

static void add_alias(bb_source_t *s, uint64_t key) {
    if (!key || s->key == key) return;
    for (int32_t a = 0; a < s->alias_count; a++) if (s->aliases[a] == key) return;
    if (s->alias_count < BTRX_BEARING_MAX_ALIASES) s->aliases[s->alias_count++] = key;
    else { memmove(&s->aliases[0], &s->aliases[1], sizeof s->aliases[0] * (BTRX_BEARING_MAX_ALIASES - 1));
           s->aliases[BTRX_BEARING_MAX_ALIASES - 1] = key; }
}

static void apply_pending_flags(btrx_bearing_t *h, bb_source_t *s) {
    for (int32_t i = 0; i < h->pend_flags_n; i++) {
        uint64_t k = h->pend_flags[i].key;
        bool match = k == s->key;
        for (int32_t a = 0; a < s->alias_count; a++) if (s->aliases[a] == k) match = true;
        if (match) s->flags = (s->flags & ~h->pend_flags[i].mask) | (h->pend_flags[i].value & h->pend_flags[i].mask);
    }
}

static int32_t new_source(btrx_bearing_t *h, uint64_t key, bool provisional, int64_t t_us) {
    int32_t slot = BB_NONE;
    for (int32_t i = 0; i < h->cfg.max_sources; i++) if (!h->sources[i].used) { slot = i; break; }
    if (slot == BB_NONE) {
        /* least recently heard without a valid estimate, else least recently heard */
        int32_t lru = BB_NONE, lru_noest = BB_NONE;
        for (int32_t i = 0; i < h->cfg.max_sources; i++) {
            const bb_source_t *s = &h->sources[i];
            if (lru == BB_NONE || s->last_us < h->sources[lru].last_us) lru = i;
            if (!s->est_valid && (lru_noest == BB_NONE || s->last_us < h->sources[lru_noest].last_us)) lru_noest = i;
        }
        slot = lru_noest != BB_NONE ? lru_noest : lru;
        bb_source_evict(h, slot);
    }
    bb_source_t *s = &h->sources[slot];
    s->used = true;
    s->key = key;
    s->provisional = provisional;
    s->first_us = s->last_us = t_us;
    /* colour: first palette entry not in use, starting after the last one handed out */
    for (int k = 0; k < 8; k++) {
        uint8_t c = (uint8_t)((h->next_colour + k) % 8);
        bool used = false;
        for (int32_t i = 0; i < h->cfg.max_sources; i++)
            if (i != slot && h->sources[i].used && h->sources[i].colour == c) used = true;
        if (!used) { s->colour = c; break; }
    }
    h->next_colour = (uint8_t)((s->colour + 1) % 8);
    for (int32_t i = 0; i < h->cfg.buckets_per_source; i++) { memset(&s->buckets[i], 0, sizeof s->buckets[i]); s->buckets[i].tile_head = BB_NONE; }
    s->ray.mirror_true_deg = NAN;
    apply_pending_flags(h, s);
    return slot;
}

static void merge_into(btrx_bearing_t *h, int32_t dst, int32_t src) {
    bb_source_t *d = &h->sources[dst], *s = &h->sources[src];
    bb_merge_source(h, d, s);
    d->reports_in += s->reports_in;
    d->reports_used += s->reports_used;
    if (s->first_us < d->first_us) d->first_us = s->first_us;
    if (s->last_us > d->last_us) { d->last_us = s->last_us; d->ray = s->ray; d->heard_order = s->heard_order; }
    merge_class(d, s->cls);
    d->flags |= s->flags & BTRX_BEARING_SRC_IGNORED;
    for (int32_t i = 0; i < h->div.n; i++) if (h->div.item[i].slot == src) h->div.item[i].slot = dst;
    srcid_remap(h, src, dst);
    bb_bucket_t *b = s->buckets;
    memset(s, 0, sizeof *s);
    s->buckets = b;
    h->stats.provisional_merges++;
    h->dirty = true;
}

int32_t bb_resolve_entry(btrx_bearing_t *h, const btrx_bearing_report_source_t *rs, int64_t t_us) {
    int32_t slot;
    if (rs->addr_key) {
        int32_t sa = bb_find_source(h, rs->addr_key);
        int32_t si = rs->src_id ? srcid_lookup(h, rs->src_id) : BB_NONE;
        if (si != BB_NONE && !h->sources[si].used) si = BB_NONE;
        if (sa != BB_NONE && si != BB_NONE && sa != si) {
            if (h->sources[si].provisional) merge_into(h, sa, si);
            else if (rs->linked) add_alias(&h->sources[sa], h->sources[si].key);
            slot = sa;
        } else if (sa != BB_NONE) {
            slot = sa;
        } else if (si != BB_NONE) {
            bb_source_t *s = &h->sources[si];
            if (s->provisional) {
                s->key = rs->addr_key;
                s->provisional = false;
                apply_pending_flags(h, s);
                h->stats.provisional_merges++;
                slot = si;
            } else if (rs->linked) {
                add_alias(s, rs->addr_key);
                apply_pending_flags(h, s);
                slot = si;
            } else {
                slot = new_source(h, rs->addr_key, false, t_us);
            }
        } else {
            slot = new_source(h, rs->addr_key, false, t_us);
        }
        srcid_set(h, rs->src_id, slot);
    } else {
        slot = rs->src_id ? srcid_lookup(h, rs->src_id) : BB_NONE;
        if (slot != BB_NONE && !h->sources[slot].used) slot = BB_NONE;
        if (slot == BB_NONE) {
            uint8_t buf[7] = { 'P' };
            uint32_t ses = h->clock.session;
            for (int i = 0; i < 4; i++) buf[1 + i] = (uint8_t)(ses >> (8 * i));
            buf[5] = (uint8_t)(rs->src_id & 0xFF);
            buf[6] = (uint8_t)(rs->src_id >> 8);
            uint64_t k = bb_siphash24(h->key, buf, sizeof buf);
            if (!k) k = 1;
            slot = bb_find_source(h, k);
            if (slot == BB_NONE) slot = new_source(h, k, true, t_us);
            srcid_set(h, rs->src_id, slot);
            h->stats.extras_unresolved++;
        }
    }
    merge_class(&h->sources[slot], bb_class_from_pod(rs->pod_dev_class));
    return slot;
}
