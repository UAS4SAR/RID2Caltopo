import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('prepare_legal', Path(__file__).with_name('prepare_legal.py'))
legal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legal)


class LegalPackagingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.wanted = legal.expected(legal.ROOT)

    def test_full_text_includes_missing_licenses_and_component_copyrights(self):
        text = self.wanted['LICENSES.txt'].decode()
        for term in ['GNU LESSER GENERAL PUBLIC LICENSE', 'The WebRTC project authors',
                     'Independent JPEG Group', 'Dailymotion', 'GeographicLib', 'Mozilla Public License']:
            self.assertIn(term, text)

    def test_missing_ffmpeg_text_is_rejected_even_if_manifest_is_present(self):
        actual = dict(self.wanted)
        actual.pop('third_party/licenses/FFmpeg-LGPL-2.1.txt')
        with self.assertRaises(ValueError):
            legal.verify(actual, self.wanted)

    def test_edited_notice_is_rejected(self):
        actual = dict(self.wanted)
        actual['NOTICE'] = b'old notice'
        with self.assertRaises(ValueError):
            legal.verify(actual, self.wanted)

    def test_apk_and_ipa_resources_are_verified_in_the_packaged_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            for prefix, extension in [('assets/legal/', 'apk'), ('Payload/App.app/legal/', 'ipa')]:
                path = Path(temporary) / ('app.' + extension)
                with zipfile.ZipFile(path, 'w') as archive:
                    for name, data in self.wanted.items():
                        archive.writestr(prefix + name, data)
                legal.verify(legal.read_artifact(path), self.wanted)

    def test_old_bundle_without_full_notices_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            app = Path(temporary) / 'Old.app'
            (app / 'legal').mkdir(parents=True)
            (app / 'legal/LICENSE').write_bytes(self.wanted['LICENSE'])
            with self.assertRaises(ValueError):
                legal.verify(legal.read_artifact(app), self.wanted)

    def test_source_notice_edit_requires_regeneration(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ['LICENSE', 'NOTICE', 'DISCLAIMER.md', 'THIRD_PARTY_NOTICES.md']:
                shutil.copy2(legal.ROOT / name, root / name)
            shutil.copytree(legal.ROOT / 'third_party/licenses', root / 'third_party/licenses')
            (root / 'NOTICE').write_text('changed notice')
            with self.assertRaises(ValueError):
                legal.verify(self.wanted, legal.expected(root))


if __name__ == '__main__':
    unittest.main()
