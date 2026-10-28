import io
import json
import struct
import unittest
import uuid
import zipfile

from prepare_apple_relink import KIT_NAME, LIBRARIES, digest, macho_uuid, validate_kit


class RelinkKitTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = str(uuid.uuid4())
        cls.framework = str(uuid.uuid4())
        cls.files = {'source/ffmpeg/f' + str(n): b'source' for n in range(8_000)}
        cls.files.update({name: b'input' for name in [
            'relink_apple.py', 'rebuild_ffmpeg.py', 'README.txt',
            'RELINKING_PERMISSIONS.txt', 'LICENSES.txt', 'objects/app.o',
            'source/ffmpeg/configure', 'source/ffmpeg/COPYING.LGPLv2.1',
            'source/application/apple/Native/FFmpeg/R2CFFmpegMobile.c',
            'source/application/app/src/main/cpp/anomaly_runtime_budget.h', 'source/eigen/COPYING.MPL2',
            'source/eigen/Eigen/Core', 'frameworks/WebRTC.framework/WebRTC']})
        cls.files.update({'libraries/' + name: b'library' for name in LIBRARIES})

    def archive(self, files=None, manifest_changes=None, changed_data=None):
        files = dict(self.files if files is None else files)
        manifest = {'version': 1, 'application_uuid': self.app,
                    'webrtc_uuid': self.framework, 'legal_manifest_sha256': 'legal',
                    'objects': ['objects/app.o'],
                    'files': {name: digest(data) for name, data in files.items()}}
        manifest.update(manifest_changes or {})
        files.update(changed_data or {})
        memory = io.BytesIO()
        with zipfile.ZipFile(memory, 'w') as archive:
            for name, data in files.items():
                archive.writestr(name, data)
            archive.writestr('manifest.json', json.dumps(manifest))
        return zipfile.ZipFile(io.BytesIO(memory.getvalue()))

    def check(self, archive):
        return validate_kit(archive, self.app, 'legal', self.framework)

    def test_complete_matching_kit_is_accepted(self):
        with self.archive() as archive:
            self.assertEqual(self.check(archive)['objects'], ['objects/app.o'])

    def test_changed_object_is_rejected(self):
        with self.archive(changed_data={'objects/app.o': b'changed'}) as archive:
            with self.assertRaisesRegex(ValueError, 'changed relink material'):
                self.check(archive)

    def test_other_app_build_is_rejected(self):
        with self.archive(manifest_changes={'application_uuid': str(uuid.uuid4())}) as archive:
            with self.assertRaisesRegex(ValueError, 'this app executable'):
                self.check(archive)

    def test_missing_link_library_cannot_be_hidden_by_manifest(self):
        files = dict(self.files)
        del files['libraries/libmediamtx_mobile.a']
        with self.archive(files=files) as archive:
            with self.assertRaisesRegex(ValueError, 'required source, objects or libraries'):
                self.check(archive)

    def test_incomplete_ffmpeg_source_is_rejected(self):
        files = {name: data for name, data in self.files.items()
                 if not name.startswith('source/ffmpeg/f')}
        with self.archive(files=files) as archive:
            with self.assertRaisesRegex(ValueError, 'source collection is incomplete'):
                self.check(archive)

    def test_other_framework_build_is_rejected(self):
        with self.archive(manifest_changes={'webrtc_uuid': str(uuid.uuid4())}) as archive:
            with self.assertRaisesRegex(ValueError, 'this app framework'):
                self.check(archive)

    def test_uuid_reads_through_code_signature_command(self):
        identifier = uuid.uuid4()
        header = struct.pack('<IIIIIIII', 0xFEEDFACF, 0, 0, 0, 2, 40, 0, 0)
        signature = struct.pack('<IIII', 0x1D, 16, 0, 0)
        command = struct.pack('<II', 0x1B, 24) + identifier.bytes
        self.assertEqual(macho_uuid(header + signature + command), str(identifier))

    def test_framework_universal_arm64_slice_is_recognized(self):
        identifier = uuid.uuid4()
        thin = struct.pack('<IIIIIIII', 0xFEEDFACF, 0, 0, 0, 1, 24, 0, 0)
        thin += struct.pack('<II', 0x1B, 24) + identifier.bytes
        fat = struct.pack('>IIIIIII', 0xCAFEBABE, 1, 0x0100000C, 0, 28, len(thin), 0)
        self.assertEqual(macho_uuid(fat + thin), str(identifier))


if __name__ == '__main__':
    unittest.main()
