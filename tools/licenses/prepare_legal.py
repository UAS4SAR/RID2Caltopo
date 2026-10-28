#!/usr/bin/env python3
"""Generate and verify the legal resources shared by both app bundles.

This checks notice packaging and integrity; it does not certify source provision,
relinking rights, service permissions, or complete dependency-license compliance.
"""
from pathlib import Path
import argparse
import hashlib
import json
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LEGAL = Path('app/src/main/assets/legal')


def expected(root: Path) -> dict[str, bytes]:
    result = {name: (root / name).read_bytes()
              for name in ['LICENSE', 'NOTICE', 'DISCLAIMER.md', 'THIRD_PARTY_NOTICES.md']}
    licenses = root / 'third_party/licenses'
    inventory = json.loads((licenses / 'INVENTORY.json').read_text())
    for item in inventory['files']:
        source = licenses / item['file']
        if hashlib.sha256(source.read_bytes()).hexdigest() != item['sha256']:
            raise ValueError(f"upstream notice changed without inventory update: {item['file']}")
    for source in sorted(licenses.rglob('*')):
        if source.is_file() and not source.name.startswith('.'):
            result['third_party/licenses/' + source.relative_to(licenses).as_posix()] = source.read_bytes()
    # Preserve the names used by older notices while keeping a canonical source.
    result['CERES-LICENSE.txt'] = (licenses / 'CERES-LICENSE.txt').read_bytes()
    result['EIGEN-MPL2-LICENSE.txt'] = (licenses / 'Eigen/COPYING.MPL2').read_bytes()
    parts = ['RID2Caltopo software licenses and notices\n\n'
             'Third-party components retain their own licenses. This collection includes '
             'conservative upstream notices; inclusion does not imply every optional '
             'upstream component is linked into the application.\n']
    for name, content in sorted(result.items()):
        if name in ['CERES-LICENSE.txt', 'EIGEN-MPL2-LICENSE.txt'] or name.endswith('.json'):
            continue
        parts.append('\n\n===== ' + name + ' =====\n\n' + content.decode('utf-8'))
    result['LICENSES.txt'] = ''.join(parts).encode('utf-8')
    manifest = {name: hashlib.sha256(data).hexdigest() for name, data in sorted(result.items())}
    result['legal-manifest.json'] = (json.dumps({'version': 1, 'files': manifest}, indent=2) + '\n').encode()
    return result


def verify(actual: dict[str, bytes], wanted: dict[str, bytes]) -> None:
    missing = sorted(wanted.keys() - actual.keys())
    stale = sorted(name for name in wanted.keys() & actual.keys() if wanted[name] != actual[name])
    if missing or stale:
        raise ValueError(f'legal resources missing={missing[:8]} stale={stale[:8]}')


def read_directory(path: Path) -> dict[str, bytes]:
    return {p.relative_to(path).as_posix(): p.read_bytes() for p in path.rglob('*')
            if p.is_file() and not p.name.startswith('.')}


def read_artifact(path: Path) -> dict[str, bytes]:
    if path.is_dir():
        return read_directory(path / 'legal')
    with zipfile.ZipFile(path) as archive:
        roots = {name[:-len('legal-manifest.json')] for name in archive.namelist()
                 if name.endswith('/legal/legal-manifest.json')}
        if not roots:
            raise ValueError('artifact has no generated legal manifest')
        if len(roots) != 1:
            raise ValueError('artifact contains multiple app legal directories')
        prefix = roots.pop()
        return {name[len(prefix):]: archive.read(name) for name in archive.namelist()
                if name.startswith(prefix) and not name.endswith('/')}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--write', action='store_true')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--artifact', type=Path, help='built .app directory, APK or IPA')
    args = parser.parse_args(argv)
    try:
        wanted = expected(args.root)
        destination = args.root / LEGAL
        if args.write:
            for name, data in wanted.items():
                path = destination / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
        verify(read_directory(destination), wanted)
        if args.artifact:
            verify(read_artifact(args.artifact), wanted)
        print(f'Legal resources verified: {len(wanted)} files; packaging integrity only.')
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f'Legal resources failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
