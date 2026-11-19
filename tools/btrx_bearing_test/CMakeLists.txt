cmake_minimum_required(VERSION 3.14)
project(btrx_bearing_test C)

# Host tests for apple/Sources/CBtrxBearing (docs/btrx/BEARING-LAYER-spec.md section 9).
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
option(BTRX_BEARING_TSAN "Build the tests with ThreadSanitizer" OFF)

set(BB_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../apple/Sources/CBtrxBearing")
set(BB_SOURCES
    ${BB_ROOT}/btrx_bearing.c
    ${BB_ROOT}/bb_clock.c
    ${BB_ROOT}/bb_pose.c
    ${BB_ROOT}/bb_pattern.c
    ${BB_ROOT}/bb_grid.c
    ${BB_ROOT}/bb_tiles.c
    ${BB_ROOT}/bb_diversity.c
    ${BB_ROOT}/bb_lag.c
    ${BB_ROOT}/bb_estimate.c
    ${BB_ROOT}/bb_render.c
    ${BB_ROOT}/bb_siphash.c
    ${BB_ROOT}/bb_steer.c)
set(BB_FLAGS -O2 -ffp-contract=off -Wall -Wextra -Werror)
find_package(Threads REQUIRED)

# The library sources, with malloc/calloc/free routed through bbt_alloc.c so the
# tests can count allocations after create (spec 9.1 item 7). Behaviour is unchanged.
set_source_files_properties(${BB_SOURCES} PROPERTIES
    COMPILE_DEFINITIONS "malloc=bbt_malloc;calloc=bbt_calloc;free=bbt_free")
add_library(btrx_bearing_host STATIC ${BB_SOURCES} bbt_alloc.c)
target_include_directories(btrx_bearing_host PUBLIC ${BB_ROOT}/include ${BB_ROOT})
target_compile_options(btrx_bearing_host PRIVATE ${BB_FLAGS})
target_link_libraries(btrx_bearing_host PUBLIC Threads::Threads m)
if(BTRX_BEARING_TSAN)
    target_compile_options(btrx_bearing_host PRIVATE -fsanitize=thread -g)
endif()

add_executable(btrx_bearing_test test_btrx_bearing.c)
target_compile_options(btrx_bearing_test PRIVATE ${BB_FLAGS})
target_compile_definitions(btrx_bearing_test PRIVATE
    "BTRX_STEER_FORMAT2_FIXTURE=\"${CMAKE_CURRENT_SOURCE_DIR}/../../test-fixtures/btrx/steer-format2.tsv\"")
target_link_libraries(btrx_bearing_test PRIVATE btrx_bearing_host)
if(BTRX_BEARING_TSAN)
    target_compile_options(btrx_bearing_test PRIVATE -fsanitize=thread -g)
    target_link_options(btrx_bearing_test PRIVATE -fsanitize=thread)
endif()

add_executable(btrx_bearing_replay btrx_bearing_replay.c)
target_compile_options(btrx_bearing_replay PRIVATE ${BB_FLAGS})
target_link_libraries(btrx_bearing_replay PRIVATE btrx_bearing_host)
