#!/usr/bin/env python3
"""Explicit administrator capture of the app registration from a configured iPad.
Only the existing FAA-style encoded registration is copied; no plaintext logs.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid
from verify_dji_provisioning import validate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ipad', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); os.umask(0o077)
    app = 'org.ncssar.RID2CaltopoApple'
    base = 'Library/Application Support/PilotCloudProvisioning/'
    def run(command):
        result = subprocess.run(command,capture_output=True,timeout=90)
        if result.returncode: raise RuntimeError()
    def copy(source,destination):
        run(['xcrun','devicectl','device','copy','from','--device',args.ipad,'--domain-type','appDataContainer',
             '--domain-identifier',app,'--source',base+source,'--destination',str(destination)])
    request = str(uuid.uuid4())
    run(['xcrun','devicectl','device','process','launch','--device',args.ipad,'--terminate-existing',app,
         '--','--export-pilot-app-registration',request])
    with tempfile.TemporaryDirectory(prefix='r2c-app-registration-') as temporary:
        folder=Path(temporary)
        for _ in range(20):
            time.sleep(1)
            try:
                copy('app-registration-receipt.json',folder/'receipt.json')
                receipt=json.loads((folder/'receipt.json').read_bytes())
                if receipt.get('requestID')!=request:continue
                copy('app-registration.json',folder/'registration.json')
                data=(folder/'registration.json').read_bytes()
                if hashlib.sha256(data).hexdigest()!=receipt.get('registrationSha256'):continue
                validate(data,'apple')
                args.output.parent.mkdir(parents=True,exist_ok=True)
                staged=args.output.with_suffix('.tmp'); staged.write_bytes(data); staged.chmod(0o600); staged.replace(args.output)
                print('App-wide DJI registration captured in FAA-style encoded transport; no credential values displayed.')
                return
            except Exception:continue
    raise RuntimeError()

if __name__=='__main__':
    try:main()
    except Exception:
        print('App registration capture did not complete. Unlock the iPad and use the development capture build.')
        raise SystemExit(1)
