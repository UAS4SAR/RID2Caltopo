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
    double tileX, double tileY, double step, std::vector<int32_t>& output, bool ordered=true,
    const std::vector<float>* fill=nullptr);
// Optional fill (see buildOrthoFill) supplies heights only where measured is NaN. Ray occlusion, seam labels and
// colours of measured cells are exactly those of the fill-less call; fill-only cells are labelled by best view.
void prepareProjection(const std::vector<float>&,int,int,double,double,double,const std::vector<double>&,const std::vector<double>&,int,int,const std::vector<std::string>&,const std::function<void(double)>&,
    const std::vector<float>* fill=nullptr);
// Ortho fill of unknown cells. Bounded memory and O(cells): a pull-push pyramid (inverse-distance-like) within
// fillRadiusMeters of a measured cell; beyond it the median of measured heights within planeWindowMeters, else
// planeHeight (non-finite = none, cell stays empty). Mesh export must keep using measured heights.
struct OrthoFillOptions { bool enabled=true; double fillRadiusMeters=25; double planeHeight=0; double planeWindowMeters=50; };
struct OrthoFillStats { int estimatedFillCells=0; double knownFraction=0, estimatedFraction=0, emptyFraction=0; int elapsedMs=0; double fillRadiusMeters=25; };
// estimatedMaskBits: packed LSB-first, (w*h+7)/8 bytes. fillOut: measured where finite, estimate where filled, NaN where empty.
OrthoFillStats buildOrthoFill(const std::vector<float>& measured, int w, int h, double cell, const OrthoFillOptions&,
    std::vector<float>& fillOut, std::vector<uint8_t>& estimatedMaskBits);
enum class ProjectionVisibility { Clear, Unknown, Blocked };
ProjectionVisibility projectionVisibility(const std::vector<float>&,int,int,double,double,double,const double*,double,double,double);
bool projectionVisible(const std::vector<float>&,int,int,double,double,double,const double*,double,double,double);
// One compact diagnostic line (logcat on Android, stderr elsewhere); callers must never pass coordinates or keys.
void logLine(const char*);
void releasePixels();
}
