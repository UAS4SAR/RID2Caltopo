/* STEER and HEALTH parsers (sections 3.4, 4.1), src_id table, provisional merge. */
#include <math.h>
#include <string.h>
#include "bb_internal.h"

static uint16_t u16le(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static int16_t i16le(const uint8_t *p) { return (int16_t)u16le(p); }
static uint32_t u32le(const uint8_t *p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }

bool btrx_bearing_yaw_sample_ms(uint32_t window_end_ms, int16_t t_off_ms, int64_t *out) {
    if (!out || t_off_ms == BTRX_BEARING_YAW_T_OFF_UNKNOWN) return false;
    *out = (int64_t)window_end_ms + (int64_t)t_off_ms;
    return true;
}

/* Assemble the 10-byte format-2 yaw block. count 0: bytes 28-37 and len must be 38.
 * count 1 or 2: record tails must sum to exactly 10 (rec_len 16 or 11). */
static bool gather_yaw(const uint8_t *p, size_t len, uint8_t count, uint8_t yaw[10]) {
    if (count == 0) {
        if (len != 38) return false;
        memcpy(yaw, p + 28, 10);
        return true;
    }
    if (count > 2 || len < 28) return false;
    size_t rec = (len - 28) / count;
    if (rec < 6 || (rec - 6) * (size_t)count != 10) return false;
    size_t tail = rec - 6, o = 0;
    for (uint8_t k = 0; k < count; k++) {
        memcpy(yaw + o, p + 28 + (size_t)k * rec + 6, tail);
        o += tail;
    }
    return true;
}

static void fill_yaw(const uint8_t y[10], btrx_bearing_report_t *out) {
    out->has_yaw = true;
    out->yaw_x10 = u16le(y);
    out->yaw_fixed_x10 = u16le(y + 2);
    out->bias_z_mdps = i16le(y + 4);
    out->t_off_ms = i16le(y + 6);
    out->turns = (int8_t)y[8];
    out->yflags = y[9];
}

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
    if (target && format != 0 && count > 0) {
        size_t rec = (len - 28) / count;
        if (count > 2 || rec < 6) {
            out->truncated = true;
        } else {
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
        }
    }
    /* Format 2 yaw: independent of a target. A length that does not fit means no yaw
     * (records, if readable, are still kept). A truncated extension has no yaw. */
    if (format == 2 && !out->truncated) {
        uint8_t y[10];
        if (gather_yaw(p, len, count, y)) fill_yaw(y, out);
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

bool bb_focus_active(const btrx_bearing_t *h) {
    return h->focus_key || h->focus_class_mask || h->focus_company >= 0;
}

/* Focused: the focus key, or the focus filter's class or company. False with no focus. */
bool bb_focus_match(const btrx_bearing_t *h, const bb_source_t *s) {
    if (h->focus_key && s->key == h->focus_key) return true;
    if (s->cls < 32 && (h->focus_class_mask & (1u << s->cls))) return true;
    return h->focus_company >= 0 && s->company_id == (uint16_t)h->focus_company;
}

uint8_t bb_class_from_pod(uint8_t pod) {
    switch (pod) {
    case 1: return BTRX_BEARING_CLASS_PHONE;
    case 2: return BTRX_BEARING_CLASS_WATCH;
    case 3: return BTRX_BEARING_CLASS_TAG;
    case 4: return BTRX_BEARING_CLASS_AUDIO;
    case 5: return BTRX_BEARING_CLASS_COMPUTER;
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

static void colour_remember(btrx_bearing_t *h, uint64_t key, uint8_t colour) {
    if (!key) return;
    for (int32_t i = 0; i < h->colmem_n; i++)
        if (h->colmem[i].key == key) {
            memmove(&h->colmem[i], &h->colmem[i + 1], sizeof h->colmem[0] * (size_t)(h->colmem_n - i - 1));
            h->colmem_n--;
            break;
        }
    if (h->colmem_n == BB_COLOUR_MEMORY) {
        memmove(&h->colmem[0], &h->colmem[1], sizeof h->colmem[0] * (BB_COLOUR_MEMORY - 1));
        h->colmem_n--;
    }
    h->colmem[h->colmem_n].key = key;
    h->colmem[h->colmem_n].colour = colour;
    h->colmem_n++;
}

/* Fine layer sources are slot-parallel: slot i there is slot i here. */
static void fine_clear_slot(btrx_bearing_t *h, int32_t slot) {
    if (!h->fine) return;
    bb_source_t *c = &h->fine->sources[slot];
    if (c->used) bb_source_free_buckets(h->fine, c);
    bb_bucket_t *b = c->buckets;
    memset(c, 0, sizeof *c);
    c->buckets = b;
    for (int32_t i = 0; i < h->fine->cfg.buckets_per_source; i++) b[i].tile_head = BB_NONE;
}

void bb_source_evict(btrx_bearing_t *h, int32_t slot) {
    bb_source_t *s = &h->sources[slot];
    colour_remember(h, s->key, s->colour);
    for (int32_t a = 0; a < s->alias_count; a++) colour_remember(h, s->aliases[a], s->colour);
    fine_clear_slot(h, slot);
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

/* Evidence a source holds now: fade-weighted, diversity-discounted report count. */
static float source_evidence(const btrx_bearing_t *h, const bb_source_t *s, int64_t now_us) {
    float e = 0;
    for (int32_t i = 0; i < h->cfg.buckets_per_source; i++)
        if (s->buckets[i].used) e += bb_fade_weight(h, &s->buckets[i], now_us) * s->buckets[i].eff;
    return e;
}

/* Full: evict the source with the least evidence (Nov 16: the 8-slot LRU erased a watch
 * with 34 reports of history for passers-by heard once). Heard in the last 30 s counts one
 * report more, so a newcomer gets a chance against an old one-off; the focused source and
 * sources with a valid estimate are kept while anything else can go; ties: least recent. */
static int32_t evict_choice(const btrx_bearing_t *h, int64_t t_us) {
    int32_t best = BB_NONE;
    float best_score = 0;
    int best_tier = 0;
    for (int32_t i = 0; i < h->cfg.max_sources; i++) {
        const bb_source_t *s = &h->sources[i];
        /* focused (key or filter): kept while any other slot can go (Nov 20) */
        bool focused = bb_focus_match(h, s);
        int tier = focused ? 2 : (s->est_valid ? 1 : 0);
        float score = source_evidence(h, s, t_us) + (t_us - s->last_us < 30000000 ? 1.0f : 0.0f);
        if (best == BB_NONE || tier < best_tier || (tier == best_tier && (score < best_score
                || (score == best_score && s->last_us < h->sources[best].last_us)))) {
            best = i; best_score = score; best_tier = tier;
        }
    }
    return best;
}

static uint8_t pick_colour(const btrx_bearing_t *h, int32_t slot, uint64_t key) {
    bool used[BB_PALETTE] = { false };
    for (int32_t i = 0; i < h->cfg.max_sources; i++)
        if (i != slot && h->sources[i].used) used[h->sources[i].colour % BB_PALETTE] = true;
    for (int32_t i = h->colmem_n - 1; i >= 0; i--)          /* the colour it had before */
        if (h->colmem[i].key == key && !used[h->colmem[i].colour % BB_PALETTE]) return h->colmem[i].colour;
    uint8_t start = (uint8_t)((key ^ (key >> 29) ^ (key >> 47)) % BB_PALETTE);
    for (int k = 0; k < BB_PALETTE; k++) {
        uint8_t c = (uint8_t)((start + k) % BB_PALETTE);
        if (!used[c]) return c;
    }
    return start;
}

/* Extras (no address) start a source only on their extra_min_sightings-th sighting within
 * the window: one-off passers-by do not take a slot (Nov 16). */
static bool extra_sighting(btrx_bearing_t *h, uint64_t key, int64_t t_us) {
    int32_t need = h->cfg.extra_min_sightings;
    if (need <= 1) return true;
    int64_t win = (int64_t)h->cfg.extra_sighting_window_ms * 1000;
    for (int32_t i = 0; i < h->cand_n; i++) {
        if (h->cand[i].key != key) continue;
        if (t_us - h->cand[i].t > win) h->cand[i].n = 0;
        h->cand[i].n++;
        h->cand[i].t = t_us;
        if (h->cand[i].n >= need) {
            memmove(&h->cand[i], &h->cand[i + 1], sizeof h->cand[0] * (size_t)(h->cand_n - i - 1));
            h->cand_n--;
            return true;
        }
        return false;
    }
    if (h->cand_n == BB_CAND_TABLE) {
        memmove(&h->cand[0], &h->cand[1], sizeof h->cand[0] * (BB_CAND_TABLE - 1));
        h->cand_n--;
    }
    h->cand[h->cand_n].key = key; h->cand[h->cand_n].t = t_us; h->cand[h->cand_n].n = 1;
    h->cand_n++;
    return false;
}

static int32_t new_source(btrx_bearing_t *h, uint64_t key, bool provisional, int64_t t_us) {
    int32_t slot = BB_NONE;
    for (int32_t i = 0; i < h->cfg.max_sources; i++) if (!h->sources[i].used) { slot = i; break; }
    if (slot == BB_NONE) {
        slot = evict_choice(h, t_us);
        h->evictions++;
        bb_source_evict(h, slot);
    }
    bb_source_t *s = &h->sources[slot];
    s->used = true;
    s->key = key;
    s->provisional = provisional;
    s->src_keyed = false;
    s->src_id = 0;
    s->first_us = s->last_us = t_us;
    /* colour tied to the device: the one it had, else a free one picked from its key */
    s->colour = pick_colour(h, slot, key);
    s->last_rssi = BTRX_BEARING_RSSI_NOT_HEARD;
    for (int32_t i = 0; i < h->cfg.buckets_per_source; i++) { memset(&s->buckets[i], 0, sizeof s->buckets[i]); s->buckets[i].tile_head = BB_NONE; }
    s->ray.mirror_true_deg = NAN;
    s->company_id = 0;
    /* flagged at the launch point before an eviction: flagged (and muted) again; the app's
       own flags (pending) come after, so an unmute stays */
    if (bb_launch_key(h, key)) s->flags |= BTRX_BEARING_SRC_LAUNCH | (h->launch_auto_mute ? BTRX_BEARING_SRC_MUTED : 0u);
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
    if (!d->company_id) d->company_id = s->company_id;
    if (s->near_valid && (!d->near_valid || s->near_rssi > d->near_rssi)) {
        d->near_valid = true; d->near_e = s->near_e; d->near_n = s->near_n; d->near_rssi = s->near_rssi; d->near_t = s->near_t;
    }
    d->flags |= s->flags & BTRX_BEARING_SRC_IGNORED;
    for (int32_t i = 0; i < h->div.n; i++) if (h->div.item[i].slot == src) h->div.item[i].slot = dst;
    if (h->fine) {
        bb_source_t *fd = &h->fine->sources[dst], *fs = &h->fine->sources[src];
        if (fs->used) {
            if (!fd->used) { fd->used = true; fd->key = d->key; }
            bb_merge_source(h->fine, fd, fs);
        }
        fine_clear_slot(h, src);
    }
    if (s->trail_n > d->trail_n) {       /* keep the longer strength trail */
        memcpy(d->trail, s->trail, sizeof d->trail);
        d->trail_head = s->trail_head; d->trail_n = s->trail_n;
    }
    srcid_remap(h, src, dst);
    bb_bucket_t *b = s->buckets;
    memset(s, 0, sizeof *s);
    s->buckets = b;
    h->stats.provisional_merges++;
    h->dirty = true;
}

/* Stable ids (pod 0x0106+): the key of a source seen by its src_id alone. 'S' keeps it apart
 * from address keys ('A', 8 bytes) and provisional keys ('P', 7 bytes). No pod session: the
 * id is the same after a pod restart. */
static uint64_t src_key(const btrx_bearing_t *h, uint16_t id) {
    uint8_t buf[3] = { 'S', (uint8_t)(id & 0xFF), (uint8_t)(id >> 8) };
    uint64_t k = bb_siphash24(h->key, buf, sizeof buf);
    return k ? k : 1;
}

/* Seen only as an extra so far (provisional, or keyed by src_id): takes the address. */
static bool unresolved(const bb_source_t *s) { return s->provisional || s->src_keyed; }

int32_t bb_resolve_entry(btrx_bearing_t *h, const btrx_bearing_report_source_t *rs, int64_t t_us) {
    int32_t slot;
    const bool stable = h->stable_src_ids && rs->src_id != 0;
    const uint64_t sk = stable ? src_key(h, rs->src_id) : 0;
    if (rs->addr_key) {
        int32_t sa = bb_find_source(h, rs->addr_key);
        int32_t si = rs->src_id ? srcid_lookup(h, rs->src_id) : BB_NONE;
        if (si != BB_NONE && !h->sources[si].used) si = BB_NONE;
        /* Stable ids: the src_id mapping may have been dropped; the src_id key still finds it. */
        if (si == BB_NONE && sk) si = bb_find_source(h, sk);
        if (sa != BB_NONE && si != BB_NONE && sa != si) {
            if (unresolved(&h->sources[si])) merge_into(h, sa, si);
            else if (rs->linked) add_alias(&h->sources[sa], h->sources[si].key);
            slot = sa;
        } else if (sa != BB_NONE) {
            slot = sa;
        } else if (si != BB_NONE) {
            bb_source_t *s = &h->sources[si];
            if (unresolved(s)) {
                if (s->src_keyed) add_alias(s, s->key);   /* extras keep finding it by src_id */
                s->key = rs->addr_key;
                s->provisional = false;
                s->src_keyed = false;
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
        /* Stable ids: an extra with this src_id later finds this source by key too. */
        if (sk && h->sources[slot].key != sk) add_alias(&h->sources[slot], sk);
    } else {
        slot = rs->src_id ? srcid_lookup(h, rs->src_id) : BB_NONE;
        if (slot != BB_NONE && !h->sources[slot].used) slot = BB_NONE;
        if (slot == BB_NONE && sk) {
            /* Stable ids: the src_id names one device; a real source, not provisional. */
            slot = bb_find_source(h, sk);
            if (slot == BB_NONE) {
                if (!extra_sighting(h, sk, t_us)) return BB_NONE;
                slot = new_source(h, sk, false, t_us);
                h->sources[slot].src_keyed = true;
                h->stats.extras_unresolved++;
            }
            srcid_set(h, rs->src_id, slot);
        } else if (slot == BB_NONE) {
            uint8_t buf[7] = { 'P' };
            uint32_t ses = h->clock.session;
            for (int i = 0; i < 4; i++) buf[1 + i] = (uint8_t)(ses >> (8 * i));
            buf[5] = (uint8_t)(rs->src_id & 0xFF);
            buf[6] = (uint8_t)(rs->src_id >> 8);
            uint64_t k = bb_siphash24(h->key, buf, sizeof buf);
            if (!k) k = 1;
            slot = bb_find_source(h, k);
            if (slot == BB_NONE) {
                if (!extra_sighting(h, k, t_us)) return BB_NONE;
                slot = new_source(h, k, true, t_us);
            }
            srcid_set(h, rs->src_id, slot);
            h->stats.extras_unresolved++;
        }
    }
    if (rs->src_id) h->sources[slot].src_id = rs->src_id;
    if (rs->company_id) h->sources[slot].company_id = rs->company_id;
    merge_class(&h->sources[slot], bb_class_from_pod(rs->pod_dev_class));
    return slot;
}
