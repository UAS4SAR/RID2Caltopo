#!/bin/bash
mapping_repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
if [ -x "$mapping_repo_root/.venv-mapping/bin/python" ]; then
    cd "$mapping_repo_root"
    "$mapping_repo_root/.venv-mapping/bin/python" tools/mapping/local_runtime.py stop
fi
printf '\n%s\n' 'Press Return to close this window.'
read -r
