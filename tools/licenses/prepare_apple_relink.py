#!/usr/bin/env python3
"""Prepare cached, hosted LGPL source/relink materials for an iOS Release app.

The ZIP contains the exact app objects and redistributable non-system link
inputs, FFmpeg source/bridge, Eigen source and all bundled notices. Apple SDKs,
signing identities, provisioning profiles and deployment configuration are
never copied. Run after linking and before signing the app. Only a small download
reference and the written offer remain in the app; the ZIP is a GitHub release asset.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import plistlib
import re
import struct
import subprocess
import sys
import tempfile
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
KIT_NAME = 'RID2Caltopo-relink.zip'
REFERENCE_NAME = 'relink-materials.json'
REPOSITORY = 'UAS4SAR/RID2Caltopo'
CACHE = ROOT / 'apple/Build/ComplianceMaterials'
LIBRARIES = ['libr2c_mapping_runtime.a', 'libmediamtx_mobile.a',
             'libR2CAnomalyApple.a', 'libR2CFFmpegMobile.a']


def digest(data):
    return hashlib.sha256(data).hexdigest()


def macho_uuid(data):
    if data[:4] in [b'\xca\xfe\xba\xbe', b'\xca\xfe\xba\xbf']:
        count = struct.unpack_from('>I', data, 4)[0]
        width = 32 if data[3] == 0xBF else 20
        if count > 16:
            raise ValueError('Unexpected universal Mach-O architecture count')
        for index in range(count):
            start = 8 + index * width
            cpu = struct.unpack_from('>I', data, start)[0]
            if cpu == 0x0100000C:
                form = '>QQ' if width == 32 else '>II'
                offset, size = struct.unpack_from(form, data, start + 8)
                return macho_uuid(data[offset:offset + size])
        raise ValueError('Universal Mach-O has no arm64 slice')
    if data[:4] != b'\xcf\xfa\xed\xfe':
        raise ValueError('Expected an arm64 Mach-O executable or framework')
    commands = struct.unpack_from('<I', data, 16)[0]
    offset = 32
    for _ in range(commands):
        command, size = struct.unpack_from('<II', data, offset)
        if size < 8 or offset + size > len(data):
            raise ValueError('Invalid Mach-O load command')
        if command == 0x1B:
            return str(uuid.UUID(bytes=data[offset + 8:offset + 24]))
        offset += size
    raise ValueError('Mach-O has no UUID')


def validate_kit(archive, app_uuid, legal_digest, framework_uuid):
    names = archive.namelist()
    if len(names) != len(set(names)):
        raise ValueError('Duplicate relink-kit archive paths')
    if any(name.startswith('/') or '..' in Path(name).parts for name in names):
        raise ValueError('Unsafe relink-kit path')
    manifest = json.loads(archive.read('manifest.json'))
    if manifest.get('version') != 1 or manifest.get('application_uuid') != app_uuid:
        raise ValueError('Relink materials do not match this app executable')
    if manifest.get('legal_manifest_sha256') != legal_digest:
        raise ValueError('Relink notices do not match this app')
    if manifest.get('webrtc_uuid') != framework_uuid:
        raise ValueError('Relink WebRTC input does not match this app framework')
    expected = manifest['files']
    if set(names) != set(expected) | {'manifest.json'}:
        raise ValueError('Relink-kit inventory differs from its contents')
    for name, sha in expected.items():
        if digest(archive.read(name)) != sha:
            raise ValueError('Missing or changed relink material: ' + name)
    required = {'relink_apple.py', 'rebuild_ffmpeg.py', 'README.txt',
                'RELINKING_PERMISSIONS.txt', 'LICENSES.txt',
                'source/ffmpeg/configure', 'source/ffmpeg/COPYING.LGPLv2.1',
                'source/application/apple/Native/FFmpeg/R2CFFmpegMobile.c',
                'source/application/app/src/main/cpp/anomaly_runtime_budget.h',
                'source/eigen/COPYING.MPL2', 'source/eigen/Eigen/Core',
                'frameworks/WebRTC.framework/WebRTC'}
    required |= {'libraries/' + name for name in LIBRARIES}
    if not required <= set(expected) or not manifest['objects']:
        raise ValueError('Relink kit lacks required source, objects or libraries')
    if any(name not in expected or not name.startswith('objects/') for name in manifest['objects']):
        raise ValueError('Invalid app object inventory')
    if sum(name.startswith('source/ffmpeg/') for name in expected) < 8_000:
        raise ValueError('FFmpeg source collection is incomplete')
    return manifest


def hosted_url(material_id):
    return 'https://github.com/' + REPOSITORY + '/releases/download/ios-relink-' + material_id + '/' + KIT_NAME


def validate_reference(reference, app_uuid, legal_digest, framework_uuid):
    if reference.get('version') != 2 or reference.get('distribution') != 'hosted-written-offer':
        raise ValueError('Unrecognized hosted relink reference')
    if reference.get('application_uuid') != app_uuid or reference.get('webrtc_uuid') != framework_uuid:
        raise ValueError('Hosted relink reference does not match this app/framework')
    if reference.get('legal_manifest_sha256') != legal_digest:
        raise ValueError('Hosted relink reference does not match the bundled notices')
    material_id = reference.get('material_id', '')
    if not re.fullmatch('[0-9a-f]{64}', material_id):
        raise ValueError('Invalid relink material identity')
    if reference.get('url') != hosted_url(material_id):
        raise ValueError('Unexpected source/relink download destination')
    for field in ['sha256', 'manifest_sha256']:
        if not re.fullmatch('[0-9a-f]{64}', reference.get(field, '')):
            raise ValueError('Invalid hosted material checksum')
    if not isinstance(reference.get('bytes'), int) or reference['bytes'] <= 0:
        raise ValueError('Invalid hosted material size')


def verify_published(reference):
    tag = 'ios-relink-' + reference['material_id']
    request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + '/releases/tags/' + tag,
                                     headers={'User-Agent': 'RID2Caltopo-license-verification'})
    with urllib.request.urlopen(request, timeout=30) as response:
        release = json.load(response)
    if release.get('draft') or release.get('tag_name') != tag:
        raise ValueError('Matching materials have not been publicly published')
    assets = {asset['name']: asset for asset in release.get('assets', [])}
    for name, expected in [(KIT_NAME, reference['sha256']), ('manifest.json', reference['manifest_sha256'])]:
        asset = assets.get(name, {})
        if asset.get('digest') != 'sha256:' + expected:
            raise ValueError('Published artifact is missing or has a different checksum: ' + name)
    if assets[KIT_NAME].get('browser_download_url') != reference['url']:
        raise ValueError('Published download URL differs from the app reference')
    print('Public GitHub materials verified:', release['html_url'])


def verify_app(app, require_published=False):
    info = plistlib.loads((app / 'Info.plist').read_bytes())
    app_id = macho_uuid((app / info['CFBundleExecutable']).read_bytes())
    legal_id = digest((app / 'legal/legal-manifest.json').read_bytes())
    framework_id = macho_uuid((app / 'Frameworks/WebRTC.framework/WebRTC').read_bytes())
    path = app / 'legal' / REFERENCE_NAME
    if path.exists():
        reference = json.loads(path.read_text())
        validate_reference(reference, app_id, legal_id, framework_id)
        if (app / 'legal' / KIT_NAME).exists():
            raise ValueError('Hosted app still contains the large relink ZIP')
        offer = app / 'legal/third_party/licenses/SOURCE_AND_RELINKING_OFFER.txt'
        if not offer.is_file() or 'at least three years' not in offer.read_text():
            raise ValueError('Hosted materials require the bundled written offer')
        if require_published:
            verify_published(reference)
        print('Hosted relink reference verified:', reference['material_id'])
        return reference
    # Older already-distributed builds may use the original accompanying-ZIP route.
    with zipfile.ZipFile(app / 'legal' / KIT_NAME) as archive:
        manifest = validate_kit(archive, app_id, legal_id, framework_id)
    print('Bundled relink materials verified:', len(manifest['objects']), 'app objects.')
    return None


def source_files(root):
    for path in sorted(root.rglob('*')):
        if path.is_file() and not any(part.startswith('.') for part in path.relative_to(root).parts):
            yield path


def bridge_sources(root):
    pending = [p for p in (root / 'apple/Native/FFmpeg').iterdir()
               if p.is_file() and p.suffix in ['.c', '.h']]
    seen = set()
    while pending:
        path = pending.pop().resolve()
        if path in seen:
            continue
        seen.add(path)
        if not path.is_relative_to(root.resolve()):
            raise ValueError('Bridge include leaves the application source root')
        yield path
        for include in re.findall(r'^\s*#\s*include\s*"([^"]+)"', path.read_text(), re.M):
            candidate = path.parent / include
            if candidate.is_file():
                pending.append(candidate)


def prepare(args):
    app, products, listing = args.app.resolve(), args.products.resolve(), args.link_file_list.resolve()
    info = plistlib.loads((app / 'Info.plist').read_bytes())
    executable = app / info['CFBundleExecutable']
    if args.platform != 'iphoneos' or args.arch != 'arm64':
        raise ValueError('Relink packaging supports the arm64 iOS device Release configuration')
    expected_inputs = json.loads((ROOT / 'tools/licenses/relink-inputs.json').read_text())
    native = json.loads((ROOT / 'tools/licenses/native-runtime-inputs.json').read_text())
    for name, record in native['libraries'].items():
        if digest((products / name).read_bytes()) != record['sha256']:
            raise ValueError('Native runtime changed without dependency review: ' + name)
    config = (ROOT / 'apple/Build/FFmpeg/device/build/config.h').read_text()
    for flag in ['GPL', 'NONFREE', 'VERSION3']:
        if not re.search(r'^#define CONFIG_' + flag + r' 0$', config, re.M):
            raise ValueError('FFmpeg license configuration requires a new review: ' + flag)
    configuration = re.search(r'^#define FFMPEG_CONFIGURATION "(.*)"$', config, re.M)
    if not configuration or configuration.group(1).encode() not in (products / 'libR2CFFmpegMobile.a').read_bytes():
        raise ValueError('FFmpeg configuration does not match its library')
    webrtc = json.loads((ROOT / 'third_party/licenses/WebRTC-build-provenance.json').read_text())
    framework_digest = digest((products / 'WebRTC.framework/WebRTC').read_bytes())
    if framework_digest not in {value['sha256'] for value in webrtc['slices'].values()}:
        raise ValueError('WebRTC framework differs from the notice-generating source build')
    ffmpeg = ROOT / 'apple/Build/FFmpeg/source'
    eigen = ROOT / 'apple/Build/Mapping/dependencies/openmvg/src/third_party/eigen'
    for name, source in [('ffmpeg', ffmpeg), ('eigen', eigen)]:
        files = {p.relative_to(source).as_posix(): digest(p.read_bytes()) for p in source_files(source)}
        if files != expected_inputs[name]['files']:
            raise ValueError(name + ' source differs from reviewed relink inputs')
    framework = products / 'WebRTC.framework'
    payload, objects = {}, []
    def put(path, name):
        payload[name] = path
    for index, line in enumerate(listing.read_text().splitlines()):
        path = Path(line)
        if not path.is_file() or path.suffix != '.o':
            raise ValueError('Missing or unexpected app object: ' + str(path))
        name = f'objects/{index:04d}-' + path.name
        put(path, name)
        objects.append(name)
    if not objects:
        raise ValueError('Empty app link object list')
    for name in LIBRARIES:
        put(products / name, 'libraries/' + name)
    for path in source_files(framework):
        if '_CodeSignature' not in path.parts:
            put(path, 'frameworks/WebRTC.framework/' + path.relative_to(framework).as_posix())
    for name, source in [('ffmpeg', ffmpeg), ('eigen', eigen)]:
        for path in source_files(source):
            put(path, 'source/' + name + '/' + path.relative_to(source).as_posix())
    for path in bridge_sources(ROOT):
        put(path, 'source/application/' + path.relative_to(ROOT.resolve()).as_posix())
    for name in ['config.h', 'ffbuild/config.mak']:
        put(ROOT / 'apple/Build/FFmpeg/device/build' / name, 'build-evidence/ffmpeg/' + name)
    for name in ['relink_apple.py', 'rebuild_ffmpeg.py']:
        put(ROOT / 'tools/licenses' / name, name)
    put(ROOT / 'tools/licenses/relink-README.txt', 'README.txt')
    put(ROOT / 'tools/licenses/native-runtime-inputs.json', 'build-evidence/native-runtime-inputs.json')
    put(ROOT / 'third_party/licenses/RELINKING_PERMISSIONS.txt', 'RELINKING_PERMISSIONS.txt')
    put(app / 'legal/LICENSES.txt', 'LICENSES.txt')
    hashes = {name: digest(path.read_bytes()) for name, path in sorted(payload.items())}
    identity = {'target': 'arm64-apple-ios17.0', 'application_uuid': macho_uuid(executable.read_bytes()),
                'webrtc_uuid': macho_uuid((framework / 'WebRTC').read_bytes()),
                'legal_manifest_sha256': digest((app / 'legal/legal-manifest.json').read_bytes()),
                'files': hashes, 'format': 2}
    material_id = digest(json.dumps(identity, sort_keys=True, separators=(',', ':')).encode())
    cache = CACHE / material_id
    output = cache / KIT_NAME
    cache.mkdir(parents=True, exist_ok=True)
    manifest_path = cache / 'manifest.json'
    reused = output.is_file() and manifest_path.is_file()
    if reused:
        with zipfile.ZipFile(output) as archive:
            manifest = validate_kit(archive, identity['application_uuid'], identity['legal_manifest_sha256'], identity['webrtc_uuid'])
            if manifest['files'] != hashes or archive.read('manifest.json') != manifest_path.read_bytes():
                raise ValueError('Cached relink materials differ from their input identity')
    else:
        manifest = {'version': 1, 'application_version': info['CFBundleShortVersionString'],
                    'application_build': info['CFBundleVersion'], 'material_id': material_id,
                    'application_uuid': identity['application_uuid'], 'webrtc_uuid': identity['webrtc_uuid'],
                    'legal_manifest_sha256': identity['legal_manifest_sha256'], 'target': identity['target'],
                    'objects': objects,
                    'source_provenance': {key: {k: v for k, v in value.items() if k != 'files'} for key, value in expected_inputs.items()},
                    'files': hashes}
        manifest_data = (json.dumps(manifest, indent=2) + '\n').encode()
        descriptor, temporary = tempfile.mkstemp(prefix='.relink-', suffix='.zip', dir=cache)
        os.close(descriptor)
        try:
            with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
                for name, path in sorted(payload.items()):
                    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                    entry.create_system = 3
                    entry.external_attr = (0o100755 if os.access(path, os.X_OK) else 0o100644) << 16
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(entry, path.read_bytes())
                entry = zipfile.ZipInfo('manifest.json', date_time=(1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, manifest_data)
            os.chmod(temporary, 0o644)
            os.replace(temporary, output)
            manifest_path.write_bytes(manifest_data)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    reference = {'version': 2, 'distribution': 'hosted-written-offer', 'material_id': material_id,
                 'application_version': info['CFBundleShortVersionString'], 'application_build': info['CFBundleVersion'],
                 'application_uuid': identity['application_uuid'], 'webrtc_uuid': identity['webrtc_uuid'],
                 'legal_manifest_sha256': identity['legal_manifest_sha256'], 'url': hosted_url(material_id),
                 'sha256': digest(output.read_bytes()), 'manifest_sha256': digest(manifest_path.read_bytes()),
                 'bytes': output.stat().st_size}
    reference_data = json.dumps(reference, indent=2) + '\n'
    (cache / 'reference.json').write_text(reference_data)
    (cache / (KIT_NAME + '.sha256')).write_text(reference['sha256'] + '  ' + KIT_NAME + '\n')
    (app / 'legal' / REFERENCE_NAME).write_text(reference_data)
    (app / 'legal' / KIT_NAME).unlink(missing_ok=True)
    verify_app(app)
    print(('Reused cached' if reused else 'Prepared new') + ' relink materials: ' + str(output))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-app', type=Path)
    parser.add_argument('--app', type=Path)
    parser.add_argument('--require-published', action='store_true')
    parser.add_argument('--products', type=Path)
    parser.add_argument('--link-file-list', type=Path)
    parser.add_argument('--platform', default='iphoneos')
    parser.add_argument('--arch', default='arm64')
    args = parser.parse_args()
    try:
        if args.verify_app:
            verify_app(args.verify_app, args.require_published)
        elif args.app and args.products and args.link_file_list:
            prepare(args)
        else:
            parser.error('Use --verify-app or supply --app, --products and --link-file-list')
        return 0
    except (OSError, ValueError, KeyError, struct.error, zipfile.BadZipFile) as error:
        print('Release relink verification failed:', error, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
