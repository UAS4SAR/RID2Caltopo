#!/bin/zsh
set -euo pipefail

usage() {
    echo "usage: $0 ARCHIVE [EXPECTED_TEAM] [EXPECTED_BUNDLE_ID] [EXPECTED_BUILD]" >&2
    exit 2
}

[[ $# -ge 1 && $# -le 4 ]] || usage

archive="$1"
expected_team="${2:-}"
expected_bundle="${3:-}"
expected_build="${4:-}"
app="$archive/Products/Applications/RID2CaltopoApple.app"
binary="$app/RID2CaltopoApple"
info="$app/Info.plist"
privacy="$app/PrivacyInfo.xcprivacy"

[[ -x "$binary" ]] || { echo "Missing app binary: $binary" >&2; exit 1; }
plutil -lint "$info" "$privacy"
file "$binary" | grep -q 'Mach-O 64-bit executable arm64'
"${0:A:h}/verify-webrtc-framework.sh" "$app"

actual_bundle="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$info")"
actual_build="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$info")"
families="$(/usr/libexec/PlistBuddy -c 'Print :UIDeviceFamily' "$info")"
schemes="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleURLTypes:0:CFBundleURLSchemes' "$info")"
python3 "${0:A:h}/../tools/privacy/check_apple_privacy.py" --app "$app"
python3 "${0:A:h}/../tools/licenses/prepare_legal.py" --check --artifact "$app"
python3 "${0:A:h}/../tools/licenses/prepare_apple_relink.py" --verify-app "$app" --require-published

[[ "$families" == *1* && "$families" == *2* ]] || { echo "Archive is not universal for iPhone and iPad" >&2; exit 1; }
for scheme in r2cmapkg1 r2creauth; do
    [[ "$schemes" == *"$scheme"* ]] || { echo "Missing QR URL scheme: $scheme" >&2; exit 1; }
done

codesign --verify --deep --strict --verbose=2 "$app"
signature="$(codesign -dv --verbose=4 "$app" 2>&1)"
actual_team="$(print -r -- "$signature" | awk -F= '/^TeamIdentifier=/{print $2; exit}')"
[[ -n "$actual_team" && "$actual_team" != "not set" ]] || { echo "Signed app has no TeamIdentifier" >&2; exit 1; }

if [[ -n "$expected_team" && "$actual_team" != "$expected_team" ]]; then
    echo "Team mismatch: expected $expected_team, found $actual_team" >&2
    exit 1
fi
if [[ -n "$expected_bundle" && "$actual_bundle" != "$expected_bundle" ]]; then
    echo "Bundle ID mismatch: expected $expected_bundle, found $actual_bundle" >&2
    exit 1
fi
if [[ -n "$expected_build" && "$actual_build" != "$expected_build" ]]; then
    echo "Build mismatch: expected $expected_build, found $actual_build" >&2
    exit 1
fi

entitlements="$(mktemp -t r2c-entitlements).plist"
trap 'rm -f "$entitlements"' EXIT
codesign -d --entitlements :- "$app" >"$entitlements" 2>/dev/null
if [[ -s "$entitlements" ]]; then
    get_task_allow="$(/usr/libexec/PlistBuddy -c 'Print :get-task-allow' "$entitlements" 2>/dev/null || true)"
    [[ "$get_task_allow" != "true" ]] || { echo "Release archive unexpectedly permits debugging" >&2; exit 1; }
fi

echo "Signed Apple archive verified"
echo "  archive: $archive"
echo "  team: $actual_team"
echo "  bundle: $actual_bundle"
echo "  build: $actual_build"
