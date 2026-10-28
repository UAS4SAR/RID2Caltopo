#!/usr/bin/env python3
"""Retain notices from a completed, pinned WebRTC GN build.

The upstream generator must have completed successfully for both slices. Its
component list determines the actual dependency-license inputs retained here.
"""
from pathlib import Path
import argparse
import ast
import hashlib
import html
import json
import plistlib
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--framework', type=Path, required=True)
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    source = args.source.resolve()
    framework = args.framework.resolve()
    source_commit = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    pin = json.loads((args.root / 'third_party/webrtc/manifest.json').read_text())
    if source_commit != pin['source_revision']:
        raise SystemExit('WebRTC source differs from reviewed pin')
    generated = html.unescape((framework / 'LICENSE.md').read_text())
    components = re.findall(r'^# ([a-z0-9_+\-]+)$', generated, re.M)
    tree = ast.parse((source / 'tools_webrtc/libs/generate_licenses.py').read_text())
    mapping = next(ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
                   and any(isinstance(target, ast.Name) and target.id == 'LIB_TO_LICENSES_DICT' for target in node.targets))
    destination = args.root / 'third_party/licenses'
    inventory_path = destination / 'INVENTORY.json'
    inventory = json.loads(inventory_path.read_text())
    retained = []

    def retain(path, name, component):
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if path.resolve() != target.resolve():
            shutil.copy2(path, target)
        relative = path.relative_to(source).as_posix() if path.is_relative_to(source) else 'generated GN build LICENSE.md'
        revision = subprocess.check_output(['git', '-C', str(path.parent), 'rev-parse', 'HEAD'], text=True).strip() if path.is_relative_to(source) else source_commit
        record = {'file': name, 'component': component, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
                  'origin': 'WebRTC M125 source build; ' + relative + '; repository revision ' + revision}
        inventory['files'] = [item for item in inventory['files'] if item['file'] != name]
        inventory['files'].append(record)
        retained.append(record)

    for component in components:
        for name in mapping[component]:
            path = source / name
            retain(path, 'WebRTC-dependencies/' + component + '/' + path.name, 'WebRTC ' + component)
            for additional in ['NOTICE', 'PATENTS', 'AUTHORS', 'COPYRIGHT']:
                extra = path.parent / additional
                if extra.is_file() and extra != path:
                    retain(extra, 'WebRTC-dependencies/' + component + '/' + additional, 'WebRTC ' + component)
    combined = destination / 'WebRTC-THIRD-PARTY-LICENSES.txt'
    combined.write_text(generated)
    retain(combined, 'WebRTC-THIRD-PARTY-LICENSES.txt', 'WebRTC generated dependency notices')
    slices = {}
    for library in plistlib.loads((framework / 'Info.plist').read_bytes())['AvailableLibraries']:
        identifier = library['LibraryIdentifier']
        binary = framework / identifier / library['LibraryPath'] / 'WebRTC'
        slices[identifier] = {'sha256': hashlib.sha256(binary.read_bytes()).hexdigest(), 'bytes': binary.stat().st_size}
    provenance = {'source_revision': source_commit, 'source_url': pin['source_url'],
                  'target': '//sdk:framework_objc', 'components': components,
                  'slices': slices, 'notice_files': retained,
                  'build_tool_patch_sha256': hashlib.sha256((args.root / pin['build_tool_patch']).read_bytes()).hexdigest()}
    path = destination / 'WebRTC-build-provenance.json'
    path.write_text(json.dumps(provenance, indent=2) + '\n')
    record = {'file': path.name, 'component': 'WebRTC build provenance',
              'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
              'origin': 'Pinned source build, both slices and successful upstream GN license generator'}
    inventory['files'] = [item for item in inventory['files'] if item['file'] != path.name]
    inventory['files'].append(record)
    inventory_path.write_text(json.dumps(inventory, indent=2) + '\n')
    print('Retained WebRTC notices for', len(components), 'GN dependency components and', len(slices), 'slices.')


if __name__ == '__main__':
    main()
