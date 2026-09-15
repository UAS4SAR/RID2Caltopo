#ifndef R2C_DJI_CAMERA_TELEMETRY_H
#define R2C_DJI_CAMERA_TELEMETRY_H

#include <math.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct R2CDJICameraTelemetry {
    bool valid;
    bool positionValid;
    double azimuthDegrees;
    double tiltDegrees;
    double horizontalFovDegrees;
    double verticalFovDegrees;
    double latitudeDegrees;
    double longitudeDegrees;
    double altitudeMeters;
    // Tag 4 stores each signed 32-bit N/E/Down value as a low 16-bit word at
    // offsets 15/17/19 and its high word at offsets 21/23/25. Values are
    // complete in each frame; no continuity-based epoch tracking is required.
    bool relativeDisplacementValid;
    int32_t relativeNorthMillimeters;
    int32_t relativeEastMillimeters;
    int32_t downMillimeters;
    double relativeUpMeters;
    // Retained for byte-level diagnostics while validating additional captures.
    int16_t relativeNorthMillimetersRaw;
    int16_t relativeEastMillimetersRaw;
    int16_t relativeDownMillimetersRaw;
    // Exact decoded RBSP bytes for comparison against simultaneous RID
    // coordinates while the private DJI field layout is being established.
    size_t type245PayloadSize;
    uint8_t type245Payload[128];
    // Opaque packed words from the two remaining fixed-size TLVs. Their 1+N*4
    // layout is established, but their meanings and scales are intentionally
    // left unlabeled until a position-controlled flight identifies them.
    bool packedTag6Valid;
    uint8_t packedTag6Header;
    uint32_t packedTag6Words[2];
    bool packedTag9Valid;
    uint8_t packedTag9Header;
    uint32_t packedTag9Words[4];
    // Diagnostic-only aligned binary-angle fields from tag 4. Their semantic
    // frames are not yet all established; operational values above remain
    // explicit until controlled captures identify them.
    double attitudeAnglesDegrees[9];
} R2CDJICameraTelemetry;

static inline bool R2CDJIHexEncode(
    const uint8_t *bytes,
    size_t size,
    char *output,
    size_t outputCapacity
) {
    static const char digits[] = "0123456789abcdef";
    if (bytes == NULL || output == NULL || size > (SIZE_MAX - 1) / 2 ||
        outputCapacity < size * 2 + 1) {
        return false;
    }
    for (size_t index = 0; index < size; ++index) {
        output[index * 2] = digits[bytes[index] >> 4];
        output[index * 2 + 1] = digits[bytes[index] & 0x0f];
    }
    output[size * 2] = '\0';
    return true;
}

static inline uint16_t R2CDJIReadUInt16LE(const uint8_t *bytes) {
    return (uint16_t) bytes[0] | ((uint16_t) bytes[1] << 8);
}

static inline int16_t R2CDJIReadSigned16LE(const uint8_t *bytes) {
    return (int16_t) R2CDJIReadUInt16LE(bytes);
}

static inline uint32_t R2CDJIReadUInt32LE(const uint8_t *bytes) {
    return (uint32_t) bytes[0] |
        ((uint32_t) bytes[1] << 8) |
        ((uint32_t) bytes[2] << 16) |
        ((uint32_t) bytes[3] << 24);
}

static inline int64_t R2CDJIReadSigned32LE(const uint8_t *bytes) {
    uint32_t raw = R2CDJIReadUInt32LE(bytes);
    return raw <= INT32_MAX ? (int64_t) raw : (int64_t) raw - 4294967296LL;
}

static inline int32_t R2CDJIReadSplitSigned32LE(
    const uint8_t *lowWord,
    const uint8_t *highWord
) {
    uint32_t raw = (uint32_t) R2CDJIReadUInt16LE(lowWord) |
        ((uint32_t) R2CDJIReadUInt16LE(highWord) << 16);
    int64_t signedValue = raw <= INT32_MAX ? (int64_t) raw : (int64_t) raw - 4294967296LL;
    return (int32_t) signedValue;
}

static inline bool R2CDJIDecodeType245Payload(
    const uint8_t *payload,
    size_t payloadSize,
    R2CDJICameraTelemetry *telemetry
) {
    if (payload == NULL || telemetry == NULL) return false;
    if (payloadSize > sizeof(telemetry->type245Payload)) return false;
    telemetry->type245PayloadSize = payloadSize;
    for (size_t index = 0; index < payloadSize; ++index) {
        telemetry->type245Payload[index] = payload[index];
    }
    const uint8_t *attitude = NULL;
    const uint8_t *optics = NULL;
    const uint8_t *packedTag6 = NULL;
    const uint8_t *packedTag9 = NULL;
    size_t offset = 0;
    while (offset + 4 <= payloadSize) {
        uint16_t tag = R2CDJIReadUInt16LE(payload + offset);
        uint16_t length = R2CDJIReadUInt16LE(payload + offset + 2);
        if (tag == 0 && length == 0) break;
        offset += 4;
        if ((size_t) length > payloadSize - offset) return false;
        if (tag == 4 && length == 39) attitude = payload + offset;
        if (tag == 6 && length == 9) packedTag6 = payload + offset;
        if (tag == 9 && length == 17) packedTag9 = payload + offset;
        if (tag == 10 && length == 13) optics = payload + offset;
        offset += length;
    }
    for (; offset < payloadSize; ++offset) {
        if (payload[offset] != 0) return false;
    }
    if (attitude == NULL || optics == NULL) return false;

    const double fullTurn = 4294967296.0;
    for (size_t index = 0; index < 9; ++index) {
        telemetry->attitudeAnglesDegrees[index] =
            (double) R2CDJIReadUInt32LE(attitude + 3 + index * 4) * 360.0 / fullTurn;
    }
    // Matrice 4TD capture 2026-09-15 crosses +1799354051 to -1793949732.
    // This is signed degrees * 1e7, not an unsigned full-turn binary angle.
    // Normalize only after decoding to preserve continuity at +/-180 degrees.
    double signedAzimuth = (double) R2CDJIReadSigned32LE(attitude + 3) / 10000000.0;
    double azimuth = fmod(signedAzimuth + 360.0, 360.0);
    double tiltEncoder = (double) R2CDJIReadUInt32LE(attitude + 11) * 360.0 / fullTurn;
    double tilt = fmod(tiltEncoder - 90.0 + 540.0, 360.0) - 180.0;
    double horizontalFov = (double) R2CDJIReadUInt32LE(optics + 1) / 256.0;
    double verticalFov = (double) R2CDJIReadUInt32LE(optics + 5) / 256.0;
    // The final tag-4 triple is the geodetic reference for the local N/E/Down
    // coordinates (normally DJI's recorded home/reference point).
    double latitude = (double) R2CDJIReadSigned32LE(attitude + 27) * 180.0 / fullTurn;
    double longitude = (double) R2CDJIReadSigned32LE(attitude + 31) * 360.0 / fullTurn;
    double altitude = -(double) R2CDJIReadSigned32LE(attitude + 35) / 1000.0;
    if (!isfinite(azimuth) || !isfinite(tilt) ||
        !(horizontalFov > 0.0 && horizontalFov <= 180.0) ||
        !(verticalFov > 0.0 && verticalFov <= 180.0)) {
        return false;
    }
    telemetry->valid = true;
    telemetry->azimuthDegrees = azimuth;
    telemetry->tiltDegrees = tilt;
    telemetry->horizontalFovDegrees = horizontalFov;
    telemetry->verticalFovDegrees = verticalFov;
    telemetry->positionValid = isfinite(latitude) && isfinite(longitude) && isfinite(altitude) &&
        latitude >= -90.0 && latitude <= 90.0 &&
        longitude >= -180.0 && longitude <= 180.0 &&
        altitude >= -1000.0 && altitude <= 30000.0 &&
        (fabs(latitude) > 0.00000001 || fabs(longitude) > 0.00000001);
    telemetry->latitudeDegrees = latitude;
    telemetry->longitudeDegrees = longitude;
    telemetry->altitudeMeters = altitude;
    telemetry->relativeDisplacementValid = true;
    telemetry->relativeNorthMillimetersRaw = R2CDJIReadSigned16LE(attitude + 15);
    telemetry->relativeEastMillimetersRaw = R2CDJIReadSigned16LE(attitude + 17);
    telemetry->relativeDownMillimetersRaw = R2CDJIReadSigned16LE(attitude + 19);
    telemetry->relativeNorthMillimeters = R2CDJIReadSplitSigned32LE(attitude + 15, attitude + 21);
    telemetry->relativeEastMillimeters = R2CDJIReadSplitSigned32LE(attitude + 17, attitude + 23);
    telemetry->downMillimeters = R2CDJIReadSplitSigned32LE(attitude + 19, attitude + 25);
    telemetry->relativeUpMeters = -(double) telemetry->downMillimeters / 1000.0 - altitude;
    if (packedTag6 != NULL) {
        telemetry->packedTag6Valid = true;
        telemetry->packedTag6Header = packedTag6[0];
        for (size_t index = 0; index < 2; ++index) {
            telemetry->packedTag6Words[index] = R2CDJIReadUInt32LE(packedTag6 + 1 + index * 4);
        }
    }
    if (packedTag9 != NULL) {
        telemetry->packedTag9Valid = true;
        telemetry->packedTag9Header = packedTag9[0];
        for (size_t index = 0; index < 4; ++index) {
            telemetry->packedTag9Words[index] = R2CDJIReadUInt32LE(packedTag9 + 1 + index * 4);
        }
    }
    return true;
}

static inline bool R2CDJIReadExtendedSEIValue(
    const uint8_t *bytes,
    size_t size,
    size_t *offset,
    size_t *value
) {
    size_t decoded = 0;
    while (*offset < size) {
        uint8_t component = bytes[(*offset)++];
        if (decoded > SIZE_MAX - component) return false;
        decoded += component;
        if (component != 0xff) {
            *value = decoded;
            return true;
        }
    }
    return false;
}

typedef void (*R2CDJISEIPayloadVisitor)(
    size_t payloadType,
    const uint8_t *payload,
    size_t payloadSize,
    void *context
);

static inline size_t R2CDJIVisitSEINAL(
    const uint8_t *nal,
    size_t nalSize,
    R2CDJISEIPayloadVisitor visitor,
    void *context
) {
    if (nal == NULL || nalSize < 2 || (nal[0] & 0x1f) != 6 || visitor == NULL) return 0;
    uint8_t rbsp[2048];
    size_t rbspSize = 0;
    for (size_t index = 1; index < nalSize; ++index) {
        if (index >= 3 && nal[index] == 0x03 && nal[index - 1] == 0x00 &&
            nal[index - 2] == 0x00 && index + 1 < nalSize && nal[index + 1] <= 0x03) {
            continue;
        }
        if (rbspSize >= sizeof(rbsp)) return 0;
        rbsp[rbspSize++] = nal[index];
    }
    size_t offset = 0;
    size_t count = 0;
    while (offset < rbspSize && rbsp[offset] != 0x80) {
        size_t payloadType = 0;
        size_t payloadLength = 0;
        if (!R2CDJIReadExtendedSEIValue(rbsp, rbspSize, &offset, &payloadType) ||
            !R2CDJIReadExtendedSEIValue(rbsp, rbspSize, &offset, &payloadLength) ||
            payloadLength > rbspSize - offset) {
            return count;
        }
        visitor(payloadType, rbsp + offset, payloadLength, context);
        ++count;
        offset += payloadLength;
    }
    return count;
}

static inline bool R2CDJIDecodeSEINAL(
    const uint8_t *nal,
    size_t nalSize,
    R2CDJICameraTelemetry *telemetry
) {
    if (nal == NULL || nalSize < 2 || (nal[0] & 0x1f) != 6) return false;
    uint8_t rbsp[512];
    size_t rbspSize = 0;
    for (size_t index = 1; index < nalSize; ++index) {
        if (index >= 3 && nal[index] == 0x03 && nal[index - 1] == 0x00 &&
            nal[index - 2] == 0x00 && index + 1 < nalSize && nal[index + 1] <= 0x03) {
            continue;
        }
        if (rbspSize >= sizeof(rbsp)) return false;
        rbsp[rbspSize++] = nal[index];
    }

    size_t offset = 0;
    while (offset < rbspSize) {
        if (rbsp[offset] == 0x80) return false;
        size_t payloadType = 0;
        size_t payloadLength = 0;
        if (!R2CDJIReadExtendedSEIValue(rbsp, rbspSize, &offset, &payloadType) ||
            !R2CDJIReadExtendedSEIValue(rbsp, rbspSize, &offset, &payloadLength) ||
            payloadLength > rbspSize - offset) {
            return false;
        }
        if (payloadType == 245 &&
            R2CDJIDecodeType245Payload(rbsp + offset, payloadLength, telemetry)) {
            return true;
        }
        offset += payloadLength;
    }
    return false;
}

static inline size_t R2CDJIStartCodeLength(const uint8_t *data, size_t size) {
    if (data == NULL || size < 3 || data[0] != 0 || data[1] != 0) return 0;
    if (data[2] == 1) return 3;
    if (size >= 4 && data[2] == 0 && data[3] == 1) return 4;
    return 0;
}

static inline size_t R2CDJIVisitH264SEIPayloads(
    const uint8_t *data,
    size_t size,
    int nalLengthSize,
    R2CDJISEIPayloadVisitor visitor,
    void *context
) {
    if (data == NULL || size == 0 || visitor == NULL) return 0;
    size_t count = 0;
    if (R2CDJIStartCodeLength(data, size) != 0) {
        size_t offset = 0;
        while (offset < size) {
            size_t startCodeSize = 0;
            while (offset < size &&
                   (startCodeSize = R2CDJIStartCodeLength(data + offset, size - offset)) == 0) {
                ++offset;
            }
            if (startCodeSize == 0) break;
            size_t nalStart = offset + startCodeSize;
            size_t nalEnd = nalStart;
            while (nalEnd < size && R2CDJIStartCodeLength(data + nalEnd, size - nalEnd) == 0) {
                ++nalEnd;
            }
            count += R2CDJIVisitSEINAL(data + nalStart, nalEnd - nalStart, visitor, context);
            offset = nalEnd;
        }
        return count;
    }
    if (nalLengthSize < 1 || nalLengthSize > 4) return 0;
    size_t offset = 0;
    while (offset + (size_t) nalLengthSize <= size) {
        size_t nalSize = 0;
        for (int index = 0; index < nalLengthSize; ++index) {
            nalSize = (nalSize << 8) | data[offset + (size_t) index];
        }
        offset += (size_t) nalLengthSize;
        if (nalSize == 0 || nalSize > size - offset) break;
        count += R2CDJIVisitSEINAL(data + offset, nalSize, visitor, context);
        offset += nalSize;
    }
    return count;
}

static inline bool R2CDJIDecodeAnnexBPacket(
    const uint8_t *data,
    size_t size,
    R2CDJICameraTelemetry *telemetry
) {
    size_t offset = 0;
    while (offset < size) {
        size_t startCodeSize = 0;
        while (offset < size &&
               (startCodeSize = R2CDJIStartCodeLength(data + offset, size - offset)) == 0) {
            ++offset;
        }
        if (startCodeSize == 0) return false;
        size_t nalStart = offset + startCodeSize;
        size_t nalEnd = nalStart;
        while (nalEnd < size && R2CDJIStartCodeLength(data + nalEnd, size - nalEnd) == 0) {
            ++nalEnd;
        }
        if (R2CDJIDecodeSEINAL(data + nalStart, nalEnd - nalStart, telemetry)) return true;
        offset = nalEnd;
    }
    return false;
}

static inline bool R2CDJIDecodeLengthPrefixedPacket(
    const uint8_t *data,
    size_t size,
    int nalLengthSize,
    R2CDJICameraTelemetry *telemetry
) {
    if (nalLengthSize < 1 || nalLengthSize > 4) return false;
    size_t offset = 0;
    while (offset + (size_t) nalLengthSize <= size) {
        size_t nalSize = 0;
        for (int index = 0; index < nalLengthSize; ++index) {
            nalSize = (nalSize << 8) | data[offset + (size_t) index];
        }
        offset += (size_t) nalLengthSize;
        if (nalSize == 0 || nalSize > size - offset) return false;
        if (R2CDJIDecodeSEINAL(data + offset, nalSize, telemetry)) return true;
        offset += nalSize;
    }
    return false;
}

static inline bool R2CDJIDecodeH264Packet(
    const uint8_t *data,
    size_t size,
    int nalLengthSize,
    R2CDJICameraTelemetry *telemetry
) {
    if (data == NULL || size == 0 || telemetry == NULL) return false;
    *telemetry = (R2CDJICameraTelemetry) {0};
    if (R2CDJIStartCodeLength(data, size) != 0) {
        return R2CDJIDecodeAnnexBPacket(data, size, telemetry);
    }
    if (R2CDJIDecodeLengthPrefixedPacket(data, size, nalLengthSize, telemetry)) {
        return true;
    }
    for (int candidate = 4; candidate >= 1; --candidate) {
        if (candidate != nalLengthSize &&
            R2CDJIDecodeLengthPrefixedPacket(data, size, candidate, telemetry)) {
            return true;
        }
    }
    return false;
}

#endif
