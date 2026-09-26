#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
manifest="$repo_root/third_party/mediamtx/manifest.env"
patch_dir="$repo_root/third_party/mediamtx/patches"

# shellcheck source=/dev/null
source "$manifest"

destination="${1:-$repo_root/.build/third-party/mediamtx-$MEDIAMTX_TAG}"
marker="$destination/.rid2caltopo-patched-source"
media_patch="$patch_dir/0001-rid2caltopo-mediamtx-v1.16.2.patch"
anet_patch="$patch_dir/0002-anet-v0.0.5-android.patch"
gortmplib_patch="$patch_dir/0003-gortmplib-v0.3.0-rid2caltopo.patch"
media_tools_patch="$patch_dir/0004-rid2caltopo-mediamtx-tests-tools.patch"
session_name_patch="$patch_dir/0005-rid2caltopo-nonempty-rtsp-session-name.patch"
regression_fixes_patch="$patch_dir/0006-rid2caltopo-regression-fixes.patch"
record_file_event_patch="$patch_dir/0007-record-file-complete-event.patch"
avcc_diagnostic_patch="$patch_dir/0008-avcc-failure-diagnostics.patch"
rtmp_timing_patch="$patch_dir/0009-rtmp-publish-timing.patch"
socket_timing_patch="$patch_dir/0010-socket-read-and-rtsp-queue-diagnostics.patch"
media_patch_sha="$(shasum -a 256 "$media_patch" | awk '{print $1}')"
anet_patch_sha="$(shasum -a 256 "$anet_patch" | awk '{print $1}')"
gortmplib_patch_sha="$(shasum -a 256 "$gortmplib_patch" | awk '{print $1}')"
media_tools_patch_sha="$(shasum -a 256 "$media_tools_patch" | awk '{print $1}')"
session_name_patch_sha="$(shasum -a 256 "$session_name_patch" | awk '{print $1}')"
regression_fixes_patch_sha="$(shasum -a 256 "$regression_fixes_patch" | awk '{print $1}')"
record_file_event_patch_sha="$(shasum -a 256 "$record_file_event_patch" | awk '{print $1}')"

avcc_diagnostic_patch_sha="$(shasum -a 256 "$avcc_diagnostic_patch" | awk '{print $1}')"

rtmp_timing_patch_sha="$(shasum -a 256 "$rtmp_timing_patch" | awk '{print $1}')"

socket_timing_patch_sha="$(shasum -a 256 "$socket_timing_patch" | awk '{print $1}')"

if [[ -d "$destination" ]]; then
    if [[ -f "$marker" ]] &&
        [[ -f "$destination/internal/core/VERSION" ]] &&
        [[ -f "$destination/internal/servers/hls/hls.min.js" ]] &&
        grep -qx "mediamtx_commit=$MEDIAMTX_COMMIT" "$marker" &&
        grep -qx "media_patch_sha256=$media_patch_sha" "$marker" &&
        grep -qx "anet_patch_sha256=$anet_patch_sha" "$marker" &&
        grep -qx "gortmplib_patch_sha256=$gortmplib_patch_sha" "$marker" &&
        grep -qx "media_tools_patch_sha256=$media_tools_patch_sha" "$marker" &&
        grep -qx "session_name_patch_sha256=$session_name_patch_sha" "$marker" &&
        grep -qx "regression_fixes_patch_sha256=$regression_fixes_patch_sha" "$marker" &&
        grep -qx "record_file_event_patch_sha256=$record_file_event_patch_sha" "$marker" &&
        grep -qx "avcc_diagnostic_patch_sha256=$avcc_diagnostic_patch_sha" "$marker" &&
        grep -qx "rtmp_timing_patch_sha256=$rtmp_timing_patch_sha" "$marker" &&
        grep -qx "socket_timing_patch_sha256=$socket_timing_patch_sha" "$marker"; then
        printf '%s\n' "$destination"
        exit 0
    fi
    rm -rf "$destination"
fi

mkdir -p "$(dirname "$destination")"
work_dir="$(mktemp -d "$(dirname "$destination")/.prepare-mediamtx.XXXXXX")"
cleanup() { rm -rf "$work_dir"; }
trap cleanup EXIT

media_source="$work_dir/mediamtx"
anet_source="$work_dir/anet"
gortmplib_source="$work_dir/gortmplib"

git clone --quiet --depth 1 --branch "$MEDIAMTX_TAG" \
    https://github.com/bluenviron/mediamtx.git "$media_source"
[[ "$(git -C "$media_source" rev-parse HEAD)" == "$MEDIAMTX_COMMIT" ]] || {
    echo "Unexpected MediaMTX commit" >&2
    exit 1
}

git clone --quiet --depth 1 --branch "$ANET_TAG" \
    https://github.com/wlynxg/anet.git "$anet_source"
[[ "$(git -C "$anet_source" rev-parse HEAD)" == "$ANET_COMMIT" ]] || {
    echo "Unexpected anet commit" >&2
    exit 1
}

git clone --quiet --depth 1 --branch "$GORTMPLIB_TAG" \
    https://github.com/bluenviron/gortmplib.git "$gortmplib_source"
[[ "$(git -C "$gortmplib_source" rev-parse HEAD)" == "$GORTMPLIB_COMMIT" ]] || {
    echo "Unexpected gortmplib commit" >&2
    exit 1
}

mkdir -p "$media_source/third_party/anet"
git -C "$anet_source" archive HEAD | tar -x -C "$media_source/third_party/anet"

git -C "$media_source" apply --check "$media_patch"
git -C "$media_source" apply "$media_patch"
git -C "$media_source" apply --check "$anet_patch"
git -C "$media_source" apply "$anet_patch"
git -C "$media_source" apply --check "$media_tools_patch"
git -C "$media_source" apply "$media_tools_patch"
(
    cd "$media_source"
    GOCACHE="$repo_root/.build/go-cache" \
    GOMODCACHE="$repo_root/.build/go-mod-cache" \
    go mod vendor
)
git -C "$media_source" apply --check --directory=vendor/github.com/bluenviron/gortmplib "$gortmplib_patch"
git -C "$media_source" apply --directory=vendor/github.com/bluenviron/gortmplib "$gortmplib_patch"
git -C "$media_source" apply --check "$session_name_patch"
git -C "$media_source" apply "$session_name_patch"
git -C "$media_source" apply --check "$regression_fixes_patch"
git -C "$media_source" apply "$regression_fixes_patch"
git -C "$media_source" apply --check "$record_file_event_patch"
git -C "$media_source" apply "$record_file_event_patch"
git -C "$media_source" apply --check "$avcc_diagnostic_patch"
git -C "$media_source" apply "$avcc_diagnostic_patch"
git -C "$media_source" apply --check "$rtmp_timing_patch"
git -C "$media_source" apply "$rtmp_timing_patch"
git -C "$media_source" apply --check "$socket_timing_patch"
git -C "$media_source" apply "$socket_timing_patch"
(
    cd "$media_source"
    GOCACHE="$repo_root/.build/go-cache" \
    GOMODCACHE="$repo_root/.build/go-mod-cache" \
    go generate ./internal/core ./internal/servers/hls
)

cat >"$media_source/.rid2caltopo-patched-source" <<EOF
mediamtx_commit=$MEDIAMTX_COMMIT
anet_commit=$ANET_COMMIT
gortmplib_commit=$GORTMPLIB_COMMIT
media_patch_sha256=$media_patch_sha
anet_patch_sha256=$anet_patch_sha
gortmplib_patch_sha256=$gortmplib_patch_sha
media_tools_patch_sha256=$media_tools_patch_sha
session_name_patch_sha256=$session_name_patch_sha
regression_fixes_patch_sha256=$regression_fixes_patch_sha
record_file_event_patch_sha256=$record_file_event_patch_sha
avcc_diagnostic_patch_sha256=$avcc_diagnostic_patch_sha
rtmp_timing_patch_sha256=$rtmp_timing_patch_sha
socket_timing_patch_sha256=$socket_timing_patch_sha
EOF

mv "$media_source" "$destination"
printf '%s\n' "$destination"
