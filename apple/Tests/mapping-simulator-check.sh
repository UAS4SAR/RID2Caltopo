#!/bin/bash
# Exercises the bundled offline viewer on an isolated simulator; no physical device.
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
apple_dir="$(dirname "$script_dir")"
app="${1:?Pass the built Debug simulator .app}"
bundle_id=$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$app/Info.plist")
fixture="$apple_dir/Build/Mapping/SimulatorFixture/00000000-0000-0000-0000-000000000070"
R2C_MAPPING_SIMULATOR_FIXTURE="$fixture" swift test --package-path "$apple_dir" --filter MappingImageTests
runtime=$(xcrun simctl list runtimes -j | python3 -c 'import json,sys; print(next(r["identifier"] for r in reversed(json.load(sys.stdin)["runtimes"]) if r.get("isAvailable") and "iOS" in r["name"]))')
device=$(xcrun simctl create "R2C mapping qualification" com.apple.CoreSimulator.SimDeviceType.iPad-A16 "$runtime")
cleanup(){ xcrun simctl shutdown "$device" >/dev/null 2>&1 || true; xcrun simctl delete "$device" >/dev/null 2>&1 || true; }
trap cleanup EXIT
xcrun simctl boot "$device"
xcrun simctl bootstatus "$device" -b
xcrun simctl install "$device" "$app"
data=$(xcrun simctl get_app_container "$device" "$bundle_id" data)
mkdir -p "$data/Documents/mapping-simulator"
cp -R "$fixture" "$data/Documents/mapping-simulator/"
xcrun simctl launch "$device" "$bundle_id" --mapping-simulator-qualification
for ((i=0;i<90;i++)); do
  if [[ -f "$data/Documents/mapping-simulator/result.json" ]]; then
    python3 - "$data/Documents/mapping-simulator/result.json" <<'PY'
import json,sys
report=json.load(open(sys.argv[1]))
print(report)
assert report.get('passed'), 'iPad local 3D viewer failed'
assert len(report['regions'])==2, 'Both regions must render'
assert report['regions'][0]!=report['regions'][1], 'Region selection did not change the loaded geometry'
PY
    exit 0
  fi
  sleep 1
done
echo 'iPad local 3D viewer timed out' >&2
exit 1
