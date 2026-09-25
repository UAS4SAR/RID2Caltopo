#include "../R2CLiveFrameQueue.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

static int released;
static void dispose(void *p) { free(p); released++; }
static R2CLiveFrame frame(int n, int64_t now) {
    R2CLiveFrame f = {.frame=malloc(1), .bytes=1024, .pts=(int64_t)n*33333,
        .enqueued=now, .sequence=(uint64_t)n};
    f.camera.sequence = n; f.camera.timestamp = f.pts; f.camera.values[0] = n;
    return f;
}
static void display_callback_regression(int callbackUs, bool rateTransition, int sourceUs) {
    R2CLiveFrameQueue q={0}; R2CLiveFrame out;
    int64_t input=1000000, tick=1000000;
    int seq=0, presented=0; uint64_t last=0, startupDrops=0;
    released=0;
    // Exercise constant cadence and a possible 24 -> 60 fps transition with irregular callbacks.
    for(int64_t now=1000000;now<121000000;now+=1000) {
        while(now>=input) {
            R2CLiveFrame f=frame(++seq,now/1000);
            f.pts=input; f.camera.timestamp=input; f.bytes=1385344;
            R2CLiveQueuePush(&q,f,dispose);
            input += rateTransition && now<11000000 ? 41667 : sourceUs;
        }
        if(now <= 3100000) startupDrops = q.dropped;
        if(now>=tick) {
            if(R2CLiveQueuePresent(&q,now/1000,&out,dispose)) {
                assert(out.sequence>last);
                assert(out.camera.sequence==out.sequence && out.camera.timestamp==out.pts);
                last=out.sequence; presented++; dispose(out.frame);
            }
            tick += callbackUs + ((presented%5)==0 ? 1000 : 0);
        }
    }
    // Two seconds of 720p/60 startup exceeds the existing 128 MiB cap.
    // No further memory eviction is allowed after that startup window.
    assert(q.dropped == startupDrops && q.underruns==0);
    assert(R2CLiveQueueSpan(&q)>=350 && R2CLiveQueueSpan(&q)<1000);
    assert(q.rendered==(uint64_t)presented+q.presentationSkipped);
    assert((uint64_t)seq==q.rendered+q.dropped+(uint64_t)q.count);
    assert(presented>2500);
    printf("Source interval %d us, display callback %d us: presented=%d pacedSkipped=%llu overflow=%llu span=%lld ms\n",
        sourceUs,callbackUs,presented,(unsigned long long)q.presentationSkipped,
        (unsigned long long)q.dropped,(long long)R2CLiveQueueSpan(&q));
    R2CLiveQueueClear(&q,dispose); assert(released==seq);
}
int main(void) {
    R2CLiveFrameQueue q = {0}; R2CLiveFrame out;
    // 30 fps arrives in 300 ms bursts; each retained frame must display in order,
    // with its own camera sample, without draining between ordinary bursts.
    int next = 1, displayed = 0, last = 0;
    for (int64_t now=1000; now<21000; now+=5) {
        if ((now-1000)%300 == 0)
            for(int j=0;j<9;j++) R2CLiveQueuePush(&q,frame(next++,now),dispose);
        if (R2CLiveQueuePop(&q,now,&out)) {
            assert(now >= 3000);
            assert(out.sequence == (uint64_t)(last+1));
            assert(out.camera.sequence == out.sequence && out.camera.timestamp == out.pts);
            assert(out.camera.values[0] == out.sequence);
            last = (int)out.sequence; displayed++; dispose(out.frame);
        }
    }
    assert(displayed > 500 && q.underruns == 0 && q.dropped == 0);
    // Drain a genuine outage; low-buffer/stall pacing must slow below source rate.
    bool slowed=false;
    for(int64_t now=21000;now<26000;now+=5) {
        if(R2CLiveQueuePop(&q,now,&out)) dispose(out.frame);
        if(q.renderInterval > q.sourceInterval) slowed=true;
    }
    assert(slowed && q.count == 0 && q.underruns == 1);
    R2CLiveQueueClear(&q,dispose);
    assert(released == next-1);
    // Hard bounds, timestamp reset, and destruction must release every frame.
    q=(R2CLiveFrameQueue){0}; released=0;
    for(int i=1;i<=300;i++) R2CLiveQueuePush(&q,frame(i,1000+i),dispose);
    assert(q.count == 240 && q.dropped == 60);
    R2CLiveQueuePush(&q,frame(1,2000),dispose);
    assert(q.count == 1 && q.resets == 1);
    assert(!R2CLiveQueuePop(&q,2100,&out));
    R2CLiveQueueClear(&q,dispose); assert(released==301);
    q=(R2CLiveFrameQueue){0}; released=0;
    for(int i=1;i<=10;i++) { R2CLiveFrame f=frame(i,1000+i); f.bytes=32u*1024u*1024u; R2CLiveQueuePush(&q,f,dispose); }
    assert(q.count==4 && q.bytes==R2C_LIVE_QUEUE_BYTES && q.dropped==6);
    R2CLiveQueueClear(&q,dispose); assert(released==10);
    // Missing PTS still produces a finite buffer span and steady paced output.
    q=(R2CLiveFrameQueue){0}; released=0;
    for(int i=1;i<=60;i++) { R2CLiveFrame f=frame(i,1000+i*33); f.pts=INT64_MIN; R2CLiveQueuePush(&q,f,dispose); }
    assert(R2CLiveQueueSpan(&q)>0);
    assert(R2CLiveQueuePop(&q,3100,&out)); dispose(out.frame);
    assert(!R2CLiveQueuePop(&q,3100,&out));
    R2CLiveQueueClear(&q,dispose); assert(released==60);
    // A 60 fps source arriving in bursts is not reduced to the old 30 fps cap.
    q=(R2CLiveFrameQueue){0}; released=0; next=1; displayed=0;
    for(int64_t now=1000;now<11000;now+=8) {
        if((now-1000)%200==0) for(int j=0;j<12;j++) {
            R2CLiveFrame f=frame(next++,now); f.pts=f.sequence*16667;
            R2CLiveQueuePush(&q,f,dispose);
        }
        if(R2CLiveQueuePop(&q,now,&out)) { displayed++; dispose(out.frame); }
    }
    assert(displayed>440 && q.underruns==0 && q.dropped==0);
    R2CLiveQueueClear(&q,dispose); assert(released==next-1);
    display_callback_regression(16667, true, 16667);
    display_callback_regression(23810, true, 16667);
    display_callback_regression(33333, true, 16667);
    display_callback_regression(16667, false, 16667);
    display_callback_regression(23810, false, 16667);
    display_callback_regression(16667, false, 33333);
    display_callback_regression(23810, false, 33333);
    display_callback_regression(33333, false, 33333);
    display_callback_regression(16667, false, 41667);
    display_callback_regression(33333, false, 41667);
    puts("Live buffer: burst pacing, stall conservation, FIFO telemetry, underruns, bounds and reset passed");
}
