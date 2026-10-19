#include "SurfaceRefinement.hpp"
#include <cassert>
#include <iostream>
int main(){
 using namespace r2c::mapping;
 constexpr int w=31,h=31;std::vector<float> plane(w*h),roof(w*h);
 for(int y=0;y<h;++y)for(int x=0;x<w;++x){plane[y*w+x]=.1f*x+.2f*y;roof[y*w+x]=x<15?0:12;}
 auto expected=plane;plane[15*w+15]=40;plane[8*w+8]=NAN;
 refineSurface(plane.data(),w,h,.2);
 assert(std::isnan(plane[8*w+8]));assert(std::abs(plane[15*w+15]-expected[15*w+15])<.11);
 for(int y=10;y<20;++y)for(int x=10;x<20;++x)assert(std::abs(plane[y*w+x]-expected[y*w+x])<.11);
 auto before=roof;refineSurface(roof.data(),w,h,.2);assert(roof==before);
 std::vector<float> z{0,0,0,0};std::vector<int32_t> ids{0,1,2,3};
 assert(surfaceFaces(z.data(),2,2,1,ids.data(),4).size()==6);
 ids[3]=-1;assert(surfaceFaces(z.data(),2,2,1,ids.data(),4).size()==3);
 ids[3]=3;z[3]=20;assert(surfaceFaces(z.data(),2,2,1,ids.data(),4).size()==3);
 z={0,0,20,20};assert(surfaceFaces(z.data(),2,2,1,ids.data(),4).empty());
 z={0,0,0,NAN};assert(surfaceFaces(z.data(),2,2,1,ids.data(),4).size()==3);
 bool rejected=false;try{surfaceFaces(z.data(),2,2,1,ids.data(),3);}catch(...){rejected=true;}assert(rejected);
 double sampled=0;float step[4]={0,0,12,12};
 assert(sampleSurface(step,2,0,0,.3,.49,sampled)&&sampled==0);
 assert(sampleSurface(step,2,0,0,.3,.51,sampled)&&std::abs(sampled-12)<1e-9);
 float slope[4]={0,.2f,.4f,.6f};assert(sampleSurface(slope,2,0,0,.5,.5,sampled)&&std::abs(sampled-.3)<1e-6);
 float missing[4]={0,0,0,NAN};assert(!sampleSurface(missing,2,0,0,.5,.5,sampled));
 std::cout<<"Surface refinement: plane, roof edge, spike, unknown cells, partial faces and discontinuities passed\n";
}
