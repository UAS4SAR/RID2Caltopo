#ifndef R2C_SEI_DISCOVERY_H
#define R2C_SEI_DISCOVERY_H
#include "R2CDJICameraTelemetry.h"
#include <stdio.h>
#include <string.h>
// Caller serializes access. Only complete samples are admitted; metadata and
// log-envelope headroom are included in the budget. Never key on changing values.
#define R2C_SEI_TYPES 64
#define R2C_SEI_BYTES (2u * 1024u * 1024u)
typedef void (*R2CSEILog)(const char *line);
typedef struct { size_t type, length; uint64_t layout, seen; unsigned saved; } R2CSEIKind;
typedef struct {
    R2CSEIKind kinds[R2C_SEI_TYPES]; unsigned count, window;
    size_t bytes; uint64_t unclassified, budgetSkipped, sampleID;
} R2CSEIDiscovery;
static inline uint64_t R2CSEIHash(uint64_t h, size_t v) {
    for (unsigned i=0;i<8;i++) { h=(h^(v&255))*1099511628211ULL; v>>=8; } return h;
}
static inline uint64_t R2CSEILayout(size_t type,const uint8_t *p,size_t n) {
    uint64_t h=1469598103934665603ULL;
    if(type==5 && n>=16) for(size_t i=0;i<16;i++) h=R2CSEIHash(h,p[i]);
    if(type==245) {
        size_t o=0;
        while(o+4<=n) {
            unsigned tag=p[o]|((unsigned)p[o+1]<<8), len=p[o+2]|((unsigned)p[o+3]<<8);
            h=R2CSEIHash(R2CSEIHash(h,tag),len); o+=4;
            if(!tag&&!len) break;
            if(len>n-o) { h=R2CSEIHash(h,SIZE_MAX); break; } o+=len;
        }
    }
    return h;
}
static inline void R2CSEISummary(R2CSEIDiscovery *s,R2CSEILog log) {
    char line[512];
    snprintf(line,sizeof(line),"SEI_CAPTURE_SUMMARY window=%u layouts=%u textBudgetUsed=%zu unclassified=%llu budgetSkipped=%llu",s->window,s->count,s->bytes,(unsigned long long)s->unclassified,(unsigned long long)s->budgetSkipped); log(line);
    for(unsigned i=0;i<s->count;i++) {
        R2CSEIKind *k=&s->kinds[i];
        snprintf(line,sizeof(line),"SEI_KIND window=%u type=%zu len=%zu layout=%016llx seen=%llu saved=%u",s->window,k->type,k->length,(unsigned long long)k->layout,(unsigned long long)k->seen,k->saved); log(line);
    }
}
static inline void R2CSEIReset(R2CSEIDiscovery *s) {
    unsigned next=s->window+1; memset(s,0,sizeof(*s)); s->window=next;
    s->bytes=65536; // Reserve summary lines and envelope overhead.
}
static inline void R2CSEICapture(R2CSEIDiscovery *s,size_t type,const uint8_t *p,size_t n,const char *stream,int64_t pts,R2CSEILog log) {
    uint64_t layout=R2CSEILayout(type,p,n); unsigned i;
    for(i=0;i<s->count;i++) if(s->kinds[i].type==type && s->kinds[i].length==n && s->kinds[i].layout==layout) break;
    if(i==s->count) {
        if(i==R2C_SEI_TYPES) { s->unclassified++; return; }
        s->kinds[i]=(R2CSEIKind){.type=type,.length=n,.layout=layout}; s->count++;
    }
    R2CSEIKind *k=&s->kinds[i]; k->seen++;
    if(k->saved>=20) return;
    size_t chunks=n/256+(n%256!=0); if(!chunks) chunks=1;
    if(n>65536 || chunks*1280>R2C_SEI_BYTES-s->bytes) { s->budgetSkipped++; return; }
    s->bytes+=chunks*1280; k->saved++; uint64_t sample=++s->sampleID;
    for(size_t o=0;o<n || (n==0 && o==0);o+=256) {
        size_t len=n-o; if(len>256) len=256;
        char hex[513],line[960]; R2CDJIHexEncode(p+o,len,hex,sizeof(hex));
        snprintf(line,sizeof(line),"SEI_SAMPLE window=%u sample=%llu stream=%.96s ptsUs=%lld type=%zu layout=%016llx example=%u len=%zu offset=%zu bytes=%zu hex=%s",s->window,(unsigned long long)sample,stream,(long long)pts,type,(unsigned long long)layout,k->saved,n,o,len,hex); log(line);
        if(!n) break;
    }
}
#endif
