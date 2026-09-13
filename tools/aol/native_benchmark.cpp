// Build with the shared CAOL sources; see README. This never downloads data.
#include "aol_grid.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <sys/resource.h>
int main(int argc,char**argv) {
    if(argc<3) { std::fprintf(stderr,"usage: native_benchmark source.laz output-prefix [width=600]\n");return 2; }
    const int width=argc>3?std::atoi(argv[3]):600;
    const auto start=std::chrono::steady_clock::now();
    void*g=aol_grid_create(39.2422,-121.0384,-width/2.0,-width/2.0,width,width);
    if(!g)return 2;
    int result=aol_grid_open(g,argv[1]);
    if(result==0)do { result=aol_grid_step(g,10000); }while(result>0);
    const auto decoded=std::chrono::steady_clock::now();
    if(result==0)result=aol_grid_write(g,(std::string(argv[2])+"-surface.f32").c_str(),(std::string(argv[2])+"-ground.f32").c_str());
    struct rusage usage;getrusage(RUSAGE_SELF,&usage);
    std::printf("{\"points\":%llu,\"cells\":%d,\"missing\":%llu,\"decodeSeconds\":%.3f,\"peakRSSBytes\":%ld,\"status\":%d}\n",(unsigned long long)aol_grid_read(g),width*width,(unsigned long long)aol_grid_missing(g),std::chrono::duration<double>(decoded-start).count(),usage.ru_maxrss,result);
    if(result<0)std::fprintf(stderr,"%s\n",aol_grid_error(g));aol_grid_free(g);return result<0?1:0;
}
