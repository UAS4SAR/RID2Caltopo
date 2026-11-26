#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=../reproducible-build.sh
source "$SCRIPT_DIR/../reproducible-build.sh"
FFMPEG_SOURCE_DIR="${FFMPEG_SOURCE_DIR:-/Users/kjt/Projects/ffmpeg}"
# Build under the fixed work path: FFmpeg embeds its configure line (with
# --prefix and the work path) in the library, so the path must not vary.
BUILD_ROOT="$R2C_NATIVE_WORK/FFmpeg"
STAGED_SOURCE="$BUILD_ROOT/source"
OUTPUT_ROOT="$REPO_ROOT/apple/Build/FFmpeg"

if [[ ! -x "$FFMPEG_SOURCE_DIR/configure" ]]; then
  echo "FFmpeg source not found at $FFMPEG_SOURCE_DIR" >&2
  exit 1
fi
"$REPO_ROOT/tools/verify_ffmpeg_source.sh" "$FFMPEG_SOURCE_DIR"

r2c_native_lock FFmpeg
trap r2c_native_unlock EXIT
read -r -a PREFIX_MAP <<< "$(r2c_prefix_map_flags)"
# Only the work path may appear in configure flags (they are embedded verbatim).
WORK_PREFIX_MAP="$(r2c_work_prefix_map_flags)"
WORK_PREFIX_MAP="${WORK_PREFIX_MAP% }"

build_slice() {
  local sdk="$1"
  local target="$2"
  local variant="$3"
  local slice_root="$BUILD_ROOT/$variant"
  local build_dir="$slice_root/build"
  local prefix="$slice_root/prefix"
  local sysroot
  local clang
  local jobs
  sysroot="$(xcrun --sdk "$sdk" --show-sdk-path)"
  clang="$(xcrun --sdk "$sdk" --find clang)"
  jobs="$(sysctl -n hw.ncpu 2>/dev/null || echo 8)"

  mkdir -p "$build_dir" "$prefix" "$slice_root/Headers"
  if [[ "${REBUILD_BRIDGE_ONLY:-false}" != "true" ]]; then
  (
    cd "$build_dir"
    "$STAGED_SOURCE/configure" \
      --prefix="$prefix" \
      --target-os=darwin \
      --arch=arm64 \
      --enable-cross-compile \
      --cc="$clang -target $target -isysroot $sysroot" \
      --ar="$(xcrun --sdk "$sdk" --find ar)" \
      --ranlib="$(xcrun --sdk "$sdk" --find ranlib)" \
      --strip="$(xcrun --sdk "$sdk" --find strip)" \
      --sysroot="$sysroot" \
      --extra-cflags="-target $target -isysroot $sysroot -fPIC $WORK_PREFIX_MAP" \
      --extra-ldflags="-target $target -isysroot $sysroot" \
      --pkg-config=/bin/false \
      --enable-static \
      --disable-shared \
      --disable-programs \
      --disable-doc \
      --disable-debug \
      --disable-autodetect \
      --disable-everything \
      --enable-avformat \
      --enable-avcodec \
      --enable-avutil \
      --enable-network \
      --enable-protocol=file,tcp,udp,rtp \
      --enable-demuxer=mov,rtsp,rtp,sdp,h264 \
      --enable-muxer=mp4 \
      --enable-parser=h264 \
      --enable-decoder=h264 \
      --enable-hwaccel=h264_videotoolbox \
      --enable-videotoolbox
    make -j"$jobs"
    make install
  )
  fi

  "$clang" -target "$target" -isysroot "$sysroot" -std=c11 -O2 -fPIC "${PREFIX_MAP[@]}" \
    -I "$prefix/include" -I "$SCRIPT_DIR" \
    -c "$SCRIPT_DIR/R2CFFmpegMobile.c" -o "$slice_root/R2CFFmpegMobile.o"

  r2c_static_archive "$slice_root/libR2CFFmpegMobile.a" \
    "$slice_root/R2CFFmpegMobile.o" \
    "$prefix/lib/libavformat.a" \
    "$prefix/lib/libavcodec.a" \
    "$prefix/lib/libavutil.a"
  cp "$SCRIPT_DIR/R2CFFmpegMobile.h" "$slice_root/Headers/"
}

if [[ "${REBUILD_BRIDGE_ONLY:-false}" == "true" ]]; then
  build_slice iphoneos arm64-apple-ios17.0 device
elif [[ "${SKIP_DEVICE_BUILD:-false}" != "true" ]]; then
  rm -rf "$BUILD_ROOT"
  mkdir -p "$STAGED_SOURCE"
  git -C "$FFMPEG_SOURCE_DIR" archive --format=tar HEAD | tar -x -C "$STAGED_SOURCE"
  build_slice iphoneos arm64-apple-ios17.0 device
elif [[ ! -f "$BUILD_ROOT/device/libR2CFFmpegMobile.a" || ! -x "$STAGED_SOURCE/configure" ]]; then
  echo "Cannot resume: completed device slice or staged source is missing" >&2
  exit 1
fi
build_slice iphonesimulator arm64-apple-ios17.0-simulator simulator

rm -rf "$BUILD_ROOT/R2CFFmpegMobile.xcframework"
xcodebuild -create-xcframework \
  -library "$BUILD_ROOT/device/libR2CFFmpegMobile.a" -headers "$BUILD_ROOT/device/Headers" \
  -library "$BUILD_ROOT/simulator/libR2CFFmpegMobile.a" -headers "$BUILD_ROOT/simulator/Headers" \
  -output "$BUILD_ROOT/R2CFFmpegMobile.xcframework"

# Keep the libraries, headers, config.h (read by the relink check) and staged
# source in this checkout; leave object files in the work path.
r2c_publish_outputs "$BUILD_ROOT" "$OUTPUT_ROOT" --exclude '*.o' --exclude '*.d'
echo "Built $OUTPUT_ROOT/R2CFFmpegMobile.xcframework"
