#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=../reproducible-build.sh
source "$SCRIPT_DIR/../reproducible-build.sh"
CORE_DIR="$REPO_ROOT/app/src/main/cpp"
WORK_DIR="$R2C_NATIVE_WORK/AnomalyCore"
BUILD_DIR="$REPO_ROOT/apple/Build/AnomalyCore"

SOURCES=(
  anomaly_detector.c anomaly_detector_annotation.c anomaly_runtime_budget.c
  anomaly_runtime_handoff.c anomaly_runtime_pressure.c anomaly_analysis.c
  anomaly_buffer.c anomaly_roi_tracks.c anomaly_roi_state.c anomaly_saliency_tracks.c
  anomaly_scratch.c anomaly_color_detector.c anomaly_debug_helpers.c
  anomaly_frame_history.c anomaly_grid_region.c anomaly_linear_solve.c
  anomaly_appearance_candidates.c anomaly_motion_estimator.c anomaly_registration_cache.c
  anomaly_registration_image.c anomaly_result_builder.c anomaly_scan_planner.c
  anomaly_scene_coverage_scheduler.c anomaly_target_observations.c
  anomaly_target_color_detector.c anomaly_target_revisit.c anomaly_target_tracks.c
  anomaly_thermal_detector.c anomaly_thermal_state.c
)

r2c_native_lock AnomalyCore
trap r2c_native_unlock EXIT
read -r -a PREFIX_MAP <<< "$(r2c_prefix_map_flags)"

build_slice() {
  local sdk="$1"
  local target="$2"
  local output="$3"
  local slice_dir="$WORK_DIR/$output"
  local object_dir="$slice_dir/objects"
  mkdir -p "$object_dir" "$slice_dir/Headers"
  local sysroot
  sysroot="$(xcrun --sdk "$sdk" --show-sdk-path)"
  local clang
  clang="$(xcrun --sdk "$sdk" --find clang)"

  local objects=()
  for source in "${SOURCES[@]}"; do
    local object="$object_dir/${source%.c}.o"
    "$clang" -target "$target" -isysroot "$sysroot" -std=c11 -O2 "${PREFIX_MAP[@]}" \
      -DANOMALY_DEBUG_TIMING=0 -I "$CORE_DIR" -c "$CORE_DIR/$source" -o "$object"
    objects+=("$object")
  done
  "$clang" -target "$target" -isysroot "$sysroot" -std=c11 -O2 "${PREFIX_MAP[@]}" \
    -DANOMALY_DEBUG_TIMING=0 -I "$CORE_DIR" -I "$SCRIPT_DIR" \
    -c "$SCRIPT_DIR/r2c_anomaly_apple.c" -o "$object_dir/r2c_anomaly_apple.o"
  objects+=("$object_dir/r2c_anomaly_apple.o")
  r2c_static_archive "$slice_dir/libR2CAnomalyApple.a" "${objects[@]}"
  cp "$SCRIPT_DIR/r2c_anomaly_apple.h" "$slice_dir/Headers/"
}

rm -rf "$WORK_DIR"
build_slice iphoneos arm64-apple-ios17.0 device
build_slice iphonesimulator arm64-apple-ios17.0-simulator simulator

xcodebuild -create-xcframework \
  -library "$WORK_DIR/device/libR2CAnomalyApple.a" -headers "$WORK_DIR/device/Headers" \
  -library "$WORK_DIR/simulator/libR2CAnomalyApple.a" -headers "$WORK_DIR/simulator/Headers" \
  -output "$WORK_DIR/R2CAnomalyApple.xcframework"

r2c_publish_outputs "$WORK_DIR" "$BUILD_DIR"
echo "Built $BUILD_DIR/R2CAnomalyApple.xcframework"
