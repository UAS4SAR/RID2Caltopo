#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

# shellcheck source=/dev/null
source "$repo_root/third_party/mediamtx/manifest.env"

if [[ -n "${MEDIAMTX_SOURCE_DIR:-}" ]]; then
    source_dir="$MEDIAMTX_SOURCE_DIR"
    [[ -f "$source_dir/.rid2caltopo-patched-source" ]] || {
        echo "Refusing unverified MediaMTX source: $source_dir" >&2
        exit 1
    }
else
    source_dir="$($script_dir/prepare_mediamtx_source.sh)"
fi
ndk_root="${NDK_ROOT:-$HOME/Library/Android/sdk/ndk/$ANDROID_NDK_VERSION}"
toolchain="$ndk_root/toolchains/llvm/prebuilt/darwin-x86_64"
api="${API:-21}"
cc="$toolchain/bin/aarch64-linux-android${api}-clang"
strip_tool="$toolchain/bin/llvm-strip"

[[ -x "$cc" ]] || {
    echo "Android NDK compiler not found: $cc" >&2
    exit 1
}
[[ -x "$strip_tool" ]] || {
    echo "Android NDK llvm-strip not found: $strip_tool" >&2
    exit 1
}
[[ "$(go version | awk '{print $3}')" == "$GO_VERSION" ]] || {
    echo "MediaMTX requires $GO_VERSION; found $(go version)" >&2
    exit 1
}

asset_dir="$repo_root/app/src/main/assets"
build_dir="$repo_root/.build/mediamtx"
versioned_name="mediamtx-1.16.2-rid2caltopo"
versioned_binary="${OUTPUT:-$build_dir/$versioned_name}"
asset_binary="$asset_dir/mediamtx"
trimpath="${MEDIAMTX_TRIMPATH:-0}"
# Default: ship a stripped binary under assets/mediamtx only.
# Set MEDIAMTX_STRIP=0 to install the unstripped binary for debugging.
strip_asset="${MEDIAMTX_STRIP:-1}"
mkdir -p "$asset_dir" "$build_dir" "$repo_root/.build/go-cache" "$repo_root/.build/go-mod-cache"
mkdir -p "$(dirname "$versioned_binary")"

case "$trimpath" in
    0|1) ;;
    *)
        echo "MEDIAMTX_TRIMPATH must be 0 or 1" >&2
        exit 1
        ;;
esac
case "$strip_asset" in
    0|1) ;;
    *)
        echo "MEDIAMTX_STRIP must be 0 or 1" >&2
        exit 1
        ;;
esac

(
    cd "$source_dir"
    GOCACHE="$repo_root/.build/go-cache" \
    GOMODCACHE="$repo_root/.build/go-mod-cache" \
    go generate ./...
    if [[ "$trimpath" == "1" ]]; then
        GOCACHE="$repo_root/.build/go-cache" \
        GOMODCACHE="$repo_root/.build/go-mod-cache" \
        GOOS=android \
        GOARCH=arm64 \
        CGO_ENABLED=1 \
        CC="$cc" \
        CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
        go build -mod=vendor -trimpath -buildvcs=false -o "$versioned_binary" .
    else
        GOCACHE="$repo_root/.build/go-cache" \
        GOMODCACHE="$repo_root/.build/go-mod-cache" \
        GOOS=android \
        GOARCH=arm64 \
        CGO_ENABLED=1 \
        CC="$cc" \
        CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
        go build -mod=vendor -buildvcs=false -o "$versioned_binary" .
    fi
)

go version -m "$versioned_binary" | grep -q 'path[[:space:]]github.com/bluenviron/mediamtx'
binary_sha="$(shasum -a 256 "$versioned_binary" | awk '{print $1}')"
if [[ "$trimpath" == "1" ]]; then
    if [[ "$binary_sha" != "$REFERENCE_ANDROID_TRIMPATH_SHA256" ]]; then
        echo "Unexpected trimpath MediaMTX SHA-256: $binary_sha" >&2
        exit 1
    fi
elif [[ "$binary_sha" != "$REFERENCE_ANDROID_SHA256" ]]; then
    echo "Unexpected unstripped MediaMTX SHA-256: $binary_sha" >&2
    echo "Update REFERENCE_ANDROID_SHA256 in third_party/mediamtx/manifest.env after intentional rebuilds." >&2
    exit 1
fi

# Remove prior packaging layout (symlink + versioned ELF under assets/) so only
# one MediaMTX entry ships. Keep mediamtx.yml and mediamtxBuildScript.sh.
rm -f "$asset_dir/mediamtx"
# shellcheck disable=SC2086
rm -f "$asset_dir"/mediamtx-*rid2caltopo "$asset_dir"/mediamtx-*rid2caltopo.*

if [[ "$strip_asset" == "1" ]]; then
    "$strip_tool" --strip-all -o "$asset_binary" "$versioned_binary"
    chmod +x "$asset_binary"
    asset_sha="$(shasum -a 256 "$asset_binary" | awk '{print $1}')"
    if [[ -z "${REFERENCE_ANDROID_STRIPPED_SHA256:-}" ]]; then
        echo "REFERENCE_ANDROID_STRIPPED_SHA256 is not set in manifest.env" >&2
        exit 1
    fi
    if [[ "$asset_sha" != "$REFERENCE_ANDROID_STRIPPED_SHA256" ]]; then
        echo "Unexpected stripped MediaMTX SHA-256: $asset_sha" >&2
        echo "Update REFERENCE_ANDROID_STRIPPED_SHA256 in third_party/mediamtx/manifest.env after intentional rebuilds." >&2
        exit 1
    fi
    printf 'unstripped %s  %s\n' "$binary_sha" "$versioned_binary"
    printf 'stripped   %s  %s\n' "$asset_sha" "$asset_binary"
else
    cp "$versioned_binary" "$asset_binary"
    chmod +x "$asset_binary"
    printf 'unstripped %s  %s (installed to assets for debugging)\n' "$binary_sha" "$asset_binary"
fi
