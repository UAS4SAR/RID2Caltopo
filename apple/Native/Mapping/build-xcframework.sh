#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
R2C_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
REPO_ROOT="$R2C_ROOT"
# shellcheck source=../reproducible-build.sh
source "$SCRIPT_DIR/../reproducible-build.sh"
BUILD="$R2C_ROOT/apple/Build/Mapping"
WORK="$R2C_NATIVE_WORK/Mapping"
# Pinned third-party sources live in the shared work path so every checkout
# compiles them from the same location. apple/Build/Mapping/dependencies points
# there when the checkout has no copy of its own (the relink check reads Eigen).
DEPS="$WORK/dependencies"
CMAKE="${CMAKE:-$(command -v cmake || echo "$HOME/Library/Android/sdk/cmake/3.22.1/bin/cmake")}"
NINJA="${NINJA:-$(command -v ninja || echo "$(dirname "$CMAKE")/ninja")}"
OPENMVG_SOURCE="$DEPS/openmvg"
OPENCV_SOURCE="$DEPS/opencv-4.12.0"

r2c_native_lock Mapping
trap r2c_native_unlock EXIT

mkdir -p "$DEPS" "$BUILD"
if [[ ! -d "$OPENMVG_SOURCE" && -d "$BUILD/dependencies/openmvg" && ! -L "$BUILD/dependencies" ]]; then
  rsync -a "$BUILD/dependencies/openmvg/" "$OPENMVG_SOURCE/"
fi
if [[ ! -d "$OPENCV_SOURCE" && -f "$BUILD/dependencies/opencv.tar.gz" && ! -L "$BUILD/dependencies" ]]; then
  cp "$BUILD/dependencies/opencv.tar.gz" "$DEPS/opencv.tar.gz"
fi
if [[ ! -d "$OPENMVG_SOURCE" ]]; then
  git clone --no-checkout https://github.com/openMVG/openMVG.git "$OPENMVG_SOURCE"
  git -C "$OPENMVG_SOURCE" checkout 01193a245ee3c36458e650b1cf4402caad8983ef
  git -C "$OPENMVG_SOURCE" submodule update --init --recursive
fi
[[ "$(git -C "$OPENMVG_SOURCE" rev-parse HEAD)" == 01193a245ee3c36458e650b1cf4402caad8983ef ]]
if [[ ! -d "$OPENCV_SOURCE" ]]; then
  [[ -f "$DEPS/opencv.tar.gz" ]] || curl -fL --retry 3 https://github.com/opencv/opencv/archive/refs/tags/4.12.0.tar.gz -o "$DEPS/opencv.tar.gz"
  echo '44c106d5bb47efec04e531fd93008b3fcd1d27138985c5baf4eafac0e1ec9e9d'"  $DEPS/opencv.tar.gz" | shasum -a 256 -c -
  tar -xzf "$DEPS/opencv.tar.gz" -C "$DEPS"
fi
if [[ ! -e "$BUILD/dependencies" ]]; then
  ln -s "$DEPS" "$BUILD/dependencies"
fi

# Stage this checkout's mapping sources into the work path. OpenCV records the
# compiler flags in its build information, so no checkout path may reach them.
STAGE="$WORK/r2c"
rm -rf "$STAGE"
mkdir -p "$STAGE/native" "$STAGE/apple/Native/Mapping"
rsync -a --exclude '.DS_Store' "$R2C_ROOT/native/mapping/" "$STAGE/native/mapping/"
cp "$SCRIPT_DIR/CMakeLists.txt" "$STAGE/apple/Native/Mapping/CMakeLists.txt"
REPRO_FLAGS="$(r2c_work_prefix_map_flags)"
REPRO_FLAGS="${REPRO_FLAGS% }"

build_slice() {
  local name="$1" sdk="$2"
  local dir source_dir r2c_root
  local args=()
  if [[ "$sdk" != macosx ]]; then
    # iOS slices ship in the app: build them under the fixed work path.
    dir="$WORK/$name"
    source_dir="$STAGE/apple/Native/Mapping"
    r2c_root="$STAGE"
    rm -rf "$dir"
    args+=(-DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO
      "-DCMAKE_C_FLAGS=$REPRO_FLAGS" "-DCMAKE_CXX_FLAGS=$REPRO_FLAGS")
  else
    # The macOS slice only runs host tests; release-check rebuilds it in place.
    dir="$BUILD/$name"
    source_dir="$SCRIPT_DIR"
    r2c_root="$R2C_ROOT"
    args+=(-DCMAKE_OSX_DEPLOYMENT_TARGET=14.0)
  fi
  "$CMAKE" -S "$source_dir" -B "$dir" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_SYSROOT="$(xcrun --sdk "$sdk" --show-sdk-path)" \
    -DOPENMVG_SOURCE="$OPENMVG_SOURCE" -DOPENCV_SOURCE="$OPENCV_SOURCE" -DR2C_ROOT="$r2c_root" "${args[@]}"
  "$CMAKE" --build "$dir" --target R2CMappingNative -j4
  mkdir -p "$dir/Headers/R2CMappingNative"
  rm -f "$dir/Headers/module.modulemap" "$dir/Headers/R2CMapping.h"
  cp "$R2C_ROOT/native/mapping/include/R2CMapping.h" "$dir/Headers/R2CMappingNative/"
  echo 'module R2CMappingNative { header "R2CMapping.h" export * }' > "$dir/Headers/R2CMappingNative/module.modulemap"
  local libs=("$dir/libR2CMappingNative.a" "$dir/ceres/lib/libopenMVG_ceres.a")
  for module in core imgproc features2d calib3d flann; do libs+=("$dir/opencv/lib/libopencv_$module.a"); done
  while IFS= read -r archive; do libs+=("$archive"); done < <(find "$dir" -path '*/3rdparty/*' -name '*.a' -type f | LC_ALL=C sort)
  r2c_static_archive "$dir/libr2c_mapping_runtime.a" "${libs[@]}"
  if [[ "$sdk" != macosx ]]; then
    rm -rf "$BUILD/$name"
    mkdir -p "$BUILD/$name"
    rsync -a "$dir/Headers/" "$BUILD/$name/Headers/"
    cp "$dir/libr2c_mapping_runtime.a" "$BUILD/$name/libr2c_mapping_runtime.a"
  fi
}
build_slice macos macosx
if [[ "${1:-}" == --host-only ]]; then exit 0; fi
build_slice device iphoneos
build_slice simulator iphonesimulator
rm -rf "$BUILD/R2CMappingNative.xcframework"
xcodebuild -create-xcframework \
 -library "$BUILD/macos/libr2c_mapping_runtime.a" -headers "$BUILD/macos/Headers" \
 -library "$BUILD/device/libr2c_mapping_runtime.a" -headers "$BUILD/device/Headers" \
 -library "$BUILD/simulator/libr2c_mapping_runtime.a" -headers "$BUILD/simulator/Headers" \
 -output "$BUILD/R2CMappingNative.xcframework"
