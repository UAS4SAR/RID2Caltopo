#include "include/aol_grid.h"
#include "lazperf/readers.hpp"
#include <GeographicLib/TransverseMercator.hpp>
#include <cmath>
#include <cstring>
#include <fstream>
#include <memory>
#include <regex>
#include <stdexcept>
#include <vector>
#include <algorithm>
#include <cctype>

namespace {
constexpr double pi=3.14159265358979323846, rad=pi/180, R=6371008.8;
struct Grid {
    double lat,lon,west,south,coslat;
    int width,height,projection=0,zone=0;
    bool wgs84=false;
    double xmin=-INFINITY,xmax=INFINITY,ymin=-INFINITY,ymax=INFINITY;
    std::vector<float> surface,ground;
    std::vector<double> distance;
    std::unique_ptr<lazperf::reader::named_file> reader;
    std::vector<char> point;
    uint64_t read=0,count=0;
    std::string crs,error;
    Grid(double la,double lo,double w,double s,int x,int y):lat(la),lon(lo),west(w),south(s),coslat(cos(la*rad)),width(x),height(y),
        surface(x*y,NAN),ground(x*y,NAN),distance(x*y,9.0f) {}
};
bool epsg(const std::string& s,int code) {
    return std::regex_search(s,std::regex("(?:AUTHORITY|ID)\\s*\\[\\s*\"EPSG\"\\s*,\\s*\"?"+std::to_string(code)+"\"?\\s*\\]"));
}
// Inverse GRS80 Albers, EPSG:6350. Coordinates stay in their survey reference;
// there is no vertical transform or mixing with an unrelated terrain model.
double q(double phi) {
    const double e=sqrt(0.006694380022900787),s=sin(phi);
    return (1-e*e)*(s/(1-e*e*s*s)-log((1-e*s)/(1+e*s))/(2*e));
}
void albers(double x,double y,double&lat,double&lon) {
    const double e2=0.006694380022900787,a=6378137;
    const double p1=29.5*rad,p2=45.5*rad;
    const double m1=cos(p1)/sqrt(1-e2*sin(p1)*sin(p1)),m2=cos(p2)/sqrt(1-e2*sin(p2)*sin(p2));
    const double n=(m1*m1-m2*m2)/(q(p2)-q(p1)),C=m1*m1+n*q(p1);
    const double rho0=a*sqrt(C-n*q(23*rad))/n;
    const double rho=hypot(x,rho0-y),theta=atan2(x,rho0-y),target=(C-pow(rho*n/a,2))/n;
    double phi=asin(std::clamp(target/2.0,-1.0,1.0));
    for(int i=0;i<8;i++) {
        const double s=sin(phi),d=2*(1-e2)*cos(phi)/pow(1-e2*s*s,2);
        const double delta=(q(phi)-target)/d;phi-=delta;if(fabs(delta)<1e-12)break;
    }
    lat=phi/rad;lon=-96+theta/n/rad;
}
const GeographicLib::TransverseMercator& transverse(bool wgs84) {
    static const GeographicLib::TransverseMercator grs80(6378137,1/298.257222101,.9996);
    static const GeographicLib::TransverseMercator wgs(6378137,1/298.257223563,.9996);
    return wgs84?wgs:grs80;
}
void utm(double x,double y,int zone,double&lat,double&lon,bool wgs84) {
    double gamma,scale;transverse(wgs84).Reverse(zone*6-183,x-500000,y,lat,lon,gamma,scale);
}
void forward(double lat,double lon,int projection,int zone,double&x,double&y,bool wgs84) {
    const double a=6378137,e2=.006694380022900787,p=lat*rad;
    if(projection==6350) {
        const double p1=29.5*rad,p2=45.5*rad,m1=cos(p1)/sqrt(1-e2*sin(p1)*sin(p1)),m2=cos(p2)/sqrt(1-e2*sin(p2)*sin(p2));
        const double n=(m1*m1-m2*m2)/(q(p2)-q(p1)),C=m1*m1+n*q(p1),rho=a*sqrt(C-n*q(p))/n,rho0=a*sqrt(C-n*q(23*rad))/n,t=n*(lon+96)*rad;
        x=rho*sin(t);y=rho0-rho*cos(t);
    } else {
        double gamma,scale;transverse(wgs84).Forward(zone*6-183,lat,lon,x,y,gamma,scale);x+=500000;
    }
}
void crop(Grid&g) {
    g.xmin=g.ymin=INFINITY;g.xmax=g.ymax=-INFINITY;
    for(int side=0;side<4;side++)for(int i=0;i<=16;i++) {
        double u=g.west-3+(g.width+6)*(side==0?0:side==1?1:i/16.0),v=g.south-3+(g.height+6)*(side==2?0:side==3?1:i/16.0),x,y;
        forward(g.lat+v/R/rad,g.lon+u/(R*g.coslat)/rad,g.projection,g.zone,x,y,g.wgs84);
        g.xmin=std::min(g.xmin,x-10);g.xmax=std::max(g.xmax,x+10);g.ymin=std::min(g.ymin,y-10);g.ymax=std::max(g.ymax,y+10);
    }
}
void add(Grid& g,const char*p) {
    const auto&h=g.reader->header();const int format=h.pointFormat();
    const unsigned char cls=(unsigned char)p[format>=6?16:15];
    const bool withheld=format>=6?((unsigned char)p[15]&4):(cls&128);
    const int classification=format>=6?cls:cls&31;
    if(withheld||classification==7||classification==18)return;
    int32_t xyz[3];memcpy(xyz,p,12);
    double x=xyz[0]*h.scale.x+h.offset.x,y=xyz[1]*h.scale.y+h.offset.y,z=xyz[2]*h.scale.z+h.offset.z;
    if(!std::isfinite(x)||!std::isfinite(y)||!std::isfinite(z)||x<g.xmin||x>g.xmax||y<g.ymin||y>g.ymax)return;
    double lat,lon;
    if(g.projection==6350)albers(x,y,lat,lon);else utm(x,y,g.zone,lat,lon,g.wgs84);
    x=(lon-g.lon)*rad*R*g.coslat;y=(lat-g.lat)*rad*R;
    if(x<g.west-3||y<g.south-3||x>g.west+g.width+3||y>g.south+g.height+3)return;
    const int col=(int)floor(x-g.west),row=(int)floor(y-g.south);
    if(col>=0&&row>=0&&col<g.width&&row<g.height) {
        float&v=g.surface[row*g.width+col];if(!std::isfinite(v)||z>v)v=(float)z;
    }
    if(classification==2)for(int r=std::max(0,row-3);r<=std::min(g.height-1,row+3);r++)for(int c=std::max(0,col-3);c<=std::min(g.width-1,col+3);c++) {
        const double d=pow(x-g.west-c-.5,2)+pow(y-g.south-r-.5,2);const int index=r*g.width+c;
        if(d<g.distance[index]) {g.distance[index]=d;g.ground[index]=(float)z;}
    }
}
}
extern "C" {
void*aol_grid_create(double la,double lo,double w,double s,int x,int y) {
    if(!std::isfinite(la)||!std::isfinite(lo)||!std::isfinite(w)||!std::isfinite(s)||fabs(la)>=70||fabs(lo)>180||x<1||y<1||x>1200||y>1200)return nullptr;
    try{return new Grid(la,lo,w,s,x,y);}catch(...){return nullptr;}
}
void aol_grid_free(void*v){delete (Grid*)v;}
int aol_grid_open(void*v,const char*path){auto&g=*(Grid*)v;try{
    g.reader.reset();g.reader=std::make_unique<lazperf::reader::named_file>(path);
    const auto&h=g.reader->header();
    if(h.point_record_length<20||h.point_record_length>512||h.pointFormat()>8||h.pointFormat()==4||h.pointFormat()==5||g.reader->pointCount()>150000000)throw std::runtime_error("Unsupported or excessive LAS point layout");
    const int minimum[]={20,28,26,34,0,0,30,36,38};
    if(h.point_record_length<minimum[h.pointFormat()] || !std::isfinite(h.scale.x) || !std::isfinite(h.scale.y) || !std::isfinite(h.scale.z) || h.scale.x<=0 || h.scale.y<=0 || h.scale.z<=0 || !std::isfinite(h.offset.x) || !std::isfinite(h.offset.y) || !std::isfinite(h.offset.z))throw std::runtime_error("Invalid LAS record or coordinate scale");
    auto bytes=g.reader->vlrData("LASF_Projection",2112);std::string crs(bytes.begin(),bytes.end());
    while(!crs.empty() && (crs.back()==0 || std::isspace((unsigned char)crs.back())))crs.pop_back();
    if(crs.empty()||crs.size()>100000||!epsg(crs,5703))throw std::runtime_error("AOL requires explicit NAVD88 metre WKT; this source needs reference qualification");
    if(!g.crs.empty()&&g.crs!=crs)throw std::runtime_error("Survey tile reference mismatch; no implicit datum mixing");
    g.projection=0;g.zone=0;
    if(epsg(crs,6350))g.projection=6350;
    for(int zone=1;zone<=60&&!g.projection;zone++) {
        if(epsg(crs,32600+zone)||(zone<=23&&epsg(crs,26900+zone))||(zone>=1&&zone<=19&&epsg(crs,6329+zone))) {g.projection=1;g.zone=zone;g.wgs84=epsg(crs,32600+zone);}
    }
    if(!g.projection)throw std::runtime_error("Unsupported horizontal lidar reference; supported: CONUS Albers and northern UTM metres");
    crop(g);g.crs=crs;g.point.resize(h.point_record_length);g.read=0;g.count=(h.maxx<g.xmin || h.minx>g.xmax || h.maxy<g.ymin || h.miny>g.ymax) ? 0 : g.reader->pointCount();g.error.clear();return 0;
}catch(const std::exception&e){g.error=e.what();g.reader.reset();return -1;}}
int aol_grid_step(void*v,int batch){auto&g=*(Grid*)v;try{
    if(!g.reader||batch<1||batch>50000)throw std::runtime_error("Invalid preparation batch");
    const uint64_t end=std::min(g.count,g.read+(uint64_t)batch);
    while(g.read<end){g.reader->readPoint(g.point.data());add(g,g.point.data());g.read++;}
    return g.read<g.count?1:0;
}catch(const std::exception&e){g.error=e.what();return -1;}}
int aol_grid_write(void*v,const char*s,const char*t){auto&g=*(Grid*)v;try{
    if(g.crs.empty())throw std::runtime_error("No verified source points");
    std::ofstream a(s,std::ios::binary),b(t,std::ios::binary);
    a.write((const char*)g.surface.data(),g.surface.size()*4);b.write((const char*)g.ground.data(),g.ground.size()*4);
    a.close();b.close();if(!a||!b)throw std::runtime_error("Unable to write prepared rasters");return 0;
}catch(const std::exception&e){g.error=e.what();return -1;}}
const char*aol_grid_error(void*v){return ((Grid*)v)->error.c_str();}
const char*aol_grid_crs(void*v){return ((Grid*)v)->crs.c_str();}
uint64_t aol_grid_read(void*v){return ((Grid*)v)->read;}
uint64_t aol_grid_count(void*v){return ((Grid*)v)->count;}
uint64_t aol_grid_missing(void*v){uint64_t n=0;for(float f:((Grid*)v)->surface)if(!std::isfinite(f))n++;return n;}
}
