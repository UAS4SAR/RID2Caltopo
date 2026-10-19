#pragma once
#include <cstdint>
#include <cstddef>
#include <stdexcept>
namespace r2c::mapping {
inline void sampleRGBA(const uint8_t* rgba,int width,int height,int factor,uint8_t* output,size_t capacity){
 if(!rgba||!output||width<1||height<1||width>12000||height>12000||factor<1||factor>16||width%factor||height%factor||capacity!=size_t(width/factor)*(height/factor))throw std::runtime_error("Invalid full-frame raster sample.");
 const int w=width/factor,h=height/factor,n=factor*factor;
 for(int y=0;y<h;++y)for(int x=0;x<w;++x){uint32_t r=0,g=0,b=0;
  for(int yy=0;yy<factor;++yy)for(int xx=0;xx<factor;++xx){const auto* p=rgba+4*(size_t(y*factor+yy)*width+x*factor+xx);r+=p[0];g+=p[1];b+=p[2];}
  r=(r+n/2)/n;g=(g+n/2)/n;b=(b+n/2)/n;
  output[y*w+x]=uint8_t((r*4899+g*9617+b*1868+8192)>>14);
 }
}
}
