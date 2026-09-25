#ifndef R2C_LIVE_FRAME_QUEUE_H
#define R2C_LIVE_FRAME_QUEUE_H
#include "../../../app/src/main/cpp/anomaly_runtime_budget.h"
#include <stddef.h>
#include <string.h>

// Same timing primitives and tuning as Android's ffmpeg_bridge render loop.
// Bounded retained hardware surfaces protect iPad memory during UI suspension.
#define R2C_LIVE_QUEUE_CAPACITY 240
#define R2C_LIVE_QUEUE_BYTES (128u * 1024u * 1024u)
typedef struct {
    double values[20];
    int64_t timestamp;
    uint64_t sequence;
} R2CFrameCamera;
typedef struct {
    void *frame;
    size_t bytes;
    int64_t pts, enqueued;
    uint64_t sequence;
    R2CFrameCamera camera;
} R2CLiveFrame;
typedef struct {
    R2CLiveFrame frames[R2C_LIVE_QUEUE_CAPACITY];
    int head, count;
    size_t bytes;
    int64_t started, lastDecode, lastPTS, nextDue, sourceInterval, renderInterval;
    int64_t stall, provenGap, lastGap, lastDecay, lastProvenDecay, target;
    int64_t cadence[24];
    int cadenceCount, cadenceNext, sourceConfidence;
    int64_t lastSourceUpdate;
    uint64_t rendered, dropped, underruns, resets, presentationSkipped;
    bool startedRendering, empty;
} R2CLiveFrameQueue;

static inline void R2CLiveQueueClear(R2CLiveFrameQueue *q, void (*release)(void *)) {
    while (q->count) {
        R2CLiveFrame *f = &q->frames[q->head];
        release(f->frame);
        q->bytes -= f->bytes;
        memset(f, 0, sizeof(*f));
        q->head = (q->head + 1) % R2C_LIVE_QUEUE_CAPACITY;
        q->count--;
    }
    q->head = 0;
}
static inline int64_t R2CLiveQueueSpan(const R2CLiveFrameQueue *q) {
    if (q->count < 2) return 0;
    const R2CLiveFrame *first = &q->frames[q->head];
    const R2CLiveFrame *last = &q->frames[(q->head + q->count - 1) % R2C_LIVE_QUEUE_CAPACITY];
    if (first->pts >= 0 && last->pts > first->pts)
        return (last->pts - first->pts) / 1000;
    return (q->count - 1) * q->sourceInterval;
}
static inline void R2CLiveQueuePush(R2CLiveFrameQueue *q, R2CLiveFrame f,
                                    void (*release)(void *)) {
    if (q->lastDecode && f.pts >= 0 && q->lastPTS >= 0 &&
        (f.pts < q->lastPTS || f.pts - q->lastPTS > 10000000)) {
        uint64_t resets = q->resets + 1, dropped = q->dropped + q->count;
        uint64_t rendered = q->rendered, underruns = q->underruns, skipped = q->presentationSkipped;
        R2CLiveQueueClear(q, release);
        memset(q, 0, sizeof(*q));
        q->resets = resets; q->dropped = dropped;
        q->rendered = rendered; q->underruns = underruns; q->presentationSkipped = skipped;
    }
    if (!q->started) {
        q->started = f.enqueued;
        q->sourceInterval = q->renderInterval = 33;
        q->stall = q->provenGap = 300;
        q->target = 700;
    }
    if (q->lastDecode) {
        int64_t gap = f.enqueued - q->lastDecode;
        if (anomaly_detector_runtime_budget_decode_delta_is_gap(gap, q->sourceInterval, 33, 150)) {
            q->stall = anomaly_detector_runtime_budget_update_stall_estimate_ms(q->stall, gap, 300, 30, 4, 1800);
            if (gap >= 900)
                q->provenGap = anomaly_detector_runtime_budget_update_proven_gap_ms(q->provenGap, gap, 300, 15, 1800);
            q->lastGap = q->lastDecay = q->lastProvenDecay = f.enqueued;
        }
        int64_t cadence = f.pts >= 0 && q->lastPTS >= 0 ? (f.pts - q->lastPTS) / 1000 : gap;
        if (cadence >= 5 && cadence <= 1000) {
            q->cadence[q->cadenceNext++ % 24] = cadence;
            if (q->cadenceCount < 24) q->cadenceCount++;
            int64_t sorted[24];
            memcpy(sorted, q->cadence, q->cadenceCount * sizeof(int64_t));
            for (int i = 1; i < q->cadenceCount; i++) {
                int64_t v = sorted[i]; int j = i;
                while (j && sorted[j - 1] > v) { sorted[j] = sorted[j - 1]; j--; }
                sorted[j] = v;
            }
            int64_t measuredInterval = sorted[q->cadenceCount / 2];
            // A majority of the rolling PTS samples establishes a real source
            // rate change (e.g. controller preview 24 fps -> flight 60 fps).
            // Relock promptly rather than accumulating seconds of backlog.
            bool rateChanged = q->cadenceCount >= 12 &&
                (measuredInterval * 4 < q->sourceInterval * 3 ||
                 measuredInterval * 2 > q->sourceInterval * 3);
            if (!q->startedRendering || rateChanged || f.enqueued - q->lastSourceUpdate >= 1000) {
                anomaly_detector_runtime_budget_source_interval_estimate_t estimate =
                    anomaly_detector_runtime_budget_apply_pts_source_interval(
                        q->sourceInterval, q->sourceConfidence, sorted[q->cadenceCount / 2],
                        !q->startedRendering || rateChanged, 33, 70, 80, 4, 20, 35, 5, 1000);
                q->sourceInterval = estimate.interval_ms;
                q->sourceConfidence = estimate.confidence;
                q->lastSourceUpdate = f.enqueued;
            }
        }
    }
    q->lastDecode = f.enqueued; q->lastPTS = f.pts;
    if (f.bytes > R2C_LIVE_QUEUE_BYTES) { release(f.frame); q->dropped++; return; }
    while (q->count && (q->count == R2C_LIVE_QUEUE_CAPACITY || q->bytes + f.bytes > R2C_LIVE_QUEUE_BYTES)) {
        R2CLiveFrame *old = &q->frames[q->head];
        release(old->frame); q->bytes -= old->bytes;
        q->head = (q->head + 1) % R2C_LIVE_QUEUE_CAPACITY; q->count--; q->dropped++;
    }
    q->frames[(q->head + q->count++) % R2C_LIVE_QUEUE_CAPACITY] = f;
    q->bytes += f.bytes;
}
static inline bool R2CLiveQueuePop(R2CLiveFrameQueue *q, int64_t now, R2CLiveFrame *out) {
    if (!q->started || now - q->started < 2000) return false;
    if (!q->count) {
        if (q->startedRendering && !q->empty) q->underruns++;
        q->empty = true;
        q->nextDue = 0;
        return false;
    }
    if (q->nextDue && now < q->nextDue) return false;
    if (!q->startedRendering) q->renderInterval = q->sourceInterval;
    if (q->lastGap && now - q->lastGap >= 5000 && now - q->lastDecay >= 1000) {
        q->stall = anomaly_detector_runtime_budget_decay_toward_floor_ms(q->stall, 300, 4);
        q->lastDecay = now;
    }
    if (q->lastGap && now - q->lastGap >= 30000 && now - q->lastProvenDecay >= 5000) {
        q->provenGap = anomaly_detector_runtime_budget_decay_toward_floor_ms(q->provenGap, 300, 2);
        q->lastProvenDecay = now;
    }
    q->target = anomaly_detector_runtime_budget_target_latency_ms(q->stall, q->provenGap, 300, 100, 700, 1800);
    bool stalled = anomaly_detector_runtime_budget_decode_stall_active(now, q->lastDecode, q->sourceInterval, 150, 3);
    q->renderInterval = anomaly_detector_runtime_budget_desired_render_interval_ms(
        q->sourceInterval, q->renderInterval, R2CLiveQueueSpan(q), q->target, stalled, 12, 40, 15).render_interval_ms;
    *out = q->frames[q->head];
    memset(&q->frames[q->head], 0, sizeof(*out));
    q->head = (q->head + 1) % R2C_LIVE_QUEUE_CAPACITY; q->count--; q->bytes -= out->bytes;
    // A display callback is a sampling opportunity, not a sleeping render thread.
    // Android advances past missed timer ticks because its worker can wake at
    // any deadline. Doing that here loses presentation opportunities whenever
    // a callback arrives just after more than one deadline. Retain phase debt
    // (bounded to one interval) so the next callback can make progress.
    int64_t next = (q->nextDue ? q->nextDue : now) + q->renderInterval;
    q->nextDue = next < now - q->renderInterval ? now - q->renderInterval : next;
    q->rendered++; q->startedRendering = true; q->empty = false;
    return true;
}
// Select one presentation for this display callback. If several paced frames
// became due since the preceding callback, consume their timing slots together
// and present the last one, keeping its own camera metadata. Never drain future
// frames or remove the adaptive reserve merely to chase the newest frame.
static inline bool R2CLiveQueuePresent(R2CLiveFrameQueue *q, int64_t now,
                                      R2CLiveFrame *out, void (*release)(void *)) {
    if (!R2CLiveQueuePop(q, now, out)) return false;
    R2CLiveFrame next;
    while (q->count && q->nextDue <= now && R2CLiveQueuePop(q, now, &next)) {
        release(out->frame);
        q->presentationSkipped++;
        *out = next;
    }
    return true;
}
#endif
