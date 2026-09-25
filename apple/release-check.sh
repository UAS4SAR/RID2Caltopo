#!/bin/zsh
set -euo pipefail

script_dir="${0:A:h}"
repo_root="${script_dir:h}"
rebuild_native=true
archive_path=""
bundle_id="org.ncssar.RID2CaltopoApple"
version_major="$(sed -nE 's/^[[:space:]]*def versionMajor = ([0-9]+)$/\1/p' "$repo_root/app/build.gradle" | head -1)"
version_minor="$(sed -nE 's/^[[:space:]]*def versionMinor = ([0-9]+)$/\1/p' "$repo_root/app/build.gradle" | head -1)"
version_patch="$(sed -nE 's/^[[:space:]]*def versionPatch = ([0-9]+)$/\1/p' "$repo_root/app/build.gradle" | head -1)"
build_number="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+)$/\1/p' "$repo_root/app/build.gradle" | head -1)"
marketing_version="${version_major}.${version_minor}.${version_patch}"
force_land_catalog_refresh=false

usage() {
    cat <<'USAGE'
usage: apple/release-check.sh [options]

Options:
  --skip-native-rebuild   Verify existing XCFrameworks instead of rebuilding
  --archive-path PATH     Keep the verified unsigned archive at PATH
  --bundle-id ID         Override CFBundleIdentifier for the archive
  --build-number NUMBER  Override CFBundleVersion for the archive (default: Android versionCode)
  --marketing-version V Override CFBundleShortVersionString (default: Android versionName)
  --force-land-catalog-refresh
                         Ignore the weekly protected-land catalog check cache
  --help                  Show this help

The default gate rebuilds all three native XCFrameworks from current sources, runs
shared/native tests and anomaly qualifications, performs a clean Simulator
link, and verifies a fresh unsigned arm64 iPhone/iPad archive.
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-native-rebuild) rebuild_native=false; shift ;;
        --archive-path) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; archive_path="$2"; shift 2 ;;
        --bundle-id) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; bundle_id="$2"; shift 2 ;;
        --build-number) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; build_number="$2"; shift 2 ;;
        --marketing-version) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; marketing_version="$2"; shift 2 ;;
        --force-land-catalog-refresh) force_land_catalog_refresh=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

print -r -- "$bundle_id" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$' || { echo "Invalid bundle ID: $bundle_id" >&2; exit 2; }
[[ "$bundle_id" == *.* ]] || { echo "Invalid bundle ID: $bundle_id" >&2; exit 2; }
if [[ "$build_number" != <-> && "$build_number" != <->.<-> && "$build_number" != <->.<->.<-> ]]; then
    echo "Invalid CFBundleVersion: $build_number" >&2
    exit 2
fi
print -r -- "$marketing_version" | grep -Eq '^[0-9]+(\.[0-9]+){1,2}$' || { echo "Invalid CFBundleShortVersionString: $marketing_version" >&2; exit 2; }

mkdir -p "$script_dir/Build"
work_dir="$(mktemp -d "$script_dir/Build/release-check.XXXXXX")"
cleanup() { rm -rf "$work_dir"; }
trap cleanup EXIT

if [[ -z "$archive_path" ]]; then
    archive_path="$work_dir/RID2CaltopoApple-release-check.xcarchive"
else
    archive_path="${archive_path:A}"
    [[ ! -e "$archive_path" ]] || { echo "Archive already exists: $archive_path" >&2; exit 1; }
fi

echo "[1/10] App Store metadata and privacy policy"
"$script_dir/AppStore/verify-metadata.sh" --marketing-version "$marketing_version"

echo "[2/10] Protected-land source catalog"
python3 -m unittest discover -s "$repo_root/tools/protected_land_catalog" -p 'test_*.py'
land_catalog_args=()
$force_land_catalog_refresh && land_catalog_args+=(--force)
python3 "$repo_root/tools/protected_land_catalog/refresh_catalog.py" "${land_catalog_args[@]}"

echo "[3/10] Native Apple dependencies"
if $rebuild_native; then
    "$script_dir/Native/MediaMTX/build-xcframework.sh"
    "$script_dir/Native/AnomalyCore/build-xcframework.sh"
    "$script_dir/Native/FFmpeg/build-xcframework.sh"
fi

media_root="$script_dir/Build/MediaMTX"
anomaly_root="$script_dir/Build/AnomalyCore"
ffmpeg_root="$script_dir/Build/FFmpeg"
plutil -lint \
    "$media_root/MediaMTXMobile.xcframework/Info.plist" \
    "$anomaly_root/R2CAnomalyApple.xcframework/Info.plist" \
    "$ffmpeg_root/R2CFFmpegMobile.xcframework/Info.plist"
for library in \
    "$media_root/device/libmediamtx_mobile.a" \
    "$media_root/simulator/libmediamtx_mobile.a" \
    "$anomaly_root/device/libR2CAnomalyApple.a" \
    "$anomaly_root/simulator/libR2CAnomalyApple.a" \
    "$ffmpeg_root/device/libR2CFFmpegMobile.a" \
    "$ffmpeg_root/simulator/libR2CFFmpegMobile.a"; do
    lipo -info "$library" | grep -q 'architecture: arm64'
done
for symbol in R2CFFmpegSessionCopyRenderDiagnostics R2CFFmpegSessionRenderAgeMilliseconds R2CFFmpegSessionCopyFrameWithCamera R2CFFmpegSessionCreate R2CFFmpegSessionCreatePlayback R2CFFmpegSessionDestroy R2CFFmpegSessionCopyLatestFrame R2CFFmpegSessionGetStatus R2CFFmpegSessionCopyLatestDJICameraTelemetry R2CFFmpegSessionCopyLatestDJISEIPayload; do
    nm -gU "$ffmpeg_root/device/libR2CFFmpegMobile.a" | grep "_$symbol$" >/dev/null
    nm -gU "$ffmpeg_root/simulator/libR2CFFmpegMobile.a" | grep "_$symbol$" >/dev/null
done
for symbol in R2CMediaMTXSetLogCallback R2CMediaMTXStart R2CMediaMTXStop; do
    nm -gU "$media_root/device/libmediamtx_mobile.a" | grep "_$symbol$" >/dev/null
    nm -gU "$media_root/simulator/libmediamtx_mobile.a" | grep "_$symbol$" >/dev/null
done
for symbol in R2CAnomalyCreate R2CAnomalyDestroy R2CAnomalyApplyConfiguration R2CAnomalyProcessBGRA R2CAnomalyFrameResultCopyBox; do
    nm -gU "$anomaly_root/device/libR2CAnomalyApple.a" | grep "_$symbol$" >/dev/null
    nm -gU "$anomaly_root/simulator/libR2CAnomalyApple.a" | grep "_$symbol$" >/dev/null
done
xcrun clang -std=c11 -Wall -Wextra -Werror \
    "$script_dir/Native/FFmpeg/tests/R2CH264PacketTests.c" \
    -o "$work_dir/R2CH264PacketTests"
"$work_dir/R2CH264PacketTests"
xcrun clang -std=c11 -Wall -Wextra -Werror \
    "$repo_root/native/tests/R2CDJICameraTelemetryTests.c" \
    -o "$work_dir/R2CDJICameraTelemetryTests"
"$work_dir/R2CDJICameraTelemetryTests"

xcrun clang -std=c11 -Wall -Wextra -Werror \
    "$script_dir/Native/FFmpeg/tests/R2CLiveFrameQueueTests.c" \
    "$repo_root/app/src/main/cpp/anomaly_runtime_budget.c" \
    -o "$work_dir/R2CLiveFrameQueueTests"
"$work_dir/R2CLiveFrameQueueTests"

echo "[4/10] Portable anomaly regression suite"
cmake -S "$repo_root/tools/anomaly_test" -B "$work_dir/anomaly-test" -DCMAKE_BUILD_TYPE=Release
cmake --build "$work_dir/anomaly-test" --target anomaly_test -j 1
"$work_dir/anomaly-test/anomaly_test" | tee "$work_dir/anomaly-test.txt"
grep -Eq 'Results: [0-9]+ passed, 0 failed' "$work_dir/anomaly-test.txt"

echo "[5/10] Color and person-relevance qualifications"
(
    cd "$repo_root"
    ./gradlew :app:colorRealtimeQualification :app:personRelevanceQualification
)

echo "[6/10] Shared Swift tests"
swift test --package-path "$script_dir"

echo "[7/10] Clean universal Simulator link"
xcodebuild -quiet \
    -project "$script_dir/RID2CaltopoApple.xcodeproj" \
    -scheme RID2CaltopoApple \
    -configuration Debug \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$work_dir/DerivedData" \
    ARCHS=arm64 \
    ONLY_ACTIVE_ARCH=YES \
    PRODUCT_BUNDLE_IDENTIFIER="$bundle_id" \
    CURRENT_PROJECT_VERSION="$build_number" \
    MARKETING_VERSION="$marketing_version" \
    CODE_SIGNING_ALLOWED=NO \
    -jobs 1 \
    build

echo "[8/10] Clean arm64 device archive"
xcodebuild -quiet \
    -project "$script_dir/RID2CaltopoApple.xcodeproj" \
    -scheme RID2CaltopoApple \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "$work_dir/DerivedData" \
    -archivePath "$archive_path" \
    PRODUCT_BUNDLE_IDENTIFIER="$bundle_id" \
    CURRENT_PROJECT_VERSION="$build_number" \
    MARKETING_VERSION="$marketing_version" \
    CODE_SIGNING_ALLOWED=NO \
    -jobs 1 \
    archive

echo "[9/10] Archive metadata and binary verification"
"$script_dir/verify-unsigned-archive.sh" "$archive_path" "$bundle_id" "$build_number" "$marketing_version"

echo "[10/10] Release gate complete"
echo "Apple release check passed."
if [[ "$archive_path" != "$work_dir/"* ]]; then
    echo "Verified archive: $archive_path"
fi
