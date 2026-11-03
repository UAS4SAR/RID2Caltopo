#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
// All work is offline and serial on a worker queue. A zero callback return cancels.
typedef int (*R2CMappingProgress)(void *context, const char *stage, double fraction);
typedef void (*R2CMappingConnectivity)(void *context,int round,const int *groups,const int *support,const int *exclusions,int count);
typedef struct {
    int count, width, height;
    double focal;
    const double *cameras; // count * 15: ENU centre, row-major world->camera rotation, RTK sigma
    const double *lasers; // count * 3, NaN for absent/unqualified targets
    const double *pitch; // count, degrees
    const char *const *grayPaths; // width*height bytes, read-only
    R2CMappingConnectivity connectivity; // optional diagnostic callback
    void *connectivityContext;
    // Appended fields default to 0 when C callers zero-initialise (NaN also means unset).
    double surfaceCellMeters; // <=0 or non-finite => default 0.2 m
    double clipWest, clipSouth, clipEast, clipNorth; // local ENU metres; used only if all finite, east>west and north>south, else unused
} R2CMappingInput;
typedef struct {
    double *adjustment; size_t adjustmentCount; // shared solver result including exclusions as NaN cameras; final value -1/-2 = approximate plane (laser median/takeoff), header[7] = plane height, zero depth observations
    float *heights; int width, height; double x,y,cell;
    int selectedPhotos, registeredPhotos, featureTracks, matchedPairs, stereoPairs, measuredCells;
} R2CMappingResult;
int R2CMappingReconstruct(const R2CMappingInput *, R2CMappingProgress, void *, R2CMappingResult **, char *, size_t);
void R2CMappingFree(R2CMappingResult *);
int R2CMappingRender(const float *,int,int,double,double,double,const double *,size_t,const double *,
 int,int,const char *const *,double,double,double,int32_t *,char *,size_t);
// Ortho fill for unknown cells. measured/fillOut: width*height floats; maskBits: (width*height+7)/8 bytes,
// LSB-first (cell i = bit i%8 of byte i/8), set where fill was estimated. fillOut keeps measured values where finite,
// holds estimated values where filled and NaN where empty. planeHeight non-finite = no global plane. stats (optional) = 6 doubles:
// estimatedCells, known, estimated, empty fractions, elapsedMs, fillRadiusMeters. enabled=0 copies measured. Returns 1/0.
int R2CMappingBuildOrthoFill(const float *measured,int width,int height,double cell,int enabled,double fillRadiusMeters,double planeHeight,
 float *fillOut,uint8_t *maskBits,double *stats,char *,size_t);
// Same as Render/PrepareProjection, but heights are sampled from fill where measured is NaN; measured alone drives
// ray occlusion and measured-cell colours/seams stay identical to the non-fill entry points.
int R2CMappingRenderFill(const float *,const float *,int,int,double,double,double,const double *,size_t,const double *,
 int,int,const char *const *,double,double,double,int32_t *,char *,size_t);
int R2CMappingPrepareProjectionFill(const float *,const float *,int,int,double,double,double,const double *,size_t,const double *,
 int,int,const char *const *,R2CMappingProgress,void *,char *,size_t);
int R2CMappingRefine(float *,int,int,double,char *,size_t);
int R2CMappingFaces(const float *,int,int,int,const int32_t *,size_t,int32_t *,size_t,char *,size_t);
int R2CMappingPrepareProjection(const float *,int,int,double,double,double,const double *,size_t,const double *,
 int,int,const char *const *,R2CMappingProgress,void *,char *,size_t);
void R2CMappingReleasePixels(void);
// Split entry points use the same implementation as R2CMappingReconstruct.
int R2CMappingAlign(const R2CMappingInput *,R2CMappingProgress,void *,R2CMappingResult **,char *,size_t);
int R2CMappingDense(const R2CMappingInput *,const double *,size_t,R2CMappingProgress,void *,R2CMappingResult **,char *,size_t);
// Deterministic full-frame box sampling and RGB luminance, independent of OS gray conversion.
int R2CMappingSampleRGBA(const uint8_t *,int,int,int,uint8_t *,size_t,char *,size_t);
#ifdef __cplusplus
}
#endif
