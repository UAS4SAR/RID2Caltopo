#!/usr/bin/env python3
"""Publish a prepared immutable-by-name compliance artifact to public GitHub.

Requires an authenticated gh CLI with release-write permission. Existing
matching releases are verified and reused; existing assets are never replaced.
The accompanying written offer requires retention for at least three years
after the last distribution of any build referring to these materials.
"""
from pathlib import Path
import argparse
import json
import subprocess
import tempfile
import zipfile

from prepare_apple_relink import CACHE, KIT_NAME, REFERENCE_NAME, REPOSITORY, digest, verify_app, verify_published


def gh_json(arguments):
    return json.loads(subprocess.check_output(['gh', *arguments], text=True))


def find_release(tag):
    lookup = subprocess.run(['gh', 'api', 'repos/' + REPOSITORY + '/releases/tags/' + tag],
                            capture_output=True, text=True)
    if lookup.returncode == 0:
        return json.loads(lookup.stdout)
    if '404' not in lookup.stderr:
        raise SystemExit(lookup.stderr)
    # GitHub's by-tag endpoint omits drafts. Authenticated release listings
    # include them, so an interrupted draft upload must resume from that list.
    pages = gh_json(['api', '--paginate', '--slurp',
                     'repos/' + REPOSITORY + '/releases?per_page=100'])
    matches = [release for page in pages for release in page if release['tag_name'] == tag]
    if len(matches) > 1:
        raise SystemExit('Multiple releases use the material tag; resolve before publishing')
    return matches[0] if matches else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--target', required=True, help='existing remote commit for the compliance tag')
    args = parser.parse_args()
    reference = verify_app(args.app)
    if not reference:
        raise SystemExit('App still uses a bundled ZIP; prepare the hosted reference first')
    repository = gh_json(['api', 'repos/' + REPOSITORY])
    if repository.get('visibility') != 'public':
        raise SystemExit('Compliance downloads require a public repository')
    material_id = reference['material_id']
    tag = 'ios-relink-' + material_id
    directory = CACHE / material_id
    archive = directory / KIT_NAME
    manifest_path = directory / 'manifest.json'
    if digest(archive.read_bytes()) != reference['sha256'] or digest(manifest_path.read_bytes()) != reference['manifest_sha256']:
        raise SystemExit('Prepared publication inputs do not match the app reference')
    with zipfile.ZipFile(archive) as source:
        if source.read('manifest.json') != manifest_path.read_bytes():
            raise SystemExit('Manifest sidecar differs from the archive')
    release = find_release(tag)
    notes = f'''Source and relinking materials for RID2Caltopo Apple app code

This is a compliance-materials release, not an application installer. It does
not change the repository's latest application release.

The matching application UUID is `{reference['application_uuid']}` and the
WebRTC UUID is `{reference['webrtc_uuid']}`. The initial referring app build is
{reference['application_version']} ({reference['application_build']}). Builds
with identical material inputs may reuse this release.

Download **{KIT_NAME}** for the complete FFmpeg source, application objects,
non-system link inputs, Eigen source, licenses and build/relink scripts.
`manifest.json` identifies every file and its hash. The ZIP SHA-256 is
`{reference['sha256']}`. Downloads are public and require no account.

The automatically generated GitHub source-code archives describe the tag's
repository commit; the attached relinking ZIP is the authoritative complete
material set for the application identified above.

These materials fulfill the written offer accompanying the app under LGPL
2.1 section 6(c). Keep this release and its assets available for at least
three years after the last distribution of any referring app build. Never
replace these assets with materials from another build. If a download is
unavailable, contact kjt@uas4sar.com to request a free copy.

Material identity: `{material_id}`
'''
    with tempfile.TemporaryDirectory(prefix='r2c-release-notes-') as temporary:
        body = Path(temporary) / 'notes.md'
        body.write_text(notes)
        if release is None:
            subprocess.run(['gh', 'release', 'create', tag, '--repo', REPOSITORY,
                            '--target', args.target, '--draft', '--latest=false',
                            '--title', 'iOS source and relinking materials ' + material_id[:12],
                            '--notes-file', str(body)], check=True)
            release = find_release(tag)
            if release is None:
                raise SystemExit('New draft was not returned by GitHub; retry to resume it')
        existing = {item['name']: item for item in release.get('assets', [])}
        files = [archive, manifest_path, directory / (KIT_NAME + '.sha256')]
        for path in files:
            asset = existing.get(path.name)
            if asset:
                if asset.get('digest') != 'sha256:' + digest(path.read_bytes()):
                    raise SystemExit('Existing GitHub asset differs; refusing to replace: ' + path.name)
            elif release.get('draft'):
                subprocess.run(['gh', 'release', 'upload', tag, str(path), '--repo', REPOSITORY], check=True)
            else:
                raise SystemExit('Published release is incomplete; refusing to modify it')
        if release.get('draft'):
            subprocess.run(['gh', 'release', 'edit', tag, '--repo', REPOSITORY,
                            '--draft=false', '--latest=false'], check=True)
    verify_published(reference)
    (directory / 'publication.json').write_text(json.dumps({
        'material_id': material_id, 'url': reference['url'], 'sha256': reference['sha256'],
        'retention': 'At least three years after last distribution of any referring app build',
        'public_download_verified': True}, indent=2) + '\n')
    print('Published/reused:', 'https://github.com/' + REPOSITORY + '/releases/tag/' + tag)


if __name__ == '__main__':
    main()
