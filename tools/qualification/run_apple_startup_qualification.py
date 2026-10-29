#!/usr/bin/env python3
"""Release-mode startup/recovery qualification on a newly-created disposable Simulator."""
import argparse
import json
import plistlib
import shutil
from pathlib import Path
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = Path(args.output).resolve(); out.mkdir(parents=True, exist_ok=True)
    def run(*command, timeout=600):
        try:
            if command[0] == "xcodebuild":
                live = out / ("live-" + command[-1] + ".log")
                with live.open("w") as stream:
                    result = subprocess.run(command, cwd=root, text=True, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
                result.stdout = live.read_text()
            else:
                result = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            output = error.stdout or b""
            (out / "timeout-command.log").write_bytes(output.encode() if isinstance(output, str) else output)
            raise
        if result.returncode:
            (out / "failure-command.log").write_text(result.stdout)
            diagnostics = "\n".join(line[:500] for line in result.stdout.splitlines() if "error:" in line)[:6000]
            raise RuntimeError(f"{command[0]} failed: {diagnostics}\n{result.stdout[-2000:]}")
        return result.stdout
    runtimes = json.loads(run("xcrun", "simctl", "list", "runtimes", "-j"))["runtimes"]
    runtime = next(r["identifier"] for r in runtimes if r.get("isAvailable") and r["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-"))
    types = json.loads(run("xcrun", "simctl", "list", "devicetypes", "-j"))["devicetypes"]
    device_type = next(t["identifier"] for t in types if t["name"].startswith("iPhone ") and "Pro" in t["name"])
    device = run("xcrun", "simctl", "create", "R2C Startup Qualification " + str(uuid.uuid4())[:8], device_type, runtime).strip()
    report = {"simulator": device, "result": "failed", "physicalDevices": "never selected"}
    try:
        run("xcrun", "simctl", "boot", device)
        run("xcrun", "simctl", "bootstatus", device, "-b", timeout=180)
        run("python3", "apple/Tests/run-settings-units-ui.py", str(out / "project"), "StartupRecovery")
        project = out / "project/StartupRecovery.xcodeproj"
        derived = out / "DerivedData"
        arguments = ["xcodebuild", "-quiet", "-project", str(project), "-scheme", "StartupRecovery", "-configuration", "Release",
                     "-destination", "id=" + device, "-derivedDataPath", str(derived), "CODE_SIGNING_ALLOWED=YES", "CODE_SIGN_IDENTITY=-",
                     "ARCHS=arm64", "ONLY_ACTIVE_ARCH=YES", "-jobs", "2"]
        (out / "build.log").write_text(run(*arguments, "build-for-testing", timeout=900))
        app = derived / "Build/Products/Release-iphonesimulator/RID2CaltopoApple.app"
        # Simulator Keychain identity is embedded in __TEXT.__entitlements;
        # the ad-hoc code-signature entitlement dictionary is normally empty.
        simulated = list((derived / "Build/Intermediates.noindex").rglob("RID2CaltopoApple.app-Simulated.xcent"))
        assert len(simulated) == 1, "Simulator app lacks generated Keychain entitlements"
        entitlements = plistlib.loads(simulated[0].read_bytes())
        assert entitlements.get("application-identifier"), "Simulator app lacks its Keychain application identity"
        shutil.copyfile(simulated[0], out / "simulator-entitlements.plist")
        run("codesign", "--verify", "--deep", "--strict", str(app))
        report["simulatorSigning"] = "ad-hoc with simulated application-identifier"
        run("xcrun", "simctl", "install", device, str(app))
        # Returning installations already have location consent. Set only this
        # disposable fixture's permission so OS prompts cannot obscure recovery UI.
        run("xcrun", "simctl", "privacy", device, "grant", "location-always", "org.ncssar.RID2CaltopoApple")
        container = Path(run("xcrun", "simctl", "get_app_container", device, "org.ncssar.RID2CaltopoApple", "data").strip())
        journal = container / "Library/Application Support/awaiting-map-flights.json"
        journal.parent.mkdir(parents=True, exist_ok=True)
        damaged = b"[{interrupted qualification journal"
        journal.write_bytes(damaged)
        mappings = [{"remoteID": "REVIEW-DUPLICATE", "mappedID": "fixture-1"},
                    {"remoteID": "REVIEW-DUPLICATE", "mappedID": "fixture-2"}]
        preferences = container / "Library/Preferences/org.ncssar.RID2CaltopoApple.plist"
        preferences.parent.mkdir(parents=True, exist_ok=True)
        preferences.write_bytes(plistlib.dumps({"org.ridMappings": mappings}))
        shutil.rmtree(out / "StartupRecovery.xcresult", ignore_errors=True)
        (out / "tests.log").write_text(run(*arguments, "-parallel-testing-enabled", "NO", "-test-timeouts-enabled", "YES", "-maximum-test-execution-time-allowance", "120", "-resultBundlePath", str(out / "StartupRecovery.xcresult"),
                                            "test-without-building", timeout=420))
        result = json.loads(run("xcrun", "xcresulttool", "get", "test-results", "summary", "--path", str(out / "StartupRecovery.xcresult"), "--format", "json"))
        assert result.get("passedTests", 0) >= 1 and result.get("failedTests") == 0 and result.get("skippedTests") == 0, "Startup test did not execute successfully"
        (out / "test-summary.json").write_text(json.dumps(result, indent=2) + "\n")
        warnings = result.get("runtimeWarnings", [])
        assert not any("unresponsiveness" in warning.get("message", "").lower() or "main thread" in warning.get("message", "").lower() for warning in warnings), "Runtime detected potentially blocking Main-thread work; see test-summary.json"
        # Xcode may relocate the application's data container during test installation.
        current_container = Path(run("xcrun", "simctl", "get_app_container", device, "org.ncssar.RID2CaltopoApple", "data").strip())
        journal = current_container / "Library/Application Support/awaiting-map-flights.json"
        assert journal.read_bytes() == damaged, "Startup or retry overwrote the unreadable journal"
        preferences = current_container / "Library/Preferences/org.ncssar.RID2CaltopoApple.plist"
        assert plistlib.loads(preferences.read_bytes()).get("org.ridMappings") == mappings, "Duplicate recovery rewrote original mappings"
        report.update(result="passed", journalPreserved=True, originalDuplicateMappingsPreserved=True,
                      duplicateMappingRecoveryVisible=True, configuration="Release", maximumLaunchSeconds=10)
        print(json.dumps(report, indent=2))
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        try: run("xcrun", "simctl", "shutdown", device, timeout=30)
        finally: run("xcrun", "simctl", "delete", device, timeout=30)


if __name__ == "__main__": main()
