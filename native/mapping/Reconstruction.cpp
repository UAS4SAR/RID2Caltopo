#include "SurfaceVoteStore.hpp"
#include "SurfaceRefinement.hpp"
#include "include/R2CMapping.h"
#include "SurfaceCore.hpp"
#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/calib3d.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>
#include <fstream>
#include <map>
#include <set>
#include <stdexcept>
#include <cstring>
#include <memory>
#include <limits>
namespace {
using V=cv::Vec3d; using M=cv::Matx33d;
constexpr double pi=3.14159265358979323846;
const float unknown=std::numeric_limits<float>::quiet_NaN();
struct Worker: r2c::mapping::Progress {
 R2CMappingProgress callback;void* context;const R2CMappingInput* input=nullptr;
 Worker(R2CMappingProgress p,void* c):callback(p),context(c){}
 void update(const char* stage,double fraction){if(callback&&!callback(context,stage,fraction))throw std::runtime_error("Processing cancelled; originals retained.");}
 bool iteration(int i,double)override{update("Adjusting RTK cameras and structure",.35+.1*std::min(i,250)/250);return true;}
 void connectivity(int round,const std::vector<int>& groups,const std::vector<int>& support,const std::vector<int>& exclusions)override{
  if(input&&input->connectivity)input->connectivity(input->connectivityContext,round,groups.data(),support.data(),exclusions.data(),int(groups.size()));
  update("Checking connected image groups",.35);
 }
};
cv::Mat gray(const char* path,int width,int height){
 std::ifstream f(path,std::ios::binary|std::ios::ate);if(!f||f.tellg()!=std::streamoff(size_t(width)*height))throw std::runtime_error("Invalid grayscale original sample.");
 cv::Mat m(height,width,CV_8U);f.seekg(0);f.read(reinterpret_cast<char*>(m.data),size_t(width)*height);if(!f)throw std::runtime_error("Cannot read original sample.");return m;
}
M rotation(const double* c){return M(c[0],c[1],c[2],c[3],c[4],c[5],c[6],c[7],c[8]);}
V point(const double* c){return V(c[0],c[1],c[2]);}
bool finite(V p){return std::isfinite(p[0])&&std::isfinite(p[1])&&std::isfinite(p[2]);}
double baseline(V a,V b){return std::hypot(a[0]-b[0],a[1]-b[1]);}
bool inView(M r,V c,V p,double f,double cx,double cy,int w,int h){V v=r*(p-c);if(v[2]<=0)return false;double x=f*v[0]/v[2]+cx,y=f*v[1]/v[2]+cy;return x>=0&&x<w&&y>=0&&y<h;}
std::vector<std::pair<int,int>> matchPairs(const R2CMappingInput& input){
 int n=input.count;std::vector<M> r;std::vector<V> c;std::vector<std::vector<V>> ground(n);
 double cx=(input.width-1)*.5,cy=(input.height-1)*.5;
 for(int i=0;i<n;++i){const double* p=input.cameras+i*15;c.push_back(point(p));r.push_back(rotation(p+3));
  for(int y=0;y<=4;++y)for(int x=0;x<=4;++x){V ray=r[i].t()*V((x*(input.width-1)/4.-cx)/input.focal,(y*(input.height-1)/4.-cy)/input.focal,1);
   if(ray[2]<-.1){double d=-c[i][2]/ray[2];if(d>0)ground[i].push_back(c[i]+d*ray);}}}
 std::set<std::pair<int,int>> pairs;
 for(int i=0;i<n;++i){std::vector<std::pair<double,int>> near;std::vector<std::pair<int,int>> overlap;
  for(int j=0;j<n;++j)if(i!=j){double b=baseline(c[i],c[j]);if(b>=2&&b<=90)near.emplace_back(b,j);
   if(b<=120){int score=0;for(V p:ground[i])score+=inView(r[j],c[j],p,input.focal,cx,cy,input.width,input.height);
    for(V p:ground[j])score+=inView(r[i],c[i],p,input.focal,cx,cy,input.width,input.height);if(score>=8)overlap.emplace_back(-score,j);}}
  std::sort(near.begin(),near.end());std::sort(overlap.begin(),overlap.end());
  for(size_t a=0;a<std::min<size_t>(12,near.size());++a)pairs.emplace(std::min(i,near[a].second),std::max(i,near[a].second));
  for(size_t a=0;a<std::min<size_t>(12,overlap.size());++a)pairs.emplace(std::min(i,overlap[a].second),std::max(i,overlap[a].second));
 }
 return {pairs.begin(),pairs.end()};
}
void interpolate(std::vector<float>& z,int w,int h,double x,double y,double cell,const std::vector<V>& points,Worker& worker){
 cv::Subdiv2D subdivision(cv::Rect(0,0,w,h));std::map<std::pair<float,float>,double> elevations;std::set<std::pair<int,int>> bins;
 for(V p:points){float xx=(p[0]-x)/cell,yy=(p[1]-y)/cell;if(xx<1||yy<1||xx>w-2||yy>h-2)continue;
  if(!bins.emplace(int(std::round(xx*2)),int(std::round(yy*2))).second)continue;
  subdivision.insert(cv::Point2f(xx,yy));elevations[{xx,yy}]=p[2];}
 std::vector<cv::Vec6f> triangles;subdivision.getTriangleList(triangles);
 for(auto t:triangles){worker.update("Completing nearby supported surface patches",.675);
  auto a=elevations.find({t[0],t[1]}),b=elevations.find({t[2],t[3]}),c=elevations.find({t[4],t[5]});if(a==elevations.end()||b==elevations.end()||c==elevations.end())continue;
  double za=a->second,zb=b->second,zc=c->second;
  double edge=std::max({std::hypot(t[0]-t[2],t[1]-t[3]),std::hypot(t[0]-t[4],t[1]-t[5]),std::hypot(t[2]-t[4],t[3]-t[5])})*cell;
  if(edge>6||std::max({za,zb,zc})-std::min({za,zb,zc})>5)continue;
  double den=(t[3]-t[5])*(t[0]-t[4])+(t[4]-t[2])*(t[1]-t[5]);if(std::abs(den)<.25)continue;
  for(int row=std::max(0,int(std::floor(std::min({t[1],t[3],t[5]}))));row<=std::min(h-1,int(std::ceil(std::max({t[1],t[3],t[5]}))));++row)
   for(int col=std::max(0,int(std::floor(std::min({t[0],t[2],t[4]}))));col<=std::min(w-1,int(std::ceil(std::max({t[0],t[2],t[4]}))));++col){int i=row*w+col;if(std::isfinite(z[i]))continue;
    double aa=((t[3]-t[5])*(col-t[4])+(t[4]-t[2])*(row-t[5]))/den,bb=((t[5]-t[1])*(col-t[4])+(t[0]-t[4])*(row-t[5]))/den,cc=1-aa-bb;
    if(aa>=0&&bb>=0&&cc>=0)z[i]=aa*za+bb*zb+cc*zc;}
 }
 for(int pass=0;pass<12;++pass){worker.update("Completing small planar gaps",.68);std::vector<std::pair<int,float>> next;
  for(int row=1;row<h-1;++row)for(int col=1;col<w-1;++col){int i=row*w+col;if(std::isfinite(z[i]))continue;
   float low=INFINITY,high=-INFINITY,sum=0;int count=0;for(int j:{i-1,i+1,i-w,i+w})if(std::isfinite(z[j])){low=std::min(low,z[j]);high=std::max(high,z[j]);sum+=z[j];++count;}
   if(count>=3&&high-low<.6)next.emplace_back(i,sum/count);}
  for(auto item:next)z[item.first]=item.second;
 }
}
struct Alignment { std::vector<double> values; int tracks=0,pairs=0; };
Alignment align(const R2CMappingInput& in,Worker& worker) {
 struct RestoreRng {uint64_t state=cv::theRNG().state;~RestoreRng(){cv::theRNG().state=state;}} restore;
 cv::setRNGSeed(0);
 struct Features { std::vector<cv::KeyPoint> points; cv::Mat descriptors; };
 std::vector<Features> features(in.count);std::vector<int> offsets(in.count+1);
 auto sift=cv::SIFT::create(4000);
 for(int i=0;i<in.count;++i){auto label="Finding 3D image detail "+std::to_string(i+1)+"/"+std::to_string(in.count);worker.update(label.c_str(),.05+.13*i/in.count);
  sift->detectAndCompute(gray(in.grayPaths[i],in.width,in.height),cv::noArray(),features[i].points,features[i].descriptors);offsets[i+1]=offsets[i]+features[i].points.size();}
 std::vector<int> parent(offsets.back());std::vector<std::map<int,int>> members(offsets.back());
 for(int i=0;i<in.count;++i)for(int j=offsets[i];j<offsets[i+1];++j){parent[j]=j;members[j][i]=j;}
 auto find=[&](int p){while(parent[p]!=p){parent[p]=parent[parent[p]];p=parent[p];}return p;};
 auto join=[&](int a,int b){int x=find(a),y=find(b);if(x==y)return;for(auto m:members[x])if(members[y].count(m.first))return;
  if(members[x].size()<members[y].size())std::swap(x,y);parent[y]=x;members[x].insert(members[y].begin(),members[y].end());members[y].clear();};
 auto pairs=matchPairs(in);Alignment result;cv::FlannBasedMatcher matcher;
 for(size_t n=0;n<pairs.size();++n){auto label="Matching 3D overlaps "+std::to_string(n+1)+"/"+std::to_string(pairs.size());worker.update(label.c_str(),.18+.16*n/pairs.size());
  auto [i,j]=pairs[n];auto& a=features[i];auto& b=features[j];if(a.descriptors.empty()||b.descriptors.rows<2)continue;
  std::vector<std::vector<cv::DMatch>> matches;matcher.knnMatch(a.descriptors,b.descriptors,matches,2);matcher.clear();
  std::vector<cv::DMatch> good;std::vector<cv::Point2f> left,right;
  for(auto& m:matches)if(m.size()==2&&m[0].distance<.72*m[1].distance){good.push_back(m[0]);left.push_back(a.points[m[0].queryIdx].pt);right.push_back(b.points[m[0].trainIdx].pt);}
  if(good.size()<32)continue;cv::Mat mask;auto fundamental=cv::findFundamentalMat(left,right,cv::FM_RANSAC,1.25,.999,4000,mask);
  if(fundamental.empty()||cv::countNonZero(mask)<32||cv::countNonZero(mask)<good.size()*.25)continue;
  ++result.pairs;for(size_t k=0;k<good.size();++k)if(mask.at<uint8_t>(int(k)))join(offsets[i]+good[k].queryIdx,offsets[j]+good[k].trainIdx);
 }
 std::vector<int> tracks;for(size_t i=0;i<members.size();++i)if(members[i].size()>=3)tracks.push_back(i);
 std::stable_sort(tracks.begin(),tracks.end(),[&](int a,int b){return members[a].size()>members[b].size();});if(tracks.size()>24000)tracks.resize(24000);
 if(tracks.size()<500)throw r2c::mapping::GeometryUnavailable("Too few connected 3D tracks. Originals are retained.");
 std::vector<double> observations;for(size_t t=0;t<tracks.size();++t)for(auto [camera,id]:members[tracks[t]]){auto p=features[camera].points[id-offsets[camera]].pt;observations.insert(observations.end(),{double(camera),double(t),p.x,p.y});}
 features.clear();members.clear();result.tracks=int(tracks.size());
 result.values=r2c::mapping::adjust(worker,{in.cameras,in.cameras+in.count*15},observations,result.tracks,in.width,in.height,in.focal,{in.lasers,in.lasers+in.count*3});return result;
}
// Negative anchor sentinel means an approximate photo projection, never measured depth.
// Header slot 7 stores the reference plane elevation only in this mode.
Alignment approximate(const R2CMappingInput& in,Worker& worker){
 worker.update("Preparing approximate photo map · depth not recovered",.45);
 std::vector<double> elevations;
 for(int i=0;i<in.count;++i){V target=point(in.lasers+i*3),camera=point(in.cameras+i*15);
  if(finite(target)&&target[2]<camera[2]-3)elevations.push_back(target[2]);}
 std::sort(elevations.begin(),elevations.end());double z=elevations.empty()?0:elevations[elevations.size()/2];
 Alignment a;a.values={double(in.count),0,in.focal,(in.width-1)*.5,(in.height-1)*.5,0,0,z,0,0,0,0};
 for(int i=0;i<in.count;++i){const double* c=in.cameras+i*15;a.values.insert(a.values.end(),c+3,c+12);a.values.insert(a.values.end(),c,c+3);}
 a.values.push_back(elevations.empty()?-2:-1);return a;
}
Alignment bestAlignment(const R2CMappingInput& in,Worker& worker){
 try{return align(in,worker);}catch(const r2c::mapping::GeometryUnavailable& e){
  worker.update(e.what(),.44);return approximate(in,worker);
 }
}
void approximateGrid(const R2CMappingInput& in,const Alignment& a,Worker& worker,R2CMappingResult& out){
 const double z=a.values[7],cx=a.values[3],cy=a.values[4],f=a.values[2];
 V lo(INFINITY,INFINITY,z),hi(-INFINITY,-INFINITY,z);
 for(int i=0;i<in.count;++i){const double* c=&a.values[12+i*12];if(!std::isfinite(c[0]))continue;
  for(double y:{0.,double(in.height-1)})for(double x:{0.,double(in.width-1)}){
   V ray=rotation(c).t()*V((x-cx)/f,(y-cy)/f,1),camera=point(c+9);
   if(ray[2]>=-.1||camera[2]<=z+3)continue;V p=camera+((z-camera[2])/ray[2])*ray;
   for(int j=0;j<2;++j){lo[j]=std::min(lo[j],p[j]);hi[j]=std::max(hi[j],p[j]);}
  }
 }
 if(!finite(lo)||!finite(hi))throw std::runtime_error("Photos have no downward footprint for an approximate map; originals retained.");
 out.cell=.2;out.x=std::floor(lo[0]/out.cell)*out.cell;out.y=std::floor(lo[1]/out.cell)*out.cell;
 const double w=std::ceil((hi[0]-out.x)/out.cell)+1,h=std::ceil((hi[1]-out.y)/out.cell)+1;
 if(w*h>3000000)throw std::runtime_error("Approximate photo extent exceeds the bounded region size.");
 out.width=int(w);out.height=int(h);out.heights=new float[size_t(out.width)*out.height];
 std::fill(out.heights,out.heights+size_t(out.width)*out.height,unknown);
 for(int row=0;row<out.height;++row){if(row%32==0)worker.update("Projecting available photo footprints · approximate heights",.46+.22*row/out.height);
  for(int col=0;col<out.width;++col){V p(out.x+col*out.cell,out.y+row*out.cell,z);
   for(int i=0;i<in.count;++i){const double* c=&a.values[12+i*12];if(std::isfinite(c[0])&&inView(rotation(c),point(c+9),p,f,cx,cy,in.width,in.height)){out.heights[row*out.width+col]=z;break;}}
  }
 }
 out.measuredCells=0;out.stereoPairs=0;
}
void dense(const R2CMappingInput& in,const Alignment& alignment,Worker& worker,R2CMappingResult& out){
 if(alignment.values.back()<0){approximateGrid(in,alignment,worker,out);return;}
 const auto& values=alignment.values;std::vector<V> points;for(size_t p=12+in.count*12;p+3<values.size();p+=3){V v=point(&values[p]);if(finite(v))points.push_back(v);}
 if(points.size()<500)throw r2c::mapping::GeometryUnavailable("Too few qualified 3D points to reconstruct the surface.");
 V lo=points.front(),hi=lo;for(V p:points)for(int a=0;a<3;++a){lo[a]=std::min(lo[a],p[a]);hi[a]=std::max(hi[a],p[a]);}
 out.cell=.2;out.x=std::floor((lo[0]-2)/out.cell)*out.cell;out.y=std::floor((lo[1]-2)/out.cell)*out.cell;
 double width=std::ceil((hi[0]+2-out.x)/out.cell)+1,height=std::ceil((hi[1]+2-out.y)/out.cell)+1;
 if(!std::isfinite(width*height)||width<1||height<1||width*height>3000000)throw std::runtime_error("The region exceeds the bounded surface size. Divide it into smaller regions.");
 out.width=int(width);out.height=int(height);size_t size=out.width*out.height;

 std::vector<int> used;std::vector<M> rotations(in.count);std::vector<V> centres(in.count);double ceiling=INFINITY;
 for(int i=0;i<in.count;++i){const double* c=&values[12+i*12];if(std::isfinite(c[0])){used.push_back(i);rotations[i]=rotation(c);centres[i]=point(c+9);ceiling=std::min(ceiling,centres[i][2]-4);}}
 double low=lo[2]-3,high=std::min(ceiling,hi[2]+3);std::set<std::pair<int,int>> pairs;
 for(int i:used){std::vector<std::pair<double,int>> near,same;
  for(int j:used)if(i!=j){double b=baseline(centres[i],centres[j]);double dot=rotations[i].row(2).dot(rotations[j].row(2));if(b>=7&&b<=24&&dot>=std::cos(55*pi/180)){near.emplace_back(b,j);if(std::abs(in.pitch[i]-in.pitch[j])<10)same.emplace_back(b,j);}}
  for(auto* list:{&near,&same}){std::sort(list->begin(),list->end());for(size_t k=0;k<std::min<size_t>(3,list->size());++k)pairs.emplace(std::min(i,(*list)[k].second),std::max(i,(*list)[k].second));}}
 r2c::mapping::SurfaceVoteStore consensus(size,pairs.size(),std::filesystem::path(in.grayPaths[0]).parent_path());
 const double f=values[2],cx=values[3],cy=values[4],k1=values[5],k2=values[6];M k(f,0,cx,0,f,cy,0,0,1);cv::Vec<double,5> distortion(k1,k2,0,0,0);
 auto visible=[&](int i,V p){V v=rotations[i]*(p-centres[i]);if(v[2]<=0)return false;double nx=v[0]/v[2],ny=v[1]/v[2],rr=nx*nx+ny*ny,radial=1+k1*rr+k2*rr*rr;double px=f*nx*radial+cx,py=f*ny*radial+cy;return px>=3&&px<=in.width-4&&py>=3&&py<=in.height-4;};
 std::vector<float> sums(size);std::vector<int> pairCounts(size),touched;int pairIndex=0;
 for(auto [i,j]:pairs){auto label="Reconstructing 3D surface · stereo "+std::to_string(++pairIndex)+"/"+std::to_string(pairs.size());worker.update(label.c_str(),.46+.22*(pairIndex-1)/pairs.size());
  M r=rotations[j]*rotations[i].t();V t=rotations[j]*(centres[i]-centres[j]);cv::Mat r1,r2,p1,p2,q,mx1,my1,mx2,my2,a,b;
  cv::Size dimensions(in.width,in.height);cv::stereoRectify(k,distortion,k,distortion,dimensions,r,t,r1,r2,p1,p2,q,cv::CALIB_ZERO_DISPARITY,1,dimensions);
  cv::initUndistortRectifyMap(k,distortion,r1,p1,dimensions,CV_32FC1,mx1,my1);cv::initUndistortRectifyMap(k,distortion,r2,p2,dimensions,CV_32FC1,mx2,my2);
  cv::remap(gray(in.grayPaths[i],in.width,in.height),a,mx1,my1,cv::INTER_LINEAR);cv::remap(gray(in.grayPaths[j],in.width,in.height),b,mx2,my2,cv::INTER_LINEAR);
  bool vertical=std::abs(p2.at<double>(1,3))>std::abs(p2.at<double>(0,3));int axis=vertical?1:0;if(vertical){cv::transpose(a,a);cv::transpose(b,b);}
  M rect;std::memcpy(rect.val,r1.ptr<double>(),9*sizeof(double));M combined=rect*rotations[i];
  double minDepth=INFINITY,maxDepth=-INFINITY;int shared=0;bool valid=true;
  for(V p:points)if(visible(i,p)&&visible(j,p)){double depth=(combined*(p-centres[i]))[2];if(!std::isfinite(depth)||depth<=5){valid=false;break;}minDepth=std::min(minDepth,depth);maxDepth=std::max(maxDepth,depth);++shared;}
  if(!valid||shared<32)continue;double padding=std::max(3.,(maxDepth-minDepth)*.1);minDepth=std::max(5.,minDepth-padding);maxDepth+=padding;
  double fb=std::abs(p2.at<double>(axis,3)),near=std::max(0.,std::floor(fb/maxDepth-12)),far=std::ceil(fb/minDepth+12);
  if(p1.at<double>(0,0)<=0||!std::isfinite(far)||fb<=0||far-near>512||far>=a.cols-16)continue;
  int range=std::max(16,int(std::ceil((far-near)/16))*16);if(near+range>=a.cols-16||range>512)continue;
  int minimum=p2.at<double>(axis,3)<0?int(near):-int(near)-range;
  auto stereo=cv::StereoSGBM::create(minimum,range,5,200,800,1,31,8,80,2,cv::StereoSGBM::MODE_SGBM_3WAY);
  auto reverse=cv::StereoSGBM::create(-minimum-range+1,range,5,200,800,1,31,8,80,2,cv::StereoSGBM::MODE_SGBM_3WAY);
  cv::Mat disparity,back;stereo->compute(a,b,disparity);worker.update(label.c_str(),.46+.22*(pairIndex-1)/pairs.size());reverse->compute(b,a,back);
  auto mapValid=[&](const cv::Mat& mx,const cv::Mat& my,int x,int y){float xx=mx.at<float>(y,x),yy=my.at<float>(y,x);return xx>=3&&xx<=in.width-4&&yy>=3&&yy<=in.height-4;};int pairPoints=0;
  for(int row=2;row<disparity.rows-2;row+=2){if(row%32==2)worker.update(label.c_str(),.46+.22*(pairIndex-1)/pairs.size());for(int col=2;col<disparity.cols-2;col+=2){
   double disp=disparity.at<int16_t>(row,col)/16.;if(disp<=minimum||disp>=minimum+range-1||std::abs(disp)<1)continue;
   int other=int(std::round(col-disp));if(other<2||other>=disparity.cols-2||std::abs(disp+back.at<int16_t>(row,other)/16.)>1)continue;
   int rx=vertical?row:col,ry=vertical?col:row,ox=vertical?row:other,oy=vertical?other:row;
   if(!mapValid(mx1,my1,rx,ry)||!mapValid(mx2,my2,ox,oy))continue;
   const double* qq=q.ptr<double>();double denominator=qq[12]*rx+qq[13]*ry+qq[14]*disp+qq[15];if(std::abs(denominator)<1e-8)continue;
   V local((qq[0]*rx+qq[1]*ry+qq[2]*disp+qq[3])/denominator,(qq[4]*rx+qq[5]*ry+qq[6]*disp+qq[7])/denominator,(qq[8]*rx+qq[9]*ry+qq[10]*disp+qq[11])/denominator);
   V world=combined.t()*local+centres[i];if(!finite(world)||world[2]<low||world[2]>high||local[2]<5)continue;
   int gx=int(std::round((world[0]-out.x)/out.cell)),gy=int(std::round((world[1]-out.y)/out.cell));if(gx<0||gx>=out.width||gy<0||gy>=out.height)continue;
   int index=gy*out.width+gx;if(pairCounts[index]++==0)touched.push_back(index);sums[index]+=world[2];++pairPoints;
  }}
  for(int index:touched){consensus.add(index,sums[index]/pairCounts[index]);sums[index]=0;pairCounts[index]=0;}
  touched.clear();if(pairPoints>1000)++out.stereoPairs;
 }
 std::vector<float> heights=consensus.resolve([&](double fraction){worker.update("Combining all supported depth observations",.67+.005*fraction);});for(float z:heights)if(std::isfinite(z))++out.measuredCells;
 interpolate(heights,out.width,out.height,out.x,out.y,out.cell,points,worker);
 if(std::none_of(heights.begin(),heights.end(),[](float z){return std::isfinite(z);}))throw r2c::mapping::GeometryUnavailable("No recoverable surface patches. Originals are retained.");
 out.heights=new float[size];std::copy(heights.begin(),heights.end(),out.heights);
}
void bestDense(const R2CMappingInput& in,Alignment& alignment,Worker& worker,R2CMappingResult& out){
 try{dense(in,alignment,worker,out);}catch(const r2c::mapping::GeometryUnavailable& e){
  worker.update(e.what(),.45);delete[] out.heights;out.heights=nullptr;
  out.stereoPairs=0;out.measuredCells=0;alignment=approximate(in,worker);
  dense(in,alignment,worker,out);
 }
}
void validateInput(const R2CMappingInput* in);
void errorText(char* out,size_t capacity,const char* text){if(out&&capacity){std::strncpy(out,text,capacity-1);out[capacity-1]=0;}}
}
extern "C" int R2CMappingReconstruct(const R2CMappingInput* in,R2CMappingProgress callback,void* context,R2CMappingResult** result,char* error,size_t capacity){
 if(result)*result=nullptr;
 try {if(!result||!in||in->count<3||in->count>192||in->width<64||in->height<64||in->width>1400||in->height>1400||!std::isfinite(in->focal)||in->focal<=0||!in->cameras||!in->lasers||!in->pitch||!in->grayPaths)throw std::runtime_error("Invalid bounded reconstruction input.");
  for(int i=0;i<in->count;++i){if(!in->grayPaths[i]||!std::isfinite(in->pitch[i]))throw std::runtime_error("Invalid original sample or pitch.");for(int j=0;j<15;++j)if(!std::isfinite(in->cameras[i*15+j]))throw std::runtime_error("Invalid camera geometry.");for(int j=12;j<15;++j)if(in->cameras[i*15+j]<=0||in->cameras[i*15+j]>10)throw std::runtime_error("Invalid RTK uncertainty.");
  M r=rotation(in->cameras+i*15+3);if(cv::norm(cv::Mat(r*r.t()-M::eye()))>1e-6||std::abs(cv::determinant(cv::Mat(r))-1)>1e-6)throw std::runtime_error("Invalid camera attitude.");}
  validateInput(in);Worker worker(callback,context);worker.input=in;worker.update("Preparing bounded 3D reconstruction",.04);auto alignment=bestAlignment(*in,worker);
  std::unique_ptr<R2CMappingResult,decltype(&R2CMappingFree)> out(new R2CMappingResult{},R2CMappingFree);
  out->selectedPhotos=in->count;out->registeredPhotos=int(alignment.values[0]);out->featureTracks=alignment.tracks;out->matchedPairs=alignment.pairs;
  bestDense(*in,alignment,worker,*out);out->registeredPhotos=int(alignment.values[0]);out->featureTracks=alignment.tracks;out->matchedPairs=alignment.pairs;out->adjustmentCount=alignment.values.size();out->adjustment=new double[out->adjustmentCount];std::copy(alignment.values.begin(),alignment.values.end(),out->adjustment);*result=out.release();return 1;
 }catch(const std::exception& e){errorText(error,capacity,e.what());return 0;}catch(...){errorText(error,capacity,"Native reconstruction failed; originals retained.");return 0;}
}
extern "C" void R2CMappingFree(R2CMappingResult* result){if(result){delete[] result->heights;delete[] result->adjustment;delete result;}}
extern "C" int R2CMappingRender(const float* surface,int width,int height,double x,double y,double cell,const double* cameras,size_t count,const double* intrinsics,int imageWidth,int imageHeight,const char*const* paths,double tileX,double tileY,double step,int32_t* output,char* error,size_t capacity){
 try {if(!surface||!cameras||!intrinsics||!paths||!output||width<=0||height<=0||size_t(width)*height>3000000||count<3||count>192||cell<=0||step<=0)throw std::runtime_error("Invalid surface render input.");
  std::vector<std::string> files;for(size_t i=0;i<count;++i){if(!paths[i])throw std::runtime_error("Missing original raster.");files.emplace_back(paths[i]);}
  std::vector<int32_t> pixels(256*256);int rendered=r2c::mapping::render({surface,surface+size_t(width)*height},width,height,x,y,cell,{cameras,cameras+count*12},{intrinsics,intrinsics+5},imageWidth,imageHeight,files,tileX,tileY,step,pixels);
  std::copy(pixels.begin(),pixels.end(),output);return rendered;
 }catch(const std::exception& e){errorText(error,capacity,e.what());return -1;}catch(...){errorText(error,capacity,"Surface rendering failed.");return -1;}
}
extern "C" void R2CMappingReleasePixels(void){r2c::mapping::releasePixels();}

extern "C" int R2CMappingRefine(float* z,int w,int h,double cell,char* error,size_t capacity){
 try{r2c::mapping::refineSurface(z,w,h,cell);return 1;}catch(const std::exception& ex){if(error&&capacity)snprintf(error,capacity,"%s",ex.what());return 0;}
}
extern "C" int R2CMappingFaces(const float* z,int w,int h,int stride,const int32_t* ids,size_t count,int32_t* output,size_t limit,char* error,size_t capacity){
 try{auto result=r2c::mapping::surfaceFaces(z,w,h,stride,ids,count);if(!output||result.size()>limit)throw std::runtime_error("Insufficient mesh output capacity.");std::copy(result.begin(),result.end(),output);return int(result.size());}catch(const std::exception& ex){if(error&&capacity)snprintf(error,capacity,"%s",ex.what());return -1;}
}

extern "C" int R2CMappingPrepareProjection(const float* surface,int width,int height,double x,double y,double cell,const double* cameras,size_t count,const double* intrinsics,int iw,int ih,const char*const* paths,R2CMappingProgress callback,void* context,char* error,size_t capacity){
 try {if(!surface||!cameras||!intrinsics||!paths||width<2||height<2||size_t(width)*height>3000000||count<3||count>192)throw std::runtime_error("Invalid projection input.");
 std::vector<std::string> files;for(size_t i=0;i<count;++i)files.emplace_back(paths[i]);
 r2c::mapping::prepareProjection({surface,surface+size_t(width)*height},width,height,x,y,cell,{cameras,cameras+count*12},{intrinsics,intrinsics+5},iw,ih,files,[&](double fraction){if(callback&&!callback(context,"Matching photo seams",fraction))throw std::runtime_error("Processing cancelled; originals retained.");});return 1;
 }catch(const std::exception& ex){errorText(error,capacity,ex.what());return 0;}
}

namespace {
void validateInput(const R2CMappingInput* in){
 if(!in||in->count<3||in->count>192||in->width<64||in->height<64||in->width>1400||in->height>1400||!std::isfinite(in->focal)||in->focal<=0||!in->cameras||!in->lasers||!in->pitch||!in->grayPaths)throw std::runtime_error("Invalid bounded reconstruction input.");
 for(int i=0;i<in->count;++i){if(!in->grayPaths[i]||!std::isfinite(in->pitch[i]))throw std::runtime_error("Invalid original sample or pitch.");for(int j=0;j<15;++j)if(!std::isfinite(in->cameras[i*15+j]))throw std::runtime_error("Invalid camera geometry.");for(int j=12;j<15;++j)if(in->cameras[i*15+j]<=0||in->cameras[i*15+j]>10)throw std::runtime_error("Invalid RTK uncertainty.");
  M r=rotation(in->cameras+i*15+3);if(cv::norm(cv::Mat(r*r.t()-M::eye()))>1e-6||std::abs(cv::determinant(cv::Mat(r))-1)>1e-6)throw std::runtime_error("Invalid camera attitude.");}
}
}
extern "C" int R2CMappingAlign(const R2CMappingInput* in,R2CMappingProgress cb,void* ctx,R2CMappingResult** result,char* error,size_t capacity){
 if(result)*result=nullptr;
 try{if(!result)throw std::runtime_error("Missing output.");validateInput(in);Worker worker(cb,ctx);worker.input=in;
  // OpenCV's randomized matcher starts from the same state for every region.
  auto a=bestAlignment(*in,worker);auto out=std::unique_ptr<R2CMappingResult,decltype(&R2CMappingFree)>(new R2CMappingResult{},R2CMappingFree);
  out->selectedPhotos=in->count;out->registeredPhotos=int(a.values[0]);out->featureTracks=a.tracks;out->matchedPairs=a.pairs;
  out->adjustmentCount=a.values.size();out->adjustment=new double[a.values.size()];std::copy(a.values.begin(),a.values.end(),out->adjustment);*result=out.release();return 1;
 }catch(const std::exception& ex){errorText(error,capacity,ex.what());return 0;}
}
extern "C" int R2CMappingDense(const R2CMappingInput* in,const double* adjustment,size_t count,R2CMappingProgress cb,void* ctx,R2CMappingResult** result,char* error,size_t capacity){
 if(result)*result=nullptr;
 try{if(!result)throw std::runtime_error("Missing output.");validateInput(in);
  if(!adjustment||count<size_t(13+in->count*12)||(count-13-in->count*12)%3||count>size_t(13+in->count*12+24000*3))throw std::runtime_error("Invalid saved camera solution.");
  for(size_t i=0;i<count;++i)if(std::isinf(adjustment[i]))throw std::runtime_error("Invalid saved camera solution.");
  for(int i=2;i<=6;++i)if(!std::isfinite(adjustment[i]))throw std::runtime_error("Invalid saved calibration.");
  if(adjustment[2]<=0)throw std::runtime_error("Invalid saved focal length.");
  int registered=0;for(int i=0;i<in->count;++i){int finiteCount=0;for(int j=0;j<12;++j)finiteCount+=std::isfinite(adjustment[12+i*12+j]);if(finiteCount!=0&&finiteCount!=12)throw std::runtime_error("Partial saved camera.");registered+=finiteCount==12;}
  if(registered<3||adjustment[0]!=registered)throw std::runtime_error("Invalid saved camera count.");
  if(count<size_t(13+in->count*12+500*3)&&!(count==size_t(13+in->count*12)&&(adjustment[count-1]==-1||adjustment[count-1]==-2)))throw std::runtime_error("Invalid saved camera solution.");
  Alignment a;a.values.assign(adjustment,adjustment+count);Worker worker(cb,ctx);auto out=std::unique_ptr<R2CMappingResult,decltype(&R2CMappingFree)>(new R2CMappingResult{},R2CMappingFree);
  bestDense(*in,a,worker,*out);
  out->adjustmentCount=a.values.size();out->adjustment=new double[a.values.size()];std::copy(a.values.begin(),a.values.end(),out->adjustment);
  *result=out.release();return 1;
 }catch(const std::exception& ex){errorText(error,capacity,ex.what());return 0;}
}

#include "SurfaceSampling.hpp"
extern "C" int R2CMappingSampleRGBA(const uint8_t* rgba,int w,int h,int factor,uint8_t* out,size_t count,char* error,size_t capacity){
 try{r2c::mapping::sampleRGBA(rgba,w,h,factor,out,count);return 1;}catch(const std::exception& ex){errorText(error,capacity,ex.what());return 0;}
}
