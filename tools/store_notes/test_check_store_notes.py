import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path

import check_store_notes


class ScanTextTest(unittest.TestCase):
    def terms(self, text):
        return [hit.term for hit in check_store_notes.scan_text(Path("notes.txt"), text)]

    def test_flags_platform_and_store_names_case_insensitively(self):
        self.assertEqual(self.terms("Works on android and iPad."), ["Android", "iPad"])
        self.assertEqual(self.terms("Get it on Google  Play."), ["Google Play"])
        self.assertEqual(self.terms("Install the apk."), ["APK"])
        self.assertEqual(self.terms("Samsung tablets"), ["Samsung"])

    def test_flags_apple_device_and_os_names_case_insensitively(self):
        self.assertEqual(self.terms("Better on IPHONE"), ["iPhone"])
        self.assertEqual(self.terms("Requires ios 17"), ["iOS"])
        self.assertEqual(self.terms("iPadOS split view"), ["iPadOS"])
        self.assertEqual(self.terms("Works on iPads and iPhones"), ["iPads", "iPhones"])

    def test_word_boundaries_avoid_partial_matches(self):
        self.assertEqual(self.terms("androids, apkx, Huaweix"), [])
        self.assertEqual(self.terms("radios, bios, iPadding, iphonex"), [])

    def test_generic_words_only_match_product_capitalization(self):
        self.assertEqual(self.terms("pixel density, multitasking windows, galaxy map"), [])
        self.assertEqual(self.terms("Pixel tablet"), ["Pixel"])

    def test_reports_line_numbers(self):
        hits = check_store_notes.scan_text(Path("n"), "Latest changes:\n- ok\n- Android fix\n")
        self.assertEqual([(hit.line, hit.term) for hit in hits], [(3, "Android")])


class MainTest(unittest.TestCase):
    def run_main(self, text):
        with tempfile.TemporaryDirectory() as directory:
            notes = Path(directory) / "whats_new.txt"
            notes.write_text(text, encoding="utf-8")
            out, err = StringIO(), StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = check_store_notes.main([str(notes)])
            return code, out.getvalue()

    def test_exit_codes_and_output_format(self):
        code, out = self.run_main("Latest changes:\n- Faster maps on Android.\n")
        self.assertEqual(code, 1)
        self.assertIn("whats_new.txt:2:Android: - Faster maps on Android.", out)
        code, _ = self.run_main("Latest changes:\n- Faster maps on all devices.\n")
        self.assertEqual(code, 0)

    def test_reads_current_version_from_build_gradle(self):
        with tempfile.TemporaryDirectory() as directory:
            gradle = Path(directory) / "build.gradle"
            gradle.write_text("def versionMajor = 2\ndef versionMinor = 4\ndef versionPatch = 1\n")
            self.assertEqual(check_store_notes.current_version(gradle), "2.4.1")


if __name__ == "__main__":
    unittest.main()
