import unittest
from unittest import mock

import native_cache


class NativeCacheTests(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(native_cache, 'toolchain', return_value={'xcode': 'fixed'})
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_fingerprint_is_stable(self):
        for name in native_cache.LIBS:
            self.assertEqual(native_cache.fingerprint(name), native_cache.fingerprint(name))

    def test_fingerprint_covers_sources_and_scripts(self):
        inputs = native_cache.fingerprint_inputs('AnomalyCore')['files']
        self.assertIn('apple/Native/reproducible-build.sh', inputs)
        self.assertIn('apple/Native/AnomalyCore/build-xcframework.sh', inputs)
        self.assertIn('app/src/main/cpp/anomaly_detector.c', inputs)
        self.assertIn('apple/Native/AnomalyCore/r2c_anomaly_apple.c', inputs)
        mapping = native_cache.fingerprint_inputs('Mapping')
        self.assertTrue(any(path.startswith('native/mapping/') for path in mapping['files']))
        self.assertIn('relink_eigen', mapping['extra'])
        self.assertIn('relink_ffmpeg', native_cache.fingerprint_inputs('FFmpeg')['extra'])

    def test_source_change_changes_fingerprint(self):
        inputs = native_cache.fingerprint_inputs('AnomalyCore')
        changed = dict(inputs, files=dict(inputs['files'], **{'app/src/main/cpp/anomaly_detector.c': '0' * 64}))
        self.assertNotEqual(native_cache.fingerprint('AnomalyCore', inputs),
                            native_cache.fingerprint('AnomalyCore', changed))

    def test_toolchain_change_changes_fingerprint(self):
        inputs = native_cache.fingerprint_inputs('MediaMTX')
        changed = dict(inputs, toolchain={'xcode': 'other'})
        self.assertNotEqual(native_cache.fingerprint('MediaMTX', inputs),
                            native_cache.fingerprint('MediaMTX', changed))


if __name__ == '__main__':
    unittest.main()
