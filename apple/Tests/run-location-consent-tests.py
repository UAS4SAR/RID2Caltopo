#!/usr/bin/env python3
"""Run the real location provider against simulated platform callbacks on macOS.

No device permissions, production preferences, or populated devices are changed.
This complements the iOS build and the tracker wire-format unit tests.
"""
from pathlib import Path
import os
import subprocess
import tempfile

apple = Path(__file__).resolve().parents[1]
fixtures = apple / "Tests/LocationConsentRegression"
with tempfile.TemporaryDirectory(prefix="r2c-location-consent-") as temporary:
    root = Path(temporary)
    command = ["xcrun", "swiftc", "-parse-as-library", "-swift-version", "6",
        str(fixtures / "Fakes.swift"), str(apple / "App/AppleLocationProvider.swift"),
        str(fixtures / "Run.swift"), "-module-cache-path", str(root / "modules"),
        "-o", str(root / "regression")]
    subprocess.run(command, check=True)
    subprocess.run([str(root / "regression")], check=True)
