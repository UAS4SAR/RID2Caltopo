import copy
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
import plistlib
import tempfile
import unittest

import check_apple_privacy as checker


class PrivacyDisclosureTests(unittest.TestCase):
    def setUp(self):
        self.catalog = checker.load_catalog(checker.CATALOG)
        with (checker.ROOT / "apple/App/PrivacyInfo.xcprivacy").open("rb") as source:
            self.manifest = plistlib.load(source)

    def test_current_source_matches_disclosures(self):
        checker.validate_manifest(self.manifest, self.catalog)

    def test_missing_media_disclosure_rejects_old_artifact(self):
        self.manifest["NSPrivacyCollectedDataTypes"] = [
            entry for entry in self.manifest["NSPrivacyCollectedDataTypes"]
            if entry["NSPrivacyCollectedDataType"] != "NSPrivacyCollectedDataTypePhotosorVideos"
        ]
        with self.assertRaisesRegex(ValueError, "missing app disclosures"):
            checker.validate_manifest(self.manifest, self.catalog)

    def test_wrong_apple_media_enum_casing_is_rejected(self):
        self.manifest["NSPrivacyCollectedDataTypes"][0]["NSPrivacyCollectedDataType"] = (
            "NSPrivacyCollectedDataTypePhotosOrVideos"
        )
        with self.assertRaisesRegex(ValueError, "unknown or undisclosed"):
            checker.validate_manifest(self.manifest, self.catalog)

    def test_tracking_or_extra_sdk_collection_cannot_hide_in_bundle(self):
        sdk = {"NSPrivacyTracking": True}
        with self.assertRaisesRegex(ValueError, "tracking contrary"):
            checker.validate_manifest(sdk, self.catalog, main_app=False)
        sdk = copy.deepcopy(self.manifest)
        sdk["NSPrivacyCollectedDataTypes"][0]["NSPrivacyCollectedDataType"] = (
            "NSPrivacyCollectedDataTypeHealth"
        )
        with self.assertRaisesRegex(ValueError, "undisclosed"):
            checker.validate_manifest(sdk, self.catalog, main_app=False)

    def test_missing_required_reason_is_rejected(self):
        self.manifest["NSPrivacyAccessedAPITypes"] = []
        with self.assertRaisesRegex(ValueError, "must declare reasons"):
            checker.validate_manifest(self.manifest, self.catalog)

    def test_sdk_analytics_purpose_is_rejected(self):
        sdk = copy.deepcopy(self.manifest)
        sdk["NSPrivacyCollectedDataTypes"][0]["NSPrivacyCollectedDataTypePurposes"] = [
            "NSPrivacyCollectedDataTypePurposeAnalytics"
        ]
        with self.assertRaisesRegex(ValueError, "undisclosed collection purposes"):
            checker.validate_manifest(sdk, self.catalog, main_app=False)

    def test_bundle_check_visits_embedded_sdk_manifests(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory) / "Example.app"
            sdk = app / "Frameworks/Example.framework/PrivacyInfo.xcprivacy"
            sdk.parent.mkdir(parents=True)
            (app / "PrivacyInfo.xcprivacy").write_bytes(plistlib.dumps(self.manifest))
            sdk.write_bytes(plistlib.dumps({"NSPrivacyCollectedDataTypes": []}))
            with redirect_stdout(StringIO()), redirect_stderr(StringIO()):
                self.assertEqual(checker.main(["--app", str(app)]), 0)
                sdk.write_bytes(plistlib.dumps({"NSPrivacyTracking": True}))
                self.assertEqual(checker.main(["--app", str(app)]), 1)


if __name__ == "__main__":
    unittest.main()
