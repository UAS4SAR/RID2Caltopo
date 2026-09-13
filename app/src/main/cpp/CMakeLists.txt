cmake_minimum_required(VERSION 3.22.1)
project(mediamtx_jni)
option(ANOMALY_DEBUG_TIMING "Enable anomaly detector stage timing instrumentation" OFF)

add_library(
    mediamtx_jni
    SHARED
    mediamtx_jni.c
)
add_library(
    ffmpeg_bridge
    SHARED
    ffmpeg_bridge.c
    anomaly_detector.c
    anomaly_detector_annotation.c
    anomaly_person_relevance.c
    anomaly_person_relevance_scheduler.c
    anomaly_runtime_budget.c
    anomaly_runtime_handoff.c
    anomaly_runtime_pressure.c
    anomaly_analysis.c
    anomaly_buffer.c
    anomaly_roi_tracks.c
    anomaly_roi_state.c
    anomaly_saliency_tracks.c
    anomaly_scratch.c
    anomaly_color_detector.c
    anomaly_debug_helpers.c
    anomaly_frame_history.c
    anomaly_grid_region.c
    anomaly_linear_solve.c
    anomaly_appearance_candidates.c
    anomaly_motion_estimator.c
    anomaly_registration_cache.c
    anomaly_registration_image.c
    anomaly_result_builder.c
    anomaly_scan_planner.c
    anomaly_scene_coverage_scheduler.c
    anomaly_target_observations.c
    anomaly_target_color_detector.c
    anomaly_target_revisit.c
    anomaly_target_tracks.c
    anomaly_thermal_detector.c
    anomaly_thermal_state.c
)

set(FFMPEG_LIB_DIR "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(FFMPEG_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/ffmpeg/include")

set(FFMPEG_LIBS_FOUND FALSE)
if (EXISTS "${FFMPEG_LIB_DIR}/libavformat.so" AND
    EXISTS "${FFMPEG_LIB_DIR}/libavcodec.so" AND
    EXISTS "${FFMPEG_LIB_DIR}/libavutil.so")
    set(FFMPEG_LIBS_FOUND TRUE)
endif()
set(FFMPEG_SWSCALE_FOUND FALSE)
if (EXISTS "${FFMPEG_LIB_DIR}/libswscale.so")
    set(FFMPEG_SWSCALE_FOUND TRUE)
endif()

find_library(
    log-lib
    log
)
find_library(
    android-lib
    android
)

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

target_link_libraries(
    mediamtx_jni
    PRIVATE
    ${log-lib}
)
target_link_libraries(
    ffmpeg_bridge
    PRIVATE
    ${log-lib}
    ${android-lib}
)
target_compile_definitions(ffmpeg_bridge PRIVATE ANOMALY_DEBUG_TIMING=$<BOOL:${ANOMALY_DEBUG_TIMING}>)

if (FFMPEG_LIBS_FOUND)
    add_library(avformat SHARED IMPORTED)
    set_target_properties(avformat PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/libavformat.so")
    add_library(avcodec SHARED IMPORTED)
    set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/libavcodec.so")
    add_library(avutil SHARED IMPORTED)
    set_target_properties(avutil PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/libavutil.so")
    if (FFMPEG_SWSCALE_FOUND)
        add_library(swscale SHARED IMPORTED)
        set_target_properties(swscale PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/libswscale.so")
    endif()

    target_compile_definitions(ffmpeg_bridge PRIVATE HAVE_FFMPEG=1)
    if (FFMPEG_SWSCALE_FOUND)
        target_compile_definitions(ffmpeg_bridge PRIVATE HAVE_SWSCALE=1)
    else()
        target_compile_definitions(ffmpeg_bridge PRIVATE HAVE_SWSCALE=0)
    endif()
    if (EXISTS "${FFMPEG_INCLUDE_DIR}")
        target_include_directories(ffmpeg_bridge PRIVATE "${FFMPEG_INCLUDE_DIR}")
    endif()
    target_link_libraries(
        ffmpeg_bridge
        PRIVATE
        avformat
        avcodec
        avutil
    )
    if (FFMPEG_SWSCALE_FOUND)
        target_link_libraries(ffmpeg_bridge PRIVATE swscale)
    endif()
else()
    target_compile_definitions(ffmpeg_bridge PRIVATE HAVE_FFMPEG=0)
    target_compile_definitions(ffmpeg_bridge PRIVATE HAVE_SWSCALE=0)
    message(WARNING "FFmpeg libs not found for ${ANDROID_ABI}. Using ffmpeg_bridge stub backend.")
endif()

# Shared streaming lidar preparation. Kept separate from the video decoder.
set(AOL_SOURCE_ROOT "${CMAKE_SOURCE_DIR}/../../../../apple/Sources/CAOL")
file(GLOB AOL_LAZ_SOURCES "${AOL_SOURCE_ROOT}/lazperf/*.cpp" "${AOL_SOURCE_ROOT}/lazperf/detail/*.cpp" "${AOL_SOURCE_ROOT}/geographiclib/*.cpp")
add_library(aol_prepare SHARED aol_jni.cpp "${AOL_SOURCE_ROOT}/aol_grid.cpp" ${AOL_LAZ_SOURCES})
target_include_directories(aol_prepare PRIVATE "${AOL_SOURCE_ROOT}/include" "${AOL_SOURCE_ROOT}/geographiclib/include")
target_compile_features(aol_prepare PRIVATE cxx_std_17)
target_compile_definitions(aol_prepare PRIVATE LAZPERF_VENDORED)
