#!/bin/zsh
set -euo pipefail

script_dir="${0:A:h}"
repo_root="${script_dir:h}"
project="$script_dir/RID2CaltopoApple.xcodeproj"
scheme="RID2CaltopoApple"
team="${R2C_DEVELOPMENT_TEAM:-}"
bundle_id="${R2C_APP_BUNDLE_ID:-org.ncssar.RID2CaltopoApple}"
version_major="$(sed -nE 's/^[[:space:]]*def versionMajor = ([0-9]+)$/\1/p' "$repo_root/app/build.gradle" | head -1)"
version_minor="$(sed -nE 's/^[[:space:]]*def versionMinor = ([0-9]+)$/\1/p' "$repo_root/app/build.gradle" | head -1)"
version_patch="$(sed -nE 's/^[[:space:]]*def versionPatch = ([0-9]+)$/\1/p' "$repo_root/app/build.gradle" | head -1)"
android_build_number="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+)$/\1/p' "$repo_root/app/build.gradle" | head -1)"
android_marketing_version="${version_major}.${version_minor}.${version_patch}"
build_number="${R2C_BUILD_NUMBER:-$android_build_number}"
marketing_version="${R2C_MARKETING_VERSION:-$android_marketing_version}"
archive_path=""
export_path=""
upload=false
internal_only=false
preflight=false
skip_release_check=false
force_land_catalog_refresh=false
api_key_id="${R2C_APPSTORE_API_KEY_ID:-}"
api_issuer="${R2C_APPSTORE_API_ISSUER:-}"

usage() {
    cat <<'USAGE'
usage: apple/archive-for-testflight.sh --team TEAM_ID [options]

Options:
  --team TEAM_ID           Apple Developer team (or R2C_DEVELOPMENT_TEAM)
  --bundle-id BUNDLE_ID    App Store Connect bundle ID
  --build-number NUMBER    CFBundleVersion (default: Android versionCode)
  --marketing-version VER CFBundleShortVersionString (default: Android versionName)
  --archive-path PATH      Verified unsigned xcarchive destination
  --export-path PATH       Locally exported IPA directory
  --internal-only          Restrict an uploaded build to internal TestFlight
  --upload                 Upload after archive verification
  --api-key-id ID          App Store Connect API key ID (or R2C_APPSTORE_API_KEY_ID)
  --api-issuer UUID        App Store Connect issuer ID (or R2C_APPSTORE_API_ISSUER)
  --preflight              Print configuration/signing readiness without building
  --skip-release-check     Reuse a release gate that just passed on unchanged sources
  --force-land-catalog-refresh
                            Ignore the weekly protected-land catalog check cache
  --help                   Show this help

The script first creates and verifies an unsigned archive, then asks Xcode to
export a locally signed App Store IPA. It never stores Apple credentials and
uploads only when --upload is explicitly supplied.
USAGE
}

[[ -n "$version_major" && -n "$version_minor" && -n "$version_patch" && -n "$android_build_number" ]] || {
    echo "Unable to read the Android version authority from $repo_root/app/build.gradle" >&2
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --team) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; team="$2"; shift 2 ;;
        --bundle-id) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; bundle_id="$2"; shift 2 ;;
        --build-number) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; build_number="$2"; shift 2 ;;
        --marketing-version) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; marketing_version="$2"; shift 2 ;;
        --archive-path) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; archive_path="$2"; shift 2 ;;
        --export-path) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; export_path="$2"; shift 2 ;;
        --internal-only) internal_only=true; shift ;;
        --upload) upload=true; shift ;;
        --api-key-id) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; api_key_id="$2"; shift 2 ;;
        --api-issuer) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; api_issuer="$2"; shift 2 ;;
        --preflight) preflight=true; shift ;;
        --skip-release-check) skip_release_check=true; shift ;;
        --force-land-catalog-refresh) force_land_catalog_refresh=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[[ -n "$team" ]] || { echo "Missing Apple Developer team. Pass --team or set R2C_DEVELOPMENT_TEAM." >&2; exit 2; }
print -r -- "$team" | grep -Eq '^[A-Z0-9]{10}$' || { echo "Invalid Apple Developer team: $team" >&2; exit 2; }
print -r -- "$bundle_id" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$' || { echo "Invalid bundle ID: $bundle_id" >&2; exit 2; }
[[ "$bundle_id" == *.* ]] || { echo "Invalid bundle ID: $bundle_id" >&2; exit 2; }
if [[ "$build_number" != <-> && "$build_number" != <->.<-> && "$build_number" != <->.<->.<-> ]]; then
    echo "Invalid CFBundleVersion: $build_number" >&2
    exit 2
fi
print -r -- "$marketing_version" | grep -Eq '^[0-9]+(\.[0-9]+){1,2}$' || { echo "Invalid CFBundleShortVersionString: $marketing_version" >&2; exit 2; }
if $internal_only && ! $upload; then
    echo "--internal-only requires --upload" >&2
    exit 2
fi

archive_path="${archive_path:-$script_dir/Build/RID2CaltopoApple-unsigned-$build_number.xcarchive}"
export_path="${export_path:-$script_dir/Build/TestFlight-$build_number}"

echo "RID2Caltopo TestFlight configuration"
echo "  team: $team"
echo "  bundle: $bundle_id"
echo "  build: $build_number"
echo "  version: $marketing_version"
echo "  archive: $archive_path"
echo "  upload: $upload"
if $upload; then echo "  internal only: $internal_only"; fi

identity_summary="$(security find-identity -v -p codesigning)"
if print -r -- "$identity_summary" | grep -Eq '[1-9][0-9]* valid identities found'; then
    print -r -- "$identity_summary"
else
    echo "  signing identity: none currently installed"
    echo "  Xcode may create a managed distribution identity during export when a paid account is configured."
fi

xcodebuild -quiet \
    -project "$project" \
    -scheme "$scheme" \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -showBuildSettings \
    DEVELOPMENT_TEAM="$team" \
    PRODUCT_BUNDLE_IDENTIFIER="$bundle_id" \
    CURRENT_PROJECT_VERSION="$build_number" \
    MARKETING_VERSION="$marketing_version" >/dev/null

# Runs even with --skip-release-check: App Review rejects store text naming other platforms.
python3 "$repo_root/tools/store_notes/check_store_notes.py" --version "$marketing_version"

if $preflight; then
    echo "Preflight completed without changing Apple or App Store Connect state."
    exit 0
fi

if ! $skip_release_check; then
    [[ ! -e "$archive_path" ]] || { echo "Archive already exists: $archive_path" >&2; exit 1; }
    release_check_args=(
        --archive-path "$archive_path" \
        --bundle-id "$bundle_id" \
        --build-number "$build_number" \
        --marketing-version "$marketing_version"
    )
    $force_land_catalog_refresh && release_check_args+=(--force-land-catalog-refresh)
    "$script_dir/release-check.sh" "${release_check_args[@]}"
else
    echo "Reusing the caller-confirmed Apple release gate for unchanged sources only."
    "$script_dir/verify-unsigned-archive.sh" "$archive_path" "$bundle_id" "$build_number" "$marketing_version"
fi

[[ ! -e "$export_path" ]] || { echo "Export path already exists: $export_path" >&2; exit 1; }

options_dir="$(mktemp -d -t r2c-testflight-options)"
trap 'rm -rf "$options_dir"' EXIT
options_plist="$options_dir/ExportOptions.plist"
manual_options="$script_dir/TestFlightManualExportOptions.plist"
if [[ -f "$manual_options" ]]; then
    cp "$manual_options" "$options_plist"
    /usr/libexec/PlistBuddy -c "Set :teamID $team" "$options_plist"
else
    cp "$script_dir/TestFlightExportOptions.plist" "$options_plist"
    /usr/libexec/PlistBuddy -c "Add :teamID string $team" "$options_plist"
    /usr/libexec/PlistBuddy -c 'Set :destination export' "$options_plist"
fi

xcodebuild -quiet \
    -exportArchive \
    -archivePath "$archive_path" \
    -exportPath "$export_path" \
    -exportOptionsPlist "$options_plist" \
    -allowProvisioningUpdates

ipas=("$export_path"/*.ipa(N))
[[ ${#ipas[@]} -eq 1 ]] || { echo "Expected one IPA in $export_path, found ${#ipas[@]}" >&2; exit 1; }
ipa="${ipas[1]}"
"$script_dir/verify-testflight-export.sh" "$ipa" "$team" "$bundle_id" "$build_number" "$marketing_version"

if ! $upload; then
    echo "Distribution-signed IPA is ready: $ipa"
    echo "Re-run with --upload only when the App Store Connect record and metadata are ready."
    exit 0
fi

if [[ -z "$api_key_id" ]]; then
    local_keys=("$HOME"/.appstoreconnect/private_keys/AuthKey_*.p8(N))
    if [[ ${#local_keys[@]} -eq 1 ]]; then
        api_key_id="${${local_keys[1]:t}#AuthKey_}"
        api_key_id="${api_key_id%.p8}"
    fi
fi
if [[ -z "$api_issuer" && "$team" == "94UV79S6LR" ]]; then
    api_issuer="c827c1d7-0eee-4d7e-bcae-c27accb00e12"
fi

if [[ -n "$api_key_id" && -n "$api_issuer" && -f "$HOME/.appstoreconnect/private_keys/AuthKey_${api_key_id}.p8" ]]; then
    $internal_only && { echo "API-key upload does not support --internal-only; use Xcode upload instead." >&2; exit 2; }
    xcrun altool --upload-app --type ios --file "$ipa" --apiKey "$api_key_id" --apiIssuer "$api_issuer"
else
    upload_options="$options_dir/UploadOptions.plist"
    cp "$script_dir/TestFlightExportOptions.plist" "$upload_options"
    /usr/libexec/PlistBuddy -c "Add :teamID string $team" "$upload_options"
    if $internal_only; then
        /usr/libexec/PlistBuddy -c 'Set :testFlightInternalTestingOnly true' "$upload_options"
    fi
    upload_result="${export_path}-upload"
    [[ ! -e "$upload_result" ]] || { echo "Upload result path already exists: $upload_result" >&2; exit 1; }
    xcodebuild -quiet \
        -exportArchive \
        -archivePath "$archive_path" \
        -exportPath "$upload_result" \
        -exportOptionsPlist "$upload_options" \
        -allowProvisioningUpdates
fi

echo "Upload completed for build $build_number. Check App Store Connect processing status."
