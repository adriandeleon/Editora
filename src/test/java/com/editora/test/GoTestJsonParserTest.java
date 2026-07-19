package com.editora.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Folding go test -json events into the tree, and the console humanization of -json lines. */
class GoTestJsonParserTest {

    @Test
    void foldsRunOutputPassIntoTree() {
        GoTestJsonParser p = new GoTestJsonParser();
        TestNode root = TestNode.root();
        feed(root, p, "{\"Action\":\"run\",\"Package\":\"ex/pkg\",\"Test\":\"TestA\"}");
        feed(root, p, "{\"Action\":\"output\",\"Package\":\"ex/pkg\",\"Test\":\"TestA\",\"Output\":\"some log\\n\"}");
        feed(root, p, "{\"Action\":\"pass\",\"Package\":\"ex/pkg\",\"Test\":\"TestA\",\"Elapsed\":0.02}");
        feed(root, p, "{\"Action\":\"run\",\"Package\":\"ex/pkg\",\"Test\":\"TestB\"}");
        feed(
                root,
                p,
                "{\"Action\":\"output\",\"Package\":\"ex/pkg\",\"Test\":\"TestB\",\"Output\":\"boom foo_test.go:9\\n\"}");
        feed(root, p, "{\"Action\":\"fail\",\"Package\":\"ex/pkg\",\"Test\":\"TestB\",\"Elapsed\":0.01}");

        TestNode suite = root.childById("ex/pkg");
        assertEquals(2, suite.children().size());
        TestNode a = suite.childById("ex/pkg#TestA");
        assertEquals(TestStatus.PASSED, a.status());
        assertEquals(20, a.durationMs());
        TestNode b = suite.childById("ex/pkg#TestB");
        assertEquals(TestStatus.FAILED, b.status());
        assertTrue(b.failureMessage().contains("foo_test.go:9"));
    }

    @Test
    void consoleLineDecodesOutputAndSuppressesBookkeeping() {
        GoTestJsonParser p = new GoTestJsonParser();
        assertEquals(
                "=== RUN   TestA",
                p.consoleLine("{\"Action\":\"output\",\"Test\":\"TestA\",\"Output\":\"=== RUN   TestA\\n\"}", false));
        assertNull(p.consoleLine("{\"Action\":\"pass\",\"Test\":\"TestA\",\"Elapsed\":0.1}", false));
        assertEquals("plain non-json", p.consoleLine("plain non-json", false));
    }

    private static void feed(TestNode root, GoTestJsonParser p, String line) {
        for (ParsedSuite s : p.onLine(line, false)) {
            TestTreeBuilder.merge(root, s);
        }
    }
}
