#!/usr/bin/env python3
"""Check the source-built WebRTC framework in a newly created Simulator.

No application is installed. The standalone executable requests no media
capture or remote connection. Only the Simulator created here is deleted.
"""
from pathlib import Path
import argparse
import json
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True, help='new dedicated result directory')
    args = parser.parse_args()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    run = lambda command: subprocess.run(command, check=True)
    output = lambda command: subprocess.check_output(command, text=True).strip()
    runtime_list = json.loads(output(['xcrun', 'simctl', 'list', 'runtimes', '-j']))['runtimes']
    runtimes = [item for item in runtime_list if item.get('isAvailable') and '.iOS-' in item['identifier']]
    version = lambda item: tuple(int(v) for v in item['version'].split('.'))
    runtime = max(runtimes, key=version)
    parts = list(version(runtime)) + [0, 0]
    encoded = (parts[0] << 16) | (parts[1] << 8) | parts[2]
    devices = json.loads(output(['xcrun', 'simctl', 'list', 'devicetypes', '-j']))['devicetypes']
    device = next(item for item in devices if item['name'].startswith('iPhone')
                  and item.get('minRuntimeVersion', 0) <= encoded <= item.get('maxRuntimeVersion', 0xFFFFFFFF))
    frameworks = work / 'Frameworks'
    frameworks.mkdir()
    framework = frameworks / 'WebRTC.framework'
    shutil.copytree(ROOT / 'apple/Build/WebRTC/WebRTC.xcframework/ios-arm64-simulator/WebRTC.framework', framework)
    sdk = output(['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-path'])
    executable = work / 'WebRTCSmoke'
    run(['xcrun', '--sdk', 'iphonesimulator', 'swiftc', '-target', 'arm64-apple-ios17.0-simulator',
         '-sdk', sdk, '-F', str(frameworks), '-framework', 'WebRTC', '-Xlinker', '-rpath',
         '-Xlinker', '@executable_path/Frameworks', str(ROOT / 'apple/Tests/WebRTCLicenseSmoke/main.swift'),
         '-o', str(executable)])
    run(['codesign', '--force', '--sign', '-', str(framework)])
    run(['codesign', '--force', '--sign', '-', str(executable)])
    simulator = output(['xcrun', 'simctl', 'create', 'RID2Caltopo isolated WebRTC license smoke',
                        device['identifier'], runtime['identifier']])
    try:
        run(['xcrun', 'simctl', 'boot', simulator])
        run(['xcrun', 'simctl', 'bootstatus', simulator, '-b'])
        with (work / 'runtime.log').open('w') as log:
            subprocess.run(['xcrun', 'simctl', 'spawn', simulator, str(executable)],
                           stdout=log, stderr=subprocess.STDOUT, timeout=30, check=True)
        lines = (work / 'runtime.log').read_text().splitlines()
        result = next(json.loads(line) for line in reversed(lines) if line.startswith('{'))
        if result.get('result') != 'passed':
            raise RuntimeError('WebRTC smoke failed')
        (work / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2))
    finally:
        subprocess.run(['xcrun', 'simctl', 'shutdown', simulator], check=False)
        subprocess.run(['xcrun', 'simctl', 'delete', simulator], check=True)


if __name__ == '__main__':
    main()
