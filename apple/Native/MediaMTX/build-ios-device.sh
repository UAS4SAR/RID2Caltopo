#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=../reproducible-build.sh
source "$SCRIPT_DIR/../reproducible-build.sh"
SOURCE_DIR="${MEDIAMTX_SOURCE_DIR:-}"
if [[ -z "$SOURCE_DIR" ]]; then
    SOURCE_DIR="$("$REPO_ROOT/tools/prepare_mediamtx_source.sh")"
fi
SDK_NAME="${R2C_APPLE_SDK:-iphoneos}"
if [[ "$SDK_NAME" == "iphonesimulator" ]]; then
    VARIANT="simulator"
    PLATFORM_FLAGS="-target arm64-apple-ios17.0-simulator"
else
    VARIANT="device"
    PLATFORM_FLAGS="-miphoneos-version-min=17.0"
fi
OUTPUT_DIR="${1:-$REPO_ROOT/apple/Build/MediaMTX/$VARIANT}"
# Fixed stage and output paths keep cgo objects and embedded build settings
# identical across checkouts and worktrees.
r2c_native_lock "MediaMTX-$VARIANT"
STAGE_DIR="$R2C_NATIVE_WORK/MediaMTX/stage-$VARIANT"
WORK_OUTPUT="$R2C_NATIVE_WORK/MediaMTX/$VARIANT"
rm -rf "$STAGE_DIR" "$WORK_OUTPUT"
mkdir -p "$STAGE_DIR" "$WORK_OUTPUT"

cleanup() {
    rm -rf "$STAGE_DIR"
    r2c_native_unlock
}
trap cleanup EXIT

if [[ ! -f "$SOURCE_DIR/go.mod" ]]; then
    echo "MediaMTX source not found at: $SOURCE_DIR" >&2
    exit 1
fi

mkdir -p "$OUTPUT_DIR" "$STAGE_DIR/mobilebridge"
rsync -a \
    --exclude '.git/' \
    --exclude '.gocache/' \
    --exclude '.gomodcache/' \
    --exclude '.DS_Store' \
    --exclude '*.pdf' \
    "$SOURCE_DIR/" "$STAGE_DIR/"
cp "$SCRIPT_DIR/mobilebridge/main.go" "$STAGE_DIR/mobilebridge/main.go"

# shellcheck source=/dev/null
source "$REPO_ROOT/third_party/mediamtx/manifest.env"
if [[ "$(GOTOOLCHAIN=local go env GOVERSION)" != "$GO_VERSION" ]]; then
    echo "MediaMTX needs $GO_VERSION for a reproducible build; found $(go env GOVERSION)" >&2
    exit 1
fi

SDKROOT="$(xcrun --sdk "$SDK_NAME" --show-sdk-path)"
CLANG="$(xcrun --sdk "$SDK_NAME" --find clang)"

(
    cd "$STAGE_DIR"
    # -checklinkname=0 allows mobilebridge to call runtime.parsegodebug.
    # Go 1.25 rejects //go:debug asyncpreemptoff, and os.Setenv does not
    # update that non-atomic runtime variable after initialization.
    SDKROOT="$SDKROOT" \
    CGO_ENABLED=1 \
    GOOS=ios \
    GOARCH=arm64 \
    CC="$CLANG" \
    CGO_CFLAGS="-isysroot $SDKROOT $PLATFORM_FLAGS -ffile-prefix-map=$STAGE_DIR=/r2c-mediamtx -fdebug-prefix-map=$STAGE_DIR=/r2c-mediamtx -fno-ident" \
    CGO_LDFLAGS="-isysroot $SDKROOT $PLATFORM_FLAGS" \
    GOFLAGS= \
    GOTOOLCHAIN=local \
    go build \
        -trimpath \
        -buildvcs=false \
        -buildmode=c-archive \
        "-ldflags=-checklinkname=0 -buildid=" \
        -o "$WORK_OUTPUT/libmediamtx_mobile.a" \
        ./mobilebridge
)
mkdir -p "$OUTPUT_DIR"
cp "$WORK_OUTPUT/libmediamtx_mobile.a" "$WORK_OUTPUT/libmediamtx_mobile.h" "$OUTPUT_DIR/"

echo "Built $OUTPUT_DIR/libmediamtx_mobile.a"
echo "Generated $OUTPUT_DIR/libmediamtx_mobile.h"
