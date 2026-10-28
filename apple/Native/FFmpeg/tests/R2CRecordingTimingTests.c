// Host integration test: link the production normalizer against host FFmpeg.
#include "../R2CFFmpegMobile.h"
#include "../R2CH264Packet.h"
#include <libavformat/avformat.h>
#include <libavutil/mathematics.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

typedef struct { int64_t pts, dts; } PictureTime;

static int read_times(const char *path, PictureTime *times, int capacity, int64_t *duration) {
    AVFormatContext *input = NULL;
    assert(avformat_open_input(&input, path, NULL, NULL) == 0);
    assert(avformat_find_stream_info(input, NULL) >= 0);
    int video = av_find_best_stream(input, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    assert(video >= 0);
    AVStream *stream = input->streams[video];
    int lengthSize = stream->codecpar->extradata_size >= 5
        ? (stream->codecpar->extradata[4] & 3) + 1 : 4;
    *duration = av_rescale_q(stream->duration, stream->time_base, AV_TIME_BASE_Q);
    AVPacket *packet = av_packet_alloc();
    int count = 0;
    while (av_read_frame(input, packet) >= 0) {
        if (packet->stream_index == video && !R2CH264PacketIsSeiOnly(
                R2CH264InspectPacket(packet->data, (size_t)packet->size, lengthSize))) {
            assert(count < capacity);
            times[count++] = (PictureTime){
                av_rescale_q(packet->pts, stream->time_base, AV_TIME_BASE_Q),
                av_rescale_q(packet->dts, stream->time_base, AV_TIME_BASE_Q)
            };
        }
        av_packet_unref(packet);
    }
    av_packet_free(&packet);
    avformat_close_input(&input);
    return count;
}

int main(int argc, char **argv) {
    assert(argc == 3);
    char detail[1024] = {0};
    int result = R2CFFmpegNormalizeRecording(argv[1], argv[2], detail, sizeof(detail));
    if (result != 0) fprintf(stderr, "%s\n", detail);
    assert(result == 0);
    const int capacity = 100000;
    PictureTime *source = calloc((size_t)capacity, sizeof(*source));
    PictureTime *normalized = calloc((size_t)capacity, sizeof(*normalized));
    assert(source != NULL && normalized != NULL);
    int64_t sourceDuration, normalizedDuration;
    int sourceCount = read_times(argv[1], source, capacity, &sourceDuration);
    int normalizedCount = read_times(argv[2], normalized, capacity, &normalizedDuration);
    assert(sourceCount > 0 && sourceCount == normalizedCount);
    assert(llabs(sourceDuration - normalizedDuration) <= 100);
    int64_t largestAdjustment = 0;
    for (int index = 0; index < sourceCount; ++index) {
        int64_t ptsDifference = llabs(source[index].pts - normalized[index].pts);
        int64_t dtsDifference = llabs(source[index].dts - normalized[index].dts);
        // Permit sub-millisecond duplicate-timestamp repair, never a new cadence.
        assert(ptsDifference <= 1000 && dtsDifference <= 1000);
        if (ptsDifference > largestAdjustment) largestAdjustment = ptsDifference;
        if (index > 0) assert(normalized[index].dts > normalized[index - 1].dts);
    }
    printf("Preserved %d pictures, duration=%lld us, maxTimestampAdjustment=%lld us: %s\n",
           sourceCount, (long long)normalizedDuration, (long long)largestAdjustment, detail);
    free(source);
    free(normalized);
    return 0;
}
