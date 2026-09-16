#!/bin/sh
set -eu

if [ "$#" -lt 1 ] || [ "$#" -gt 4 ]; then
    echo "usage: $0 ARCHIVE [EXPECTED_BUNDLE_ID] [EXPECTED_BUILD] [EXPECTED_VERSION]" >&2
    exit 2
fi

archive=$1
expected_bundle=${2:-}
expected_build=${3:-}
expected_version=${4:-}
app="$archive/Products/Applications/RID2CaltopoApple.app"
binary="$app/RID2CaltopoApple"
info="$app/Info.plist"
privacy="$app/PrivacyInfo.xcprivacy"

test -x "$binary"
plutil -lint "$info" "$privacy"
file "$binary" | grep -q "Mach-O 64-bit executable arm64"
"$(dirname "$0")/verify-webrtc-framework.sh" "$app"

actual_bundle=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$info")
actual_build=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$info")
actual_version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$info")
if [ -n "$expected_bundle" ] && [ "$actual_bundle" != "$expected_bundle" ]; then
    echo "bundle ID mismatch: expected $expected_bundle, found $actual_bundle" >&2
    exit 1
fi
if [ -n "$expected_version" ] && [ "$actual_version" != "$expected_version" ]; then
    echo "marketing version mismatch: expected $expected_version, found $actual_version" >&2
    exit 1
fi
if [ -n "$expected_build" ] && [ "$actual_build" != "$expected_build" ]; then
    echo "build mismatch: expected $expected_build, found $actual_build" >&2
    exit 1
fi

families=$(/usr/libexec/PlistBuddy -c 'Print :UIDeviceFamily' "$info")
echo "$families" | grep -q "1"
echo "$families" | grep -q "2"

schemes=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleURLTypes:0:CFBundleURLSchemes' "$info")
echo "$schemes" | grep -q "r2c2"
echo "$schemes" | grep -q "r2cenroll"
echo "$schemes" | grep -q "r2cfaa1"
echo "$schemes" | grep -q "r2cma1"
echo "$schemes" | grep -q "r2cmapkg1"

privacy_types=$(/usr/libexec/PlistBuddy -c 'Print :NSPrivacyCollectedDataTypes' "$privacy")
echo "$privacy_types" | grep -q "NSPrivacyCollectedDataTypePreciseLocation"
echo "$privacy_types" | grep -q "NSPrivacyCollectedDataTypeDeviceID"
echo "$privacy_types" | grep -q "NSPrivacyCollectedDataTypeOtherUserContent"
echo "$privacy_types" | grep -q "NSPrivacyCollectedDataTypeOtherDiagnosticData"

if codesign -dv "$app" >/dev/null 2>&1; then
    echo "expected an unsigned archive, but the app is signed" >&2
    exit 1
fi

echo "Unsigned Apple archive verified: $archive"
