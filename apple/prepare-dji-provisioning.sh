#!/bin/sh
# App-wide DJI registration, using the same obfuscated transport as FAA config.
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output="${TARGET_BUILD_DIR:?}/${UNLOCALIZED_RESOURCES_FOLDER_PATH:?}/dji-registration"
source=${R2C_DJI_APP_REGISTRATION:-$repo_root/.local-deployment/dji-app-registration.json}
if [ ! -f "$source" ]; then
    rm -f "$output/registration.json"
    if [ "${CONFIGURATION:-}" = Release ]; then
        python3 "$repo_root/tools/mapping/verify_dji_provisioning.py" --platform apple --bundle "$source"
    fi
    exit 0
fi
python3 "$repo_root/tools/mapping/verify_dji_provisioning.py" --platform apple --bundle "$source"
mkdir -p "$output"
cp "$source" "$output/registration.json"
python3 "$repo_root/tools/mapping/verify_dji_provisioning.py" --platform apple --bundle "$source" --artifact "${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}"
