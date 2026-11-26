# shellcheck shell=bash
# Shared settings for the reproducible Apple native builds (FFmpeg, Mapping,
# MediaMTX, AnomalyCore). Source it from a build script after REPO_ROOT is set.
#
# The libraries are compiled under one fixed work path, not the checkout or
# worktree path, so the same inputs give byte-identical archives wherever the
# repository lives. Results are then copied to apple/Build/<Library>.

: "${REPO_ROOT:?REPO_ROOT must be set before sourcing reproducible-build.sh}"

# Fixed inputs. Do not inherit these from the caller: a different value would
# change the bytes and fail the reviewed-hash check.
export SOURCE_DATE_EPOCH=1704067200   # 2024-01-01T00:00:00Z
export ZERO_AR_DATE=1
export LC_ALL=C
export TZ=UTC

# Canonical, symlink-free location shared by every checkout and worktree.
R2C_NATIVE_WORK="/Users/Shared/RID2Caltopo/native-work"
if [[ -n "${R2C_NATIVE_WORK_OVERRIDE:-}" ]]; then
    echo "warning: R2C_NATIVE_WORK_OVERRIDE set; output will not match reviewed hashes" >&2
    R2C_NATIVE_WORK="$R2C_NATIVE_WORK_OVERRIDE"
fi

# Strip both the checkout and the work path from __FILE__ and debug info.
r2c_prefix_map_flags() {
    printf '%s ' \
        "-ffile-prefix-map=$REPO_ROOT=/r2c" \
        "-fdebug-prefix-map=$REPO_ROOT=/r2c" \
        "-ffile-prefix-map=$R2C_NATIVE_WORK=/r2c-work" \
        "-fdebug-prefix-map=$R2C_NATIVE_WORK=/r2c-work" \
        "-fno-ident"
}

# Work-path maps only, for builds that record their compiler flags in the
# library (FFmpeg's configure line, OpenCV's build information): those must
# compile staged copies under the work path and never name the checkout.
r2c_work_prefix_map_flags() {
    printf '%s ' \
        "-ffile-prefix-map=$R2C_NATIVE_WORK=/r2c-work" \
        "-fdebug-prefix-map=$R2C_NATIVE_WORK=/r2c-work" \
        "-fno-ident"
}

# Deterministic static archive: zero dates, fixed uid/gid/mode, given order.
r2c_static_archive() {
    local output="$1"; shift
    rm -f "$output"
    xcrun libtool -static -D -o "$output" "$@"
}

# One build per library at a time, because the work path is shared.
r2c_native_lock() {
    local name="$1" lock waited=0
    mkdir -p "$R2C_NATIVE_WORK"
    lock="$R2C_NATIVE_WORK/.lock-$name"
    until mkdir "$lock" 2>/dev/null; do
        if [[ -f "$lock/pid" ]] && ! kill -0 "$(cat "$lock/pid")" 2>/dev/null; then
            rm -rf "$lock"
            continue
        fi
        (( waited++ % 30 == 0 )) && echo "Waiting for another $name native build ($lock)" >&2
        sleep 2
    done
    echo $$ > "$lock/pid"
    R2C_NATIVE_LOCK_DIR="$lock"
}

r2c_native_unlock() {
    [[ -n "${R2C_NATIVE_LOCK_DIR:-}" ]] && rm -rf "$R2C_NATIVE_LOCK_DIR"
    R2C_NATIVE_LOCK_DIR=""
}

# Copy finished outputs from the work path into this checkout's apple/Build.
r2c_publish_outputs() {
    local source="$1" destination="$2"; shift 2
    mkdir -p "$destination"
    rsync -a --delete "$@" "$source/" "$destination/"
}
