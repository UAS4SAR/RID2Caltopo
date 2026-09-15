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
    put_u32_le(payload + offset + 1, 9652);
    put_u32_le(payload + offset + 5, 5429);
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

int main(int argc, char **argv) {
    test_recorded_heading_wrap();
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
    assert(fabs(decoded.horizontalFovDegrees - 37.703125) < 0.000001);
    assert(fabs(decoded.verticalFovDegrees - 21.20703125) < 0.000001);
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
