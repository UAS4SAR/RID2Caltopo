#include "include/R2CMapping.h"
#include <cassert>
#include <opencv2/features2d.hpp>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <vector>
#include <unistd.h>
static double noise(int x,int y){uint32_t v=uint32_t(x)*374761393u+uint32_t(y)*668265263u;v=(v^(v>>13))*1274126177u;return double((v^(v>>16))&255);}
static double texture(double x,double y){x*=4;y*=4;int xx=int(std::floor(x)),yy=int(std::floor(y));double a=x-xx,b=y-yy;return (1-b)*((1-a)*noise(xx,yy)+a*noise(xx+1,yy))+b*((1-a)*noise(xx,yy+1)+a*noise(xx+1,yy+1));}
int main(int argc,char** argv){
 constexpr int n=9,w=1008,h=756;double focal=700;std::vector<double> cameras,lasers,pitch(n,-90);std::vector<std::string> paths;std::vector<const char*> pointers;
 auto root=argc>1?std::filesystem::path(argv[1]):std::filesystem::temp_directory_path()/std::filesystem::path("r2c-native-pipeline-"+std::to_string(getpid()));std::filesystem::create_directories(root);
 for(int i=0;i<n;++i){double x=(i%3-1)*8,y=(i/3-1)*8;cameras.insert(cameras.end(),{x,y,60,1,0,0,0,-1,0,0,0,-1,.02,.02,.03});lasers.insert(lasers.end(),{x,y,0});
  std::vector<uint8_t> image(w*h);for(int row=0;row<h;++row)for(int col=0;col<w;++col)image[row*w+col]=uint8_t(texture(x+(col-(w-1)*.5)*60/focal,y-(row-(h-1)*.5)*60/focal));
  cv::Mat pixels(h,w,CV_8U,image.data());std::vector<cv::KeyPoint> keys;cv::SIFT::create(4000)->detect(pixels,keys);
  auto best=std::min_element(keys.begin(),keys.end(),[&](auto a,auto b){return std::hypot(a.pt.x-w*.5,a.pt.y-h*.5)<std::hypot(b.pt.x-w*.5,b.pt.y-h*.5);});
  assert(best!=keys.end());lasers[i*3]=x+(best->pt.x-(w-1)*.5)*60/focal;lasers[i*3+1]=y-(best->pt.y-(h-1)*.5)*60/focal;
  auto path=root/(std::to_string(i)+".gray");std::ofstream file(path,std::ios::binary);file.write(reinterpret_cast<char*>(image.data()),image.size());paths.push_back(path.string());}
 if(argc>1){std::ofstream fixture(root/"fixture.json");fixture<<"{\"width\":"<<w<<",\"height\":"<<h<<",\"focal\":"<<focal<<",\"cameras\":[";
  for(int i=0;i<n;++i){if(i)fixture<<",";fixture<<"{\"x\":"<<cameras[i*15]<<",\"y\":"<<cameras[i*15+1]<<",\"laserX\":"<<lasers[i*3]<<",\"laserY\":"<<lasers[i*3+1]<<",\"path\":\""<<i<<".gray\"}";}fixture<<"]}";}
 for(auto& path:paths)pointers.push_back(path.c_str());
 R2CMappingInput input{n,w,h,focal,cameras.data(),lasers.data(),pitch.data(),pointers.data()};R2CMappingResult* result=nullptr;char error[1024]{};int calls=0;
 int ok=R2CMappingReconstruct(&input,[](void* c,const char*,double){++*static_cast<int*>(c);return 1;},&calls,&result,error,sizeof(error));
 if(!ok){std::cerr<<error<<"\n";std::filesystem::remove_all(root);return 1;}
 assert(result&&result->registeredPhotos==n&&result->featureTracks>=500&&result->stereoPairs>0&&result->measuredCells>1000&&calls>100);
 std::cout<<"Synthetic originals: "<<result->registeredPhotos<<" cameras, "<<result->featureTracks<<" tracks, "<<result->stereoPairs<<" stereo pairs, "<<result->measuredCells<<" measured cells\n";
 if(argc>1){
  auto write=[&](const char* name,const void* bytes,size_t length){std::ofstream file(root/name,std::ios::binary);file.write(static_cast<const char*>(bytes),length);};
  write("cameras.f64",cameras.data(),cameras.size()*8);write("lasers.f64",lasers.data(),lasers.size()*8);
  write("adjustment.f64",result->adjustment,result->adjustmentCount*8);write("heights.f32",result->heights,size_t(result->width)*result->height*4);
 }
 R2CMappingResult *aligned=nullptr,*split=nullptr;
 assert(R2CMappingAlign(&input,nullptr,nullptr,&aligned,error,sizeof(error))==1);
 assert(aligned->adjustmentCount==result->adjustmentCount);
 for(size_t i=0;i<result->adjustmentCount;++i)assert(aligned->adjustment[i]==result->adjustment[i]||(std::isnan(aligned->adjustment[i])&&std::isnan(result->adjustment[i])));
 assert(R2CMappingDense(&input,aligned->adjustment,aligned->adjustmentCount,nullptr,nullptr,&split,error,sizeof(error))==1);
 assert(split->width==result->width&&split->height==result->height&&split->x==result->x&&split->y==result->y&&split->cell==result->cell);
 for(int i=0;i<result->width*result->height;++i)assert(split->heights[i]==result->heights[i]||(std::isnan(split->heights[i])&&std::isnan(result->heights[i])));
 R2CMappingFree(split);R2CMappingFree(aligned);
 assert(R2CMappingAlign(&input,[](void*,const char*,double){return 0;},nullptr,&aligned,error,sizeof(error))==0&&aligned==nullptr);
 assert(R2CMappingDense(&input,nullptr,0,nullptr,nullptr,&split,error,sizeof(error))==0&&split==nullptr);
 // Textureless partial coverage must yield the same explicitly approximate result
 // through the iOS full call and Android split calls, without invented depth.
 std::vector<std::vector<char>> savedImages;for(const auto& path:paths){std::ifstream file(path,std::ios::binary);savedImages.emplace_back(std::istreambuf_iterator<char>(file),std::istreambuf_iterator<char>());}
 for(const auto& path:paths){std::ofstream file(path,std::ios::binary);std::vector<uint8_t> blank(w*h,127);file.write(reinterpret_cast<const char*>(blank.data()),blank.size());}
 R2CMappingResult *fallback=nullptr;
 assert(R2CMappingReconstruct(&input,nullptr,nullptr,&fallback,error,sizeof(error))==1);
 assert(fallback->adjustmentCount==size_t(13+n*12)&&fallback->adjustment[fallback->adjustmentCount-1]==-1);
 assert(fallback->measuredCells==0&&fallback->stereoPairs==0&&fallback->featureTracks==0);
 assert(R2CMappingAlign(&input,nullptr,nullptr,&aligned,error,sizeof(error))==1);
 assert(R2CMappingDense(&input,aligned->adjustment,aligned->adjustmentCount,nullptr,nullptr,&split,error,sizeof(error))==1);
 assert(split->width==fallback->width&&split->height==fallback->height);
 int covered=0;
 for(int i=0;i<split->width*split->height;++i){assert(split->heights[i]==fallback->heights[i]||(std::isnan(split->heights[i])&&std::isnan(fallback->heights[i])));covered+=std::isfinite(split->heights[i]);}
 assert(covered>1000);
 // A camera solution can also fail to produce any dense/interpolated patches.
 // That later geometry failure must recover through the split Android path.
 std::vector<double> isolated(aligned->adjustment,aligned->adjustment+aligned->adjustmentCount-1);
 for(int i=0;i<500;++i)isolated.insert(isolated.end(),{0,0,0});isolated.push_back(0);
 R2CMappingResult* late=nullptr;
 assert(R2CMappingDense(&input,isolated.data(),isolated.size(),nullptr,nullptr,&late,error,sizeof(error))==1);
 assert(late->adjustmentCount==size_t(13+n*12)&&late->adjustment[late->adjustmentCount-1]<0&&late->measuredCells==0);
 R2CMappingFree(late);R2CMappingFree(fallback);R2CMappingFree(split);R2CMappingFree(aligned);
 assert(R2CMappingReconstruct(&input,[](void*,const char* stage,double){return int(std::string(stage).find("approximate")==std::string::npos);},nullptr,&fallback,error,sizeof(error))==0&&fallback==nullptr);
 // Missing files are not geometry failures and must not be disguised as recovered maps.
 std::filesystem::remove(paths[0]);
 assert(R2CMappingReconstruct(&input,nullptr,nullptr,&fallback,error,sizeof(error))==0&&fallback==nullptr);
 for(size_t i=0;i<paths.size();++i){std::ofstream file(paths[i],std::ios::binary);file.write(savedImages[i].data(),savedImages[i].size());}
 // The four corners remain present, with identical fixed-point RGB luminance on every platform.
 uint8_t rgba[8*8*4]{};for(int y=0;y<8;++y)for(int x=0;x<8;++x){int i=4*(y*8+x);rgba[i]=(x<4&&y<4)?255:0;rgba[i+1]=(x>=4&&y<4)?255:0;rgba[i+2]=(y>=4)?255:0;}
 uint8_t sampled[4]{};assert(R2CMappingSampleRGBA(rgba,8,8,4,sampled,4,error,sizeof(error))==1);
 assert(sampled[0]==76&&sampled[1]==150&&sampled[2]==29&&sampled[3]==29);
 assert(R2CMappingSampleRGBA(rgba,8,8,3,sampled,4,error,sizeof(error))==0);
 assert(R2CMappingSampleRGBA(rgba,8,8,4,sampled,3,error,sizeof(error))==0);
 std::cout<<"Shared split/full reconstruction identical; cancellation, bounds and full-frame sampling passed\n";
 R2CMappingFree(result);R2CMappingReleasePixels();if(argc==1)std::filesystem::remove_all(root);
 return 0;
}
