#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace r2c::mapping {
// One local median pass suppresses isolated stereo heights, without extending
// coverage into unmeasured cells. A majority of the 1 m neighborhood is required.
inline void refineSurface(float* heights,int width,int height,double cell) {
 if(!heights||width<2||height<2||size_t(width)*height>3000000||!std::isfinite(cell)||cell<=0)
  throw std::runtime_error("Invalid surface refinement input.");
 const int radius=std::clamp(int(std::floor(.5/cell)),1,2);
 const std::vector<float> source(heights,heights+size_t(width)*height);
 for(int y=radius;y<height-radius;++y)for(int x=radius;x<width-radius;++x){
  const auto index=y*width+x;if(!std::isfinite(source[index]))continue;
  std::array<float,25> values{};int n=0;
  for(int dy=-radius;dy<=radius;++dy)for(int dx=-radius;dx<=radius;++dx){float v=source[(y+dy)*width+x+dx];if(std::isfinite(v))values[n++]=v;}
  if(n<((2*radius+1)*(2*radius+1)/2+1))continue;
  std::nth_element(values.begin(),values.begin()+n/2,values.begin()+n);
  heights[index]=values[n/2];
 }
}
// Project within one local height surface. Blending a roof corner with ground
// invents a sloping wall and stretches its photograph across that strip. Select
// the nearest corner's height group at such discontinuities; smooth slopes keep
// ordinary bilinear interpolation. Unknown support retains the existing mask.
inline bool sampleSurface(const float* z,int width,int x,int y,double u,double v,double& result){
 const std::array<float,4> h{z[y*width+x],z[y*width+x+1],z[(y+1)*width+x],z[(y+1)*width+x+1]};
 const std::array<double,4> weights{(1-u)*(1-v),u*(1-v),(1-u)*v,u*v};
 double known=0;int nearest=-1;
 for(int i=0;i<4;++i)if(std::isfinite(h[i])){known+=weights[i];if(nearest<0||weights[i]>weights[nearest])nearest=i;}
 if(known<.99||nearest<0)return false;
 double sum=0,weight=0;
 for(int i=0;i<4;++i)if(std::isfinite(h[i])&&std::abs(h[i]-h[nearest])<=1.){sum+=weights[i]*h[i];weight+=weights[i];}
 if(weight<=0)return false;result=sum/weight;return true;
}
// Emit only supported triangles; one absent corner must not discard the other
// triangle. A diagonal across a canopy/roof discontinuity is never forced.
inline std::vector<int32_t> surfaceFaces(const float* z,int width,int height,int stride,const int32_t* ids,size_t count){
 if(!z||!ids||width<2||height<2||size_t(width)*height>3000000||stride<1||stride>=std::min(width,height))throw std::runtime_error("Invalid mesh input.");
 const int cols=(width-1)/stride+1,rows=(height-1)/stride+1;
 if(count!=size_t(cols)*rows)throw std::runtime_error("Invalid mesh vertex count.");
 std::vector<int32_t> out;out.reserve(size_t(cols-1)*(rows-1)*6);
 for(int row=0;row<rows-1;++row)for(int col=0;col<cols-1;++col){
  int at=row*cols+col,src=row*stride*width+col*stride;
  std::array<int32_t,4> v{ids[at],ids[at+1],ids[at+cols],ids[at+cols+1]};
  std::array<float,4> h{z[src],z[src+stride],z[src+stride*width],z[src+stride*width+stride]};
  constexpr int tris[4][3]={{0,1,2},{1,3,2},{0,1,3},{0,3,2}};
  bool valid[4]{};
  for(int t=0;t<4;++t){auto a=tris[t][0],b=tris[t][1],c=tris[t][2];valid[t]=v[a]>=0&&v[b]>=0&&v[c]>=0&&std::isfinite(h[a])&&std::isfinite(h[b])&&std::isfinite(h[c])&&std::max({h[a],h[b],h[c]})-std::min({h[a],h[b],h[c]})<=6;}
  int first=valid[0]+valid[1],second=valid[2]+valid[3];
  int start=second>first||(second==first&&std::abs(h[0]-h[3])<std::abs(h[1]-h[2]))?2:0;
  for(int t=start;t<start+2;++t)if(valid[t])for(int corner:tris[t])out.push_back(v[corner]);
 }
 return out;
}
}
