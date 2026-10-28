#!/usr/bin/env python3
"""Compare source or packaged app privacy declarations with the disclosure draft.

This verifies declared metadata, not all runtime data flows or live store answers.
"""

from __future__ import annotations

import argparse
import json
import plistlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "apple/AppStore/privacy-disclosures.json"

# Apple's collected-data vocabulary. Keep the irregular PhotosorVideos spelling.
# https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacycollecteddatatypes/nsprivacycollecteddatatype
VALID_TYPES = {"NSPrivacyCollectedDataType" + name for name in (
    "Name", "EmailAddress", "PhoneNumber", "PhysicalAddress", "OtherUserContactInfo",
    "Health", "Fitness", "PaymentInfo", "CreditInfo", "OtherFinancialInfo",
    "PreciseLocation", "CoarseLocation", "SensitiveInfo", "Contacts",
    "EmailsOrTextMessages", "PhotosorVideos", "AudioData", "GameplayContent",
    "CustomerSupport", "OtherUserContent", "BrowsingHistory", "SearchHistory",
    "UserID", "DeviceID", "PurchaseHistory", "ProductInteraction", "AdvertisingData",
    "OtherUsageData", "CrashData", "PerformanceData", "OtherDiagnosticData",
    "EnvironmentScanning", "Hands", "Head", "OtherDataTypes",
)}


def load_catalog(path: Path) -> dict:
    catalog = json.loads(path.read_text(encoding="utf-8"))
    if catalog.get("schema_version") != 1:
        raise ValueError("unsupported disclosure schema")
    entries = catalog["collected_data"]
    types = [entry["type"] for entry in entries]
    if not types or len(types) != len(set(types)) or not set(types) <= VALID_TYPES:
        raise ValueError("disclosure types must be unique, recognized Apple values")
    if catalog.get("tracking") is not False or catalog.get("tracking_domains") != []:
        raise ValueError("the current policy discloses no tracking")
    if catalog.get("linked_to_user") is not True or catalog.get("purposes") != [
        "NSPrivacyCollectedDataTypePurposeAppFunctionality"
    ]:
        raise ValueError("unexpected disclosure linkage/purposes")
    for entry in entries:
        if not entry.get("label") or not entry.get("basis") or not entry.get("evidence"):
            raise ValueError(f"missing disclosure basis: {entry['type']}")
        if any(not (ROOT / item).is_file() for item in entry["evidence"]):
            raise ValueError(f"missing source evidence: {entry['type']}")
    return catalog


def validate_manifest(manifest: dict, catalog: dict, *, main_app: bool = True) -> None:
    if main_app and ("NSPrivacyTracking" not in manifest or "NSPrivacyTrackingDomains" not in manifest):
        raise ValueError("main app must explicitly declare tracking and tracking domains")
    if manifest.get("NSPrivacyTracking", False) is not False:
        raise ValueError("manifest declares tracking contrary to policy")
    if manifest.get("NSPrivacyTrackingDomains", []) != []:
        raise ValueError("manifest declares tracking domains contrary to policy")
    expected = {entry["type"] for entry in catalog["collected_data"]}
    entries = manifest.get("NSPrivacyCollectedDataTypes", [])
    actual = [entry.get("NSPrivacyCollectedDataType") for entry in entries]
    if len(actual) != len(set(actual)) or not set(actual) <= expected:
        raise ValueError("manifest has duplicate, unknown or undisclosed collected data types")
    if main_app and set(actual) != expected:
        raise ValueError(f"missing app disclosures: {sorted(expected - set(actual))}")
    for entry in entries:
        data_type = entry["NSPrivacyCollectedDataType"]
        if entry.get("NSPrivacyCollectedDataTypeTracking") is not False:
            raise ValueError(f"{data_type} must declare tracking false")
        if type(entry.get("NSPrivacyCollectedDataTypeLinked")) is not bool:
            raise ValueError(f"{data_type} has no linkage declaration")
        if main_app and entry["NSPrivacyCollectedDataTypeLinked"] is not catalog["linked_to_user"]:
            raise ValueError(f"{data_type} has unexpected linkage")
        purposes = entry.get("NSPrivacyCollectedDataTypePurposes", [])
        if not purposes or not set(purposes) <= set(catalog["purposes"]):
            raise ValueError(f"{data_type} has undisclosed collection purposes")
    if main_app:
        accessed = {
            entry.get("NSPrivacyAccessedAPIType"): set(entry.get("NSPrivacyAccessedAPITypeReasons", []))
            for entry in manifest.get("NSPrivacyAccessedAPITypes", [])
        }
        for category, reasons in catalog["required_reasons"].items():
            if not set(reasons) <= accessed.get(category, set()):
                raise ValueError(f"{category} must declare reasons {reasons}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group()
    target.add_argument("--manifest", type=Path)
    target.add_argument("--app", type=Path, help="app bundle, including SDK manifests")
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--check-policy", action="store_true")
    args = parser.parse_args(argv)
    try:
        catalog = load_catalog(args.catalog)
        if args.check_policy:
            policy = (ROOT / "PrivacyPolicyApple.md").read_text(encoding="utf-8")
            if f"Last updated: {catalog['policy_last_updated']}\n" not in policy:
                raise ValueError("privacy policy and disclosure dates differ")
        primary = args.app / "PrivacyInfo.xcprivacy" if args.app else (
            args.manifest or ROOT / "apple/App/PrivacyInfo.xcprivacy"
        )
        manifests = [primary]
        if args.app:
            manifests.extend(sorted(path for path in args.app.rglob("PrivacyInfo.xcprivacy")
                                    if path != primary))
        for index, path in enumerate(manifests):
            try:
                with path.open("rb") as source:
                    validate_manifest(plistlib.load(source), catalog, main_app=index == 0)
            except ValueError as exc:
                raise ValueError(f"{path}: {exc}") from exc
        print(f"Apple privacy declarations verified: {len(catalog['collected_data'])} data types, "
              f"{len(manifests)} manifest(s). Live store answers and runtime behavior are not verified.")
        return 0
    except (ValueError, OSError, KeyError, TypeError, plistlib.InvalidFileException) as exc:
        print(f"Apple privacy check failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
