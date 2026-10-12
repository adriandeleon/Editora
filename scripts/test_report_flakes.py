"""Tests for report_flakes.py: run with `python3 -m unittest discover -s scripts -p 'test_*.py'`."""

import contextlib
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import report_flakes  # noqa: E402

REPORT = """<?xml version="1.0"?>
<testsuite name="a.B" tests="3">
  <testcase name="steady" classname="a.B"/>
  <testcase name="racy(Path)" classname="a.B">
    <flakyFailure message="expected: &lt;true&gt; but was: &lt;false&gt;" type="AssertionFailedError">trace</flakyFailure>
    <flakyError type="RuntimeException">java.lang.RuntimeException: boom
    at a.B.racy</flakyError>
  </testcase>
  <testcase name="broken" classname="a.B"><failure message="always"/></testcase>
</testsuite>
"""


class ReportFlakesTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def write(self, text):
        with open(os.path.join(self.dir.name, "TEST-a.B.xml"), "w", encoding="utf-8") as fh:
            fh.write(text)

    def run_main(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = report_flakes.main(["x", self.dir.name])
        return code, out.getvalue(), err.getvalue()

    def test_only_a_test_that_passed_on_a_rerun_is_listed(self):
        self.write(REPORT)
        self.assertEqual(
            [("a.B", "racy(Path)", 2, "expected: <true> but was: <false>")],
            report_flakes.flaky_tests(self.dir.name),
        )

    def test_a_flake_is_reported_in_the_summary_and_as_a_warning_without_failing(self):
        self.write(REPORT)
        code, out, err = self.run_main()
        self.assertEqual(0, code)
        self.assertIn("| `a.B.racy(Path)` | 2 |", out)
        self.assertIn("::warning title=Flaky test::a.B.racy(Path)", err)

    def test_no_flakes_says_so(self):
        self.write('<testsuite name="a.B"><testcase name="steady" classname="a.B"/></testsuite>')
        code, out, err = self.run_main()
        self.assertEqual(0, code)
        self.assertIn("None", out)
        self.assertEqual("", err)


if __name__ == "__main__":
    unittest.main()
