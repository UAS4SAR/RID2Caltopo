#pragma once
#ifdef __cplusplus
extern "C" {
#endif
// Qualification harness. Call serially on a worker thread. No network access.
int r2c_openmvg_stage(int stage, const char *job_root);
#ifdef __cplusplus
}
#endif
