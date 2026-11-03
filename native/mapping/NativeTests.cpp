#include "SurfaceVoteStore.hpp"
#include "PhotoSeams.hpp"
#include "RasterCache.hpp"
#include "include/R2CMapping.h"
#include "SurfaceCore.hpp"
#include "SurfaceExtent.hpp"
#include <cassert>
#include <cmath>
#include <iostream>
#include <vector>
#include <fstream>
#include <unistd.h>
struct Progress : r2c::mapping::Progress {
 int rounds=0;
 bool iteration(int,double)override{return true;}
 void connectivity(int,const std::vector<int>&,const std::vector<int>&,const std::vector<int>&)override{++rounds;}
};
int main(){
 // More source images than the cache permits must never grow mappings with
 // photo count, and revisiting evicted sources must reproduce their pixels.
 {
  r2c::mapping::RasterCache cache(3*16*16*4,3);std::vector<std::string> files;
  for(int i=0;i<330;++i){auto path="/tmp/r2c-raster-cache-"+std::to_string(getpid())+"-"+std::to_string(i);files.push_back(path);std::vector<uint32_t> data(16*16,0xff000000u+i);std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<char*>(data.data()),data.size()*4);}
  for(int round=0;round<2;++round)for(int i=0;i<330;++i){assert(cache.get(files[i],16,16)[255]==0xff000000u+i);assert(cache.mappedBytes()<=3*16*16*4);assert(cache.mappedCount()<=3);}
  bool bad=false;try{cache.get(files[0],17,16);}catch(const std::runtime_error&){bad=true;}assert(bad);
  cache.clear();assert(cache.mappedBytes()==0&&cache.mappedCount()==0);for(const auto& f:files)unlink(f.c_str());
 }
 // Fail safely at the C ABI boundary before touching invalid buffers.
 char error[512]{};R2CMappingResult* result=reinterpret_cast<R2CMappingResult*>(1);
 assert(!R2CMappingReconstruct(nullptr,nullptr,nullptr,&result,error,sizeof(error)));
 assert(result==nullptr&&error[0]);R2CMappingFree(nullptr);
 // A deterministic, non-planar, nine-camera RTK fixture verifies actual adjustment.
 constexpr int n=9,tracks=900;double f=700,cx=503.5,cy=377.5;
 std::vector<double> cameras,observations,lasers(n*3,NAN);
 for(int i=0;i<n;++i){double x=(i%3-1)*8.,y=(i/3-1)*8.;lasers[i*3]=x;lasers[i*3+1]=y;lasers[i*3+2]=2*std::sin(x*.15)*std::cos(y*.12);cameras.insert(cameras.end(),{x,y,60, 1,0,0, 0,-1,0, 0,0,-1, .02,.02,.03});}
 for(int t=0;t<tracks;++t){double x=(t%30-14.5)*1.1,y=(t/30-14.5)*1.1,z=2*std::sin(x*.15)*std::cos(y*.12);
  for(int i=0;i<n;++i){double px=f*(x-cameras[i*15])/(60-z)+cx,py=-f*(y-cameras[i*15+1])/(60-z)+cy;observations.insert(observations.end(),{double(i),double(t),px,py});}}
 Progress progress;auto values=r2c::mapping::adjust(progress,cameras,observations,tracks,1008,756,f,lasers);
 assert(values.size()==13+n*12+tracks*3);assert(values[0]==n);assert(values[7]<.1);assert(progress.rounds>0);
 size_t known=0;for(size_t i=12+n*12;i+3<values.size();i+=3)if(std::isfinite(values[i]))++known;
 assert(known>800);std::cout<<"Shared RTK fixture: "<<known<<" points, RMS "<<values[7]<<" sample pixels\n";
 std::fill(lasers.begin(),lasers.end(),NAN);
 for(int i=0;i<n;++i){cameras[i*15+12]=1;cameras[i*15+13]=1;cameras[i*15+14]=2;}
 auto nominal=r2c::mapping::adjust(progress,cameras,observations,tracks,1008,756,f,lasers);
 assert(nominal.back()==0);assert(nominal[2]==f);assert(nominal[7]<.1);
 double maxHeightError=0;for(int t=0;t<tracks;++t){size_t offset=12+n*12+t*3;if(!std::isfinite(nominal[offset]))continue;
 double expected=2*std::sin((t%30-14.5)*1.1*.15)*std::cos((t/30-14.5)*1.1*.12);
 maxHeightError=std::max(maxHeightError,std::abs(nominal[offset+2]-expected));}
 assert(maxHeightError<.1);std::cout<<"No-laser nominal fixture maximum height error "<<maxHeightError<<" m\n";
 cameras[4*15]+=.8;
 auto noisy=r2c::mapping::adjust(progress,cameras,observations,tracks,1008,756,f,lasers);
 assert(noisy[9]>.5);assert(noisy[7]<.1);
 double noisyHeightError=0;for(int t=0;t<tracks;++t){size_t offset=12+n*12+t*3;if(!std::isfinite(noisy[offset]))continue;
 double expected=2*std::sin((t%30-14.5)*1.1*.15)*std::cos((t/30-14.5)*1.1*.12);
 noisyHeightError=std::max(noisyHeightError,std::abs(noisy[offset+2]-expected));}
 assert(noisyHeightError<.3);std::cout<<"Noisy GNSS fixture maximum height error "<<noisyHeightError<<" m\n";
 // Narrow-angle GNSS initialization: sub-metre priors produce >24 px error,
 // but image-supported adjustment must still recover the surface without
 // relaxing final reprojection or position qualification.
 {
  const double teleFocal=4800;std::vector<double> teleCameras,teleObservations;
  for(int i=0;i<n;++i){double x=(i%3-1)*1.5,y=(i/3-1)*1.5;
   teleCameras.insert(teleCameras.end(),{x+.8*std::sin(i*2.),y+.8*std::cos(i*3.),60,1,0,0,0,-1,0,0,0,-1,1,1,2});}
  for(int t=0;t<tracks;++t){double x=(t%30-14.5)*.12,y=(t/30-14.5)*.12,z=.3*std::sin(x)*std::cos(y);
   for(int i=0;i<n;++i)teleObservations.insert(teleObservations.end(),{double(i),double(t),teleFocal*(x-(i%3-1)*1.5)/(60-z)+cx,-teleFocal*(y-(i/3-1)*1.5)/(60-z)+cy});}
  auto tele=r2c::mapping::adjust(progress,teleCameras,teleObservations,tracks,1008,756,teleFocal,std::vector<double>(n*3,NAN));
  assert(tele[0]==n&&tele[7]<.1&&tele.back()==0);
  std::cout<<"Telephoto noisy GNSS fixture: "<<tele[0]<<" cameras, RMS "<<tele[7]<<" sample pixels\n";
 }
 // Verify actual tile projection applies nonzero radial calibration. This tests
 // the rendering path, not whether a flight's estimated coefficients are correct.
 char path[]="/tmp/r2c-calibration-pixels-XXXXXX";int fd=mkstemp(path);assert(fd>=0);close(fd);
 std::vector<uint32_t> rgba(1008*756);
 for(int y=0;y<756;++y)for(int x=0;x<1008;++x)rgba[y*1008+x]=0xff000000u|uint32_t(x/4)|(uint32_t(y/3)<<8);
 {std::ofstream file(path,std::ios::binary);file.write(reinterpret_cast<char*>(rgba.data()),rgba.size()*4);assert(file.good());}
 std::vector<float> flat(401*401,0);std::vector<double> camera{1,0,0,0,-1,0,0,0,-1,0,0,60};
 std::vector<int32_t> corrected,uncorrected;
 r2c::mapping::render(flat,401,401,-40,-40,.2,camera,{700,503.5,377.5,-.2,.03},1008,756,{path},20,10,.03,corrected);
 r2c::mapping::render(flat,401,401,-40,-40,.2,camera,{700,503.5,377.5,0,0},1008,756,{path},20,10,.03,uncorrected);
 int index=128*256+128;double nx=(20+128.5*.03)/60,ny=-(10-128.5*.03)/60,rr=nx*nx+ny*ny;
 double expectedRed=(700*nx*(1-.2*rr+.03*rr*rr)+503.5)/4;
 assert(std::abs(double((uint32_t(corrected[index])>>16)&255)-expectedRed)<2);
 assert(std::abs(int((uint32_t(corrected[index])>>16)&255)-int((uint32_t(uncorrected[index])>>16)&255))>=2);
 R2CMappingReleasePixels();
 // A centred oblique view must not displace a nearer-overhead view. Constant
 // colors make source selection observable independently of interpolation.
 char other[]="/tmp/r2c-overhead-pixels-XXXXXX";fd=mkstemp(other);assert(fd>=0);close(fd);
 std::fill(rgba.begin(),rgba.end(),0xff0000ffu);
 {std::ofstream file(path,std::ios::binary);file.write(reinterpret_cast<char*>(rgba.data()),rgba.size()*4);}
 std::fill(rgba.begin(),rgba.end(),0xff00ff00u);
 {std::ofstream file(other,std::ios::binary);file.write(reinterpret_cast<char*>(rgba.data()),rgba.size()*4);}
 double cosine=60./65,sine=25./65;
 std::vector<double> views{cosine,0,-sine,0,-1,0,-sine,0,-cosine,25,0,60, 1,0,0,0,-1,0,0,0,-1,3,0,60};
 r2c::mapping::render(flat,401,401,-40,-40,.2,views,{700,503.5,377.5,0,0},1008,756,{path,other},-1.285,1.285,.01,corrected);
 assert(uint32_t(corrected[index])==0xff00ff00u);
 r2c::mapping::render(flat,401,401,-40,-40,.2,views,{700,503.5,377.5,0,0},1008,756,{path,other},-1.285,1.285,.01,uncorrected,false);
 assert(corrected==uncorrected);
 R2CMappingReleasePixels();unlink(path);unlink(other);
 std::cout<<"Near-overhead source selection and traversal equivalence passed\n";
 std::cout<<"Radial calibration applied to actual 2D projection fixture\n";
 // Identical support fixture is exercised by Android's height consensus test.
 r2c::mapping::SurfaceConsensus votes(2);
 for(float z:{10.f,10.2f,5.f,5.1f,0.f,.1f,0.f,.2f,0.f})votes.add(0,z);
 assert(std::abs(votes.height(0)-.06f)<1e-5f);votes.add(1,3);assert(std::isnan(votes.height(1)));
 // All permutations retain a late-arriving supported fifth surface.
 std::vector<float> permutation{0,2,4,6,8,8};do{auto copy=permutation;assert(r2c::mapping::supportedHeight(copy)==8);}while(std::next_permutation(permutation.begin(),permutation.end()));
 std::vector<float> airgap{0,.1f,12,12.1f};assert(std::abs(r2c::mapping::supportedHeight(airgap)-12.05f)<1e-5f);
 std::sort(airgap.begin(),airgap.end());do{auto copy=airgap;assert(std::abs(r2c::mapping::supportedHeight(copy)-12.05f)<1e-5f);}while(std::next_permutation(airgap.begin(),airgap.end()));
 std::vector<float> noBridge{0,.1f,12,12.1f,12.2f};assert(std::abs(r2c::mapping::supportedHeight(noBridge)-12.1f)<1e-5f);
 auto spoolRoot=std::filesystem::temp_directory_path()/("r2c-vote-test-"+std::to_string(getpid()));std::filesystem::create_directory(spoolRoot);
 {
  r2c::mapping::SurfaceVoteStore spool(90000,6,spoolRoot);
  for(size_t i=0;i<90000;++i)for(float z:{0.f,2.f,4.f,6.f,8.f,8.f})spool.add(i,z);
  auto depths=spool.resolve([](double){});assert(depths.size()==90000);for(float z:depths)assert(z==8);
 }
 assert(std::filesystem::is_empty(spoolRoot));
 try{r2c::mapping::SurfaceVoteStore spool(90000,6,spoolRoot);spool.add(0,1);spool.resolve([](double){throw std::runtime_error("cancel");});assert(false);}catch(const std::runtime_error&){}
 assert(std::filesystem::is_empty(spoolRoot));std::filesystem::remove(spoolRoot);
 std::vector<int> ownership(40*20);
 for(int y=0;y<20;++y)for(int x=0;x<40;++x)ownership[y*40+x]=x<20?0:1;
 ownership[0]=-1;
 auto photometric=[](int p,int camera){int x=p%40,y=p/40;r2c::mapping::SeamSample s;
  s.score=(camera==0?x:39-x)*.002;
  double color=(x>=15&&x<=24&&y>=5&&y<=14&&camera==1)?255:100;
  s.rgb.fill(color);return s;};
 r2c::mapping::choosePhotoSeams(ownership,40,20,photometric,[](double){});
 assert(ownership[0]==-1&&ownership[40]==0&&ownership[79]==1);
 int object=ownership[5*40+15];
 for(int y=5;y<=14;++y)for(int x=15;x<=24;++x)assert(ownership[y*40+x]==object);
 // Competing pair cuts may not accumulate a drift away from the initial
 // best camera, even when matching colors strongly favor a farther view.
 std::vector<int> boundedLabels(60*20),initialLabels;
 for(int y=0;y<20;++y)for(int x=0;x<60;++x)boundedLabels[y*60+x]=x/20;
 initialLabels=boundedLabels;
 auto angular=[](int p,int camera){r2c::mapping::SeamSample s;
  double x=p%60; s.score=std::abs(x-(camera*20+9.5))*.003;
  s.rgb.fill(camera==0?0:255);return s;};
 r2c::mapping::choosePhotoSeams(boundedLabels,60,20,angular,[](double){});
 for(int p=0;p<1200;++p)assert(angular(p,boundedLabels[p]).score<=angular(p,initialLabels[p]).score+r2c::mapping::overheadSeamAllowance);
 // The viewing ray is local to the ground point, independent of gimbal angle.
 assert(r2c::mapping::overheadProjectionScore(0,0,-60,.2,100)<r2c::mapping::overheadProjectionScore(12,0,-60,0,100));
 assert(r2c::mapping::overheadProjectionScore(12,0,-60,0,100)-r2c::mapping::overheadSeamAllowance>r2c::mapping::overheadProjectionScore(0,0,-60,.2,100));
 bool cancelled=false;try{r2c::mapping::choosePhotoSeams(ownership,40,20,photometric,[](double){throw std::runtime_error("cancel");});}catch(const std::runtime_error&){cancelled=true;}assert(cancelled);
 r2c::mapping::SurfaceConsensus canopy(1);for(float z:{0.f,.1f,10.f,10.1f,10.2f,10.f})canopy.add(0,z);assert(std::abs(canopy.height(0)-10.075f)<1e-5f);
 bool bounded=false;r2c::mapping::SeamCut chain(2100,[]{});for(int i=0;i<2099;++i)chain.add(i,i+1,1);
 try{chain.solve(0,2099);}catch(const std::length_error&){bounded=true;}assert(bounded);
 // Do not let a neighboring high corner occlude
 // a ray that travels entirely away from the roof on the ground side.
 std::vector<float> edge(40*40,0);
 for(int y=0;y<40;++y)for(int x=20;x<40;++x)edge[y*40+x]=6;
 double away[]{1,0,0,0,-1,0,.1,0,-.995,3,10,60};
 double across[]{1,0,0,0,-1,0,.1,0,-.995,30,10,60};
 assert(r2c::mapping::projectionVisible(edge,40,40,0,0,1,away,19.2,10,0));
 assert(!r2c::mapping::projectionVisible(edge,40,40,0,0,1,across,19.2,10,0));
 // Same camera position and surface ray: changing the camera's orientation
 // cannot change obstruction. A nadir optical axis is not an overhead ray.
 double nadirAcross[]{1,0,0,0,-1,0,0,0,-1,30,10,60};
 double nadirAway[]{1,0,0,0,-1,0,0,0,-1,3,10,60};
 double directlyAbove[]{1,0,0,0,-1,0,0,0,-1,19.2,10,60};
 assert(!r2c::mapping::projectionVisible(edge,40,40,0,0,1,nadirAcross,19.2,10,0));
 assert(r2c::mapping::projectionVisible(edge,40,40,0,0,1,nadirAway,19.2,10,0));
 assert(r2c::mapping::projectionVisible(edge,40,40,0,0,1,directlyAbove,19.2,10,0));
 // Exercise the actual color renderer: the nearer nadir camera is behind
 // the roof, so it must use the farther, unobstructed photo instead.
 std::fill(rgba.begin(),rgba.end(),0xff0000ffu);
 {std::ofstream file(path,std::ios::binary);file.write(reinterpret_cast<char*>(rgba.data()),rgba.size()*4);}
 std::fill(rgba.begin(),rgba.end(),0xff00ff00u);
 {std::ofstream file(other,std::ios::binary);file.write(reinterpret_cast<char*>(rgba.data()),rgba.size()*4);}
 std::vector<double> roofViews(nadirAcross,nadirAcross+12);roofViews.insert(roofViews.end(),nadirAway,nadirAway+12);
 r2c::mapping::render(edge,40,40,0,0,1,roofViews,{700,503.5,377.5,0,0},1008,756,{path,other},19.2-1.285,10+1.285,.01,corrected);
 assert(uint32_t(corrected[index])==0xff00ff00u);
 R2CMappingReleasePixels();unlink(path);unlink(other);
 edge[10*40+17]=NAN;
 assert(!r2c::mapping::projectionVisible(edge,40,40,0,0,1,away,19.2,10,0));
 assert(!r2c::mapping::projectionVisible(edge,40,40,0,0,1,nadirAway,19.2,10,0));
 // Unknown support is distinct from a measured obstruction; keep walking
 // after unknown cells so a later roof still rejects the source.
 using Visibility=r2c::mapping::ProjectionVisibility;
 assert(r2c::mapping::projectionVisibility(edge,40,40,0,0,1,nadirAway,19.2,10,0)==Visibility::Unknown);
 auto interrupted=edge;for(int y=0;y<40;++y)for(int x=20;x<40;++x)interrupted[y*40+x]=12;interrupted[10*40+19]=NAN;
 assert(r2c::mapping::projectionVisibility(interrupted,40,40,0,0,1,nadirAcross,19.2,10,0)==Visibility::Blocked);
 // An unknown-only source can supply real color when no clear source exists.
 std::fill(rgba.begin(),rgba.end(),0xff0000ffu);
 {std::ofstream file(path,std::ios::binary);file.write(reinterpret_cast<char*>(rgba.data()),rgba.size()*4);}
 std::fill(rgba.begin(),rgba.end(),0xff00ff00u);
 {std::ofstream file(other,std::ios::binary);file.write(reinterpret_cast<char*>(rgba.data()),rgba.size()*4);}
 std::vector<double> fallback(nadirAway,nadirAway+12);
 r2c::mapping::render(edge,40,40,0,0,1,fallback,{700,503.5,377.5,0,0},1008,756,{path},19.2-1.285,10+1.285,.01,corrected);
 assert(uint32_t(corrected[index])==0xffff0000u);
 // A farther clear camera on the same side must beat that nearer unknown ray.
 double fartherClear[]{1,0,0,0,-1,0,0,0,-1,19.2,30,60};
 fallback.insert(fallback.end(),fartherClear,fartherClear+12);
 r2c::mapping::render(edge,40,40,0,0,1,fallback,{700,503.5,377.5,0,0},1008,756,{path,other},19.2-1.285,10+1.285,.01,corrected);
 assert(uint32_t(corrected[index])==0xff00ff00u);
 R2CMappingReleasePixels();unlink(path);unlink(other);
 std::vector<int> three(20*12);
 for(int y=0;y<12;++y)for(int x=0;x<20;++x)three[y*20+x]=y==0?2:(x<10?0:1);
 auto third=[](int p,int camera){r2c::mapping::SeamSample s;
  if(p<20&&camera!=2)return s;
  s.score=camera==2?.3:(camera==0?p%20:19-p%20)*.001;
  s.rgb.fill(camera==1?200:100);return s;};
 r2c::mapping::choosePhotoSeams(three,20,12,third,[](double){});
 for(int x=0;x<20;++x){assert(three[x]==2);assert(three[20+x]==0);}
 std::cout<<"Height consensus, photometric seam, three-view boundaries, ray visibility, unknown mask and cancellation fixtures passed\n";
 // Ortho fill: measured cells stay identical; holes inside photo coverage fill; out-of-view stay empty.
 {
  const int gw=81,gh=81;std::vector<float> surface(gw*gh,NAN);
  for(int y=10;y<70;++y)for(int x=10;x<70;++x)surface[y*gw+x]=12;
  for(int y=30;y<45;++y)for(int x=30;x<45;++x)surface[y*gw+x]=NAN; // hole
  std::vector<float> filled;std::vector<uint8_t> mask;
  r2c::mapping::OrthoFillOptions opt;opt.planeHeight=12;
  auto stats=r2c::mapping::buildOrthoFill(surface,gw,gh,.2,opt,filled,mask);
  assert(stats.estimatedFillCells>0);assert(mask.size()==(size_t(gw)*gh+7)/8);
  for(size_t i=0;i<surface.size();++i)if(std::isfinite(surface[i]))assert(filled[i]==surface[i]);
  assert(std::isfinite(filled[37*gw+37]));
  char fillPath[]="/tmp/r2c-ortho-fill-XXXXXX";int fillFd=mkstemp(fillPath);assert(fillFd>=0);close(fillFd);
  std::vector<uint32_t> fillRgba(1008*756,0xff00ff00u);
  {std::ofstream file(fillPath,std::ios::binary);file.write(reinterpret_cast<char*>(fillRgba.data()),fillRgba.size()*4);}
  std::vector<double> cam{1,0,0,0,-1,0,0,0,-1,8,8,60};
  std::vector<int32_t> measuredOnly,withFill;
  r2c::mapping::render(surface,gw,gh,0,0,.2,cam,{700,503.5,377.5,0,0},1008,756,{fillPath},7,9,.02,measuredOnly);
  r2c::mapping::render(surface,gw,gh,0,0,.2,cam,{700,503.5,377.5,0,0},1008,756,{fillPath},7,9,.02,withFill,true,&filled);
  for(int p=0;p<256*256;++p)if(measuredOnly[p]!=0)assert(withFill[p]==measuredOnly[p]);
  int filledPixels=0;for(int v:withFill)if(v!=0)++filledPixels;assert(filledPixels>int(measuredOnly.size()*.01));
  // Far corner with no photo visibility stays transparent even if height was estimated.
  std::vector<int32_t> far;
  r2c::mapping::render(surface,gw,gh,0,0,.2,cam,{700,503.5,377.5,0,0},1008,756,{fillPath},-30,50,.02,far,true,&filled);
  int farCovered=0;for(int v:far)if(v!=0)++farCovered;assert(farCovered==0);
  // Radius/plane: cell beyond fill radius uses plane when local median unavailable.
  std::vector<float> sparse(200*200,NAN);sparse[100*200+100]=5;sparse[100*200+101]=5;sparse[101*200+100]=5;sparse[101*200+101]=5;
  r2c::mapping::OrthoFillOptions farOpt;farOpt.fillRadiusMeters=2;farOpt.planeHeight=9;farOpt.planeWindowMeters=1;
  std::vector<float> sparseFill;std::vector<uint8_t> sparseMask;
  r2c::mapping::buildOrthoFill(sparse,200,200,.5,farOpt,sparseFill,sparseMask);
  assert(std::abs(sparseFill[0]-9)<1e-3f);
  // Bounded mask size on a large sparse grid.
  std::vector<float> large(2000*1500,NAN);for(int i=0;i<2000;i+=40)large[750*2000+i]=3;
  std::vector<float> largeFill;std::vector<uint8_t> largeMask;
  r2c::mapping::OrthoFillOptions largeOpt;largeOpt.fillRadiusMeters=25;largeOpt.planeHeight=3;
  r2c::mapping::buildOrthoFill(large,2000,1500,.2,largeOpt,largeFill,largeMask);
  assert(largeMask.size()==(size_t(2000)*1500+7)/8);
  R2CMappingReleasePixels();unlink(fillPath);
  std::cout<<"Ortho fill hole, measured identity, plane fallback and bounded mask fixtures passed\n";
 }

 // Ortho fill through the measured+fill render path (heights only where measured is NaN).
 {
  const int gw=81,gh=81;std::vector<float> measured(gw*gh,NAN);
  for(int y=10;y<70;++y)for(int x=10;x<70;++x)measured[y*gw+x]=12;
  for(int y=30;y<45;++y)for(int x=30;x<45;++x)measured[y*gw+x]=NAN;
  std::vector<float> filled;std::vector<uint8_t> mask;r2c::mapping::OrthoFillOptions opt;opt.planeHeight=NAN;
  auto stats=r2c::mapping::buildOrthoFill(measured,gw,gh,.2,opt,filled,mask);
  size_t bits=0,known=0;for(size_t i=0;i<measured.size();++i){bool bit=(mask[i>>3]>>(i&7))&1;bits+=bit;known+=std::isfinite(measured[i]);
   if(std::isfinite(measured[i]))assert(!bit&&filled[i]==measured[i]);else assert(bit==std::isfinite(filled[i]));}
  assert(int(bits)==stats.estimatedFillCells&&stats.estimatedFillCells>0&&known==size_t(stats.knownFraction*measured.size()+.5));
  assert(std::abs(stats.knownFraction+stats.estimatedFraction+stats.emptyFraction-1)<1e-9);
  for(float v:filled)assert(std::isnan(v)||std::abs(v-12)<1e-4f); // constant surface: estimates are the constant
  char path[]="/tmp/r2c-fill-visible-XXXXXX";int fd=mkstemp(path);assert(fd>=0);close(fd);
  std::vector<uint32_t> rgba(1008*756);for(int y=0;y<756;++y)for(int x=0;x<1008;++x)rgba[y*1008+x]=0xff000000u|uint32_t(x/4)|(uint32_t(y/3)<<8);
  {std::ofstream file(path,std::ios::binary);file.write(reinterpret_cast<char*>(rgba.data()),rgba.size()*4);}
  const std::vector<double> intrinsics{700,503.5,377.5,.01,0};
  std::vector<double> camera{1,0,0,0,-1,0,0,0,-1,8,8,60},away{1,0,0,0,-1,0,0,0,-1,500,500,60};
  const double tx=.3,ty=15.7,step=.06;std::vector<int32_t> plain,withFill,unseen;
  r2c::mapping::render(measured,gw,gh,0,0,.2,camera,intrinsics,1008,756,{path},tx,ty,step,plain);
  r2c::mapping::render(measured,gw,gh,0,0,.2,camera,intrinsics,1008,756,{path},tx,ty,step,withFill,true,&filled);
  int inside=0,transparentBefore=0;
  for(int row=0;row<256;++row)for(int col=0;col<256;++col){double u=(tx+(col+.5)*step)/.2,v=(ty-(row+.5)*step)/.2;int p=row*256+col;
   if(u>=0&&v>=0&&u<gw-1&&v<gh-1){++inside;assert(withFill[p]!=0);transparentBefore+=plain[p]==0;}
   if(plain[p]!=0)assert(withFill[p]==plain[p]); // measured pixels byte-identical
   else if(!(u>=0&&v>=0&&u<gw-1&&v<gh-1))assert(withFill[p]==0);}
  assert(inside>10000&&transparentBefore>1000);
  // A camera that sees nothing leaves every pixel transparent even though heights were estimated.
  r2c::mapping::render(measured,gw,gh,0,0,.2,away,intrinsics,1008,756,{path},tx,ty,step,unseen,true,&filled);
  for(int v:unseen)assert(v==0);
  // The C ABI agrees with the C++ core and survives disabled fill.
  std::vector<float> abiFill(measured.size());std::vector<uint8_t> abiMask((measured.size()+7)/8);double abiStats[6]{};char error[256]{};
  assert(R2CMappingBuildOrthoFill(measured.data(),gw,gh,.2,1,25,NAN,abiFill.data(),abiMask.data(),abiStats,error,sizeof error)==1);
  assert(abiStats[0]==stats.estimatedFillCells&&abiMask==mask);
  assert(R2CMappingBuildOrthoFill(measured.data(),gw,gh,.2,0,25,NAN,abiFill.data(),abiMask.data(),abiStats,error,sizeof error)==1&&abiStats[0]==0);
  assert(R2CMappingBuildOrthoFill(nullptr,gw,gh,.2,1,25,NAN,abiFill.data(),abiMask.data(),abiStats,error,sizeof error)==0);
  R2CMappingReleasePixels();unlink(path);
 }
 // Fill radius, local median window and global plane fallback.
 {
  const int n=200;std::vector<float> z(n*n,NAN);
  for(int y=100;y<110;++y)for(int x=100;x<110;++x)z[y*n+x]=7;
  std::vector<float> f;std::vector<uint8_t> mask;r2c::mapping::OrthoFillOptions opt;opt.fillRadiusMeters=5;opt.planeHeight=9;opt.planeWindowMeters=30;
  r2c::mapping::buildOrthoFill(z,n,n,.5,opt,f,mask);
  auto at=[&](int x,int y){return f[y*n+x];};auto est=[&](int x,int y){size_t i=size_t(y)*n+x;return bool((mask[i>>3]>>(i&7))&1);};
  assert(std::abs(at(105,115)-7)<1e-4f&&est(105,115)); // inside R: from the known patch
  assert(std::abs(at(105,125)-7)<1e-4f&&est(105,125)); // beyond R, inside the 30 m window: local median
  assert(std::abs(at(0,0)-9)<1e-4f&&est(0,0));          // beyond R and window: global plane
  opt.planeHeight=NAN;r2c::mapping::buildOrthoFill(z,n,n,.5,opt,f,mask);
  assert(std::isnan(at(0,0))&&!est(0,0)&&std::abs(at(105,125)-7)<1e-4f);
  // Sloped data: nearby estimates blend the closest heights, not a distant constant.
  std::vector<float> ramp(60*60,NAN);for(int y=0;y<60;++y)for(int x=0;x<20;++x){ramp[y*60+x]=x*.1f;ramp[y*60+40+x]=4+x*.1f;}
  r2c::mapping::OrthoFillOptions rampOpt;r2c::mapping::buildOrthoFill(ramp,60,60,.5,rampOpt,f,mask);
  float left=f[30*60+20],mid=f[30*60+30],right=f[30*60+39];assert(left<mid&&mid<right);assert(left>=1.5f&&right<=4.2f);
 }
 // A 3,000,000-cell grid with sparse known cells stays bounded and consistent.
 {
  const int gw=2000,gh=1500;std::vector<float> z(size_t(gw)*gh,NAN);for(int i=0;i<gw;i+=40)z[size_t(750)*gw+i]=3;
  std::vector<float> f;std::vector<uint8_t> mask;r2c::mapping::OrthoFillOptions opt;opt.planeHeight=3;
  auto stats=r2c::mapping::buildOrthoFill(z,gw,gh,.2,opt,f,mask);
  assert(mask.size()==(size_t(gw)*gh+7)/8&&f.size()==z.size());size_t bits=0;for(uint8_t b:mask)bits+=__builtin_popcount(b);
  assert(int(bits)==stats.estimatedFillCells&&stats.estimatedFraction>.9&&stats.elapsedMs<20000);
  assert(std::abs(stats.knownFraction+stats.estimatedFraction+stats.emptyFraction-1)<1e-9);
 }
 // Grid extent: footprints cover edge photos, clip applies only when usable, and an oversize footprint falls back.
 {
  using namespace r2c::mapping;Box footprint{0,0,100,50},sparse{40,20,60,30},none;
  auto plain=surfaceExtent(footprint,sparse,none,.2,1,2,3000000);
  assert(plain.fits&&!plain.sparseFallback&&plain.x<=-1&&plain.y<=-1&&plain.x+plain.width*.2>=101&&plain.y+plain.height*.2>=51);
  Box clip{10,10,30,30};auto clipped=surfaceExtent(footprint,sparse,clip,.2,1,2,3000000);
  assert(clipped.fits&&!clipped.sparseFallback&&std::abs(clipped.x-10)<1e-9&&clipped.width==101&&clipped.height==101);
  for(Box unusable:{Box{0,0,0,0},Box{NAN,0,30,30},Box{30,10,10,30},Box{10,30,30,10}}){auto e=surfaceExtent(footprint,sparse,unusable,.2,1,2,3000000);assert(e.fits&&e.width==plain.width&&e.height==plain.height);}
  assert(!clipUsable(Box{0,0,0,0})&&clipUsable(clip));
  Box huge{0,0,2000,2000};auto fallback=surfaceExtent(huge,sparse,none,.2,1,2,3000000);
  assert(fallback.fits&&fallback.sparseFallback&&std::abs(fallback.x-38)<.2&&fallback.width<=3000000/fallback.height);
  assert(surfaceExtent(huge,sparse,none,2,1,2,3000000).fits&&!surfaceExtent(huge,sparse,none,2,1,2,3000000).sparseFallback); // coarser cells fit
  assert(!surfaceExtent(huge,Box{0,0,2000,2000},none,.2,1,2,3000000).fits);
  auto disjoint=surfaceExtent(footprint,sparse,Box{500,500,600,600},.2,1,2,3000000);assert(disjoint.fits&&disjoint.sparseFallback);
  assert(!surfaceExtent(Box{},Box{},none,.2,1,2,3000000).fits);
 }
 std::cout<<"Ortho fill render, radius/plane, 3M-cell bound and grid extent fixtures passed\n";
 return 0;
}
