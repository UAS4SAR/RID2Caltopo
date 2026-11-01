import unittest
from unittest.mock import patch
from types import SimpleNamespace
from tempfile import TemporaryDirectory
from pathlib import Path
from run_android_release_qualification import (
    instrumentation_passed, p95, app_owned_io, inspect_violations,
    package_process_pids, force_stop_and_await_gone,
)


class QualificationResultTests(unittest.TestCase):
    def test_no_success_from_zero_tests_skips_or_adb_exit_status(self):
        for output in ("", "OK (0 tests)", "OK (9 tests)", "FAILURES!!! OK (12 tests)", "INSTRUMENTATION_FAILED"):
            self.assertFalse(instrumentation_passed(output, 12))
        self.assertTrue(instrumentation_passed("Time: 1\nOK (12 tests)", 12))

    def test_direct_app_io_is_distinguished_from_framework_rendering(self):
        self.assertTrue(app_owned_io("at java.io.FileInputStream.read(File.java:1)\n at org.ncssar.rid2caltopo.Data.load(Data.kt:2)"))
        self.assertFalse(app_owned_io("at java.io.FileInputStream.read(File.java:1)\n at android.graphics.Typeface.create(Typeface.java:2)\n at org.ncssar.rid2caltopo.App.start(App.kt:3)"))
        self.assertTrue(app_owned_io("at kotlin.io.FilesKt.readBytes(File.kt:1)\n at StreamsViewModel.load(StreamsViewModel.kt:2)", {"StreamsViewModel"}))
        self.assertFalse(app_owned_io("at a.b(a.java:1)"))  # raw R8 names must be retraced first

    def test_minified_detector_must_calibrate_and_retraced_app_io_fails(self):
        calibration = "MAIN_THREAD_IO_CANDIDATE\n at org.ncssar.rid2caltopo.qualification.QualificationPolicyProvider.calibrateDetector(Provider.java:1)\n"
        app_call = "MAIN_THREAD_IO_CANDIDATE\n at java.io.FileInputStream.read(File.java:1)\n at org.ncssar.rid2caltopo.Data.load(Data.kt:2)\n"
        with TemporaryDirectory() as directory:
            mapping = Path(directory) / "mapping.txt"
            mapping.write_text("StreamsViewModel -> a:\n")
            for stack, passes in (("", False), (calibration + app_call, False), (calibration, True)):
                with patch("run_android_release_qualification.subprocess.run", return_value=SimpleNamespace(stdout=stack)):
                    if passes:
                        self.assertTrue(inspect_violations("", "java", "r8", str(mapping), Path(directory))["calibrated"])
                    else:
                        with self.assertRaises(AssertionError): inspect_violations("", "java", "r8", str(mapping), Path(directory))

    def test_empty_or_invalid_timings_cannot_pass_a_budget(self):
        for values in ([], [float("nan")], [-1]):
            with self.assertRaises(AssertionError): p95(values)
        self.assertEqual(19, p95(list(range(1, 21))))

    def test_force_stop_waits_until_main_and_caltopo_probe_are_gone(self):
        calls = []

        def adb_run(command, timeout=10):
            calls.append(command)
            if command[:2] == ["shell", "am"]:
                return SimpleNamespace(returncode=0, stdout="")
            name = command[2]
            # First poll still sees the probe; later polls are clear.
            if name.endswith(":caltopo_probe") and sum(1 for c in calls if c[:2] == ["shell", "pidof"]) <= 2:
                return SimpleNamespace(returncode=0, stdout="4321")
            return SimpleNamespace(returncode=1, stdout="")

        force_stop_and_await_gone(adb_run, "org.ncssar.rid2caltopo", timeout_s=2.0)
        self.assertTrue(any(c[:3] == ["shell", "am", "force-stop"] for c in calls))
        self.assertEqual([], package_process_pids(adb_run, "org.ncssar.rid2caltopo"))


if __name__ == "__main__": unittest.main()
