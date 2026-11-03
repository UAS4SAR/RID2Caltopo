#pragma once
#include <algorithm>
#include <cmath>
namespace r2c::mapping {
// Axis-aligned local ENU rectangle in metres. Empty (default) = infinite west/south, so min/max accumulate.
struct Box {
 double west=INFINITY,south=INFINITY,east=-INFINITY,north=-INFINITY;
 void add(double x,double y){west=std::min(west,x);east=std::max(east,x);south=std::min(south,y);north=std::max(north,y);}
 bool valid()const{return std::isfinite(west)&&std::isfinite(south)&&std::isfinite(east)&&std::isfinite(north)&&east>=west&&north>=south;}
};
// A clip is used only when all four bounds are finite and strictly ordered; zero-initialised C structs are unused.
inline bool clipUsable(const Box& c){return std::isfinite(c.west)&&std::isfinite(c.south)&&std::isfinite(c.east)&&std::isfinite(c.north)&&c.east>c.west&&c.north>c.south;}
struct SurfaceExtent { bool fits=false,sparseFallback=false;double x=0,y=0;int width=0,height=0; };
// Grid for the union of photo footprints (padded, then clipped). If the footprint is unusable or exceeds maxCells,
// falls back to the sparse bbox +/- sparsePad (never clipped, as before); `fits` is false if that also exceeds maxCells.
inline SurfaceExtent surfaceExtent(const Box& footprint,const Box& sparse,const Box& clip,double cell,double footprintPad,double sparsePad,double maxCells){
 auto grid=[&](Box b,bool fallback){SurfaceExtent e;e.sparseFallback=fallback;e.x=std::floor(b.west/cell)*cell;e.y=std::floor(b.south/cell)*cell;
  double w=std::ceil((b.east-e.x)/cell)+1,h=std::ceil((b.north-e.y)/cell)+1;
  if(std::isfinite(w*h)&&w>=1&&h>=1&&w*h<=maxCells){e.fits=true;e.width=int(w);e.height=int(h);}return e;};
 if(footprint.valid()){
  Box b{footprint.west-footprintPad,footprint.south-footprintPad,footprint.east+footprintPad,footprint.north+footprintPad};
  if(clipUsable(clip)){b.west=std::max(b.west,clip.west);b.south=std::max(b.south,clip.south);b.east=std::min(b.east,clip.east);b.north=std::min(b.north,clip.north);}
  if(b.valid()){auto e=grid(b,false);if(e.fits)return e;}
 }
 if(!sparse.valid())return {};
 return grid(Box{sparse.west-sparsePad,sparse.south-sparsePad,sparse.east+sparsePad,sparse.north+sparsePad},true);
}
}
