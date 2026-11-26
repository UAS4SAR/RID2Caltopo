#!/usr/bin/env python3
"""Shared cache of the reviewed, reproducible Apple native libraries.

Each library is keyed by a fingerprint of everything that can change its
bytes: the build scripts, the sources they compile, the pinned third-party
inputs (including the reviewed relink-inputs digests) and the toolchain.
When a checkout's fingerprint matches a cache entry the outputs are restored
instead of rebuilt; any real source change produces a new fingerprint and a
rebuild. --rebuild forces a rebuild and refreshes the entry.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CACHE = Path(os.environ.get('R2C_NATIVE_CACHE', '/Users/Shared/RID2Caltopo/native-cache'))
FORMAT = 1


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def tree(*relative, suffixes=None):
    for name in relative:
        base = ROOT / name
        if base.is_file():
            yield base
            continue
        for path in sorted(base.rglob('*')):
            parts = path.relative_to(base).parts
            if path.is_file() and not any(p.startswith('.') for p in parts) and (
                    suffixes is None or path.suffix in suffixes):
                yield path


def includes(*starts):
    """Yield the start files and every quoted #include they reach in the checkout."""
    pending, seen = [ROOT / s for s in starts], set()
    while pending:
        path = pending.pop().resolve()
        if path in seen or not path.is_file():
            continue
        seen.add(path)
        yield path
        for name in re.findall(r'^\s*#\s*include\s*"([^"]+)"', path.read_text(errors='replace'), re.M):
            pending.append(path.parent / name)


def relink_digest(name):
    data = json.loads((ROOT / 'tools/licenses/relink-inputs.json').read_text())[name]
    return hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()


def manifest_value(key):
    text = (ROOT / 'third_party/mediamtx/manifest.env').read_text()
    match = re.search(r'^' + key + r'=(.*)$', text, re.M)
    return match.group(1).strip().strip('"') if match else ''


def command(*argv):
    try:
        return subprocess.run(argv, capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return 'unavailable'


def anomaly_starts():
    cpp = ROOT / 'app/src/main/cpp'
    starts = [p.relative_to(ROOT).as_posix() for p in sorted(cpp.glob('anomaly_*')) if p.suffix in ('.c', '.h')]
    starts += [p.relative_to(ROOT).as_posix() for p in sorted((ROOT / 'apple/Native/AnomalyCore').iterdir())
               if p.suffix in ('.c', '.h')]
    return starts


def cmake():
    return os.environ.get('CMAKE') or shutil.which('cmake') or str(
        Path.home() / 'Library/Android/sdk/cmake/3.22.1/bin/cmake')


def go_binary():
    return shutil.which('go') or 'go'


LIBS = {
    'AnomalyCore': {
        'script': 'apple/Native/AnomalyCore/build-xcframework.sh',
        'library': 'libR2CAnomalyApple.a',
        'outputs': ['device', 'simulator', 'R2CAnomalyApple.xcframework'],
        'files': lambda: list(tree('apple/Native/AnomalyCore')) + list(includes(*anomaly_starts())),
        'extra': lambda: {},
    },
    'FFmpeg': {
        'script': 'apple/Native/FFmpeg/build-xcframework.sh',
        'library': 'libR2CFFmpegMobile.a',
        'outputs': ['device', 'simulator', 'source', 'R2CFFmpegMobile.xcframework'],
        'files': lambda: list(tree('apple/Native/FFmpeg', 'tools/verify_ffmpeg_source.sh')) + list(includes(
            *[p.relative_to(ROOT).as_posix() for p in (ROOT / 'apple/Native/FFmpeg').iterdir()
              if p.suffix in ('.c', '.h')])),
        'extra': lambda: {'relink_ffmpeg': relink_digest('ffmpeg'), 'ffmpeg_commit': manifest_value('FFMPEG_COMMIT')},
    },
    'MediaMTX': {
        'script': 'apple/Native/MediaMTX/build-xcframework.sh',
        'library': 'libmediamtx_mobile.a',
        'outputs': ['device', 'simulator', 'MediaMTXMobile.xcframework'],
        'files': lambda: list(tree('apple/Native/MediaMTX', 'third_party/mediamtx', 'tools/prepare_mediamtx_source.sh')),
        'extra': lambda: {'go': command(go_binary(), 'version')},
    },
    'Mapping': {
        'script': 'apple/Native/Mapping/build-xcframework.sh',
        'library': 'libr2c_mapping_runtime.a',
        'outputs': ['device', 'simulator', 'R2CMappingNative.xcframework'],
        'files': lambda: list(tree('apple/Native/Mapping', 'native/mapping')),
        'extra': lambda: {'relink_eigen': relink_digest('eigen'), 'cmake': command(cmake(), '--version')},
    },
}


def toolchain():
    return {
        'xcode': command('xcodebuild', '-version'),
        'iphoneos_sdk': command('xcrun', '--sdk', 'iphoneos', '--show-sdk-build-version'),
        'iphonesimulator_sdk': command('xcrun', '--sdk', 'iphonesimulator', '--show-sdk-build-version'),
        'macosx_sdk': command('xcrun', '--sdk', 'macosx', '--show-sdk-build-version'),
        'clang': command('xcrun', 'clang', '--version').splitlines()[0],
        'libtool': command('xcrun', 'libtool', '-V').splitlines()[0] if command('xcrun', 'libtool', '-V') else '',
    }


def fingerprint_inputs(name):
    spec = LIBS[name]
    files = {}
    for path in [ROOT / 'apple/Native/reproducible-build.sh', *spec['files']()]:
        files[path.resolve().relative_to(ROOT.resolve()).as_posix()] = sha256(path)
    return {'format': FORMAT, 'library': name, 'files': dict(sorted(files.items())),
            'extra': spec['extra'](), 'toolchain': toolchain(),
            'work_root': os.environ.get('R2C_NATIVE_WORK_OVERRIDE', '/Users/Shared/RID2Caltopo/native-work')}


def fingerprint(name, inputs=None):
    inputs = inputs or fingerprint_inputs(name)
    return hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()


def build_dir(name):
    return ROOT / 'apple/Build' / name


def listing(base, outputs):
    files = {}
    for output in outputs:
        top = base / output
        for path in sorted(top.rglob('*')) if top.is_dir() else [top]:
            if path.is_file() and not path.is_symlink():
                files[path.relative_to(base).as_posix()] = sha256(path)
    return files


def reviewed(name):
    library = LIBS[name]['library']
    record = json.loads((ROOT / 'tools/licenses/native-runtime-inputs.json').read_text())['libraries'][library]
    return record['sha256']


def device_digest(base, name):
    path = base / 'device' / LIBS[name]['library']
    return sha256(path) if path.is_file() else None


def store(name, key, inputs):
    entry = CACHE / name / key
    if (entry / 'complete.json').is_file():
        shutil.rmtree(entry)
    (CACHE / name).mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix='.' + key[:12] + '-', dir=CACHE / name))
    base = build_dir(name)
    for output in LIBS[name]['outputs']:
        source = base / output
        if not source.is_dir():
            raise SystemExit(f'{name}: build did not produce {source}')
        target = staging / 'outputs' / output
        target.mkdir(parents=True)
        subprocess.run(['rsync', '-a', '--exclude', '*.o', '--exclude', '*.d', f'{source}/', f'{target}/'], check=True)
    files = listing(staging / 'outputs', LIBS[name]['outputs'])
    (staging / 'inputs.json').write_text(json.dumps(inputs, indent=2, sort_keys=True) + '\n')
    (staging / 'complete.json').write_text(json.dumps({'files': files}, indent=2, sort_keys=True) + '\n')
    staging.rename(entry)
    return entry


def restore(name, entry):
    files = json.loads((entry / 'complete.json').read_text())['files']
    if listing(entry / 'outputs', LIBS[name]['outputs']) != files:
        raise SystemExit(f'{name}: cache entry {entry} is damaged; rerun with --rebuild-native')
    base = build_dir(name)
    base.mkdir(parents=True, exist_ok=True)
    for output in LIBS[name]['outputs']:
        source = entry / 'outputs' / output
        subprocess.run(['rsync', '-a', '--delete', str(source) + '/', str(base / output) + '/'], check=True)


def ensure(name, rebuild, require_reviewed):
    inputs = fingerprint_inputs(name)
    key = fingerprint(name, inputs)
    entry = CACHE / name / key
    base = build_dir(name)
    if not rebuild and (entry / 'complete.json').is_file():
        restore(name, entry)
        action = 'restored from cache'
    else:
        subprocess.run([str(ROOT / LIBS[name]['script'])], check=True)
        entry = store(name, key, inputs)
        action = 'rebuilt and cached'
    if name == 'Mapping' and not (base / 'macos/build.ninja').is_file():
        # The host slice only runs the parity tests and is never shipped.
        subprocess.run([str(ROOT / LIBS[name]['script']), '--host-only'], check=True)
    built = device_digest(base, name)
    status = 'matches' if built == reviewed(name) else 'DIFFERS FROM'
    print(f'{name}: {action} ({key[:16]}, {entry}); device library {built} {status} the reviewed baseline')
    if require_reviewed and built != reviewed(name):
        raise SystemExit(f'{name}: the device library is not the reviewed one in '
                         'tools/licenses/native-runtime-inputs.json. A native source or toolchain change '
                         'needs a dependency/license review and a separate baseline commit before release.')


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    sub = parser.add_subparsers(dest='command', required=True)
    for command_name in ['fingerprint', 'ensure', 'status']:
        p = sub.add_parser(command_name)
        p.add_argument('libraries', nargs='*', metavar='LIBRARY', help='one of ' + ', '.join(LIBS))
        if command_name == 'ensure':
            p.add_argument('--rebuild', action='store_true', help='rebuild even when the cache has this fingerprint')
            p.add_argument('--require-reviewed', action='store_true',
                           help='fail unless each device library matches native-runtime-inputs.json')
        if command_name == 'fingerprint':
            p.add_argument('--inputs', action='store_true', help='print the fingerprint inputs as JSON')
    args = parser.parse_args()
    names = args.libraries or list(LIBS)
    unknown = sorted(set(names) - set(LIBS))
    if unknown:
        parser.error('unknown library: ' + ', '.join(unknown))
    for name in names:
        if args.command == 'fingerprint':
            inputs = fingerprint_inputs(name)
            print(json.dumps(inputs, indent=2, sort_keys=True) if args.inputs else f'{name} {fingerprint(name, inputs)}')
        elif args.command == 'status':
            key = fingerprint(name)
            cached = (CACHE / name / key / 'complete.json').is_file()
            print(f'{name} {key} {"cached" if cached else "not cached"}')
        else:
            ensure(name, args.rebuild, args.require_reviewed)


if __name__ == '__main__':
    sys.exit(main())
