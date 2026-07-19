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
}
