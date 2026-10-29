#!/usr/bin/env python3
"""Reject Android/Apple marketing-version or build-number drift."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
gradle = (root / "app/build.gradle").read_text()
version = ".".join(re.search(rf"def version{part} = (\d+)", gradle)[1] for part in ("Major", "Minor", "Patch"))
build = re.search(r"versionCode = (\d+)", gradle)[1]
project = (root / "apple/RID2CaltopoApple.xcodeproj/project.pbxproj").read_text()
assert set(re.findall(r"MARKETING_VERSION = ([^;]+);", project)) == {version}, "Apple marketing version differs from Android"
assert set(re.findall(r"CURRENT_PROJECT_VERSION = ([^;]+);", project)) == {build}, "Apple build number differs from Android"
assert f"path = ../release-notes/{version}/whats_new.txt;" in project, "Apple bundles release notes for a different version"
notes = root / f"release-notes/{version}/whats_new.txt"
assert notes.is_file(), "Canonical release notes are missing"
assert notes.read_text() == (root / "apple/AppStore/metadata/en-US/whats_new.txt").read_text(), "Apple release notes differ from the shared canonical notes"
print(f"Android and Apple release identities match: {version} ({build})")
