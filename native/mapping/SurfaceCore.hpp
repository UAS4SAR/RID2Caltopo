#pragma once
#include <cstdint>
#include <functional>
#include <string>
#include <stdexcept>
#include <vector>
namespace r2c::mapping {
// Only insufficient/unstable geometry may fall back; I/O, cancellation and invalid inputs must propagate.
struct GeometryUnavailable : std::runtime_error { using std::runtime_error::runtime_error; };
struct Progress {
    virtual ~Progress() = default;
    virtual bool iteration(int iteration, double cost) = 0;
    virtual void connectivity(int round, const std::vector<int>& groups,
                              const std::vector<int>& support, const std::vector<int>& exclusions) = 0;
};
std::vector<double> adjust(Progress&, const std::vector<double>& cameras,
    const std::vector<double>& observations, int count, int width, int height,
    double focal, const std::vector<double>& laser, bool nominalCalibration=false);
// Serial worker API. RGBA input rasters are mapped read-only; output is ARGB.
int render(const std::vector<float>& surface, int width, int height, double x, double y,
    double cell, const std::vector<double>& cameras, const std::vector<double>& intrinsics,
    int imageWidth, int imageHeight, const std::vector<std::string>& paths,
    double tileX, double tileY, double step, std::vector<int32_t>& output, bool ordered=true);
void prepareProjection(const std::vector<float>&,int,int,double,double,double,const std::vector<double>&,const std::vector<double>&,int,int,const std::vector<std::string>&,const std::function<void(double)>&);
enum class ProjectionVisibility { Clear, Unknown, Blocked };
ProjectionVisibility projectionVisibility(const std::vector<float>&,int,int,double,double,double,const double*,double,double,double);
bool projectionVisible(const std::vector<float>&,int,int,double,double,double,const double*,double,double,double);
void releasePixels();
}
