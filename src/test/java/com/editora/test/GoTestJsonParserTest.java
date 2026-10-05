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

    /** Go 1.24+: the compiler output arrives as build-output events and the package fail names the build. */
    @Test
    void aPackageThatDoesNotCompileBecomesAnErrorLeafCarryingTheCompilerOutput() {
        GoTestJsonParser p = new GoTestJsonParser();
        TestNode root = TestNode.root();
        feed(
                root,
                p,
                "{\"ImportPath\":\"ex/q [ex/q.test]\",\"Action\":\"build-output\",\"Output\":\"# ex/q [ex/q.test]\\n\"}");
        feed(
                root,
                p,
                "{\"ImportPath\":\"ex/q [ex/q.test]\",\"Action\":\"build-output\","
                        + "\"Output\":\"./q_test.go:5:2: undefined: nope\\n\"}");
        feed(root, p, "{\"ImportPath\":\"ex/q [ex/q.test]\",\"Action\":\"build-fail\"}");
        feed(root, p, "{\"Action\":\"run\",\"Package\":\"ex/p\",\"Test\":\"TestParse\"}");
        feed(root, p, "{\"Action\":\"pass\",\"Package\":\"ex/p\",\"Test\":\"TestParse\",\"Elapsed\":0}");
        feed(root, p, "{\"Action\":\"output\",\"Package\":\"ex/p\",\"Output\":\"ok  \\tex/p\\t0.002s\\n\"}");
        feed(root, p, "{\"Action\":\"pass\",\"Package\":\"ex/p\",\"Elapsed\":0.002}");
        feed(root, p, "{\"Action\":\"start\",\"Package\":\"ex/q\"}");
        feed(root, p, "{\"Action\":\"output\",\"Package\":\"ex/q\",\"Output\":\"FAIL\\tex/q [build failed]\\n\"}");
        feed(root, p, "{\"Action\":\"fail\",\"Package\":\"ex/q\",\"Elapsed\":0,\"FailedBuild\":\"ex/q [ex/q.test]\"}");

        assertEquals(1, root.childById("ex/p").children().size(), "a passing package gets no extra leaf");
        TestNode leaf = root.childById("ex/q").childById("ex/q#" + GoTestJsonParser.BUILD_FAILED);
        assertEquals(TestStatus.ERROR, leaf.status());
        assertTrue(leaf.failureMessage().contains("./q_test.go:5:2: undefined: nope"), leaf.failureMessage());
        assertTrue(root.tally().anyFailed(), "the run is no longer all-green");
    }

    /** Go < 1.24 prints the compiler text on stderr; the package still fails with no test event. */
    @Test
    void aPackageFailWithoutFailedBuildStillBecomesAnErrorLeaf() {
        GoTestJsonParser p = new GoTestJsonParser();
        TestNode root = TestNode.root();
        feed(root, p, "{\"Action\":\"output\",\"Package\":\"ex/q\",\"Output\":\"FAIL\\tex/q [build failed]\\n\"}");
        feed(root, p, "{\"Action\":\"fail\",\"Package\":\"ex/q\",\"Elapsed\":0}");
        assertEquals(
                TestStatus.ERROR,
                root.childById("ex/q")
                        .childById("ex/q#" + GoTestJsonParser.BUILD_FAILED)
                        .status());
    }

    /** TestMain exiting 1 after its tests passed: the package fails although every test leaf is green. */
    @Test
    void aPackageThatFailsAfterItsTestsPassedGetsAPackageFailedLeaf() {
        GoTestJsonParser p = new GoTestJsonParser();
        TestNode root = TestNode.root();
        feed(root, p, "{\"Action\":\"run\",\"Package\":\"ex/p\",\"Test\":\"TestA\"}");
        feed(root, p, "{\"Action\":\"pass\",\"Package\":\"ex/p\",\"Test\":\"TestA\",\"Elapsed\":0}");
        feed(root, p, "{\"Action\":\"output\",\"Package\":\"ex/p\",\"Output\":\"teardown: db still open\\n\"}");
        feed(root, p, "{\"Action\":\"fail\",\"Package\":\"ex/p\",\"Elapsed\":0.004}");

        TestNode leaf = root.childById("ex/p").childById("ex/p#" + GoTestJsonParser.PACKAGE_FAILED);
        assertEquals(TestStatus.ERROR, leaf.status());
        assertTrue(leaf.failureMessage().contains("teardown: db still open"));
        assertEquals(
                TestStatus.PASSED,
                root.childById("ex/p").childById("ex/p#TestA").status());
    }

    /** The binary dies inside a test: no per-test event follows, so the package fail settles it. */
    @Test
    void aTestStillRunningWhenItsPackageFailsIsSettledAsAnError() {
        GoTestJsonParser p = new GoTestJsonParser();
        TestNode root = TestNode.root();
        feed(root, p, "{\"Action\":\"run\",\"Package\":\"ex/p\",\"Test\":\"TestLoad\"}");
        feed(
                root,
                p,
                "{\"Action\":\"output\",\"Package\":\"ex/p\",\"Test\":\"TestLoad\",\"Output\":\"load fixture: no such file\\n\"}");
        feed(root, p, "{\"Action\":\"output\",\"Package\":\"ex/p\",\"Output\":\"FAIL\\tex/p\\t0.004s\\n\"}");
        feed(root, p, "{\"Action\":\"fail\",\"Package\":\"ex/p\",\"Elapsed\":0.004}");

        TestNode suite = root.childById("ex/p");
        assertEquals(1, suite.children().size(), "the dead test explains the failure — no synthetic leaf");
        TestNode load = suite.childById("ex/p#TestLoad");
        assertEquals(TestStatus.ERROR, load.status());
        assertTrue(load.failureMessage().contains("load fixture: no such file"));
    }

    /** Control: an ordinary failed test already explains its package's fail event. */
    @Test
    void aPackageFailExplainedByAFailedTestAddsNothing() {
        GoTestJsonParser p = new GoTestJsonParser();
        TestNode root = TestNode.root();
        feed(root, p, "{\"Action\":\"run\",\"Package\":\"ex/p\",\"Test\":\"TestBoom\"}");
        feed(root, p, "{\"Action\":\"fail\",\"Package\":\"ex/p\",\"Test\":\"TestBoom\",\"Elapsed\":0}");
        feed(root, p, "{\"Action\":\"fail\",\"Package\":\"ex/p\",\"Elapsed\":0.004}");
        assertEquals(1, root.childById("ex/p").children().size());
        assertEquals(
                TestStatus.FAILED,
                root.childById("ex/p").childById("ex/p#TestBoom").status());
    }

    @Test
    void consoleLineShowsCompilerOutputOfABuildFailure() {
        GoTestJsonParser p = new GoTestJsonParser();
        assertEquals(
                "./q_test.go:5:2: undefined: nope",
                p.consoleLine(
                        "{\"ImportPath\":\"ex/q [ex/q.test]\",\"Action\":\"build-output\","
                                + "\"Output\":\"./q_test.go:5:2: undefined: nope\\n\"}",
                        false));
        assertNull(p.consoleLine("{\"ImportPath\":\"ex/q [ex/q.test]\",\"Action\":\"build-fail\"}", false));
    }

    private static void feed(TestNode root, GoTestJsonParser p, String line) {
        for (ParsedSuite s : p.onLine(line, false)) {
            TestTreeBuilder.merge(root, s);
        }
    }
}
