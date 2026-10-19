#!/bin/bash
set -euo pipefail

mapping_repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
mapping_brew="$(command -v brew || true)"
if [ -z "$mapping_brew" ]; then
    for mapping_candidate in /opt/homebrew/bin/brew /usr/local/bin/brew; do
        if [ -x "$mapping_candidate" ]; then mapping_brew="$mapping_candidate"; break; fi
    done
fi
if [ -z "$mapping_brew" ]; then
    printf '%s\n' 'One-time prerequisite: install Homebrew using https://brew.sh, then run this setup again.'
    exit 1
fi
mapping_brew_prefix="$("$mapping_brew" --prefix)"
mapping_missing=()
for mapping_formula in colima docker python@3.12; do
    mapping_binary="$mapping_formula"
    if [ "$mapping_formula" = python@3.12 ]; then mapping_binary=python3.12; fi
    if [ ! -x "$mapping_brew_prefix/opt/$mapping_formula/bin/$mapping_binary" ]; then
        mapping_missing+=("$mapping_formula")
    fi
done
printf '%s\n' 'Preparing the local mapping runtime. This first setup needs internet access.'
if [ "${#mapping_missing[@]}" -gt 0 ]; then
    if ! HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALL_CLEANUP=1 "$mapping_brew" install "${mapping_missing[@]}"; then
        printf '%s\n' 'Homebrew reported an error. Checking whether the required binaries were installed.'
    fi
fi
for mapping_formula in colima docker python@3.12; do
    mapping_binary="$mapping_formula"
    if [ "$mapping_formula" = python@3.12 ]; then mapping_binary=python3.12; fi
    mapping_candidate="$mapping_brew_prefix/opt/$mapping_formula/bin/$mapping_binary"
    if [ ! -x "$mapping_candidate" ] || ! "$mapping_candidate" --version >/dev/null 2>&1; then
        printf '%s\n' "Setup needs attention: $mapping_formula is not usable at $mapping_candidate."
        exit 1
    fi
done
# Use formula binaries directly; global links and shell completions are optional.
export PATH="$mapping_brew_prefix/opt/docker/bin:$mapping_brew_prefix/opt/colima/bin:$mapping_brew_prefix/bin:$PATH"
mapping_python="$mapping_brew_prefix/opt/python@3.12/bin/python3.12"
"$mapping_python" -m venv "$mapping_repo_root/.venv-mapping"
"$mapping_repo_root/.venv-mapping/bin/python" -m pip install -r "$mapping_repo_root/tools/mapping/requirements.txt"
cd "$mapping_repo_root"
"$mapping_repo_root/.venv-mapping/bin/python" -c 'from pathlib import Path; from tools.mapping.apply_update import ensure_ignore; ensure_ignore(Path.cwd())'
exec "$mapping_repo_root/.venv-mapping/bin/python" tools/mapping/local_runtime.py setup
