/* SipHash-2-4 (Aumasson, Bernstein) and the 6-character short ID. */
#include <string.h>
#include "bb_internal.h"

#define ROTL(x, b) (uint64_t)(((x) << (b)) | ((x) >> (64 - (b))))
#define SIPROUND do { \
    v0 += v1; v1 = ROTL(v1, 13); v1 ^= v0; v0 = ROTL(v0, 32); \
    v2 += v3; v3 = ROTL(v3, 16); v3 ^= v2; \
    v0 += v3; v3 = ROTL(v3, 21); v3 ^= v0; \
    v2 += v1; v1 = ROTL(v1, 17); v1 ^= v2; v2 = ROTL(v2, 32); } while (0)

static uint64_t le64(const uint8_t *p) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; i--) v = (v << 8) | p[i];
    return v;
}

uint64_t bb_siphash24(const uint8_t key[16], const uint8_t *data, size_t len) {
    uint64_t k0 = le64(key), k1 = le64(key + 8);
    uint64_t v0 = 0x736f6d6570736575ULL ^ k0, v1 = 0x646f72616e646f6dULL ^ k1;
    uint64_t v2 = 0x6c7967656e657261ULL ^ k0, v3 = 0x7465646279746573ULL ^ k1;
    size_t end = len - (len % 8);
    for (size_t i = 0; i < end; i += 8) {
        uint64_t m = le64(data + i);
        v3 ^= m; SIPROUND; SIPROUND; v0 ^= m;
    }
    uint64_t b = ((uint64_t)len) << 56;
    for (size_t i = 0; i < len % 8; i++) b |= ((uint64_t)data[end + i]) << (8 * i);
    v3 ^= b; SIPROUND; SIPROUND; v0 ^= b;
    v2 ^= 0xff; SIPROUND; SIPROUND; SIPROUND; SIPROUND;
    return v0 ^ v1 ^ v2 ^ v3;
}

btrx_bearing_status_t btrx_bearing_source_key(const btrx_bearing_t *h,
        const uint8_t addr_msb_first[6], uint8_t addr_type, uint64_t *key_out) {
    if (!h || !addr_msb_first || !key_out) return BTRX_BEARING_E_INVALID;
    uint8_t buf[8] = { 'A' };
    memcpy(buf + 1, addr_msb_first, 6);
    buf[7] = addr_type;
    uint64_t k = bb_siphash24(h->key, buf, sizeof buf);
    *key_out = k ? k : 1;            /* 0 is reserved for "no address" */
    return BTRX_BEARING_OK;
}

void btrx_bearing_short_id(uint64_t key, char out[8]) {
    static const char alphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    uint64_t top = key >> 28;        /* top 36 bits */
    for (int i = 0; i < 6; i++) out[i] = alphabet[(top >> (30 - 6 * i)) & 63];
    out[6] = 0;
    out[7] = 0;
}
