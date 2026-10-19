package com.editora.test;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TAP sniffing + ok/not ok/skip parsing + YAML diagnostic attachment. */
class TapParserTest {

    @Test
    void looksLikeTap() {
        assertTrue(TapParser.looksLikeTap(List.of("TAP version 13", "1..2")));
        assertTrue(TapParser.looksLikeTap(List.of("ok 1 - works")));
        assertTrue(TapParser.looksLikeTap(List.of("not ok 2 - broken")));
        assertFalse(TapParser.looksLikeTap(List.of("PASS  src/foo.test.js", "Tests: 3 passed")));
    }

    @Test
    void parsesResultsSkipAndYaml() {
        TapParser p = new TapParser();
        TestNode root = TestNode.root();
        String[] lines = {
            "TAP version 13",
            "1..3",
            "ok 1 - adds numbers",
            "not ok 2 - subtracts numbers",
            "  ---",
            "  message: 'expected 1 to equal 2'",
            "  ...",
            "ok 3 - skipped one # SKIP not ready",
        };
        for (String l : lines) {
            for (ParsedSuite s : p.onLine(l, false)) {
                TestTreeBuilder.merge(root, s);
            }
        }
        TestNode suite = root.childById(TapParser.SUITE);
        assertEquals(3, suite.children().size());
        assertEquals(
                TestStatus.PASSED,
                suite.childById(TapParser.SUITE + "#adds numbers").status());
        TestNode failed = suite.childById(TapParser.SUITE + "#subtracts numbers");
        assertEquals(TestStatus.FAILED, failed.status());
        assertTrue(failed.failureMessage().contains("expected 1 to equal 2"));
        assertEquals(
                TestStatus.SKIPPED,
                suite.childById(TapParser.SUITE + "#skipped one").status());
    }

    private static TestNode parse(String... lines) {
        TapParser p = new TapParser();
        TestNode root = TestNode.root();
        for (String l : lines) {
            for (ParsedSuite s : p.onLine(l, false)) {
                TestTreeBuilder.merge(root, s);
            }
        }
        root.rollUp();
        return root;
    }

    /** node:test's TAP reporter: the same `it` name under two `describe`s, one failing. */
    @Test
    void sameNamedSubtestsOfDifferentParentsKeepTheirOwnRows() {
        TestNode root = parse(
                "TAP version 13",
                "# Subtest: Parser",
                "    # Subtest: handles empty input",
                "    not ok 1 - handles empty input",
                "      ---",
                "      error: '1 !== 2'",
                "      ...",
                "    1..1",
                "not ok 1 - Parser",
                "  ---",
                "  error: '1 subtest failed'",
                "  ...",
                "# Subtest: Lexer",
                "    # Subtest: handles empty input",
                "    ok 1 - handles empty input",
                "    1..1",
                "ok 2 - Lexer",
                "1..2");
        TestNode suite = root.childById(TapParser.SUITE);
        TestNode failing = suite.childById(TapParser.SUITE + "#Parser \u203a handles empty input");
        assertEquals(TestStatus.FAILED, failing.status(), "the failing leaf used to be overwritten by the passing one");
        assertTrue(failing.failureMessage().contains("1 !== 2"));
        assertEquals(
                TestStatus.PASSED,
                suite.childById(TapParser.SUITE + "#Lexer \u203a handles empty input")
                        .status());
        assertEquals(2, suite.children().size(), "the describe blocks' own results are suites, not tests");
        assertEquals(TestStatus.FAILED, root.status());
    }

    /** Flat TAP (tape's default assertion names): a later pass must not hide an earlier failure. */
    @Test
    void repeatedNamesInFlatTapAreSeparateResults() {
        TestNode root = parse("not ok 1 returns 404 when missing", "ok 2 returns 404 when missing");
        TestNode suite = root.childById(TapParser.SUITE);
        assertEquals(2, suite.children().size());
        assertEquals(
                TestStatus.FAILED,
                suite.childById(TapParser.SUITE + "#returns 404 when missing").status());
        assertEquals(
                TestStatus.PASSED,
                suite.childById(TapParser.SUITE + "#returns 404 when missing (2)")
                        .status());
        assertEquals(1, root.tally().failed());
    }

    @Test
    void theTestNumberIsOptional() {
        TestNode suite = parse("ok - first", "not ok - second", "ok").childById(TapParser.SUITE);
        assertEquals(
                TestStatus.PASSED, suite.childById(TapParser.SUITE + "#first").status());
        assertEquals(
                TestStatus.FAILED, suite.childById(TapParser.SUITE + "#second").status());
        assertEquals(3, suite.children().size());
        assertEquals(0, parse("okay then", "ok: true").children().size(), "prose is not a result");
    }

    /** node:test's real TAP (Node 24): suites skipped, the error and stack lifted out of the YAML. */
    @Test
    void nodeTestTapGivesTheErrorStackAndLocation() {
        TapParser p = new TapParser();
        TestNode root = TestNode.root();
        String[] lines = {
            "TAP version 13",
            "# Subtest: adds",
            "ok 1 - adds",
            "  ---",
            "  duration_ms: 0.4",
            "  type: 'test'",
            "  ...",
            "# Subtest: fails",
            "not ok 2 - fails",
            "  ---",
            "  duration_ms: 0.5",
            "  type: 'test'",
            "  location: '/p/a.test.js:4:1'",
            "  failureType: 'testCodeFailure'",
            "  error: |-",
            "    Expected values to be strictly equal:",
            "    ",
            "    2 !== 3",
            "    ",
            "  code: 'ERR_ASSERTION'",
            "  name: 'AssertionError'",
            "  stack: |-",
            "    TestContext.<anonymous> (/p/a.test.js:4:30)",
            "    Test.run (node:internal/test_runner/test:1325:25)",
            "  ...",
            "# Subtest: group",
            "    # Subtest: inner bad",
            "    not ok 1 - inner bad",
            "      ---",
            "      location: '/p/a.test.js:5:68'",
            "      error: 'boom'",
            "      ...",
            "    1..1",
            "not ok 3 - group",
            "  ---",
            "  type: 'suite'",
            "  error: '1 subtest failed'",
            "  ...",
            "1..3",
        };
        for (String l : lines) {
            for (ParsedSuite s : p.onLine(l, false)) {
                TestTreeBuilder.merge(root, s);
            }
        }
        root.rollUp();
        TestNode suite = root.childById(TapParser.SUITE);
        assertEquals(3, suite.children().size(), "adds, fails, group › inner bad — not group");
        TestNode fails = suite.childById(TapParser.SUITE + "#fails");
        assertEquals("Expected values to be strictly equal:\n\n2 !== 3", fails.failureMessage());
        assertTrue(fails.stackTrace().contains("at fails (/p/a.test.js:4:1)"), fails.stackTrace());
        assertTrue(fails.stackTrace().contains("at TestContext.<anonymous> (/p/a.test.js:4:30)"));
        assertEquals(
                "boom",
                suite.childById(TapParser.SUITE + "#group \u203a inner bad").failureMessage());
        assertEquals(2, root.tally().failed());
        assertTrue(p.nodeTest());
    }

    @Test
    void colourEscapesDoNotHideResults() {
        TestNode root = parse("\u001B[32mok 1 - green\u001B[0m");
        assertEquals(1, root.childById(TapParser.SUITE).children().size());
    }
}
