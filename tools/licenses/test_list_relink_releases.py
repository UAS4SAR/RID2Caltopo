import datetime as dt
import unittest

from list_relink_releases import add_years, classify, notes_build

NOW = dt.datetime(2026, 11, 25, tzinfo=dt.timezone.utc)
RELEASE = {'published_at': '2026-10-26T19:17:21Z'}


def build(expired=False, expires='2027-01-26T00:00:00Z', states=()):
    return {'number': '431', 'version': '2.5.4', 'expired': expired, 'state': 'VALID',
            'uploaded': dt.datetime(2026, 10, 28, tzinfo=dt.timezone.utc),
            'expires': dt.datetime.fromisoformat(expires.replace('Z', '+00:00')), 'store_states': list(states)}


class ListRelinkReleasesTests(unittest.TestCase):
    def test_notes_build(self):
        body = 'The initial referring app build is\n2.6.1 (451). Builds with identical'
        self.assertEqual(notes_build(body), ('2.6.1', '451'))
        self.assertIsNone(notes_build('no build here'))

    def test_live_testflight_build(self):
        status, keep, _ = classify(RELEASE, {('2.5.4', '431')}, {'431': [build()]}, NOW, set())
        self.assertEqual((status, keep), ('live', None))

    def test_app_store_build_is_live_even_if_testflight_expired(self):
        status, _, _ = classify(RELEASE, {('2.5.4', '431')},
                                {'431': [build(expired=True, states=['READY_FOR_SALE'])]}, NOW, set())
        self.assertEqual(status, 'live')

    def test_expired_build_kept_three_years_after_expiry(self):
        status, keep, _ = classify(RELEASE, {('2.5.4', '431')},
                                   {'431': [build(expired=True, expires='2026-11-01T00:00:00Z')]}, NOW, set())
        self.assertEqual((status, keep.date().isoformat()), ('retained-3-year-rule', '2029-11-01'))

    def test_unknown_build_kept_three_years_after_publication(self):
        status, keep, _ = classify(RELEASE, {('2.5.4', '429')}, {}, NOW, set())
        self.assertEqual((status, keep.date().isoformat()), ('retained-3-year-rule', '2029-10-26'))

    def test_eligible_only_after_three_years(self):
        later = add_years(NOW, 4)
        status, _, _ = classify(RELEASE, {('2.5.4', '429')}, {}, later, set())
        self.assertEqual(status, 'eligible-for-deletion')

    def test_leap_day(self):
        self.assertEqual(add_years(dt.datetime(2028, 2, 29), 3).date().isoformat(), '2031-02-28')


if __name__ == '__main__':
    unittest.main()
