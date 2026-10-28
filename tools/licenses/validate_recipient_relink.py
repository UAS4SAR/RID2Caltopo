#!/usr/bin/env python3
"""Exercise the exported recipient workflow with an actual FFmpeg modification.

Creates a new dedicated directory; never overwrites or deletes an existing one.
Optional --deny-checkout runs commands under macOS sandbox-exec with reads of
the developer checkout denied. No app is installed or signed by this check.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kit', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--deny-checkout', type=Path)
    args = parser.parse_args()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    kit = work / 'materials'
    subprocess.run(['/usr/bin/unzip', '-q', str(args.kit.resolve()), '-d', str(kit)], check=True)
    prefix = []
    if args.deny_checkout:
        profile = '(version 1)(allow default)(deny file-read* (subpath ' + json.dumps(str(args.deny_checkout.resolve())) + '))'
        prefix = ['/usr/bin/sandbox-exec', '-p', profile]

    def run(command, name):
        with (work / (name + '.log')).open('w') as log:
            subprocess.run(prefix + command, cwd=kit, stdout=log, stderr=subprocess.STDOUT, check=True)

    original = work / 'original'
    replacement = work / 'replacement'
    run([sys.executable, 'relink_apple.py', '--output', str(original)], 'original-link')
    source = kit / 'source/ffmpeg/libavutil/error.c'
    text = source.read_text()
    marker = 'RID2Caltopo independent FFmpeg replacement proof'
    if '"Unknown error occurred"' not in text:
        raise SystemExit('Update the replacement probe for this FFmpeg source version')
    source.write_text(text.replace('"Unknown error occurred"', json.dumps(marker)))
    build = work / 'ffmpeg-build'
    run([sys.executable, 'rebuild_ffmpeg.py', '--build', str(build)], 'replacement-build')
    run([sys.executable, 'relink_apple.py', '--ffmpeg', str(build / 'libR2CFFmpegMobile.a'),
         '--output', str(replacement)], 'replacement-link')
    if marker.encode() in original.read_bytes() or marker.encode() not in replacement.read_bytes():
        raise SystemExit('Modified library code did not reach the replacement executable')
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report = {'result': 'passed', 'kit_sha256': sha(args.kit),
              'application_uuid': json.loads((kit / 'manifest.json').read_text())['application_uuid'],
              'original_relinked_sha256': sha(original), 'replacement_relinked_sha256': sha(replacement),
              'replacement_library_sha256': sha(build / 'libR2CFFmpegMobile.a'),
              'proof': 'Modified FFmpeg error text occurs only in the replacement-library app executable.',
              'original_checkout_reads_denied': bool(args.deny_checkout),
              'installation_or_signing': 'none'}
    (work / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
