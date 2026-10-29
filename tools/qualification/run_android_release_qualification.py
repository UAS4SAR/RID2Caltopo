#!/usr/bin/env python3
"""Own a read-only emulator invocation; never select a connected physical device."""
import argparse
import json
import math
from pathlib import Path
import re
import socket
import shutil
import subprocess
import time


def instrumentation_passed(output, minimum):
    match = re.search(r"OK \((\d+) tests?\)", output)
    return bool(match and int(match[1]) >= minimum and "FAILURES!!!" not in output and "INSTRUMENTATION_FAILED" not in output)


def numbers(value):
    if isinstance(value, list):
        return [number for item in value for number in numbers(item)]
    return [float(value)] if isinstance(value, (int, float)) else []


def p95(values):
    assert values, "Benchmark produced no timing samples"
    assert all(math.isfinite(value) and value >= 0 for value in values), "Invalid timing sample"
    return sorted(values)[math.ceil(len(values) * .95) - 1]


# Classify retraced frames: R8 can inline or rename the actual app caller.
IO_PLUMBING = ("android.os.StrictMode", "android.os.strictmode.", "libcore.io.", "dalvik.system.",
               "java.io.", "java.nio.", "java.net.", "java.util.zip.", "java.util.jar.", "kotlin.io.",
               "android.graphics.BitmapFactory", "android.graphics.ImageDecoder",
               "android.app.SharedPreferencesImpl", "android.database.sqlite.", "android.database.DatabaseUtils",
               "android.content.ContentResolver", "android.content.ContentProviderClient",
               "android.provider.DocumentsContract", "androidx.documentfile.", "okhttp3.", "okio.")


def app_owned_io(stack, app_classes=()):
    for name in re.findall(r"(?:at |<OR> )(\S+)\([^\n]*", stack):
        if name.startswith(IO_PLUMBING): continue
        return name.startswith("org.ncssar.rid2caltopo.") or name.rsplit(".", 1)[0] in app_classes
    return False


def inspect_violations(raw, java, r8_jar, mapping, out):
    assert "FATAL EXCEPTION" not in raw, "Startup crashed; see startup-violations.log"
    clean = "\n".join(line.split("R2CQualification:", 1)[-1].strip() for line in raw.splitlines())
    result = subprocess.run([java, "-cp", r8_jar, "com.android.tools.r8.retrace.Retrace", mapping],
                            input=clean, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    retraced = result.stdout
    (out / "startup-violations-retraced.log").write_text(retraced)
    blocks = retraced.split("MAIN_THREAD_IO_CANDIDATE")[1:]
    calibrations = [b for b in blocks if "QualificationPolicyProvider.calibrateDetector(" in b]
    assert calibrations, "Minified startup StrictMode detector was not calibrated"
    # Legacy app classes such as StreamsViewModel live in the default package.
    # Their original R8 class headers provide identities even after inlining.
    classes = set(re.findall(r"^([^ .\n]+) -> [^\n]+:$", Path(mapping).read_text(), re.MULTILINE))
    failures = [b for b in blocks if b not in calibrations and app_owned_io(b, classes)]
    assert not failures, "App-owned Main-thread I/O detected; see startup-violations-retraced.log"
    return {"calibrated": True, "appOwnedViolations": len(failures), "frameworkOrLibraryViolations": len(blocks) - len(calibrations)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("adb", "emulator", "avd", "app", "tests", "benchmark-app", "benchmark-tests", "output", "java", "r8-jar", "mapping"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    out = Path(args.output).resolve(); out.mkdir(parents=True, exist_ok=True)
    for port in range(5560, 5600, 2):
        try:
            with socket.socket() as probe: probe.bind(("127.0.0.1", port))
            break
        except OSError:
            continue
    else:
        raise RuntimeError("No isolated emulator port is available")
    serial = f"emulator-{port}"
    log = (out / "emulator.log").open("w")
    emulator = subprocess.Popen([args.emulator, "-avd", args.avd, "-port", str(port), "-read-only", "-no-window",
                                 "-no-snapshot", "-no-audio", "-gpu", "swiftshader", "-memory", "4096"], stdout=log, stderr=subprocess.STDOUT)

    def adb(*command, timeout=120):
        assert serial.startswith("emulator-")
        result = subprocess.run([args.adb, "-s", serial, *command], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode: raise RuntimeError(f"Emulator command failed: {command}: {result.stdout[-4000:]}")
        return result.stdout

    report = {"serial": serial, "avd": args.avd, "result": "failed", "physicalDevices": "never selected"}
    try:
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if emulator.poll() is not None: raise RuntimeError("Qualification emulator exited before boot")
            try:
                if adb("shell", "getprop", "sys.boot_completed", timeout=5).strip() == "1": break
            except (RuntimeError, subprocess.TimeoutExpired): pass
            time.sleep(2)
        else: raise RuntimeError("Qualification emulator did not boot")
        assert adb("shell", "getprop", "ro.hardware").strip() == "ranchu", "Refusing non-emulator target"
        package = "org.ncssar.rid2caltopo"
        benchmark_package = package + ".startupbenchmark"
        remote = f"/sdcard/Android/media/{benchmark_package}/qualification"
        adb("install", "-r", args.app)
        adb("install", "-r", args.tests)
        assert "Success" in adb("shell", "pm", "clear", package)
        assert "Success" in adb("shell", "pm", "clear", package + ".test")
        classes = ",".join(package + ".storage." + name for name in
                           ("OperationalStorageTest", "RealArchiveProviderTest", "StartupWorkerQualificationTest"))
        output = adb("shell", "am", "instrument", "-w", "-e", "storageQualification", "true", "-e", "class", classes,
                     package + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=180)
        (out / "recovery-tests.log").write_text(output)
        assert instrumentation_passed(output, 12), "Recovery qualification failed or skipped; see recovery-tests.log"
        report["recoveryTests"] = int(re.search(r"OK \((\d+) tests?\)", output)[1])
        adb("install", "-r", args.benchmark_app)
        # The AVD's base image may contain previous qualification preferences.
        # Clear only this invocation's read-only emulator overlay, after ranchu validation.
        assert "Success" in adb("shell", "pm", "clear", package)
        report["startupFixture"] = "fresh data in owned read-only emulator overlay"
        adb("install", "-r", args.benchmark_tests)
        adb("shell", "mkdir", "-p", remote)
        adb("logcat", "-c")
        output = adb("shell", "am", "instrument", "-w", "-e", "androidx.benchmark.suppressErrors", "EMULATOR",
                     "-e", "additionalTestOutputDir", remote, benchmark_package + "/androidx.test.runner.AndroidJUnitRunner", timeout=240)
        (out / "macrobenchmark.log").write_text(output)
        violations = adb("logcat", "-d", "-v", "threadtime", "R2CQualification:E", "AndroidRuntime:E", "*:S")
        (out / "startup-violations.log").write_text(violations)
        shutil.rmtree(out / "benchmark", ignore_errors=True)
        adb("pull", remote, str(out / "benchmark"))
        if not instrumentation_passed(output, 3):
            adb("shell", "uiautomator", "dump", "/sdcard/qualification-window.xml")
            (out / "failure-window.xml").write_text(adb("shell", "cat", "/sdcard/qualification-window.xml"))
            screenshot = subprocess.run([args.adb, "-s", serial, "exec-out", "screencap", "-p"], capture_output=True, check=True)
            (out / "failure-screen.png").write_bytes(screenshot.stdout)
            raise AssertionError("Macrobenchmark tests failed; see macrobenchmark.log and failure-window.xml")
        report["strictMode"] = inspect_violations(violations, args.java, args.r8_jar, args.mapping, out)
        result_files = list((out / "benchmark").rglob("*benchmarkData.json"))
        assert result_files, "Macrobenchmark did not deliver its JSON measurements"
        timings = []
        for result_file in result_files:
            for benchmark in json.loads(result_file.read_text()).get("benchmarks", []):
                startup = numbers(benchmark.get("metrics", {}).get("timeToInitialDisplayMs", {}).get("runs", []))
                frames = numbers(benchmark.get("sampledMetrics", {}).get("frameDurationCpuMs", {}).get("runs", []))
                if "scrollOperationalWarnings" in benchmark["name"]:
                    frame_p95 = p95(frames)
                    assert frame_p95 <= 100, f"Scrolling frame CPU p95 exceeded 100 ms: {frame_p95}"
                    timings.append({"name": benchmark["name"], "frameCpuP95Ms": frame_p95})
                else:
                    startup_p95 = p95(startup)
                    assert startup_p95 <= 5000, f"Startup p95 exceeded 5000 ms: {startup_p95}"
                    timing = {"name": benchmark["name"], "startupP95Ms": startup_p95}
                    if "coldStartup" in benchmark["name"]:
                        frame_p95 = p95(frames)
                        assert frame_p95 <= 500, f"Initial frame CPU p95 exceeded 500 ms: {frame_p95}"
                        timing["initialFrameCpuP95Ms"] = frame_p95
                    timings.append(timing)
        assert len(timings) == 3, "Cold/foreground-return/scrolling measurements are missing or duplicated"
        report["timings"] = timings
        report["result"] = "passed"
        print(json.dumps(report, indent=2))
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        try: adb("emu", "kill", timeout=10)
        except Exception: emulator.terminate()
        try: emulator.wait(timeout=15)
        except subprocess.TimeoutExpired: emulator.kill(); emulator.wait(timeout=5)
        log.close()


if __name__ == "__main__": main()
