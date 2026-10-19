#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
R2C_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
BUILD="$R2C_ROOT/apple/Build/Mapping"
DEPS="$BUILD/dependencies"
CMAKE="${CMAKE:-$(command -v cmake || echo "$HOME/Library/Android/sdk/cmake/3.22.1/bin/cmake")}"
NINJA="${NINJA:-$(command -v ninja || echo "$(dirname "$CMAKE")/ninja")}"
OPENMVG_SOURCE="${OPENMVG_SOURCE:-$DEPS/openmvg}"
OPENCV_SOURCE="${OPENCV_SOURCE:-$DEPS/opencv-4.12.0}"
mkdir -p "$DEPS"
if [[ ! -d "$OPENMVG_SOURCE" ]]; then
  git clone --no-checkout https://github.com/openMVG/openMVG.git "$OPENMVG_SOURCE"
  git -C "$OPENMVG_SOURCE" checkout 01193a245ee3c36458e650b1cf4402caad8983ef
  git -C "$OPENMVG_SOURCE" submodule update --init --recursive
fi
[[ "$(git -C "$OPENMVG_SOURCE" rev-parse HEAD)" == 01193a245ee3c36458e650b1cf4402caad8983ef ]]
if [[ ! -d "$OPENCV_SOURCE" ]]; then
  curl -fL --retry 3 https://github.com/opencv/opencv/archive/refs/tags/4.12.0.tar.gz -o "$DEPS/opencv.tar.gz"
  echo '44c106d5bb47efec04e531fd93008b3fcd1d27138985c5baf4eafac0e1ec9e9d'"  $DEPS/opencv.tar.gz" | shasum -a 256 -c -
  tar -xzf "$DEPS/opencv.tar.gz" -C "$DEPS"
fi
build_slice() {
  local name="$1" sdk="$2"
  local dir="$BUILD/$name"
  local args=()
  if [[ "$sdk" != macosx ]]; then
    args+=(-DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO)
  else
    args+=(-DCMAKE_OSX_DEPLOYMENT_TARGET=14.0)
  fi
  "$CMAKE" -S "$SCRIPT_DIR" -B "$dir" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_SYSROOT="$(xcrun --sdk "$sdk" --show-sdk-path)" \
    -DOPENMVG_SOURCE="$OPENMVG_SOURCE" -DOPENCV_SOURCE="$OPENCV_SOURCE" -DR2C_ROOT="$R2C_ROOT" "${args[@]}"
  "$CMAKE" --build "$dir" --target R2CMappingNative -j4
  mkdir -p "$dir/Headers/R2CMappingNative"
  rm -f "$dir/Headers/module.modulemap" "$dir/Headers/R2CMapping.h"
  cp "$R2C_ROOT/native/mapping/include/R2CMapping.h" "$dir/Headers/R2CMappingNative/"
  echo 'module R2CMappingNative { header "R2CMapping.h" export * }' > "$dir/Headers/R2CMappingNative/module.modulemap"
  local libs=("$dir/libR2CMappingNative.a" "$dir/ceres/lib/libopenMVG_ceres.a")
  for module in core imgproc features2d calib3d flann; do libs+=("$dir/opencv/lib/libopencv_$module.a"); done
  while IFS= read -r archive; do libs+=("$archive"); done < <(find "$dir" -path '*/3rdparty/*' -name '*.a' -type f)
  xcrun libtool -static -o "$dir/libr2c_mapping_runtime.a" "${libs[@]}"
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
