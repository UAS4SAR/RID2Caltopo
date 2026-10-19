cmake_minimum_required(VERSION 3.22)
project(r2c_mapping_surface LANGUAGES CXX)
if(NOT EXISTS "${OPENMVG_BUILD}/third_party/ceres-solver/lib/libopenMVG_ceres.a")
  message(FATAL_ERROR "Build the pinned OpenMVG CPU dependencies first using build_openmvg.py")
endif()
add_library(r2c_mapping_surface SHARED "${R2C_SOURCE}/app/src/main/cpp/mapping_surface.cpp" "${R2C_SOURCE}/native/mapping/Reconstruction.cpp" "${R2C_SOURCE}/native/mapping/SurfaceCore.cpp")
target_compile_features(r2c_mapping_surface PRIVATE cxx_std_17)
target_include_directories(r2c_mapping_surface SYSTEM PRIVATE
  "${OPENMVG_SOURCE}/src/third_party/eigen"
  "${OPENMVG_SOURCE}/src/third_party/ceres-solver/include"
  "${OPENMVG_SOURCE}/src/third_party/ceres-solver/internal/ceres/miniglog"
  "${OPENMVG_BUILD}/third_party/ceres-solver/config")
target_compile_options(r2c_mapping_surface PRIVATE -O3 -fvisibility=hidden -Wall -Wextra -fopenmp)
target_link_options(r2c_mapping_surface PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -fopenmp -static-openmp)
target_link_libraries(r2c_mapping_surface PRIVATE
  "${OPENMVG_BUILD}/third_party/ceres-solver/lib/libopenMVG_ceres.a" log)

if(NOT EXISTS "${OPENCV_PREFAB}/include/opencv2/core.hpp")
 message(FATAL_ERROR "Pass the pinned OpenCV 4.12.0 prefab module")
endif()
target_include_directories(r2c_mapping_surface PRIVATE "${OPENCV_PREFAB}/include")
target_link_libraries(r2c_mapping_surface PRIVATE "${OPENCV_PREFAB}/libs/android.arm64-v8a/libopencv_java4.so")
