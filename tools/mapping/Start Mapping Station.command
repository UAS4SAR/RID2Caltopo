#!/bin/bash
mapping_repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
if [ ! -x "$mapping_repo_root/.venv-mapping/bin/python" ]; then
    printf '%s\n' 'Run Setup Mapping Station first.'
else
    cd "$mapping_repo_root"
    "$mapping_repo_root/.venv-mapping/bin/python" tools/mapping/local_runtime.py start
fi
printf '\n%s\n' 'Press Return to close this window. The station keeps running until you use Stop Mapping Station.'
read -r
