import unittest
from check_apple_ui_copy import scan

class CopyTests(unittest.TestCase):
    def test_original_idle_sentence_is_rejected(self):
        self.assertTrue(scan('Text("Track filtering and maximum idle time use the same controls as Android.")'))
    def test_multiline_and_raw_literals(self):
        self.assertTrue(scan('Text(\n #"Android help"#\n)'))
        self.assertTrue(scan('Text("""Help\nAndroid\n""")'))
    def test_indirect_status_and_help_strings_are_checked(self):
        self.assertTrue(scan('status = "Android QR loaded"'))
        self.assertTrue(scan('let help = "Uses Android detector"'))
    def test_comments_identifiers_and_logs_are_not_ui(self):
        self.assertEqual([], scan('// Text("Android")\n/* Text("Android") */\nlet androidMode = true\nAppleLog.info("Android diagnostics")\nText("RID messages reset the idle timer.")'))
