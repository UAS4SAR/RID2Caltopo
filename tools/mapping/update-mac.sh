#!/bin/bash
set -euo pipefail
mapping_repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$mapping_repo_root"
mapping_python="$mapping_repo_root/.venv-mapping/bin/python"
if [ ! -x "$mapping_python" ]; then
    printf '%s\n' 'Run Setup Mapping Station once before applying this update.'
    exit 1
fi
# Only stop this station after ensuring no reconstruction or share is active.
"$mapping_python" - <<'PY'
from pathlib import Path
import json
root=Path.home()/'R2C-Mapping'
for state in root.glob('*/state.json'):
    value=json.loads(state.read_text())
    if value.get('status') in {'submitting','processing','collecting-results','cancelling'} or value.get('publishState')=='uploading':
        raise SystemExit('Finish or cancel the active mapping task before updating the station.')
PY
"$mapping_python" tools/mapping/local_runtime.py stop
exec "$mapping_python" tools/mapping/local_runtime.py start
