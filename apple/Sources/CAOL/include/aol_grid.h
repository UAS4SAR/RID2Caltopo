#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
void *aol_grid_create(double lat, double lon, double west, double south, int width, int height);
void aol_grid_free(void *grid);
int aol_grid_open(void *grid, const char *path);
int aol_grid_step(void *grid, int batch);
int aol_grid_write(void *grid, const char *surface, const char *ground);
const char *aol_grid_error(void *grid);
const char *aol_grid_crs(void *grid);
uint64_t aol_grid_read(void *grid);
uint64_t aol_grid_count(void *grid);
uint64_t aol_grid_missing(void *grid);
#ifdef __cplusplus
}
#endif
