#!/bin/zsh
set -euo pipefail

script_dir="${0:A:h}"
repo_root="${script_dir:h:h}"
locale_dir="$script_dir/metadata/en-US"
marketing_version=""

usage() {
    cat <<'USAGE'
usage: apple/AppStore/verify-metadata.sh [--marketing-version VERSION]

When a marketing version is supplied, metadata/en-US/whats_new.txt must match
the repository's release-notes/VERSION/whats_new.txt exactly.
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --marketing-version)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            marketing_version="$2"
            shift 2
            ;;
        --help|-h) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

check_limit() {
    local file="$1"
    local maximum="$2"
    local label="$3"
    [[ -s "$file" ]] || { echo "Missing or empty $label: $file" >&2; exit 1; }
    local count
    count="$(python3 -c 'import pathlib, sys; print(len(pathlib.Path(sys.argv[1]).read_text().rstrip("\\n")))' "$file")"
    (( count <= maximum )) || { echo "$label is $count characters; maximum is $maximum" >&2; exit 1; }
    echo "  $label: $count/$maximum"
}

echo "App Store metadata"
check_limit "$locale_dir/name.txt" 30 "name"
check_limit "$locale_dir/subtitle.txt" 30 "subtitle"
check_limit "$locale_dir/promotional_text.txt" 170 "promotional text"
check_limit "$locale_dir/keywords.txt" 100 "keywords"
check_limit "$locale_dir/description.txt" 4000 "description"
check_limit "$locale_dir/whats_new.txt" 4000 "what's new"
[[ -s "$locale_dir/review_notes.txt" ]] || { echo "Missing review notes" >&2; exit 1; }

whats_new="$locale_dir/whats_new.txt"
rg -q '^Latest changes:$' "$whats_new" || {
    echo "What's new must contain a 'Latest changes:' section." >&2
    exit 1
}
if rg -q '^(Platform-specific changes:|Known platform differences:)$' "$whats_new"; then
    echo "Keep platform differences in docs/PLATFORM_PARITY_LEDGER.md, not public release notes." >&2
    exit 1
fi

if [[ -n "$marketing_version" ]]; then
    print -r -- "$marketing_version" | grep -Eq '^[0-9]+(\.[0-9]+){1,2}$' || {
        echo "Invalid marketing version: $marketing_version" >&2
        exit 2
    }
    version_notes="$repo_root/release-notes/$marketing_version/whats_new.txt"
    [[ -s "$version_notes" ]] || {
        echo "Missing release notes for version $marketing_version: $version_notes" >&2
        exit 1
    }
    cmp -s "$version_notes" "$whats_new" || {
        echo "What's new does not match release-notes/$marketing_version/whats_new.txt" >&2
        echo "Run tools/sync_release_notes.sh $marketing_version before release." >&2
        exit 1
    }
    project_file="$repo_root/apple/RID2CaltopoApple.xcodeproj/project.pbxproj"
    grep -Fq "MARKETING_VERSION = $marketing_version;" "$project_file" || {
        echo "The Apple MARKETING_VERSION does not match canonical release notes $marketing_version." >&2
        exit 1
    }
    grep -Fq "path = ../release-notes/$marketing_version/whats_new.txt;" "$project_file" || {
        echo "The Apple app does not bundle the canonical $marketing_version release notes." >&2
        exit 1
    }
    echo "  release notes: version $marketing_version"
fi

privacy="$script_dir/../../PrivacyPolicy.md"
[[ -s "$privacy" ]] || { echo "Missing public privacy policy source: $privacy" >&2; exit 1; }
rg -q '^Last updated: ' "$privacy"
rg -q 'does not contain advertising or analytics SDKs' "$privacy"
rg -q 'Apple Keychain' "$privacy"
rg -q 'tracker peer coordination' "$privacy"
rg -q 'diagnostic bundle' "$privacy"

python3 - "$script_dir/../App/PrivacyInfo.xcprivacy" <<'PY'
import plistlib
import sys

with open(sys.argv[1], "rb") as source:
    manifest = plistlib.load(source)

if manifest.get("NSPrivacyTracking") is not False:
    raise SystemExit("privacy manifest must declare tracking false")
if manifest.get("NSPrivacyTrackingDomains") != []:
    raise SystemExit("privacy manifest must not declare tracking domains")

expected = {
    "NSPrivacyCollectedDataTypeOtherDiagnosticData",
    "NSPrivacyCollectedDataTypePreciseLocation",
    "NSPrivacyCollectedDataTypeDeviceID",
    "NSPrivacyCollectedDataTypeOtherUserContent",
}
entries = manifest.get("NSPrivacyCollectedDataTypes", [])
actual = {entry.get("NSPrivacyCollectedDataType") for entry in entries}
if actual != expected:
    raise SystemExit(f"unexpected privacy data types: {sorted(actual)}")
for entry in entries:
    data_type = entry["NSPrivacyCollectedDataType"]
    if entry.get("NSPrivacyCollectedDataTypeLinked") is not True:
        raise SystemExit(f"{data_type} must be disclosed as linked")
    if entry.get("NSPrivacyCollectedDataTypeTracking") is not False:
        raise SystemExit(f"{data_type} must declare tracking false")
    if entry.get("NSPrivacyCollectedDataTypePurposes") != ["NSPrivacyCollectedDataTypePurposeAppFunctionality"]:
        raise SystemExit(f"{data_type} has unexpected purposes")
PY

echo "App Store metadata and public privacy-policy source verified."
