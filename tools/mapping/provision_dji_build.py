#!/usr/bin/env python3
"""Administrator-only exact credential intake; operators use the provisioned build.

Private values stay in masked local prompts or a private input file. Only a
device-encrypted envelope leaves the temporary intake directory. No OCR.
"""
import argparse
import getpass
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def local_prompt(label, default='', mac=False, secret=False):
    if not mac:
        return getpass.getpass(label + ': ') if secret else input(label + ': ')
    script = ('on run argv\n'
              'set response to display dialog (item 1 of argv) with title "R2C protected build setup" '
              'default answer (item 2 of argv) with hidden answer buttons {"Cancel", "Continue"} '
              'default button "Continue"\nreturn text returned of response\nend run')
    result = subprocess.run(['osascript', '-e', script, label, default],
                            capture_output=True, text=True, check=False)
    if result.returncode:
        raise ValueError('Private input cancelled. No deployment change was made.')
    return result.stdout.strip()


def validate(value):
    required = ('appId', 'appKey', 'appLicense')
    if not all(isinstance(value.get(name), str) for name in required):
        raise ValueError('Private input needs appId, appKey, and appLicense strings.')
    value = {name: ''.join(value[name].split()) for name in required}
    if not re.fullmatch(r'[0-9]{1,12}', value['appId']):
        raise ValueError('Cloud API app ID must be numeric.')
    if not 16 <= len(value['appKey']) <= 256 or not 32 <= len(value['appLicense']) <= 8192:
        raise ValueError('Copy the complete Cloud API app key and basic license.')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--public-key', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--registration', type=Path, help='Exact private registration JSON; never a screenshot.')
    parser.add_argument('--defaults', type=Path, help='Private JSON used only to prefill the app ID.')
    parser.add_argument('--mac-prompts', action='store_true', help='Masked local Mac prompts instead of Terminal input.')
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    os.umask(0o077)
    if args.registration:
        value = validate(json.loads(args.registration.read_text()))
    else:
        default = json.loads(args.defaults.read_text()).get('appId', '') if args.defaults else ''
        value = validate({
            'appId': local_prompt('Cloud API app ID from DJI Developer', default, args.mac_prompts),
            'appKey': local_prompt('Paste the EXACT Cloud API app key from DJI Developer', mac=args.mac_prompts, secret=True),
            'appLicense': local_prompt('Paste the EXACT App Basic License from DJI Developer', mac=args.mac_prompts, secret=True),
        })
    tool = Path(__file__).with_name('SealDjiCredentials.java')
    with tempfile.TemporaryDirectory(prefix='r2c-private-registration-') as folder:
        private = Path(folder) / 'registration.json'
        private.write_text(json.dumps(value))
        result = subprocess.run([args.java, str(tool), str(private), str(args.public_key), str(args.output)],
                                capture_output=True, check=False)
        if result.returncode:
            raise ValueError('Could not create the device-encrypted deployment bundle.')
    args.output.chmod(0o600)
    print('Exact credentials sealed for this tablet. No values displayed; temporary plaintext removed.')


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print('Protected provisioning failed; no credential values displayed.', file=sys.stderr)
        sys.exit(1)
