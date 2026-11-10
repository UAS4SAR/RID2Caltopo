#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
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
OUTPUT_DIR="${1:-$SCRIPT_DIR/../../Build/MediaMTX/$VARIANT}"
STAGE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/r2c-mediamtx-ios.XXXXXX")"

cleanup() {
    rm -rf "$STAGE_DIR"
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
    CGO_CFLAGS="-isysroot $SDKROOT $PLATFORM_FLAGS" \
    CGO_LDFLAGS="-isysroot $SDKROOT $PLATFORM_FLAGS" \
    go build \
        -trimpath \
        -buildmode=c-archive \
        -ldflags=-checklinkname=0 \
        -o "$OUTPUT_DIR/libmediamtx_mobile.a" \
        ./mobilebridge
)

echo "Built $OUTPUT_DIR/libmediamtx_mobile.a"
echo "Generated $OUTPUT_DIR/libmediamtx_mobile.h"
