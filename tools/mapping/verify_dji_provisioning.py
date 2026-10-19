#!/usr/bin/env python3
"""Check protected DJI deployment input and the exact packaged asset; never print it."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import sys
import zipfile


class InvalidProvisioning(ValueError):
    pass


def validate(data, platform):
    # Same obfuscated transport as FAA configuration. It carries the app's
    # registration, not a per-tablet secret or a substitute for secure storage.
    try:
        import hashlib
        if len(data) > 32768:
            raise ValueError()
        def unique(pairs):
            value = {}
            for key, item in pairs:
                if key in value:
                    raise ValueError()
                value[key] = item
            return value
        value = json.loads(data, object_pairs_hook=unique)
        if (not isinstance(value, dict) or set(value) != {'type','version','encoding','enc','sha256'}
                or value['type'] != 'r2c_dji_app_registration' or type(value['version']) is not int
                or value['version'] != 1 or value['encoding'] != 'r2c-config'):
            raise ValueError()
        encoded = base64.b64decode(value['enc'], validate=True)
        key = b'RID2CaltopoQR'
        clear = bytes(byte ^ key[index % len(key)] for index, byte in enumerate(encoded))
        if hashlib.sha256(clear).hexdigest() != value['sha256']:
            raise ValueError()
        fields = json.loads(clear, object_pairs_hook=unique)
        if (set(fields) != {'appId','appKey','appLicense'}
                or not all(isinstance(item,str) for item in fields.values())
                or not re.fullmatch('[0-9]{1,12}',fields['appId'])
                or not 16 <= len(fields['appKey']) <= 256
                or not 32 <= len(fields['appLicense']) <= 8192):
            raise ValueError()
        return data
    except Exception:
        raise InvalidProvisioning("The app-wide DJI registration is missing, corrupt or invalid. Device-specific bundles and plaintext inputs cannot qualify a release.") from None


def packaged(path, platform):
    path = Path(path)
    try:
        if path.is_dir():
            if path.suffix == '.xcarchive':
                apps = list((path / 'Products/Applications').glob('*.app'))
                if len(apps) != 1:
                    raise ValueError()
                path = apps[0]
            return (path / 'dji-registration/registration.json').read_bytes()
        with zipfile.ZipFile(path) as archive:
            if platform == 'android':
                name = 'base/assets/dji-registration/registration.json' if path.suffix == '.aab' else 'assets/dji-registration/registration.json'
                matches = [n for n in archive.namelist() if n == name]
            else:
                matches = [n for n in archive.namelist() if re.fullmatch(r'Payload/[^/]+\.app/dji-registration/registration.json', n)]
            if len(matches) != 1 or archive.getinfo(matches[0]).file_size > 32768:
                raise ValueError()
            return archive.read(matches[0])
    except Exception:
        raise InvalidProvisioning('Packaged DJI provisioning is missing, duplicated or unreadable. This artifact is not qualified for deployment.') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', choices=['android','apple'], required=True)
    parser.add_argument('--bundle', type=Path)
    parser.add_argument('--artifact', type=Path)
    args = parser.parse_args()
    env = 'R2C_DJI_APP_REGISTRATION'
    default = Path(__file__).resolve().parents[2] / '.local-deployment/dji-app-registration.json'
    source = args.bundle or (Path(os.environ[env]) if os.environ.get(env) else (default if default.is_file() else None))
    expected = None
    if source:
        try:
            if source.stat().st_size > 32768:
                raise ValueError()
            expected = validate(source.read_bytes(), args.platform)
        except InvalidProvisioning:
            raise
        except Exception:
            raise InvalidProvisioning('The protected DJI deployment input is missing or unreadable.') from None
    elif not args.artifact:
        raise InvalidProvisioning('App-wide DJI registration input is required. Set ' + env + ' to the app registration before producing a release.')
    if args.artifact:
        actual = validate(packaged(args.artifact, args.platform), args.platform)
        if expected is not None and actual != expected:
            raise InvalidProvisioning('Packaged DJI provisioning does not match the selected protected input.')
    print('App-wide DJI registration verified' + (' in packaged artifact.' if args.artifact else '.') + ' Fresh-install default and protected per-installation storage are required.')


if __name__ == '__main__':
    try:
        main()
    except InvalidProvisioning as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
