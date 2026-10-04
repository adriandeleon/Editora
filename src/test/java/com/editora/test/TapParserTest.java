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
        assertEquals(4, suite.children().size());
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
}
