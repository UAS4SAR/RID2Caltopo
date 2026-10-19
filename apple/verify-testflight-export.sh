#!/bin/zsh
set -euo pipefail

usage() {
    echo "usage: $0 IPA [EXPECTED_TEAM] [EXPECTED_BUNDLE_ID] [EXPECTED_BUILD] [EXPECTED_VERSION]" >&2
    exit 2
}

[[ $# -ge 1 && $# -le 5 ]] || usage

ipa="${1:A}"
expected_team="${2:-}"
expected_bundle="${3:-}"
expected_build="${4:-}"
expected_version="${5:-}"
[[ -f "$ipa" ]] || { echo "Missing IPA: $ipa" >&2; exit 1; }

work_dir="$(mktemp -d -t r2c-testflight-verify)"
cleanup() { rm -rf "$work_dir"; }
trap cleanup EXIT
ditto -x -k "$ipa" "$work_dir"

apps=("$work_dir"/Payload/*.app(N))
[[ ${#apps[@]} -eq 1 ]] || { echo "Expected one app in IPA, found ${#apps[@]}" >&2; exit 1; }
app="${apps[1]}"
binary="$app/RID2CaltopoApple"
info="$app/Info.plist"
privacy="$app/PrivacyInfo.xcprivacy"
profile="$app/embedded.mobileprovision"

[[ -x "$binary" && -f "$profile" ]] || { echo "IPA is missing its app binary or provisioning profile" >&2; exit 1; }
plutil -lint "$info" "$privacy"
file "$binary" | grep -q 'Mach-O 64-bit executable arm64'
"${0:A:h}/verify-webrtc-framework.sh" "$app"
codesign --verify --deep --strict --verbose=2 "$app"

python3 "${0:A:h}/../tools/mapping/verify_dji_provisioning.py" --platform apple --artifact "$ipa"

signature="$(codesign -dv --verbose=4 "$app" 2>&1)"
actual_team="$(print -r -- "$signature" | awk -F= '/^TeamIdentifier=/{print $2; exit}')"
authority="$(print -r -- "$signature" | awk -F= '/^Authority=/{print $2; exit}')"
actual_bundle="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$info")"
actual_build="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$info")"
actual_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$info")"
[[ "$authority" == "Apple Distribution:"* ]] || { echo "Unexpected signing authority: $authority" >&2; exit 1; }

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
if [[ -n "$expected_version" && "$actual_version" != "$expected_version" ]]; then
    echo "Marketing version mismatch: expected $expected_version, found $actual_version" >&2
    exit 1
fi

entitlements="$work_dir/entitlements.plist"
codesign -d --entitlements :- "$app" >"$entitlements" 2>/dev/null
application_identifier="$(/usr/libexec/PlistBuddy -c 'Print :application-identifier' "$entitlements")"
team_entitlement="$(/usr/libexec/PlistBuddy -c 'Print :com.apple.developer.team-identifier' "$entitlements")"
get_task_allow="$(/usr/libexec/PlistBuddy -c 'Print :get-task-allow' "$entitlements")"
beta_reports="$(/usr/libexec/PlistBuddy -c 'Print :beta-reports-active' "$entitlements")"
[[ "$application_identifier" == "$actual_team.$actual_bundle" ]] || { echo "Application identifier entitlement mismatch" >&2; exit 1; }
[[ "$team_entitlement" == "$actual_team" ]] || { echo "Team entitlement mismatch" >&2; exit 1; }
[[ "$get_task_allow" == "false" ]] || { echo "TestFlight export unexpectedly permits debugging" >&2; exit 1; }
[[ "$beta_reports" == "true" ]] || { echo "TestFlight beta reports entitlement is missing" >&2; exit 1; }

profile_plist="$work_dir/profile.plist"
security cms -D -i "$profile" >"$profile_plist"
profile_name="$(/usr/libexec/PlistBuddy -c 'Print :Name' "$profile_plist")"
profile_team="$(/usr/libexec/PlistBuddy -c 'Print :TeamIdentifier:0' "$profile_plist")"
profile_app="$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:application-identifier' "$profile_plist")"
profile_beta="$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:beta-reports-active' "$profile_plist")"
profile_debug="$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:get-task-allow' "$profile_plist")"
[[ "$profile_team" == "$actual_team" && "$profile_app" == "$application_identifier" ]] || { echo "Provisioning profile identity mismatch" >&2; exit 1; }
[[ "$profile_beta" == "true" && "$profile_debug" == "false" ]] || { echo "Provisioning profile is not TestFlight-safe" >&2; exit 1; }
if /usr/libexec/PlistBuddy -c 'Print :ProvisionedDevices' "$profile_plist" >/dev/null 2>&1; then
    echo "App Store profile unexpectedly contains registered devices" >&2
    exit 1
fi

families="$(/usr/libexec/PlistBuddy -c 'Print :UIDeviceFamily' "$info")"
schemes="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleURLTypes:0:CFBundleURLSchemes' "$info")"
[[ "$families" == *1* && "$families" == *2* ]] || { echo "IPA is not universal for iPhone and iPad" >&2; exit 1; }
for scheme in r2c2 r2cenroll r2cfaa1 r2cma1; do
    [[ "$schemes" == *"$scheme"* ]] || { echo "Missing QR URL scheme: $scheme" >&2; exit 1; }
done

echo "TestFlight export verified"
echo "  ipa: $ipa"
echo "  authority: $authority"
echo "  team: $actual_team"
echo "  bundle: $actual_bundle"
echo "  build: $actual_build"
echo "  version: $actual_version"
echo "  profile: $profile_name"
