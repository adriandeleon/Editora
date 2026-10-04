package com.editora.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cargo libtest text parsing: result lines + the trailing failures: block flushed on exit. */
class CargoTestParserTest {

    @Test
    void parsesResultsAndFailureMessages() {
        CargoTestParser p = new CargoTestParser();
        TestNode root = TestNode.root();
        String[] lines = {
            "running 3 tests",
            "test tests::it_works ... ok",
            "test tests::it_fails ... FAILED",
            "test tests::skipme ... ignored",
            "",
            "failures:",
            "",
            "---- tests::it_fails stdout ----",
            "thread 'tests::it_fails' panicked at src/lib.rs:10:9:",
            "assertion `left == right` failed",
            "",
            "failures:",
            "    tests::it_fails",
            "",
            "test result: FAILED. 1 passed; 1 failed; 1 ignored;",
        };
        for (String l : lines) {
            for (ParsedSuite s : p.onLine(l, false)) {
                TestTreeBuilder.merge(root, s);
            }
        }
        for (ParsedSuite s : p.onExit(101)) {
            TestTreeBuilder.merge(root, s);
        }
        TestNode suite = root.childById(CargoTestParser.SUITE);
        assertNotNull(suite);
        assertEquals(3, suite.children().size());
        assertEquals(
                TestStatus.PASSED,
                suite.childById(CargoTestParser.SUITE + "#tests::it_works").status());
        assertEquals(
                TestStatus.SKIPPED,
                suite.childById(CargoTestParser.SUITE + "#tests::skipme").status());
        TestNode failed = suite.childById(CargoTestParser.SUITE + "#tests::it_fails");
        assertEquals(TestStatus.FAILED, failed.status());
        assertTrue(failed.failureMessage().contains("assertion"));
        assertTrue(failed.failureMessage().contains("src/lib.rs:10"));
    }

    private static TestNode parse(String... lines) {
        CargoTestParser p = new CargoTestParser();
        TestNode root = TestNode.root();
        for (String l : lines) {
            for (ParsedSuite s : p.onLine(l, false)) {
                TestTreeBuilder.merge(root, s);
            }
        }
        for (ParsedSuite s : p.onExit(101)) {
            TestTreeBuilder.merge(root, s);
        }
        root.rollUp();
        return root;
    }

    /** A workspace: the same test name in two crates, one passing and one failing. */
    @Test
    void sameNamedTestsOfDifferentCratesKeepTheirOwnRows() {
        TestNode root = parse(
                "     Running unittests src/lib.rs (target/debug/deps/alpha-0123456789abcdef)",
                "running 1 test",
                "test tests::it_works ... ok",
                "test result: ok. 1 passed; 0 failed;",
                "     Running unittests src/lib.rs (target/debug/deps/beta-fedcba9876543210)",
                "running 1 test",
                "test tests::it_works ... FAILED",
                "",
                "failures:",
                "",
                "---- tests::it_works stdout ----",
                "thread 'tests::it_works' panicked at beta/src/lib.rs:7:9:",
                "",
                "failures:",
                "    tests::it_works",
                "",
                "test result: FAILED. 0 passed; 1 failed;");
        TestNode alpha = root.childById("alpha (src/lib.rs)");
        TestNode beta = root.childById("beta (src/lib.rs)");
        assertNotNull(alpha);
        assertNotNull(beta);
        assertEquals(TestStatus.PASSED, alpha.children().get(0).status());
        assertEquals(TestStatus.FAILED, beta.children().get(0).status());
        assertTrue(beta.children().get(0).failureMessage().contains("beta/src/lib.rs:7"));
        TestCounts counts = root.tally();
        assertEquals(2, counts.total());
        assertEquals(1, counts.passed());
        assertEquals(1, counts.failed());
    }

    /** libtest prints "name - should panic" on the result line but the bare name in the failures block. */
    @Test
    void aFailedShouldPanicTestIsCountedOnce() {
        TestNode root = parse(
                "running 1 test",
                "test tests::must_panic - should panic ... FAILED",
                "",
                "failures:",
                "",
                "---- tests::must_panic stdout ----",
                "note: test did not panic as expected",
                "",
                "test result: FAILED. 0 passed; 1 failed;");
        TestNode suite = root.childById(CargoTestParser.SUITE);
        assertEquals(1, suite.children().size());
        assertTrue(suite.children().get(0).failureMessage().contains("did not panic"));
    }

    @Test
    void suiteNamesComeFromTheRunningHeader() {
        assertEquals(
                "it (tests/it.rs)",
                CargoTestParser.suiteOf("Running tests/it.rs (target/debug/deps/it-0123456789abcdef)"));
        assertEquals("alpha (doc-tests)", CargoTestParser.suiteOf("Doc-tests alpha"));
        assertEquals(
                "alpha (src/main.rs)",
                CargoTestParser.suiteOf(
                        "Running unittests src/main.rs (target\\debug\\deps\\alpha-0123456789abcdef.exe)"));
        assertEquals(null, CargoTestParser.suiteOf("running 3 tests"));
    }
}
