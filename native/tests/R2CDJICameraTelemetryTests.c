#include "../R2CDJICameraTelemetry.h"

#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void put_u16_le(uint8_t *bytes, uint16_t value) {
    bytes[0] = (uint8_t) value;
    bytes[1] = (uint8_t) (value >> 8);
}

static void put_u32_le(uint8_t *bytes, uint32_t value) {
    bytes[0] = (uint8_t) value;
    bytes[1] = (uint8_t) (value >> 8);
    bytes[2] = (uint8_t) (value >> 16);
    bytes[3] = (uint8_t) (value >> 24);
}

static void put_split_i32_le(uint8_t *bytes, size_t lowOffset, size_t highOffset, int32_t value) {
    uint32_t raw = (uint32_t) value;
    put_u16_le(bytes + lowOffset, (uint16_t) raw);
    put_u16_le(bytes + highOffset, (uint16_t) (raw >> 16));
}

typedef struct SEIVisitResult {
    size_t count;
    size_t payloadType;
    size_t payloadSize;
} SEIVisitResult;

static void visit_sei_payload(
    size_t payloadType,
    const uint8_t *payload,
    size_t payloadSize,
    void *context
) {
    assert(payload != NULL);
    SEIVisitResult *result = context;
    result->count += 1;
    result->payloadType = payloadType;
    result->payloadSize = payloadSize;
}

static size_t make_payload(uint8_t *payload) {
    size_t offset = 0;
    put_u16_le(payload + offset, 9); put_u16_le(payload + offset + 2, 17); offset += 4;
    payload[offset] = 0x91;
    for (uint32_t index = 0; index < 4; ++index) put_u32_le(payload + offset + 1 + index * 4, 1000 + index);
    offset += 17;
    put_u16_le(payload + offset, 6); put_u16_le(payload + offset + 2, 9); offset += 4;
    payload[offset] = 0x61;
    put_u32_le(payload + offset + 1, 6001);
    put_u32_le(payload + offset + 5, 6002);
    offset += 9;
    put_u16_le(payload + offset, 10); put_u16_le(payload + offset + 2, 13); offset += 4;
    memset(payload + offset, 0, 13);
    payload[offset] = 0x01;
    put_u32_le(payload + offset + 1, 9652);
    put_u32_le(payload + offset + 5, 5429);
    put_u32_le(payload + offset + 9, 6720);
    offset += 13;
    put_u16_le(payload + offset, 4); put_u16_le(payload + offset + 2, 39); offset += 4;
    memset(payload + offset, 0, 39);
    put_u32_le(payload + offset + 3, (uint32_t) (int32_t) (111.46 * 10000000.0));
    put_u32_le(payload + offset + 11, (uint32_t) (53.0 / 360.0 * 4294967296.0));
    put_split_i32_le(payload + offset, 15, 21, 98661);
    put_split_i32_le(payload + offset, 17, 23, -50350);
    put_split_i32_le(payload + offset, 19, 25, -577409);
    put_u32_le(payload + offset + 27, (uint32_t) (int32_t) (39.153083 * 4294967296.0 / 180.0));
    put_u32_le(payload + offset + 31, (uint32_t) (int32_t) (-121.132845 * 4294967296.0 / 360.0));
    put_u32_le(payload + offset + 35, (uint32_t) (int32_t) (-574595));
    offset += 39;
    return offset;
}

static void test_recorded_heading_wrap(void) {
    uint8_t payload[128] = {0};
    size_t size = make_payload(payload);
    const size_t attitudeOffset = 55;
    const int32_t recorded[] = {1799354051, -1793949732};
    const double expected[] = {179.9354051, 180.6050268};
    double headings[2];
    for (size_t i = 0; i < 2; ++i) {
        put_u32_le(payload + attitudeOffset + 3, (uint32_t) recorded[i]);
        R2CDJICameraTelemetry sample = {0};
        assert(R2CDJIDecodeType245Payload(payload, size, &sample));
        headings[i] = sample.azimuthDegrees;
        assert(fabs(headings[i] - expected[i]) < 0.0000001);
    }
    assert(fabs(headings[1] - headings[0] - 0.6696217) < 0.0000001);
}

static size_t hex_to_bytes(const char *hex, uint8_t *bytes, size_t capacity) {
    size_t length = strlen(hex) / 2;
    assert(length <= capacity);
    for (size_t index = 0; index < length; ++index) {
        unsigned int value = 0;
        assert(sscanf(hex + index * 2, "%2x", &value) == 1);
        bytes[index] = (uint8_t) value;
    }
    return length;
}

typedef struct RealOpticsVector {
    const char *label;
    const char *tag10Hex;
    uint32_t width;
    uint32_t height;
    uint32_t focal;
    double horizontal;
    double vertical;
    const char *payloadHex;
    double referenceLatitude;
    double referenceLongitude;
    int32_t north;
    int32_t east;
    int32_t down;
} RealOpticsVector;

// Real type-245 payloads from the 2026-10-06 08:49:35 PT M4TD ground zoom sweep
// (sei245_timeseries.csv), one per camera / zoom step.
static const RealOpticsVector realVectors[] = {
    {"wide 1x", "01b425000035150000401a0000", 9652, 5429, 6720, 71.368395, 43.991846,
     "0900110000f6082b09f608d3f609f72b0909f7d3f6060009000000000000ffffffff0a000d0001b425000035150000401a000004002700230200b763675f5cf2ffffd2d0a43526005fff41830000fffff7ffba2eaf371272dca94183f7ff0000",
     39.15302817709744, -121.13280622288585, 38, -161, -556223},
    {"IR", "01001e000000180000e02e0000", 7680, 6144, 12000, 35.489343, 28.718673,
     "090011000000003a000000c6ffffff3a00ffffc6ff060009000000260000ffb3ffff0a000d0001001e000000180000e02e00000400270021f9ff20866d5fbcbdffffb3eea4350500d4ffef820000fffff7ff872faf379271dca9ef82f7ff00000000",
     39.15303676854819, -121.1328169517219, 5, -44, -556305},
    {"medium 3x", "018020000047120000964b0000", 8320, 4679, 19350, 24.266345, 13.787700,
     "0900110000341da1235b1cced9c4e31626ebe243dc060009000000000000ffffffff0a000d00018020000047120000964b00000400270020f8ff4692685f938effffb4f2a4351b0083ff29830000fffff7ffe02eaf37f971dca92983f7ff0000",
     39.15302976965904, -121.13280831836164, 27, -125, -556247},
    {"tele 7x", "01771f0000b2110000409c0000", 8055, 4530, 40000, 11.499183, 6.481825,
     "0900110000fc105f0b490e17ecb5f1e71302ef9ff4060009000000000000ffffffff0a000d0001771f0000b2110000409c00000400270020f8ff51f26c5f42eefffff4d2a4350300cafffa820000fffff7ff5c2faf37b071dca9fa82f7ff0000",
     39.15303496643901, -121.13281443715096, 3, -54, -556294},
    {"tele 112x", "010402000022010000409c0000", 516, 290, 40000, 0.739105, 0.415393,
     "0900110000b3713d735b71bb8d7e8e3f74268ebc8e060009000000000000ffffffff0a000d00010402000022010000409c0000040027002106000841675fa5d1ffffd3d8a435590048febc830000fffff7ff6c2eaf37e071dca9bd83f7ff0000",
     39.1530249081552, -121.13281041383743, 89, -440, -556100},
};

static void test_real_tag10_optics_vectors(void) {
    for (size_t index = 0; index < sizeof(realVectors) / sizeof(realVectors[0]); ++index) {
        const RealOpticsVector *vector = &realVectors[index];
        uint8_t optics[13] = {0};
        assert(hex_to_bytes(vector->tag10Hex, optics, sizeof(optics)) == 13);
        uint32_t width = 0, height = 0, focal = 0;
        double horizontal = 0, vertical = 0;
        assert(R2CDJIDecodeOptics(optics, 13, &width, &height, &focal, &horizontal, &vertical));
        assert(width == vector->width && height == vector->height && focal == vector->focal);
        assert(fabs(horizontal - vector->horizontal) < 0.000001);
        assert(fabs(vertical - vector->vertical) < 0.000001);
        // Never the old degrees*256 reading (wide 37.70, IR 30.00, 112x 2.02).
        assert(fabs(horizontal - (double) vector->width / 256.0) > 0.5);

        uint8_t payload[128] = {0};
        size_t payloadSize = hex_to_bytes(vector->payloadHex, payload, sizeof(payload));
        R2CDJICameraTelemetry decoded = {0};
        assert(R2CDJIDecodeType245Payload(payload, payloadSize, &decoded));
        assert(decoded.valid);
        assert(fabs(decoded.horizontalFovDegrees - vector->horizontal) < 0.000001);
        assert(fabs(decoded.verticalFovDegrees - vector->vertical) < 0.000001);
        assert(decoded.sensorWidthMicrometers == vector->width);
        assert(decoded.focalLengthMicrometers == vector->focal);
        // Tag-4 offsets 27/31 are the N/E/Down reference (home), kept separate from N/E.
        assert(decoded.positionValid);
        assert(fabs(decoded.latitudeDegrees - vector->referenceLatitude) < 0.0000000001);
        assert(fabs(decoded.longitudeDegrees - vector->referenceLongitude) < 0.0000000001);
        assert(decoded.relativeNorthMillimeters == vector->north);
        assert(decoded.relativeEastMillimeters == vector->east);
        assert(decoded.downMillimeters == vector->down);
    }
}

static void test_implausible_optics_are_rejected(void) {
    uint8_t optics[13] = {0};
    double horizontal = -1;
    assert(hex_to_bytes("01b425000035150000401a0000", optics, sizeof(optics)) == 13);
    assert(R2CDJIDecodeOptics(optics, 13, NULL, NULL, NULL, &horizontal, NULL));
    assert(!R2CDJIDecodeOptics(optics, 12, NULL, NULL, NULL, NULL, NULL));
    uint8_t zeroFocal[13]; memcpy(zeroFocal, optics, 13); put_u32_le(zeroFocal + 9, 0);
    assert(!R2CDJIDecodeOptics(zeroFocal, 13, NULL, NULL, NULL, NULL, NULL));
    uint8_t wrongHeader[13]; memcpy(wrongHeader, optics, 13); wrongHeader[0] = 0x00;
    assert(!R2CDJIDecodeOptics(wrongHeader, 13, NULL, NULL, NULL, NULL, NULL));
    uint8_t zeroWidth[13]; memcpy(zeroWidth, optics, 13); put_u32_le(zeroWidth + 1, 0);
    assert(!R2CDJIDecodeOptics(zeroWidth, 13, NULL, NULL, NULL, NULL, NULL));
    uint8_t hugeHeight[13]; memcpy(hugeHeight, optics, 13); put_u32_le(hugeHeight + 5, 0xffffffffu);
    assert(!R2CDJIDecodeOptics(hugeHeight, 13, NULL, NULL, NULL, NULL, NULL));
    uint8_t tinyFocal[13]; memcpy(tinyFocal, optics, 13); put_u32_le(tinyFocal + 9, 1);
    assert(!R2CDJIDecodeOptics(tinyFocal, 13, NULL, NULL, NULL, NULL, NULL));

    // A whole payload with zero focal length is rejected rather than drawing a bogus wedge.
    uint8_t payload[128] = {0};
    size_t payloadSize = make_payload(payload);
    const size_t opticsOffset = 4 + 17 + 4 + 9 + 4;
    assert(payload[opticsOffset] == 0x01);
    put_u32_le(payload + opticsOffset + 9, 0);
    R2CDJICameraTelemetry decoded = {0};
    assert(!R2CDJIDecodeType245Payload(payload, payloadSize, &decoded));
}

int main(int argc, char **argv) {
    test_recorded_heading_wrap();
    test_real_tag10_optics_vectors();
    test_implausible_optics_are_rejected();
    uint8_t payload[128] = {0};
    size_t payloadSize = make_payload(payload);
    R2CDJICameraTelemetry decoded = {0};
    assert(R2CDJIDecodeType245Payload(payload, payloadSize, &decoded));
    assert(decoded.type245PayloadSize == payloadSize);
    assert(memcmp(decoded.type245Payload, payload, payloadSize) == 0);
    assert(decoded.valid);
    assert(fabs(decoded.azimuthDegrees - 111.46) < 0.001);
    assert(decoded.relativeDisplacementValid);
    assert(decoded.relativeNorthMillimeters == 98661);
    assert(decoded.relativeEastMillimeters == -50350);
    assert(decoded.downMillimeters == -577409);
    assert(decoded.relativeNorthMillimetersRaw == -32411);
    assert(decoded.relativeEastMillimetersRaw == 15186);
    assert(decoded.relativeDownMillimetersRaw == 12415);
    assert(fabs(decoded.relativeUpMeters - 2.814) < 0.000001);
    assert(fabs(decoded.tiltDegrees - (-37.0)) < 0.001);
    // Wide camera: 9.652 x 5.429 mm active sensor, 6.72 mm focal length.
    assert(fabs(decoded.horizontalFovDegrees - 71.368395) < 0.000001);
    assert(fabs(decoded.verticalFovDegrees - 43.991846) < 0.000001);
    assert(decoded.sensorWidthMicrometers == 9652);
    assert(decoded.sensorHeightMicrometers == 5429);
    assert(decoded.focalLengthMicrometers == 6720);
    assert(decoded.positionValid);
    assert(fabs(decoded.latitudeDegrees - 39.153083) < 0.000001);
    assert(fabs(decoded.longitudeDegrees - (-121.132845)) < 0.000001);
    assert(fabs(decoded.altitudeMeters - 574.595) < 0.000001);
    assert(decoded.packedTag6Valid && decoded.packedTag6Header == 0x61);
    assert(decoded.packedTag6Words[0] == 6001 && decoded.packedTag6Words[1] == 6002);
    assert(decoded.packedTag9Valid && decoded.packedTag9Header == 0x91);
    assert(decoded.packedTag9Words[0] == 1000 && decoded.packedTag9Words[3] == 1003);
    char payloadHex[sizeof(payload) * 2 + 1] = {0};
    assert(R2CDJIHexEncode(
        decoded.type245Payload,
        decoded.type245PayloadSize,
        payloadHex,
        sizeof(payloadHex)
    ));
    assert(strlen(payloadHex) == decoded.type245PayloadSize * 2);
    assert(strncmp(payloadHex, "09001100", 8) == 0);
    char tooSmall[4] = {0};
    assert(!R2CDJIHexEncode(payload, 2, tooSmall, sizeof(tooSmall)));

    uint8_t nal[160] = {0x06, 245, 0};
    nal[2] = (uint8_t) payloadSize;
    memcpy(nal + 3, payload, payloadSize);
    nal[3 + payloadSize] = 0x80;
    size_t nalSize = payloadSize + 4;

    uint8_t avcc[180] = {0};
    avcc[3] = (uint8_t) nalSize;
    memcpy(avcc + 4, nal, nalSize);
    assert(R2CDJIDecodeH264Packet(avcc, nalSize + 4, 4, &decoded));
    SEIVisitResult visitResult = {0};
    assert(R2CDJIVisitH264SEIPayloads(
        avcc, nalSize + 4, 4, visit_sei_payload, &visitResult) == 1);
    assert(visitResult.count == 1);
    assert(visitResult.payloadType == 245);
    assert(visitResult.payloadSize == payloadSize);

    uint8_t twoByteLengths[180] = {0};
    twoByteLengths[0] = (uint8_t) (nalSize >> 8);
    twoByteLengths[1] = (uint8_t) nalSize;
    memcpy(twoByteLengths + 2, nal, nalSize);
    assert(R2CDJIDecodeH264Packet(twoByteLengths, nalSize + 2, 4, &decoded));

    uint8_t annexB[180] = {0, 0, 0, 1};
    memcpy(annexB + 4, nal, nalSize);
    assert(R2CDJIDecodeH264Packet(annexB, nalSize + 4, 4, &decoded));

    payload[2] = 0xff;
    payload[3] = 0xff;
    assert(!R2CDJIDecodeType245Payload(payload, payloadSize, &decoded));

    if (argc == 2) {
        FILE *input = fopen(argv[1], "rb");
        assert(input != NULL);
        assert(fseek(input, 0, SEEK_END) == 0);
        long fileSize = ftell(input);
        assert(fileSize > 0);
        assert(fseek(input, 0, SEEK_SET) == 0);
        uint8_t *fileBytes = malloc((size_t) fileSize);
        assert(fileBytes != NULL);
        assert(fread(fileBytes, 1, (size_t) fileSize, input) == (size_t) fileSize);
        fclose(input);
        assert(R2CDJIDecodeH264Packet(fileBytes, (size_t) fileSize, 4, &decoded));
        printf(
            "azimuth=%.3f tilt=%.3f fov=%.3fx%.3f\n",
            decoded.azimuthDegrees,
            decoded.tiltDegrees,
            decoded.horizontalFovDegrees,
            decoded.verticalFovDegrees
        );
        free(fileBytes);
    }
    return 0;
}
