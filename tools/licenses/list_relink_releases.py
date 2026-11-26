#!/usr/bin/env python3
"""List ios-relink GitHub releases and which ones could eventually be pruned.

This helper is read-only: it never deletes or edits a release. A release is
"live" while any non-expired TestFlight build or any App Store build refers
to it. Otherwise it must still be kept for three years after the last
distribution of any referring build (the written-offer promise), and is only
reported as eligible for deletion after that date. Releases whose referring
build cannot be found are kept for three years from publication.

Referring builds come from the app's own relink reference when a local
archive or IPA for the build is available (--evidence), otherwise from the
"initial referring app build" line of the release notes.
"""
import argparse
import base64
import datetime as dt
import json
import os
import plistlib
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = 'UAS4SAR/RID2Caltopo'
APP_ID = '6792518823'
DEFAULT_ISSUER = 'c827c1d7-0eee-4d7e-bcae-c27accb00e12'
RETENTION_YEARS = 3
PREFIX = 'ios-relink-'
LIVE_STORE_STATES = {'READY_FOR_SALE', 'READY_FOR_DISTRIBUTION', 'PROCESSING_FOR_DISTRIBUTION',
                     'PENDING_DEVELOPER_RELEASE', 'PENDING_APPLE_RELEASE', 'IN_REVIEW',
                     'WAITING_FOR_REVIEW', 'ACCEPTED', 'PREORDER_READY_FOR_SALE'}
FORMER_STORE_STATES = {'REPLACED_WITH_NEW_VERSION', 'REMOVED_FROM_SALE', 'DEVELOPER_REMOVED_FROM_SALE'}


def parse_time(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00')) if value else None


def add_years(moment, years):
    try:
        return moment.replace(year=moment.year + years)
    except ValueError:  # 29 February
        return moment.replace(year=moment.year + years, day=28)


# --- App Store Connect (read-only GET requests) ---------------------------

def _b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=')


def _der_to_raw(signature):
    index = 2 if signature[1] < 0x80 else 3
    length = signature[index + 1]
    r = signature[index + 2:index + 2 + length]
    index += 2 + length
    length = signature[index + 1]
    s = signature[index + 2:index + 2 + length]
    return r.lstrip(b'\0').rjust(32, b'\0') + s.lstrip(b'\0').rjust(32, b'\0')


class AppStoreConnect:
    def __init__(self, key_id, issuer):
        self.key_id, self.issuer = key_id, issuer
        self.key = Path.home() / '.appstoreconnect/private_keys' / f'AuthKey_{key_id}.p8'
        if not self.key.is_file():
            raise SystemExit(f'App Store Connect key AuthKey_{key_id}.p8 not found')

    def token(self):
        now = int(time.time())
        header = _b64(json.dumps({'alg': 'ES256', 'kid': self.key_id, 'typ': 'JWT'}).encode())
        payload = _b64(json.dumps({'iss': self.issuer, 'iat': now, 'exp': now + 600,
                                   'aud': 'appstoreconnect-v1'}).encode())
        signing_input = header + b'.' + payload
        signature = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(self.key)],
                                   input=signing_input, capture_output=True, check=True).stdout
        return (signing_input + b'.' + _b64(_der_to_raw(signature))).decode()

    def get_all(self, path, params):
        url = 'https://api.appstoreconnect.apple.com' + path + '?' + urllib.parse.urlencode(params)
        data, included = [], []
        while url:
            request = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + self.token()})
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    page = json.load(response)
            except urllib.error.HTTPError as error:
                detail = json.loads(error.read() or b'{}').get('errors', [{}])[0].get('detail', '')
                raise SystemExit(f'App Store Connect {error.code} for {path}: {detail}')
            data += page.get('data', [])
            included += page.get('included', [])
            url = page.get('links', {}).get('next')
        return data, included


def default_key_id():
    keys = sorted((Path.home() / '.appstoreconnect/private_keys').glob('AuthKey_*.p8'))
    return keys[0].stem.removeprefix('AuthKey_') if len(keys) == 1 else None


def store_builds(asc):
    """Return {build number: {...}} for every build App Store Connect still lists."""
    builds, included = asc.get_all('/v1/builds', {
        'filter[app]': APP_ID, 'limit': 200, 'include': 'preReleaseVersion',
        'fields[builds]': 'version,uploadedDate,expirationDate,expired,processingState,preReleaseVersion',
        'fields[preReleaseVersions]': 'version'})
    versions = {item['id']: item['attributes']['version'] for item in included
                if item['type'] == 'preReleaseVersions'}
    result = {}
    for build in builds:
        attributes = build['attributes']
        relation = (build.get('relationships', {}).get('preReleaseVersion', {}).get('data') or {})
        result[build['id']] = {
            'number': attributes['version'], 'version': versions.get(relation.get('id'), '?'),
            'uploaded': parse_time(attributes.get('uploadedDate')),
            'expires': parse_time(attributes.get('expirationDate')),
            'expired': bool(attributes.get('expired')), 'state': attributes.get('processingState'),
            'store_states': []}
    store_versions, included = asc.get_all(f'/v1/apps/{APP_ID}/appStoreVersions', {
        'limit': 200, 'include': 'build', 'fields[appStoreVersions]': 'versionString,appStoreState,build'})
    for version in store_versions:
        relation = (version.get('relationships', {}).get('build', {}).get('data') or {})
        if relation.get('id') in result:
            result[relation['id']]['store_states'].append(version['attributes'].get('appStoreState'))
    by_number = {}
    for build in result.values():
        by_number.setdefault(build['number'], []).append(build)
    return by_number


# --- GitHub (read-only) ----------------------------------------------------

def releases():
    pages = json.loads(subprocess.run(
        ['gh', 'api', '--paginate', '--slurp', f'repos/{REPOSITORY}/releases?per_page=100'],
        capture_output=True, text=True, check=True).stdout)
    for page in pages:
        for release in page:
            if release['tag_name'].startswith(PREFIX):
                yield release


def notes_build(body):
    match = re.search(r'initial referring app build is\s+(\S+)\s+\((\d+(?:\.\d+)*)\)', body or '', re.S)
    return (match.group(1), match.group(2)) if match else None


# --- Local evidence: the app's own relink reference ---------------------------

def evidence(paths):
    """Map material id -> {(version, build)} from local archives and IPAs."""
    found = {}

    def record(info, reference):
        material = json.loads(reference).get('material_id')
        info = plistlib.loads(info)
        if material:
            found.setdefault(material, set()).add(
                (info.get('CFBundleShortVersionString', '?'), str(info.get('CFBundleVersion', '?'))))

    for base in paths:
        base = Path(base).expanduser()
        if not base.exists():
            continue
        for reference in base.rglob('relink-materials.json'):
            app = reference.parent.parent
            if reference.parent.name == 'legal' and app.suffix == '.app' and (app / 'Info.plist').is_file():
                record((app / 'Info.plist').read_bytes(), reference.read_text())
        for ipa in base.rglob('*.ipa'):
            try:
                with zipfile.ZipFile(ipa) as archive:
                    for name in archive.namelist():
                        if re.fullmatch(r'Payload/[^/]+\.app/legal/relink-materials\.json', name):
                            app = name.rsplit('/legal/', 1)[0]
                            record(archive.read(app + '/Info.plist'), archive.read(name))
            except (zipfile.BadZipFile, KeyError, OSError):
                continue
    return found


def classify(release, referring, builds, today, unresolved_numbers):
    published = parse_time(release.get('published_at') or release.get('created_at'))
    live, last, notes = [], None, []
    for version, number in sorted(referring):
        matches = builds.get(number, [])
        if not matches:
            notes.append(f'{version} ({number}) not in App Store Connect')
            continue
        for build in matches:
            states = set(build['store_states'])
            if states & LIVE_STORE_STATES:
                live.append(f'{version} ({number}) App Store')
            elif not build['expired'] and build['state'] == 'VALID':
                live.append(f'{version} ({number}) TestFlight until {build["expires"]:%Y-%m-%d}')
            elif states & FORMER_STORE_STATES:
                # The replacement date is not exposed; count it as distributed until today.
                last = max(filter(None, [last, today]))
                notes.append(f'{version} ({number}) former App Store build')
            else:
                end = build['expires'] or build['uploaded'] or published
                last = max(filter(None, [last, end]))
                notes.append(f'{version} ({number}) TestFlight expired {end:%Y-%m-%d}')
    if number_ambiguity := sorted(n for _, n in referring if n in unresolved_numbers):
        notes.append('build ' + ', '.join(number_ambiguity) + ' is claimed by several releases (no local evidence)')
    if live:
        return 'live', None, live + notes
    start = last or published
    keep_until = add_years(start, RETENTION_YEARS)
    status = 'eligible-for-deletion' if keep_until < today else 'retained-3-year-rule'
    return status, keep_until, notes


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--evidence', action='append', default=[],
                        help='folder to search for archived apps or IPAs (repeatable; default apple/Build of this checkout and its sibling worktrees)')
    parser.add_argument('--api-key-id', default=os.environ.get('R2C_APPSTORE_API_KEY_ID') or default_key_id())
    parser.add_argument('--api-issuer', default=os.environ.get('R2C_APPSTORE_API_ISSUER', DEFAULT_ISSUER))
    parser.add_argument('--json', action='store_true', help='print machine-readable output')
    args = parser.parse_args()
    if not args.api_key_id:
        parser.error('pass --api-key-id or set R2C_APPSTORE_API_KEY_ID')
    today = dt.datetime.now(dt.timezone.utc)
    builds = store_builds(AppStoreConnect(args.api_key_id, args.api_issuer))
    default_evidence = [ROOT / 'apple/Build'] + sorted(
        path / 'apple/Build' for path in ROOT.parent.glob(ROOT.name + '-*') if path.is_dir())
    local = evidence(args.evidence or default_evidence)
    proven_numbers = {number for refs in local.values() for _, number in refs}
    all_releases = list(releases())
    claimed = {}
    for release in all_releases:
        claim = notes_build(release.get('body'))
        if claim:
            claimed.setdefault(claim[1], []).append(release['tag_name'])
    unresolved = {n for n, tags in claimed.items() if len(tags) > 1 and n not in proven_numbers}
    rows = []
    for release in all_releases:
        material = release['tag_name'][len(PREFIX):]
        referring = set(local.get(material, set()))
        source = 'app evidence' if referring else 'release notes'
        claim = notes_build(release.get('body'))
        if not referring and claim and claim[1] not in proven_numbers:
            referring = {claim}
        status, keep_until, notes = classify(release, referring, builds, today, unresolved)
        if not referring and claim:
            source = 'release notes'
            notes.append(f'notes name {claim[0]} ({claim[1]}), but local evidence shows that build '
                         'shipped other materials; this one was likely never uploaded')
        size = sum(asset.get('size', 0) for asset in release.get('assets', []))
        rows.append({'tag': release['tag_name'], 'published': release.get('published_at'),
                     'draft': release.get('draft'), 'bytes': size, 'status': status,
                     'keep_until': keep_until.date().isoformat() if keep_until else None,
                     'referring_source': source, 'notes': notes})
    rows.sort(key=lambda row: (row['status'], row['published'] or ''))
    if args.json:
        print(json.dumps(rows, indent=2))
        return
    totals = {}
    for row in rows:
        totals.setdefault(row['status'], [0, 0])
        totals[row['status']][0] += 1
        totals[row['status']][1] += row['bytes']
        print(f"{row['status']:22} {row['tag'][len(PREFIX):][:12]}  {row['published'][:10] if row['published'] else '-':10}  "
              f"{row['bytes'] / 1e6:7.1f} MB  keep until {row['keep_until'] or 'live'}  [{row['referring_source']}] "
              + '; '.join(row['notes']))
    print()
    for status, (count, size) in sorted(totals.items()):
        print(f'{status}: {count} releases, {size / 1e6:.1f} MB')
    print('List only: nothing was deleted. Delete a release only after it is eligible and Ken approves.')


if __name__ == '__main__':
    sys.exit(main())
