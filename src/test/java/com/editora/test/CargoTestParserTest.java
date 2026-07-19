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
}
