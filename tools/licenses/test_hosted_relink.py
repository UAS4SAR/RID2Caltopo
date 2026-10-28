import io
import json
import unittest
from unittest.mock import patch

from prepare_apple_relink import KIT_NAME, hosted_url, validate_reference, verify_published


class HostedMaterialsTests(unittest.TestCase):
    def reference(self):
        material_id = 'a' * 64
        return {'version': 2, 'distribution': 'hosted-written-offer', 'material_id': material_id,
                'application_uuid': 'app', 'webrtc_uuid': 'framework',
                'legal_manifest_sha256': 'legal', 'url': hosted_url(material_id),
                'sha256': 'b' * 64, 'manifest_sha256': 'c' * 64, 'bytes': 75_000_000}

    def test_build_matched_hosted_reference(self):
        validate_reference(self.reference(), 'app', 'legal', 'framework')

    def test_foreign_download_host_is_rejected(self):
        reference = self.reference()
        reference['url'] = 'https://example.com/other.zip'
        with self.assertRaisesRegex(ValueError, 'destination'):
            validate_reference(reference, 'app', 'legal', 'framework')

    def test_stale_app_reference_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'this app/framework'):
            validate_reference(self.reference(), 'other-app', 'legal', 'framework')

    def test_missing_checksum_is_rejected(self):
        reference = self.reference()
        del reference['sha256']
        with self.assertRaisesRegex(ValueError, 'checksum'):
            validate_reference(reference, 'app', 'legal', 'framework')

    def release(self, draft=False, zip_digest=None):
        reference = self.reference()
        return {'draft': draft, 'tag_name': 'ios-relink-' + reference['material_id'],
                'html_url': 'https://github.com/UAS4SAR/RID2Caltopo/releases/tag/example',
                'assets': [{'name': KIT_NAME, 'digest': zip_digest or 'sha256:' + reference['sha256'],
                            'browser_download_url': reference['url']},
                           {'name': 'manifest.json', 'digest': 'sha256:' + reference['manifest_sha256']}]}

    def test_public_matching_assets_are_accepted(self):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(json.dumps(self.release()).encode())):
            verify_published(self.reference())

    def test_draft_assets_are_not_distribution_ready(self):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(json.dumps(self.release(draft=True)).encode())):
            with self.assertRaisesRegex(ValueError, 'publicly published'):
                verify_published(self.reference())

    def test_replaced_remote_archive_is_rejected(self):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(json.dumps(self.release(zip_digest='sha256:' + 'd' * 64)).encode())):
            with self.assertRaisesRegex(ValueError, 'different checksum'):
                verify_published(self.reference())


if __name__ == '__main__':
    unittest.main()
