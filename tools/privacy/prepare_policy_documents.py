#!/usr/bin/env python3
"""Prepare bundled, readable policy and service-credit documents. --check detects stale copies."""
from pathlib import Path
import argparse
import json
import re

ROOT = Path(__file__).resolve().parents[2]

def plain_markdown(source):
    source = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", lambda m: m[1] if m[1] == m[2] else m[1] + " (" + m[2] + ")", source)
    source = source.replace("**", "").replace("`", "")
    blocks = []; paragraph = []
    def flush():
        if paragraph:
            blocks.append(" ".join(paragraph)); paragraph.clear()
    for raw in source.splitlines():
        line = raw.strip()
        if not line:
            flush()
        elif re.match(r"^#{1,6} ", line):
            flush(); blocks.append(re.sub(r"^#{1,6} ", "", line))
        elif line.startswith("- "):
            flush(); paragraph.append("• " + line[2:])
        else:
            paragraph.append(line)
    flush()
    return "\n\n".join(blocks).strip() + "\n"

def acknowledgments(platform):
    services = json.loads((ROOT / "tools/privacy/service_acknowledgments.json").read_text())
    blocks = ["RID2Caltopo — Service & data acknowledgments",
        "Optional features use the selected services and datasets. These credits supplement the map/provider notices displayed with each feature."]
    for entry in services:
        if platform in entry["platforms"]:
            blocks.append(entry["supplier"] + "\n" + entry["use"] + ("\n" + entry["url"] if entry["url"] else ""))
    blocks.append("Service names and trademarks belong to their respective owners. These acknowledgments do not imply affiliation, sponsorship or endorsement. Applicable service terms and permissions still apply. Software component licenses and the source/relinking offer are available separately in License & Notices.")
    return "\n\n".join(blocks) + "\n"

def outputs():
    android_policy = (ROOT / "PrivacyPolicyAndroid.md").read_text()
    for apple_service in ["Apple MapKit", "iCloud Drive", "Apple Keychain"]:
        if apple_service in android_policy:
            raise SystemExit("Android privacy policy names an Apple-only app service: " + apple_service)
    return {
        ROOT / "apple/Sources/R2CCore/Resources/PrivacyPolicyApple.txt": plain_markdown((ROOT / "PrivacyPolicyApple.md").read_text()),
        ROOT / "apple/Sources/R2CCore/Resources/ServiceAcknowledgmentsApple.txt": acknowledgments("apple"),
        ROOT / "app/src/main/assets/information/PrivacyPolicyAndroid.txt": plain_markdown((ROOT / "PrivacyPolicyAndroid.md").read_text()),
        ROOT / "app/src/main/assets/information/ServiceAcknowledgmentsAndroid.txt": acknowledgments("android"),
    }

def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for path, text in outputs().items():
        if args.check:
            if not path.is_file() or path.read_text() != text:
                raise SystemExit("Missing/stale bundled policy document: " + str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text)
    print("Bundled policy and service documents " + ("verified." if args.check else "prepared."))

if __name__ == "__main__":
    main()
