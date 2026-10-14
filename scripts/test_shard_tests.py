"""Tests for shard_tests.py: run with `python3 -m unittest discover -s scripts -p 'test_*.py'`."""

import contextlib
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shard_tests  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class ShardTestsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = os.path.join(self.dir.name, "src")
        self.reports = os.path.join(self.dir.name, "reports")
        os.makedirs(self.reports)

    def source(self, path, text="class X { @Test void t() {} }"):
        full = os.path.join(self.root, *path.split("/"))
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as fh:
            fh.write(text)

    def report(self, name):
        with open(os.path.join(self.reports, f"TEST-{name}.xml"), "w", encoding="utf-8") as fh:
            fh.write("<testsuite/>")

    def test_only_the_files_surefire_would_pick_are_listed(self):
        for path in ("a/FooTest.java", "a/TestBar.java", "a/BazTests.java", "a/QuxTestCase.java"):
            self.source(path)
        self.source("a/FxTestSupport.java")
        self.source("a/Fixture.java")
        self.assertEqual(
            ["a/BazTests.java", "a/FooTest.java", "a/QuxTestCase.java", "a/TestBar.java"],
            shard_tests.test_classes(self.root),
        )

    def test_every_class_lands_on_exactly_one_shard(self):
        classes = [f"p/C{i:03d}Test.java" for i in range(41)] + [f"p/U{i:03d}FxTest.java" for i in range(23)]
        for count in (1, 2, 3, 4, 7):
            shards = [shard_tests.shard(classes, index, count) for index in range(1, count + 1)]
            dealt = [c for s in shards for c in s]
            self.assertEqual(sorted(classes), sorted(dealt), f"{count} shards")
            self.assertEqual(len(dealt), len(set(dealt)), f"{count} shards")

    def test_the_slow_fx_classes_are_spread_evenly(self):
        classes = [f"p/C{i:03d}Test.java" for i in range(40)] + [f"p/U{i:03d}FxTest.java" for i in range(22)]
        fx = [sum(c.endswith("FxTest.java") for c in shard_tests.shard(classes, i, 4)) for i in range(1, 5)]
        self.assertLessEqual(max(fx) - min(fx), 1, fx)

    def test_the_real_suite_splits_into_four_shards_of_similar_size(self):
        classes = shard_tests.test_classes(os.path.join(REPO, "src", "test", "java"))
        self.assertGreater(len(classes), 1000)
        sizes = [len(shard_tests.shard(classes, i, 4)) for i in range(1, 5)]
        self.assertEqual(len(classes), sum(sizes))
        self.assertLessEqual(max(sizes) - min(sizes), 2, sizes)

    def test_a_class_with_a_test_and_no_report_is_named(self):
        self.source("a/RanTest.java")
        self.source("a/LostTest.java")
        self.source("a/NestedOnlyTest.java")
        self.source("a/AbstractBaseTest.java", "public abstract class AbstractBaseTest { @Test void t() {} }")
        self.source("a/TestHelpers.java", "final class TestHelpers { static void help() {} }")
        self.report("a.RanTest")
        self.report("a.NestedOnlyTest$Inner")
        classes = shard_tests.test_classes(self.root)
        self.assertEqual(["a.LostTest"], shard_tests.unreported(classes, self.reports, self.root))

    def test_a_shard_that_does_not_exist_is_refused(self):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(2, shard_tests.main(["x", "list", "5", "4"]))
        self.assertIn("does not exist", err.getvalue())


if __name__ == "__main__":
    unittest.main()
