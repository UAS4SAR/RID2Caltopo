#!/usr/bin/env python3
"""Relink the supplied app object files with an original or replacement FFmpeg.

Run after extracting RID2Caltopo-relink.zip. Requires macOS and Xcode with the
iOS SDK. No application source checkout, signing key or developer credential
is needed to produce the unsigned executable.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg', type=Path, default=root / 'libraries/libR2CFFmpegMobile.a')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--allow-modified-inputs', action='store_true',
                        help='allow intentional changes to other supplied app/link inputs')
    args = parser.parse_args()
    manifest = json.loads((root / 'manifest.json').read_text())
    # Input integrity is checked before linking; a replacement library is allowed.
    for name, digest in manifest['files'].items():
        if (args.allow_modified_inputs or name == 'libraries/libR2CFFmpegMobile.a'
                or name.startswith('source/')):
            continue
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
            raise SystemExit('Input changed or missing: ' + name)
    sdk = subprocess.check_output(['xcrun', '--sdk', 'iphoneos', '--show-sdk-path'], text=True).strip()
    clang = subprocess.check_output(['xcrun', '--sdk', 'iphoneos', '--find', 'clang'], text=True).strip()
    swift = Path(clang).parents[1] / 'lib/swift/iphoneos'
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='rid2caltopo-link-') as temporary:
        listing = Path(temporary) / 'objects.txt'
        listing.write_text(''.join(str(root / name) + '\n' for name in manifest['objects']))
        command = [clang, '-target', 'arm64-apple-ios17.0', '-isysroot', sdk, '-Os',
                   '-Wl,-no_warn_duplicate_libraries', '-Wl,-dead_strip',
                   '-Wl,-rpath,/usr/lib/swift', '-Wl,-rpath,@executable_path/Frameworks',
                   '-fobjc-link-runtime', '-L' + str(swift), '-L/usr/lib/swift',
                   '-filelist', str(listing), '-F' + str(root / 'frameworks'),
                   '-lc++', '-lresolv', '-framework', 'VideoToolbox',
                   '-framework', 'CoreMedia', '-framework', 'CoreVideo',
                   '-framework', 'Accelerate']
        command += [str(root / 'libraries' / name) for name in
                    ['libr2c_mapping_runtime.a', 'libmediamtx_mobile.a', 'libR2CAnomalyApple.a']]
        command += [str(args.ffmpeg.resolve()), '-framework', 'WebRTC', '-o', str(args.output.resolve())]
        subprocess.run(command, check=True)
    print('Relinked unsigned executable:', args.output)


if __name__ == '__main__':
    main()
