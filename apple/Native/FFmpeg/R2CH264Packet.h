#ifndef R2C_H264_PACKET_H
#define R2C_H264_PACKET_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct R2CH264PacketContents {
    bool recognized;
    bool hasPicture;
    bool hasSei;
    bool hasOtherNal;
} R2CH264PacketContents;

static inline void R2CH264PacketRecordNal(
    R2CH264PacketContents *contents,
    const uint8_t *nal,
    size_t nalSize
) {
    if (contents == NULL || nal == NULL || nalSize == 0) return;
    contents->recognized = true;
    uint8_t type = nal[0] & 0x1f;
    if (type >= 1 && type <= 5) contents->hasPicture = true;
    else if (type == 6) contents->hasSei = true;
    else contents->hasOtherNal = true;
}

static inline R2CH264PacketContents R2CH264InspectLengthPrefixedPacket(
    const uint8_t *data,
    size_t size,
    int nalLengthSize
) {
    R2CH264PacketContents contents = {0};
    if (data == NULL || size == 0 || nalLengthSize < 1 || nalLengthSize > 4) return contents;
    size_t offset = 0;
    while (offset + (size_t) nalLengthSize <= size) {
        size_t nalSize = 0;
        for (int index = 0; index < nalLengthSize; ++index) {
            nalSize = (nalSize << 8) | data[offset + (size_t) index];
        }
        offset += (size_t) nalLengthSize;
        if (nalSize == 0 || nalSize > size - offset) return (R2CH264PacketContents) {0};
        R2CH264PacketRecordNal(&contents, data + offset, nalSize);
        offset += nalSize;
    }
    if (offset != size) return (R2CH264PacketContents) {0};
    return contents;
}

static inline size_t R2CH264StartCodeLength(const uint8_t *data, size_t size) {
    if (data == NULL || size < 3 || data[0] != 0 || data[1] != 0) return 0;
    if (data[2] == 1) return 3;
    if (size >= 4 && data[2] == 0 && data[3] == 1) return 4;
    return 0;
}

static inline R2CH264PacketContents R2CH264InspectAnnexBPacket(
    const uint8_t *data,
    size_t size
) {
    R2CH264PacketContents contents = {0};
    size_t offset = 0;
    while (offset < size) {
        size_t startCodeSize = 0;
        while (offset < size &&
               (startCodeSize = R2CH264StartCodeLength(data + offset, size - offset)) == 0) {
            offset += 1;
        }
        if (startCodeSize == 0) break;
        size_t nalStart = offset + startCodeSize;
        size_t nalEnd = nalStart;
        while (nalEnd < size && R2CH264StartCodeLength(data + nalEnd, size - nalEnd) == 0) {
            nalEnd += 1;
        }
        R2CH264PacketRecordNal(&contents, data + nalStart, nalEnd - nalStart);
        offset = nalEnd;
    }
    return contents;
}

static inline R2CH264PacketContents R2CH264InspectPacket(
    const uint8_t *data,
    size_t size,
    int nalLengthSize
) {
    // A valid AVCC length such as 00 00 01 06 also starts like Annex B.
    // Validate the complete length-prefixed layout before trying start codes.
    R2CH264PacketContents contents = R2CH264InspectLengthPrefixedPacket(data, size, nalLengthSize);
    if (contents.recognized) return contents;
    if (R2CH264StartCodeLength(data, size) != 0) {
        return R2CH264InspectAnnexBPacket(data, size);
    }
    return contents;
}

static inline bool R2CH264PacketIsSeiOnly(R2CH264PacketContents contents) {
    return contents.recognized && contents.hasSei &&
        !contents.hasPicture && !contents.hasOtherNal;
}

#endif
