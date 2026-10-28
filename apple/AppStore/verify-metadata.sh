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
check_limit "$locale_dir/review_notes.txt" 4000 "review notes"

whats_new="$locale_dir/whats_new.txt"
rg -q '^Latest changes:$' "$whats_new" || {
    echo "What's new must contain a 'Latest changes:' section." >&2
    exit 1
}
if rg -q '^(Platform-specific changes:|Known platform differences:)$' "$whats_new"; then
    echo "Keep platform differences in docs/PLATFORM_PARITY_LEDGER.md, not public release notes." >&2
    exit 1
fi
# App Review rejects store text that names other platforms or stores (e.g. "Android").
store_notes_args=()
[[ -n "$marketing_version" ]] && store_notes_args=(--version "$marketing_version")
python3 "$repo_root/tools/store_notes/check_store_notes.py" "${store_notes_args[@]}"
# In-app UI is a separate scope from store metadata.
python3 -m unittest discover -s "$repo_root/tools/ui_copy" -p 'test_*.py'
python3 "$repo_root/tools/ui_copy/check_apple_ui_copy.py"

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

privacy="$repo_root/PrivacyPolicyApple.md"
android_privacy="$repo_root/PrivacyPolicyAndroid.md"
[[ -s "$privacy" ]] || { echo "Missing public privacy policy source: $privacy" >&2; exit 1; }
[[ -s "$android_privacy" ]] || { echo "Missing public privacy policy source: $android_privacy" >&2; exit 1; }
rg -q '^Last updated: ' "$privacy"
rg -Fq 'It contains no advertising' "$privacy"
rg -Fq 'The app uses Firebase Analytics and Crashlytics' "$android_privacy"
rg -Fq 'The app does not include analytics SDKs' "$privacy"
rg -q 'Apple Keychain' "$privacy"
rg -q 'tracker peer coordination' "$privacy"
rg -q 'diagnostic bundle' "$privacy"
rg -Fq 'BundledInformationView(document: .privacyPolicy)' "$script_dir/../App/AboutPrivacyView.swift" || {
    echo "About & Privacy must open the complete bundled Apple privacy policy." >&2
    exit 1
}
rg -Fq 'document = AppInformationDocument.PrivacyPolicy' "$repo_root/app/src/main/java/org/ncssar/rid2caltopo/ui/MainScreen.kt" || {
    echo "About & Privacy must open the complete bundled Android privacy policy." >&2
    exit 1
}
python3 "$repo_root/tools/privacy/prepare_policy_documents.py" --check
if rg -ni '\b(Android|iOS|Apple) app\b|both platforms|platform-specific|Android-only|iPhone and iPad-only' "$privacy" "$android_privacy"; then
    echo "Each privacy policy must describe the app on its own." >&2
    exit 1
fi

python3 "$repo_root/tools/privacy/check_apple_privacy.py" --check-policy
python3 -m unittest discover -s "$repo_root/tools/privacy" -p 'test_*.py'

echo "App Store metadata and public privacy-policy source verified."
