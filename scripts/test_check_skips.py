"""Tests for check_skips.py: run with `python3 -m unittest discover -s scripts -p 'test_*.py'`."""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_skips  # noqa: E402

REPORT = """<?xml version="1.0"?>
<testsuite name="{cls}" tests="3">
  <testcase name="runs" classname="{cls}"/>
  <testcase name="needsTool(Path)[2]" classname="{cls}"><skipped message="no tool"/></testcase>
  <testcase name="alsoSkipped" classname="{cls}"><skipped/></testcase>
</testsuite>
"""


class CheckSkipsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.reports = os.path.join(self.dir.name, "reports")
        os.mkdir(self.reports)
        with open(os.path.join(self.reports, "TEST-a.B.xml"), "w", encoding="utf-8") as fh:
            fh.write(REPORT.format(cls="a.B"))
        self.allow = os.path.join(self.dir.name, "allow.txt")

    def write_allow(self, text):
        with open(self.allow, "w", encoding="utf-8") as fh:
            fh.write(text)

    def test_an_unlisted_skip_fails(self):
        self.write_allow("# nothing\n")
        unexpected, _, total = check_skips.check(self.reports, self.allow)
        self.assertEqual(["a.B#alsoSkipped", "a.B#needsTool"], unexpected)
        self.assertEqual(2, total)
        self.assertEqual(1, check_skips.main(["x", self.reports, self.allow]))

    def test_a_class_entry_allows_every_test_in_it(self):
        self.write_allow("a.B\n")
        self.assertEqual(0, check_skips.main(["x", self.reports, self.allow]))

    def test_a_method_entry_ignores_parameters_and_allows_only_that_method(self):
        self.write_allow("a.B#needsTool\n")
        unexpected, _, _ = check_skips.check(self.reports, self.allow)
        self.assertEqual(["a.B#alsoSkipped"], unexpected)

    def test_an_entry_that_did_not_skip_is_reported_but_passes(self):
        self.write_allow("a.B\nc.D\n")
        unexpected, unused, _ = check_skips.check(self.reports, self.allow)
        self.assertEqual([], unexpected)
        self.assertEqual(["c.D"], unused)

    def test_a_missing_report_directory_is_an_error_not_a_pass(self):
        self.write_allow("a.B\n")
        self.assertEqual(2, check_skips.main(["x", os.path.join(self.dir.name, "none"), self.allow]))


if __name__ == "__main__":
    unittest.main()
