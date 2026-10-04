#!/usr/bin/env python3
"""Fail when store-facing release notes or App Store metadata name other platforms.

App Review has rejected a release whose notes mentioned "Android", and the notes
are shared with Google Play, so Apple device names are excluded too. The canonical
release-notes/<version>/whats_new.txt ships to both Google Play and the App Store,
and apple/AppStore/metadata/en-US/*.txt is the App Store Connect mirror, so both
are scanned. Prints file:line:term for every hit and exits 1 when any are found.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Iterable, List, NamedTuple, Optional

# The denylist: edit here. Each entry is (term, case_sensitive). Generic English
# words (pixel, windows, galaxy) only match their capitalized product spelling.
DENYLIST = [
    ("Android", False),
    ("Google Play", False),
    ("Play Store", False),
    ("Play Console", False),
    ("Samsung", False),
    ("Galaxy", True),
    ("Pixel", True),
    ("Windows", True),
    ("Huawei", False),
    ("Amazon Appstore", False),
    ("APK", False),
    # The notes are shared with Google Play, so Apple device and OS names are out too.
    ("iPad", False),
    ("iPads", False),
    ("iPhone", False),
    ("iPhones", False),
    ("iOS", False),
    ("iPadOS", False),
]

REPO_ROOT = Path(__file__).resolve().parents[2]
APP_STORE_METADATA = REPO_ROOT / "apple" / "AppStore" / "metadata"


class Hit(NamedTuple):
    path: Path
    line: int
    term: str
    text: str


def compile_denylist(entries: Iterable = DENYLIST) -> List[tuple]:
    patterns = []
    for term, case_sensitive in entries:
        words = r"\s+".join(re.escape(word) for word in term.split())
        flags = 0 if case_sensitive else re.IGNORECASE
        patterns.append((term, re.compile(r"(?<![A-Za-z0-9])" + words + r"(?![A-Za-z0-9])", flags)))
    return patterns


def scan_text(path: Path, text: str, patterns: Optional[List[tuple]] = None) -> List[Hit]:
    hits = []
    for number, line in enumerate(text.splitlines(), start=1):
        for term, pattern in patterns or compile_denylist():
            if pattern.search(line):
                hits.append(Hit(path, number, term, line.strip()))
    return hits


def current_version(build_gradle: Path = REPO_ROOT / "app" / "build.gradle") -> str:
    source = build_gradle.read_text(encoding="utf-8")
    parts = []
    for name in ("versionMajor", "versionMinor", "versionPatch"):
        match = re.search(r"^\s*def\s+" + name + r"\s*=\s*(\d+)\s*$", source, re.MULTILINE)
        if not match:
            raise SystemExit(f"Could not read {name} from {build_gradle}")
        parts.append(match.group(1))
    return ".".join(parts)


def default_targets(version: str) -> List[Path]:
    targets = [REPO_ROOT / "release-notes" / version / "whats_new.txt"]
    targets += sorted(APP_STORE_METADATA.glob("*/*.txt"))
    return targets


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", help="release-notes version to scan (default: app/build.gradle version)")
    parser.add_argument("files", nargs="*", type=Path, help="scan these files instead of the defaults")
    args = parser.parse_args(argv)

    if args.files:
        targets = args.files
        label = "given files"
    else:
        version = args.version or current_version()
        if not re.fullmatch(r"\d+(\.\d+){1,2}", version):
            parser.error(f"invalid version: {version}")
        targets = default_targets(version)
        label = f"release notes {version} and App Store metadata"

    patterns = compile_denylist()
    hits: List[Hit] = []
    for path in targets:
        if not path.is_file():
            print(f"Missing store-facing text: {path}", file=sys.stderr)
            return 2
        hits += scan_text(path, path.read_text(encoding="utf-8"), patterns)

    for hit in hits:
        try:
            shown = hit.path.resolve().relative_to(REPO_ROOT)
        except ValueError:
            shown = hit.path
        print(f"{shown}:{hit.line}:{hit.term}: {hit.text}")
    if hits:
        print(
            f"Store-facing text names another platform or store ({len(hits)} hit(s)). "
            "Use neutral wording; keep platform details in docs/PLATFORM_PARITY_LEDGER.md.",
            file=sys.stderr,
        )
        return 1
    print(f"Store notes check passed: {label} ({len(targets)} files).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
