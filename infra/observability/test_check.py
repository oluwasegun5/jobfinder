import unittest

import check


class CheckTests(unittest.TestCase):
    def test_balanced_accepts_promql(self):
        self.assertTrue(check.balanced('sum by (le) (rate(x_bucket{a="b)"}[5m]))'))

    def test_balanced_rejects_broken_expressions(self):
        for expr in ("sum(rate(x[5m])", "rate(x[5m)]", 'x{a="b}'):
            self.assertFalse(check.balanced(expr), expr)

    def test_the_shipped_config_passes(self):
        self.assertEqual(check.check_dashboards(), [])
        self.assertEqual(check.check_scrape_config(), [])
        self.assertEqual(check.check_compose_profile(), [])


if __name__ == "__main__":
    unittest.main()
