import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from publish_apple_relink import find_release


class FindReleaseTests(unittest.TestCase):
    @patch('publish_apple_relink.gh_json')
    @patch('publish_apple_relink.subprocess.run')
    def test_published_release_uses_by_tag(self, run, query):
        release = {'tag_name': 'tag', 'draft': False}
        run.return_value = SimpleNamespace(returncode=0, stdout=json.dumps(release))
        self.assertEqual(find_release('tag'), release)
        query.assert_not_called()

    @patch('publish_apple_relink.gh_json')
    @patch('publish_apple_relink.subprocess.run')
    def test_interrupted_draft_is_resumed(self, run, query):
        run.return_value = SimpleNamespace(returncode=1, stderr='HTTP 404')
        draft = {'id': 123, 'tag_name': 'tag', 'draft': True}
        query.return_value = [[{'tag_name': 'different'}], [draft]]
        self.assertEqual(find_release('tag'), draft)

    @patch('publish_apple_relink.gh_json')
    @patch('publish_apple_relink.subprocess.run')
    def test_authentication_failure_does_not_create_duplicate(self, run, query):
        run.return_value = SimpleNamespace(returncode=1, stderr='HTTP 401')
        with self.assertRaises(SystemExit):
            find_release('tag')
        query.assert_not_called()


if __name__ == '__main__':
    unittest.main()
