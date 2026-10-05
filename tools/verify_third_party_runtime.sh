#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=/dev/null
source "$repo_root/third_party/mediamtx/manifest.env"

media_binary="$repo_root/app/src/main/assets/mediamtx"
ffmpeg_dir="$repo_root/app/src/main/jniLibs/arm64-v8a"
ffmpeg_header="$repo_root/app/src/main/cpp/ffmpeg/include/libavutil/ffversion.h"

[[ -x "$media_binary" ]] || {
    echo "Missing MediaMTX runtime; run tools/build_mediamtx_android_arm64.sh" >&2
    exit 1
}
if [[ -L "$media_binary" ]]; then
    echo "assets/mediamtx must be a regular file so Android packaging does not also ship a versioned duplicate" >&2
    exit 1
fi
# shellcheck disable=SC2086
extras=( "$repo_root/app/src/main/assets"/mediamtx-*rid2caltopo )
if ((${#extras[@]})) && [[ -e "${extras[0]}" ]]; then
    echo "Extra MediaMTX assets would duplicate packaging: ${extras[*]}" >&2
    echo "Keep only assets/mediamtx; versioned unstripped binaries belong under .build/mediamtx/" >&2
    exit 1
fi
go version -m "$media_binary" | grep -q 'path[[:space:]]github.com/bluenviron/mediamtx'
asset_sha="$(shasum -a 256 "$media_binary" | awk '{print $1}')"
case "$asset_sha" in
    "$REFERENCE_ANDROID_STRIPPED_SHA256"|"$REFERENCE_ANDROID_SHA256")
        ;;
    *)
        echo "Unexpected assets/mediamtx SHA-256: $asset_sha" >&2
        echo "Expected stripped $REFERENCE_ANDROID_STRIPPED_SHA256 or unstripped $REFERENCE_ANDROID_SHA256" >&2
        exit 1
        ;;
esac

for library in libavformat.so libavcodec.so libavutil.so libswscale.so; do
    [[ -f "$ffmpeg_dir/$library" ]] || {
        echo "Missing FFmpeg runtime: $ffmpeg_dir/$library" >&2
        exit 1
    }
done
grep -q 'FFMPEG_VERSION "n7.0"' "$ffmpeg_header"
strings "$ffmpeg_dir/libavutil.so" | grep -q 'FFmpeg version n7.0'

if git -C "$repo_root" ls-files --error-unmatch \
    app/src/main/assets/mediamtx \
    app/src/main/jniLibs/arm64-v8a/libavutil.so >/dev/null 2>&1; then
    echo "Generated media binaries must not be tracked by Git" >&2
    exit 1
fi
