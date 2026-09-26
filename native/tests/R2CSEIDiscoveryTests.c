#include "../R2CSEIDiscovery.h"
#include <assert.h>
static unsigned lines, visits; static size_t logged;
static void logLine(const char *line) {
    assert(strlen(line)<960); lines++; logged+=strlen(line)+128;
    const char *b=strstr(line," bytes="), *h=strstr(line," hex=");
    if(b) { size_t n=0; assert(sscanf(b," bytes=%zu",&n)==1 && h); assert(strlen(h+5)==2*n); }
}
static void visit(size_t type,const uint8_t *p,size_t n,void *c) { (void)c; assert(type==128 && n==3000 && p[2999]==42); visits++; }
int main(void) {
    R2CSEIDiscovery s={0}; R2CSEIReset(&s);
    uint8_t p[65537]={0};
    for(int i=0;i<100;i++) { p[0]=(uint8_t)i; R2CSEICapture(&s,42,p,513,"test",i,logLine); }
    assert(s.count==1 && s.kinds[0].seen==100 && s.kinds[0].saved==20 && lines==60);
    R2CSEIReset(&s); lines=0; logged=0;
    uint8_t tlv[]={4,0,1,0,10};
    R2CSEICapture(&s,245,tlv,5,"test",0,logLine); tlv[4]=20;
    R2CSEICapture(&s,245,tlv,5,"test",0,logLine); assert(s.count==1);
    tlv[0]=99; R2CSEICapture(&s,245,tlv,5,"test",0,logLine); assert(s.count==2);
    for(int i=0;i<100;i++) R2CSEICapture(&s,(size_t)i+500,p,65536,"test",0,logLine);
    assert(s.count==64 && s.unclassified>0 && s.budgetSkipped>0 && s.bytes<=R2C_SEI_BYTES);
    R2CSEISummary(&s,logLine); assert(logged<=R2C_SEI_BYTES);
    R2CSEIReset(&s); assert(s.count==0); unsigned before=lines;
    R2CSEICapture(&s,77,p,sizeof(p),"test",0,logLine); assert(s.budgetSkipped==1 && lines==before);
    // Large SEI, extended lengths, valid payload type 128 (not RBSP trailing bits).
    uint8_t nal[3015]; size_t k=0; nal[k++]=6; nal[k++]=128;
    for(int i=0;i<11;i++) nal[k++]=255; nal[k++]=195;
    memset(nal+k,42,3000); k+=3000; nal[k++]=128;
    assert(R2CDJIVisitSEINAL(nal,k,visit,NULL)==1 && visits==1);
    assert(R2CDJIVisitSEINAL(nal,4,visit,NULL)==0);
    puts("SEI discovery: quotas, layout changes, complete chunks, budget, reset and large payload parsing passed");
}
