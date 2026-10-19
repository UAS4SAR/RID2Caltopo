#include <chrono>
#include "PhotoSeams.hpp"
#include "RasterCache.hpp"
// R2C local camera adjustment and orthographic surface texturing.
// Original imagery is read-only; all coordinates are a bounded local ENU frame.
#include "SurfaceCore.hpp"
#include "SurfaceRefinement.hpp"
#include <ceres/ceres.h>
#include <ceres/rotation.h>
#include <Eigen/Dense>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <map>
#include <stdexcept>
#include <sstream>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
#ifdef _OPENMP
#include <omp.h>
#endif

namespace r2c::mapping {
using V3=Eigen::Vector3d; using M3=Eigen::Matrix3d;
struct Observation{int camera,point;double x,y;};
struct Camera{std::array<double,6> p;V3 prior,sigma;M3 initial;};
struct Reprojection {
  double x,y;
  template<class T> bool operator()(const T* c,const T* k,const T* point,T* residual)const{
    T d[3]={point[0]-c[3],point[1]-c[4],point[2]-c[5]},v[3];
    ceres::AngleAxisRotatePoint(c,d,v);
    T nx=v[0]/v[2],ny=v[1]/v[2],rr=nx*nx+ny*ny,rad=T(1)+k[3]*rr+k[4]*rr*rr;
    residual[0]=k[0]*nx*rad+k[1]-T(x);residual[1]=k[0]*ny*rad+k[2]-T(y);return true;
  }
};
struct PositionPrior {
  V3 center,sigma;
  template<class T> bool operator()(const T* c,T* r)const{
    for(int k=0;k<3;++k)r[k]=(c[k+3]-T(center[k]))/T(sigma[k]);return true;
  }
};
struct AttitudePrior {
  M3 initial;
  template<class T> bool operator()(const T* c,T* residual)const{
    T r[9];ceres::AngleAxisToRotationMatrix(c,r);
    for(int row=0;row<3;++row)for(int col=0;col<3;++col)
      residual[row*3+col]=(r[col*3+row]-T(initial(row,col)))*T(8);return true;
  }
};
struct FocalPrior{double focal;template<class T>bool operator()(const T* k,T* r)const{r[0]=(k[0]-T(focal))/T(focal*.05);return true;}};
struct SurfaceAnchor{V3 target;template<class T>bool operator()(const T* p,T* r)const{for(int k=0;k<3;++k)r[k]=(p[k]-T(target[k]))/T(.6);return true;}};
M3 rotation(const Camera& c){double r[9];ceres::AngleAxisToRotationMatrix(c.p.data(),r);return Eigen::Map<M3>(r);}
V3 center(const Camera& c){return V3(c.p[3],c.p[4],c.p[5]);}
double error(const Camera& c,const std::array<double,5>& k,const V3& p,const Observation& o){
  V3 v=rotation(c)*(p-center(c));if(v.z()<=1)return 1e9;
  double x=v.x()/v.z(),y=v.y()/v.z(),rr=x*x+y*y,rad=1+k[3]*rr+k[4]*rr*rr;
  return std::hypot(k[0]*x*rad+k[1]-o.x,k[0]*y*rad+k[2]-o.y);
}
struct Callback:ceres::IterationCallback {
  Progress* progress;int offset;bool stopped=false;
  ceres::CallbackReturnType operator()(const ceres::IterationSummary& s)override{
    stopped=!progress->iteration(s.iteration+offset,s.cost);
    return stopped?ceres::SOLVER_ABORT:ceres::SOLVER_CONTINUE;
  }
};
std::vector<double> adjust(Progress& progress,const std::vector<double>& input,
  const std::vector<double>& raw,int count,int width,int height,double focal,const std::vector<double>& laser,bool nominalCalibration){
  const int n=int(input.size()/15);if(input.size()%15||n<3||n>200||count<100||count>100000||raw.size()%4)throw std::runtime_error("Invalid surface adjustment input.");
  #ifdef _OPENMP
  omp_set_num_threads(2);
  #endif
  if(laser.size()!=size_t(n*3))throw std::runtime_error("Invalid exposure laser targets.");
  std::vector<Camera> cameras(n);
  for(int i=0;i<n;++i){auto& c=cameras[i];const double* a=&input[i*15];
    c.prior=V3(a[0],a[1],a[2]);c.sigma=V3(a[12],a[13],a[14]);
    if(!c.prior.allFinite()||c.sigma.minCoeff()<.005||c.sigma.maxCoeff()>10)throw std::runtime_error("Invalid RTK uncertainty.");
    for(int row=0;row<3;++row)for(int col=0;col<3;++col)c.initial(row,col)=a[3+row*3+col];
    if(!c.initial.allFinite()||(c.initial*c.initial.transpose()-M3::Identity()).norm()>1e-6||std::abs(c.initial.determinant()-1)>1e-6)throw std::runtime_error("Invalid camera attitude.");
    Eigen::AngleAxisd angle(c.initial);V3 aa=angle.axis()*angle.angle();
    for(int k=0;k<3;++k){c.p[k]=aa[k];c.p[k+3]=a[k];}
  }
  std::vector<Observation> observations;std::vector<std::vector<int>> tracks(count);
  for(size_t i=0;i<raw.size();i+=4){Observation o{int(raw[i]),int(raw[i+1]),raw[i+2],raw[i+3]};
    if(o.camera<0||o.camera>=n||o.point<0||o.point>=count||!std::isfinite(o.x)||!std::isfinite(o.y))throw std::runtime_error("Invalid feature track.");
    tracks[o.point].push_back(int(observations.size()));observations.push_back(o);
  }
  std::array<double,5> intrinsics{focal,(width-1)*.5,(height-1)*.5,0,0};
  std::vector<V3> points(count,V3::Zero());std::vector<bool> valid(count,false);int initialCount=0;
  for(int t=0;t<count;++t){if(tracks[t].size()<3)continue;M3 A=M3::Zero();V3 b=V3::Zero();
    for(int oi:tracks[t]){const auto& o=observations[oi];const auto& c=cameras[o.camera];
      V3 ray=c.initial.transpose()*V3((o.x-intrinsics[1])/focal,(o.y-intrinsics[2])/focal,1).normalized();
      M3 m=M3::Identity()-ray*ray.transpose();A+=m;b+=m*c.prior;
    }
    // Repeated exposures at a waypoint are useful observations, but their
    // nearly parallel rays alone cannot establish 3D depth.
    Eigen::SelfAdjointEigenSolver<M3> conditioning(A);
    if(conditioning.eigenvalues().minCoeff()<1e-4*conditioning.eigenvalues().maxCoeff())continue;
    points[t]=A.ldlt().solve(b);if(!points[t].allFinite()||points[t].z() < -60||points[t].z()>cameras[0].prior.z()-3)continue;
    // A fixed pixel gate implicitly assumes wide-angle sampling. A metre of GNSS
    // uncertainty spans far more pixels through a telephoto lens. Relax only the
    // initialization gate using the recorded prior; final residual/position checks
    // remain unchanged and determine whether this is a measured surface.
    std::vector<double> errors;for(int oi:tracks[t]){const auto& c=cameras[observations[oi].camera];
      const double depth=(c.initial*(points[t]-c.prior)).z();
      const double gate=std::max(24.,3*focal*c.sigma.head<2>().maxCoeff()/std::max(5.,depth));
      errors.push_back(error(c,intrinsics,points[t],observations[oi])/gate);}
    std::sort(errors.begin(),errors.end());if(errors[errors.size()/2]>1)continue;
    valid[t]=true;++initialCount;
  }
  if(initialCount<500)throw GeometryUnavailable("Too few triangulated tracks; no corrected surface published.");
  std::vector<bool> registered(n,true);
  // Exclusion codes are reported to Kotlin: 1 = insufficient observations,
  // 2 = a minor component disconnected from the dominant image block.
  std::vector<int> exclusions(n,0);
  int iterations=0;std::map<int,V3> anchors;
  for(int round=0;round<=3;++round){
    auto eligible=[&](const Observation& o){return registered[o.camera]&&valid[o.point]&&(round==0||error(cameras[o.camera],intrinsics,points[o.point],o)<(round==1?5:2.5));};
    // A selected image with no qualified multi-view support must never texture
    // the result. Remove it from adjustment, then recheck all remaining tracks
    // and cameras; originals remain retained and exclusions are returned.
    bool changed=true;
    while(changed){changed=false;std::vector<int> counts(n,0);
      for(const auto& o:observations)if(eligible(o))++counts[o.camera];
      for(int i=0;i<n;++i)if(registered[i]&&counts[i]<40){registered[i]=false;exclusions[i]=1;changed=true;}
      for(int t=0;t<count;++t)if(valid[t]){int good=0;for(int oi:tracks[t])if(eligible(observations[oi]))++good;
        if(good<3){valid[t]=false;changed=true;}}
    }
    if(std::count(registered.begin(),registered.end(),true)<3||std::count(valid.begin(),valid.end(),true)<500)
      throw GeometryUnavailable("Too few supported cameras or triangulated tracks; originals and previous layers are retained.");
    // RTK priors do not substitute for image connections between sub-blocks.
    std::vector<int> component(n);for(int i=0;i<n;++i)component[i]=i;
    auto root=[&](int i){while(component[i]!=i){component[i]=component[component[i]];i=component[i];}return i;};
    for(int t=0;t<count;++t)if(valid[t]){int first=-1;for(int oi:tracks[t]){const auto& o=observations[oi];if(!eligible(o))continue;
      if(first<0)first=o.camera;else component[root(o.camera)]=root(first);}}
    std::map<int,std::vector<int>> groups;std::vector<int> labels(n,-1),support(n,0);
    for(int i=0;i<n;++i)if(registered[i]){labels[i]=root(i);groups[root(i)].push_back(i);}
    for(const auto& o:observations)if(eligible(o))++support[o.camera];
    if(groups.size()>1){
      const auto largest=std::max_element(groups.begin(),groups.end(),[](const auto& a,const auto& b){return a.second.size()<b.second.size();});
      // Reconstruct the largest connected block instead of rejecting all usable
      // observations. Disconnected blocks are explicitly excluded, never joined
      // by RTK proximity. Geometric and laser-scale checks still apply to the
      // retained block, and the output reports its actual target coverage.
      for(int i=0;i<n;++i)if(registered[i]&&root(i)!=largest->first){registered[i]=false;exclusions[i]=2;}
      for(int t=0;t<count;++t)if(valid[t]){int good=0;for(int oi:tracks[t])if(eligible(observations[oi]))++good;if(good<3)valid[t]=false;}
    }
    progress.connectivity(round+1,labels,support,exclusions);
    if(std::count(valid.begin(),valid.end(),true)<500)throw GeometryUnavailable("The connected image group has too few triangulated tracks; no corrected surface published.");
    // Recheck connectivity after the final solve's outlier removal as well.
    if(round==3)break;
    ceres::Problem problem;
    problem.AddResidualBlock(new ceres::AutoDiffCostFunction<FocalPrior,1,5>(new FocalPrior{focal}),nullptr,intrinsics.data());
    for(const auto& anchor:anchors)if(valid[anchor.first])problem.AddResidualBlock(new ceres::AutoDiffCostFunction<SurfaceAnchor,3,3>(new SurfaceAnchor{anchor.second}),new ceres::HuberLoss(1.5),points[anchor.first].data());
    for(int i=0;i<n;++i)if(registered[i]){auto& c=cameras[i];problem.AddResidualBlock(new ceres::AutoDiffCostFunction<PositionPrior,3,6>(new PositionPrior{c.prior,c.sigma}),nullptr,c.p.data());
      problem.AddResidualBlock(new ceres::AutoDiffCostFunction<AttitudePrior,9,6>(new AttitudePrior{c.initial}),nullptr,c.p.data());}
    std::vector<int> counts(n,0);int active=0;
    for(const auto& o:observations)if(eligible(o)){
      problem.AddResidualBlock(new ceres::AutoDiffCostFunction<Reprojection,2,6,5,3>(new Reprojection{o.x,o.y}),new ceres::HuberLoss(1),cameras[o.camera].p.data(),intrinsics.data(),points[o.point].data());++counts[o.camera];++active;}
    if(std::any_of(counts.begin(),counts.end(),[&](int value){return value>0&&value<40;})){
      std::ostringstream message;message<<"Camera alignment round "<<(round+1)<<": photos with fewer than 40 consistent 3D observations: ";
      for(int i=0;i<n;++i)if(registered[i]&&counts[i]<40)message<<(i+1)<<" ("<<counts[i]<<"), ";
      message<<"Originals and previous layers are retained.";throw GeometryUnavailable(message.str());
    }
    if(nominalCalibration)problem.SetParameterBlockConstant(intrinsics.data());
    problem.SetParameterLowerBound(intrinsics.data(),0,focal*.7);problem.SetParameterUpperBound(intrinsics.data(),0,focal*1.3);
    for(int k=1;k<=2;++k){problem.SetParameterLowerBound(intrinsics.data(),k,(k==1?width:height)*.45);problem.SetParameterUpperBound(intrinsics.data(),k,(k==1?width:height)*.55);}
    for(int k=3;k<5;++k){problem.SetParameterLowerBound(intrinsics.data(),k,-.5);problem.SetParameterUpperBound(intrinsics.data(),k,.5);}
    ceres::Solver::Options options;options.linear_solver_type=ceres::SPARSE_SCHUR;options.sparse_linear_algebra_library_type=ceres::EIGEN_SPARSE;options.num_threads=2;
    options.max_num_iterations=round==0?100:60;options.function_tolerance=1e-7;options.parameter_tolerance=1e-8;options.minimizer_progress_to_stdout=false;
    Callback callback;callback.progress=&progress;callback.offset=round*100;options.callbacks.push_back(&callback);
    ceres::Solver::Summary summary;ceres::Solve(options,&problem,&summary);iterations+=int(summary.iterations.size());
    if(callback.stopped)throw std::runtime_error("Processing cancelled; originals retained.");if(!summary.IsSolutionUsable())throw GeometryUnavailable("RTK camera adjustment did not converge.");
    for(int t=0;t<count;++t)if(valid[t]){int good=0;for(int oi:tracks[t])if(registered[observations[oi].camera]&&error(cameras[observations[oi].camera],intrinsics,points[t],observations[oi])<3)++good;
      if(good<3||!points[t].allFinite()||points[t].z() < -60||points[t].z()>cameras[0].prior.z()-3)valid[t]=false;}
    if(round==0&&!nominalCalibration){
      // A near-constant-altitude nadir block has a focal/height ambiguity. Valid
      // exposure laser targets anchor nearby measured features, with a generous
      // 0.6 m tolerance for beam/feature separation and terrain roughness.
      for(int camera=0;camera<n;++camera){if(!registered[camera])continue;V3 target(laser[camera*3],laser[camera*3+1],laser[camera*3+2]);if(!target.allFinite())continue;
        int closest=-1;double distance=.8;
        for(const auto& o:observations)if(o.camera==camera&&valid[o.point]&&std::hypot(o.x-width*.5,o.y-height*.5)<20){
          // Preserve the qualified nadir scale-anchor association: vertical scale
          // is precisely what is still ambiguous before this first anchor round.
          // Obliques require proximity in all three dimensions.
          bool nadir=std::hypot(cameras[camera].initial(2,0),cameras[camera].initial(2,1))<std::sin(3*std::acos(-1.)/180);
          double d=nadir?(points[o.point]-target).head<2>().norm():(points[o.point]-target).norm();if(d<distance){distance=d;closest=o.point;}}
        if(closest>=0)anchors.emplace(closest,target);
      }
      if(anchors.size()<6){
        // Without enough independent height anchors, fitting focal length and
        // surface height together is underconstrained. Fix the nominal camera
        // calibration instead; the measured camera baseline establishes scale.
        // This is explicitly reported as an unverified nominal-calibration result.
        return adjust(progress,input,raw,count,width,height,focal,laser,true);
      }
    }
  }
  double err=0,prior=0,maxShift=0,maxNormalizedShift=0;bool positionFailed=false;int accepted=0,pointCount=0;std::vector<int> cameraObs(n,0);
  for(const auto& o:observations)if(registered[o.camera]&&valid[o.point]){double e=error(cameras[o.camera],intrinsics,points[o.point],o);if(e<3){err+=e*e;++accepted;++cameraObs[o.camera];}}
  int used=0;int minimumObservations=std::numeric_limits<int>::max();
  for(int i=0;i<n;++i)if(registered[i]){const auto& c=cameras[i];double e=(center(c)-c.prior).norm();prior+=e*e;maxShift=std::max(maxShift,e);
    double normalized=((center(c)-c.prior).array()/c.sigma.array()).matrix().norm();maxNormalizedShift=std::max(maxNormalizedShift,normalized);
    // Fixed-RTK retains its 0.5 m limit. Conservative GNSS priors require an
    // uncertainty-normalized check; never allow a camera to move over 10 m.
    positionFailed |= c.sigma.maxCoeff()<1 ? e>.5 : (normalized>5 || e>10);
    ++used;minimumObservations=std::min(minimumObservations,cameraObs[i]);}
  for(bool v:valid)if(v)++pointCount;
  const double rms=std::sqrt(err/std::max(1,accepted));if(pointCount<500||minimumObservations<40||rms>1.5||positionFailed){std::ostringstream message;
 message<<"Surface geometry checks: "<<pointCount<<" points (need 500), minimum "<<minimumObservations<<" observations per photo (need 40), reprojection "<<rms<<" px (limit 1.5), maximum position adjustment "<<maxShift<<" m, maximum normalized position residual "<<maxNormalizedShift<<" sigma (fixed-RTK limit 0.5 m; GNSS limits 5 sigma and 10 m).";throw GeometryUnavailable(message.str());}
  std::vector<double> result{double(used),double(count),intrinsics[0],intrinsics[1],intrinsics[2],intrinsics[3],intrinsics[4],rms,std::sqrt(prior/used),maxShift,double(accepted),double(iterations)};
  for(int i=0;i<n;++i){if(!registered[i]){for(int k=0;k<12;++k)result.push_back(std::numeric_limits<double>::quiet_NaN());continue;}
    const auto& c=cameras[i];M3 r=rotation(c);for(int row=0;row<3;++row)for(int col=0;col<3;++col)result.push_back(r(row,col));V3 p=center(c);for(int k=0;k<3;++k)result.push_back(p[k]);}
  int validAnchors=0;for(const auto& anchor:anchors)if(valid[anchor.first])++validAnchors;
  if(!nominalCalibration&&validAnchors<6)throw GeometryUnavailable("Too few retained exposure laser anchors to resolve the terrain-height scale.");
  for(int t=0;t<count;++t)for(int k=0;k<3;++k)result.push_back(valid[t]?points[t][k]:std::numeric_limits<double>::quiet_NaN());result.push_back(double(validAnchors));return result;
}
RasterCache images;
// Visibility depends on the surface-to-camera ray, never on gimbal pitch.
// A downward-pointing camera can still look across a roof at an offset point.
ProjectionVisibility projectionVisibility(const std::vector<float>& z,int gw,int gh,double gx,double gy,double cell,const double* a,double x,double y,double value){
 const double horizontal=std::hypot(x-a[9],y-a[10]);
 const int steps=int(std::ceil(horizontal/(cell*.5)));
 if(steps>1024)return ProjectionVisibility::Blocked; // Bound unqualified rays.
 bool unknown=false;
 for(int s=1;s<steps;++s){
  const double f=double(s)/steps;
  const double rx=x+(a[9]-x)*f,ry=y+(a[10]-y)*f,rz=value+(a[11]-value)*f;
  const int sx=int(std::floor((rx-gx)/cell)),sy=int(std::floor((ry-gy)/cell));
  if(sx<0||sy<0||sx>=gw-1||sy>=gh-1){unknown=true;continue;}
  // Sample the ray position, preserving roof/ground height discontinuities.
  double terrain=0;
  if(!sampleSurface(z.data(),gw,sx,sy,(rx-gx)/cell-sx,(ry-gy)/cell-sy,terrain)){unknown=true;continue;}
  if(terrain>rz+.65)return ProjectionVisibility::Blocked;
 }
 return unknown?ProjectionVisibility::Unknown:ProjectionVisibility::Clear;
}
bool projectionVisible(const std::vector<float>& z,int gw,int gh,double gx,double gy,double cell,const double* a,double x,double y,double value){
 return projectionVisibility(z,gw,gh,gx,gy,cell,a,x,y,value)==ProjectionVisibility::Clear;
}
// Exceeds every angular/border score: a clear ray always beats an unknown one.
constexpr double unknownRayPenalty=10.;
struct SeamAtlas {
 std::vector<float> heights;
 std::vector<double> cameras,intrinsics;
 std::vector<std::string> paths;
 std::vector<int> labels;
 int width=0,height=0,imageWidth=0,imageHeight=0;
 double x=0,y=0,cell=0;
 bool ready=false;
};
SeamAtlas seams;
void prepareProjection(const std::vector<float>& z,int gw,int gh,double gx,double gy,double cell,
 const std::vector<double>& c,const std::vector<double>& k,int iw,int ih,const std::vector<std::string>& paths,
 const std::function<void(double)>& progress){
 if(gw<2||gh<2||size_t(gw)*gh>3000000||z.size()!=size_t(gw)*gh||cell<=0||c.size()!=paths.size()*12||k.size()!=5||iw<4||ih<4)throw std::runtime_error("Invalid projection input.");
 if(seams.ready&&seams.width==gw&&seams.height==gh&&seams.x==gx&&seams.y==gy&&seams.cell==cell&&seams.imageWidth==iw&&seams.imageHeight==ih&&seams.cameras==c&&seams.intrinsics==k&&seams.paths==paths&&seams.heights.size()==z.size()&&!std::memcmp(seams.heights.data(),z.data(),z.size()*sizeof(float)))return;
 auto last=std::chrono::steady_clock::now()-std::chrono::seconds(1);
 auto pulse=[&](double fraction){auto now=std::chrono::steady_clock::now();if(fraction>=1||now-last>=std::chrono::milliseconds(100)){progress(fraction);last=now;}};
 pulse(0);
 seams={};seams.width=gw;seams.height=gh;seams.x=gx;seams.y=gy;seams.cell=cell;seams.imageWidth=iw;seams.imageHeight=ih;seams.cameras=c;seams.intrinsics=k;seams.paths=paths;seams.heights=z;seams.labels.assign(z.size(),-1);
 auto project=[&](int p,int i,bool color,double limit=INFINITY){SeamSample result;int ix=p%gw,iy=p/gw;double h=0;if(ix>=gw-1||iy>=gh-1||!sampleSurface(z.data(),gw,ix,iy,.5,.5,h))return result;
  double x=gx+(ix+.5)*cell,y=gy+(iy+.5)*cell;const double* a=&c[i*12];double dx=x-a[9],dy=y-a[10],dz=h-a[11],cz=a[6]*dx+a[7]*dy+a[8]*dz;if(cz<=3)return result;
  double cx=(a[0]*dx+a[1]*dy+a[2]*dz)/cz,cy=(a[3]*dx+a[4]*dy+a[5]*dz)/cz,rr=cx*cx+cy*cy,rad=1+k[3]*rr+k[4]*rr*rr,px=k[0]*cx*rad+k[1],py=k[0]*cy*rad+k[2];
  if(px<1||py<1||px>=iw-2||py>=ih-2)return result;
  double edge=std::min({px,py,iw-1-px,ih-1-py});result.score=overheadProjectionScore(dx,dy,dz,rr,edge);
  if(result.score>=limit)return result;
  auto visibility=projectionVisibility(z,gw,gh,gx,gy,cell,a,x,y,h);
  if(visibility==ProjectionVisibility::Blocked)return SeamSample{};
  if(visibility==ProjectionVisibility::Unknown)result.score+=unknownRayPenalty;
  if(result.score>=limit)return SeamSample{};
  if(!color)return result;
  const auto* pixels=images.get(paths[i],iw,ih);
  int xx=int(px),yy=int(py);double fx=px-xx,fy=py-yy;
  for(int ch=0;ch<3;++ch){auto v=[&](int dx,int dy){return double((pixels[(yy+dy)*iw+xx+dx]>>(ch*8))&255);};result.rgb[ch]=(1-fy)*((1-fx)*v(0,0)+fx*v(1,0))+fy*((1-fx)*v(0,1)+fx*v(1,1));}return result;
 };
 for(int p=0;p<int(z.size());++p){if(p%4096==0)pulse(.5*p/z.size());if(!std::isfinite(z[p]))continue;double best=INFINITY;for(int i=0;i<int(paths.size());++i){double score=project(p,i,false,best).score;if(score<best){best=score;seams.labels[p]=i;}}}
 choosePhotoSeams(seams.labels,gw,gh,[&](int p,int i){return project(p,i,true);},[&](double fraction){pulse(.5+.5*fraction);});seams.ready=true;
}
int render(const std::vector<float>& z,int gw,int gh,double gx,double gy,double cell,
 const std::vector<double>& c,const std::vector<double>& k,int iw,int ih,
 const std::vector<std::string>& paths,double tx,double ty,double step,std::vector<int32_t>& out,bool ordered){
 if(gw<2||gh<2||cell<=0||z.size()!=size_t(gw)*gh)throw std::runtime_error("Invalid surface tile.");
 if(c.size()%12||k.size()!=5)throw std::runtime_error("Invalid calibrated cameras.");
 int n=int(c.size()/12);if(paths.size()!=size_t(n))throw std::runtime_error("Image camera count mismatch.");
 prepareProjection(z,gw,gh,gx,gy,cell,c,k,iw,ih,paths,[](double){});
 out.assign(256*256,0);int coverage=0;
    // Prefer likely central views before expensive oblique occlusion walks. Every
    // camera is still tested per pixel, with original-index tie breaking preserved.
    const double centreX=tx+128*step,centreY=ty-128*step;
    int centreCol=int(std::floor((centreX-gx)/cell)),centreRow=int(std::floor((centreY-gy)/cell));
    double centreZ=0;
    if(centreCol>=0&&centreRow>=0&&centreCol<gw&&centreRow<gh&&std::isfinite(z[centreRow*gw+centreCol]))centreZ=z[centreRow*gw+centreCol];
    std::vector<std::pair<double,int>> order;order.reserve(n);
    for(int i=0;i<n;++i){const double* a=&c[i*12];double dx=centreX-a[9],dy=centreY-a[10],dz=centreZ-a[11];
      double cz=a[6]*dx+a[7]*dy+a[8]*dz,score=std::numeric_limits<double>::infinity();
      if(cz>3){double cx=(a[0]*dx+a[1]*dy+a[2]*dz)/cz,cy=(a[3]*dx+a[4]*dy+a[5]*dz)/cz;
        double rr=cx*cx+cy*cy,rad=1+k[3]*rr+k[4]*rr*rr,px=k[0]*cx*rad+k[1],py=k[0]*cy*rad+k[2];
        if(px>=1&&py>=1&&px<iw-2&&py<ih-2){double edge=std::min({px,py,iw-1-px,ih-1-py});score=overheadProjectionScore(dx,dy,dz,rr,edge);}}
      order.emplace_back(score,i);}
    if(ordered&&std::abs(step)*256<=8)std::sort(order.begin(),order.end());
    for(int row=0;row<256;++row)for(int col=0;col<256;++col){double x=tx+(col+.5)*step,y=ty-(row+.5)*step,u=(x-gx)/cell,v=(y-gy)/cell;int ix=int(std::floor(u)),iy=int(std::floor(v));if(ix<0||iy<0||ix>=gw-1||iy>=gh-1)continue;
      double value=0;if(!sampleSurface(z.data(),gw,ix,iy,u-ix,v-iy,value))continue;
      int best=-1;double bestScore=1e9,bx=0,by=0;
      for(const auto& candidate:order){int i=candidate.second;const double* a=&c[i*12];double dx=x-a[9],dy=y-a[10],dz=value-a[11];double cz=a[6]*dx+a[7]*dy+a[8]*dz;if(cz<=3)continue;
        double cx=(a[0]*dx+a[1]*dy+a[2]*dz)/cz,cy=(a[3]*dx+a[4]*dy+a[5]*dz)/cz,rr=cx*cx+cy*cy,rad=1+k[3]*rr+k[4]*rr*rr;
        double px=k[0]*cx*rad+k[1],py=k[0]*cy*rad+k[2];if(px<1||py<1||px>=iw-2||py>=ih-2)continue;
        // Prefer the most nearly vertical viewing ray, not the optical-axis
        // centre of an oblique photo: height errors then cause less XY warping.
        double edge=std::min({px,py,iw-1-px,ih-1-py});double score=overheadProjectionScore(dx,dy,dz,rr,edge);
        // A seam label is only a bounded tie-breaker. Recheck at each output
        // pixel because the grid cell may straddle a roof/ground discontinuity.
        if(seams.labels[iy*gw+ix]==i)score-=overheadSeamAllowance;
        if(score>bestScore||(score==bestScore&&(best<0||i>=best)))continue;
        // Reject measured obstructions for every camera. Missing intervening
        // heights are a fallback only; they never outrank a checked clear ray.
        auto visibility=projectionVisibility(z,gw,gh,gx,gy,cell,a,x,y,value);
        if(visibility==ProjectionVisibility::Blocked)continue;
        if(visibility==ProjectionVisibility::Unknown)score+=unknownRayPenalty;
        if(score<bestScore||(score==bestScore&&(best<0||i<best))){best=i;bestScore=score;bx=px;by=py;}}
      if(best<0)continue;int xx=int(bx),yy=int(by);double fx=bx-xx,fy=by-yy;uint32_t color=0xff000000;
      const auto* pixels=images.get(paths[best],iw,ih);
      // Android copyPixels is RGBA bytes on little-endian ARM; Java colors are ARGB ints.
      for(int channel=0;channel<3;++channel){double a=(pixels[yy*iw+xx]>>(channel*8))&255,b=(pixels[yy*iw+xx+1]>>(channel*8))&255,d=(pixels[(yy+1)*iw+xx]>>(channel*8))&255,f=(pixels[(yy+1)*iw+xx+1]>>(channel*8))&255;
        unsigned val=unsigned(std::clamp((1-fy)*((1-fx)*a+fx*b)+fy*((1-fx)*d+fx*f),0.,255.));color|=val<<((2-channel)*8);}
      out[row*256+col]=int32_t(color);++coverage;
    }
 return coverage;
}
void releasePixels(){images.clear();seams={};}
}
