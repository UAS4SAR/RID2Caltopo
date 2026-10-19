# Included at the end of the pinned OpenMVG project by build_openmvg.py.
# Test harness only: upstream CLI stages execute in-process, without subprocesses.
set(R2C_SFM_SOURCES "${PROJECT_SOURCE_DIR}/software/SfM")
set(R2C_PROBE_SOURCES)
foreach(stage SfMInit_ImageListing ComputeFeatures ComputeMatches GeometricFilter SfM)
  set(source "${R2C_SFM_SOURCES}/main_${stage}.cpp")
  set(wrapper "${PROJECT_BINARY_DIR}/r2c_${stage}.cpp")
  file(WRITE "${wrapper}" "#define main r2c_main_${stage}\n#include \"${source}\"\n")
  list(APPEND R2C_PROBE_SOURCES "${wrapper}")
endforeach()
add_library(r2c_openmvg_probe STATIC ${R2C_PROBE_SOURCES} "${R2C_PROBE_DIR}/probe.cpp")
target_compile_features(r2c_openmvg_probe PRIVATE cxx_std_17)
target_include_directories(r2c_openmvg_probe PRIVATE "${PROJECT_SOURCE_DIR}")
target_link_libraries(r2c_openmvg_probe PRIVATE openMVG_system openMVG_image
  openMVG_features openMVG_sfm openMVG_matching_image_collection openMVG_graph
  openMVG_multiview openMVG_stlplus vlsift)
