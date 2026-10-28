#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
WORK="${WEBRTC_WORK_ROOT:-$REPO_ROOT/.build/webrtc-source}"
PIN="$REPO_ROOT/third_party/webrtc/manifest.json"
read_pin() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$PIN" "$1"; }
SOURCE_REV="$(read_pin source_revision)"
DEPOT_REV="$(read_pin depot_tools_revision)"
mkdir -p "$WORK/checkout"
if [[ ! -d "$WORK/depot_tools/.git" ]]; then
  git clone --no-checkout "$(read_pin depot_tools_url)" "$WORK/depot_tools"
  git -C "$WORK/depot_tools" checkout --detach "$DEPOT_REV"
fi
[[ "$(git -C "$WORK/depot_tools" rev-parse HEAD)" == "$DEPOT_REV" ]]
export PATH="$WORK/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0
export DEPOT_TOOLS_METRICS=0
cd "$WORK/checkout"
if [[ ! -f .gclient ]]; then
  cat > .gclient <<'EOF'
solutions = [{"name": "src", "url": "https://github.com/webrtc-sdk/webrtc.git", "managed": False, "custom_deps": {}, "custom_vars": {}}]
target_os = ["ios"]
EOF
fi
if [[ ! -d src/.git ]]; then
  git clone --depth 1 --branch m125_release "$(read_pin source_url)" src
  if [[ "$(git -C src rev-parse HEAD)" != "$SOURCE_REV" ]]; then
    git -C src fetch --depth 1 origin "$SOURCE_REV"
    git -C src checkout --detach "$SOURCE_REV"
  fi
fi
[[ "$(git -C src rev-parse HEAD)" == "$SOURCE_REV" ]]
if [[ ! -f "$WORK/deps.ready" ]]; then
  gclient sync --no-history --shallow --jobs=8 --revision "src@$SOURCE_REV"
  touch "$WORK/deps.ready"
fi
[[ "$(git -C src/build rev-parse HEAD)" == "$(read_pin build_dependency_revision)" ]]
PATCH="$REPO_ROOT/$(read_pin build_tool_patch)"
if ! git -C src/build apply --reverse --check "$PATCH" 2>/dev/null; then
  git -C src/build apply --check "$PATCH"
  git -C src/build apply "$PATCH"
fi
CLANG_BIN="$(dirname "$(xcrun --sdk iphoneos --find clang)")"
python3 - "$WORK" "$CLANG_BIN" <<'PY'
from pathlib import Path
import json,subprocess,sys
work=Path(sys.argv[1]); clang=Path(sys.argv[2]).parent
flags=['clang_base_path='+json.dumps(str(clang)), 'clang_use_chrome_plugins=false',
       'use_lld=false', 'mac_deployment_target="14.0"', 'ios_enable_code_signing=false',
       'rtc_include_tests=false', 'is_debug=false', 'enable_dsyms=false',
       'use_custom_libcxx=false', 'treat_warnings_as_errors=false',
       'rtc_libvpx_build_vp9=true', 'rtc_enable_symbol_export=true', 'rtc_build_examples=false']
subprocess.run(['python3','src/tools_webrtc/ios/build_ios_libs.py','--build_config','release',
                '--arch','device:arm64','simulator:arm64','--deployment-target','17.0',
                '--output-dir',str(work/'output'),'--extra-gn-args',*flags],check=True)
PY
SOURCE="$WORK/output/WebRTC.xcframework"
DESTINATION="$REPO_ROOT/apple/Build/WebRTC"
mkdir -p "$DESTINATION"
rsync -a --delete "$SOURCE/" "$DESTINATION/WebRTC.xcframework/"
python3 "$REPO_ROOT/tools/licenses/collect_webrtc_notices.py" \
  --source "$WORK/checkout/src" --framework "$DESTINATION/WebRTC.xcframework"
python3 "$REPO_ROOT/tools/licenses/prepare_legal.py" --write
echo "Built pinned WebRTC with complete GN-derived notices: $DESTINATION/WebRTC.xcframework"
