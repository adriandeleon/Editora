package com.editora.test;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** node:test's spec reporter, as Node 24.18.1 prints it on a pipe (captured from a real run). */
class NodeSpecParserTest {

    private static final String[] NODE24 = {
        "✔ adds (0.949469ms)",
        "✖ fails (1.089708ms)",
        "▶ group",
        "  ✔ inner ok (0.191075ms)",
        "  ✖ inner bad (0.133788ms)",
        "✖ group (0.69532ms)",
        "﹣ later (0.1ms) # SKIP",
        "ℹ tests 5",
        "ℹ suites 1",
        "ℹ pass 2",
        "ℹ fail 2",
        "",
        "✖ failing tests:",
        "",
        "test at a.test.js:4:1",
        "✖ fails (1.089708ms)",
        "  AssertionError [ERR_ASSERTION]: Expected values to be strictly equal:",
        "  ",
        "  2 !== 3",
        "  ",
        "      at TestContext.<anonymous> (/p/a.test.js:4:30)",
        "      at Test.run (node:internal/test_runner/test:1325:25) {",
        "    generatedMessage: true,",
        "    code: 'ERR_ASSERTION',",
        "  }",
        "",
        "test at a.test.js:5:68",
        "✖ inner bad (0.133788ms)",
        "  Error: boom",
        "      at TestContext.<anonymous> (/p/a.test.js:5:98)",
    };

    private static TestNode parse(String... lines) {
        NodeSpecParser p = new NodeSpecParser();
        TestNode root = TestNode.root();
        for (String l : lines) {
            for (ParsedSuite s : p.onLine(l, false)) {
                TestTreeBuilder.merge(root, s);
            }
        }
        for (ParsedSuite s : p.onExit(1)) {
            TestTreeBuilder.merge(root, s);
        }
        root.rollUp();
        return root;
    }

    @Test
    void recognizesTheSpecReporterAndNotOtherOutput() {
        assertTrue(NodeSpecParser.looksLikeSpec(List.of("> x", "✔ adds (0.9ms)")));
        assertTrue(NodeSpecParser.looksLikeSpec(List.of("\u001B[32m✔ adds \u001B[90m(0.9ms)\u001B[39m")));
        assertTrue(NodeSpecParser.looksLikeSpec(List.of("ℹ tests 4")));
        assertFalse(NodeSpecParser.looksLikeSpec(List.of("PASS src/a.test.js", "  ✓ works (3 ms)")));
        assertFalse(NodeSpecParser.looksLikeSpec(List.of("▶ group")), "a suite line alone is not decisive");
    }

    @Test
    void testsGetRowsSuitesDoNotAndFailuresGetTheirDetail() {
        TestNode suite = parse(NODE24).childById(TapParser.SUITE);
        assertEquals(5, suite.children().size(), suite.children().toString());
        assertEquals(
                TestStatus.PASSED, suite.childById(TapParser.SUITE + "#adds").status());
        assertEquals(
                TestStatus.SKIPPED, suite.childById(TapParser.SUITE + "#later").status());
        assertEquals(
                TestStatus.PASSED,
                suite.childById(TapParser.SUITE + "#group › inner ok").status());
        TestNode fails = suite.childById(TapParser.SUITE + "#fails");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals(
                "AssertionError [ERR_ASSERTION]: Expected values to be strictly equal:\n\n2 !== 3",
                fails.failureMessage());
        assertTrue(fails.stackTrace().startsWith("    at fails (a.test.js:4)"), fails.stackTrace());
        assertTrue(fails.stackTrace().contains("at TestContext.<anonymous> (/p/a.test.js:4:30)"));
        TestNode bad = suite.childById(TapParser.SUITE + "#group › inner bad");
        assertEquals("Error: boom", bad.failureMessage());
        assertTrue(bad.stackTrace().contains("(a.test.js:5)"));
    }

    @Test
    void anOlderNodesInlineDetailIsReadToo() {
        TestNode suite = parse(
                        "✖ fails (1ms)", "  AssertionError: 1 == 2", "      at t (/p/x.test.js:3:9)", "✔ next (1ms)")
                .childById(TapParser.SUITE);
        TestNode fails = suite.childById(TapParser.SUITE + "#fails");
        assertEquals("AssertionError: 1 == 2", fails.failureMessage());
        assertTrue(fails.stackTrace().contains("/p/x.test.js:3:9"));
    }
}
