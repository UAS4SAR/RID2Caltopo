#!/usr/bin/env python3
"""Explicit local deployment: reuse Android's protected DJI setup on an enrolled iPad.

Requires unlocked, connected devices and the administrator-only Android test APK.
Only public enrollment and recipient-encrypted ciphertext transit this computer.
This is not a release test, upload, or distribution step.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--android', required=True)
    parser.add_argument('--ipad', required=True)
    parser.add_argument('--helper-apk', type=Path, required=True)
    args = parser.parse_args()
    android = 'org.ncssar.rid2caltopo'
    apple = 'org.ncssar.RID2CaltopoApple'
    adb = [args.adb, '-s', args.android]
    base = 'Library/Application Support/PilotCloudProvisioning/'

    def run(command, *, input=None):
        result = subprocess.run(command, input=input, capture_output=True, timeout=90)
        if result.returncode:
            raise RuntimeError('Local deployment command failed. Check that both devices are connected and unlocked.')
        return result.stdout

    def copy(direction, source, destination):
        return run(['xcrun', 'devicectl', 'device', 'copy', direction, '--device', args.ipad,
                    '--domain-type', 'appDataContainer', '--domain-identifier', apple,
                    '--source', str(source), '--destination', str(destination)])

    with tempfile.TemporaryDirectory(prefix='r2c-encrypted-pilot-deployment-') as temporary:
        folder = Path(temporary)
        public = folder / 'enrollment.pem'
        copy('from', base + 'enrollment.pem', public)
        encoded = base64.b64decode(''.join(line for line in public.read_text().splitlines() if not line.startswith('---')), validate=True)
        recipient = hashlib.sha256(encoded).hexdigest()
        copy('from', base + 'status.json', folder / 'status.json')
        if json.loads((folder / 'status.json').read_text())['recipientSha256'] != recipient:
            raise RuntimeError('iPad enrollment fingerprint does not match its status receipt.')
        run(adb + ['install', '-r', str(args.helper_apk)])
        run(adb + ['shell', 'run-as', android, 'mkdir', '-p', 'no_backup/pilot-deployment'])
        try:
            run(adb + ['shell', 'run-as', android, 'sh', '-c', "'cat > no_backup/pilot-deployment/recipient.pem'"], input=public.read_bytes())
            report = run(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class',
                                'org.ncssar.rid2caltopo.mapping.PilotCredentialDeployment',
                                '-e', 'provisionPilotCredentialsToEnrolledDevice', 'true',
                                '-e', 'recipientSha256', recipient,
                                android + '.test/androidx.test.runner.AndroidJUnitRunner'])
            if b'OK (1 test)' not in report or b'FAILURES' in report:
                raise RuntimeError('Android could not seal the protected setup. Ensure it is unlocked.')
            sealed = run(adb + ['exec-out', 'run-as', android, 'cat', 'no_backup/pilot-deployment/sealed.json'])
            value = json.loads(sealed)
            expected = {'type','version','algorithm','recipientSha256','wrappedKey','iv','ciphertext'}
            if set(value) != expected or value['recipientSha256'] != recipient or value['version'] != 2:
                raise RuntimeError('Protected envelope validation failed.')
            envelope = folder / 'sealed.json';envelope.write_bytes(sealed);envelope.chmod(0o600)
        finally:
            run(adb + ['shell', 'run-as', android, 'rm', '-rf', 'no_backup/pilot-deployment'])
        copy('to', envelope, base + 'sealed.json')
        run(['xcrun','devicectl','device','process','launch','--device',args.ipad,'--terminate-existing',apple])
        accepted = False
        for _ in range(10):
            time.sleep(1)
            copy('from', base + 'status.json', folder / 'status.json')
            status = json.loads((folder / 'status.json').read_text())
            if status.get('configured') is True and status.get('recipientSha256') == recipient:
                copy('from', base + 'accepted.sha256', folder / 'accepted.sha256')
                accepted = (folder / 'accepted.sha256').read_text() == hashlib.sha256(sealed).hexdigest()
                if accepted:
                    break
        if not accepted:
            raise RuntimeError('iPad import is not confirmed. Unlock it and open R2C to complete protected setup.')
    print('iPad accepted the device-encrypted DJI setup and reports configured. No credential values displayed.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never print captured tool output or credential-bearing objects.
        print(str(error) if isinstance(error, RuntimeError) else 'Protected local deployment failed; no credential values displayed.')
        raise SystemExit(1)
