package com.editora.test;

import java.nio.file.Path;
import java.util.Map;

import com.editora.build.BuildTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JUnit XML parsing: statuses, timing, stack text, source hint, the resource-file path, and XXE hardening. */
class JUnitXmlParserTest {

    private final JUnitXmlParser parser = new JUnitXmlParser(BuildTool.MAVEN);

    @Test
    void parsesAllStatuses() throws Exception {
        Path file = Path.of(
                getClass().getResource("/test/TEST-com.example.SampleTest.xml").toURI());
        ParsedSuite suite = parser.parseReportFile(file);
        assertNotNull(suite);
        assertEquals("com.example.SampleTest", suite.suiteName());
        assertEquals(4, suite.tests().size());
        Map<String, ParsedTest> byName = new java.util.HashMap<>();
        suite.tests().forEach(t -> byName.put(t.methodName(), t));

        assertEquals(TestStatus.PASSED, byName.get("passes").status());
        assertEquals(10, byName.get("passes").durationMs());
        assertEquals("SampleTest.java", byName.get("passes").sourceFileHint());

        ParsedTest failed = byName.get("failsAssertion");
        assertEquals(TestStatus.FAILED, failed.status());
        assertEquals("org.opentest4j.AssertionFailedError", failed.failureType());
        assertTrue(failed.stackTrace().contains("SampleTest.java:42"));

        assertEquals(TestStatus.ERROR, byName.get("throws").status());
        assertEquals(TestStatus.SKIPPED, byName.get("disabled").status());
        assertEquals("hello from stdout", suite.suiteStdout());
    }

    /** Surefire's reruns: a pass after failures is flaky, a failure with reruns failed every try (review D16). */
    @Test
    void surefireRerunsAreCountedAndTheirTracesKept() throws Exception {
        String xml = "<testsuite name=\"com.x.T\"><testcase name=\"flaky\" classname=\"com.x.T\" time=\"0.1\">"
                + "<flakyFailure message=\"first try\" type=\"org.opentest4j.AssertionFailedError\">trace one</flakyFailure>"
                + "<flakyError message=\"second try\" type=\"java.lang.IllegalStateException\">trace two</flakyError>"
                + "</testcase><testcase name=\"hopeless\" classname=\"com.x.T\">"
                + "<failure message=\"still\" type=\"E\">final trace</failure>"
                + "<rerunFailure message=\"again\" type=\"E\">retry trace</rerunFailure>"
                + "</testcase><testcase name=\"plain\" classname=\"com.x.T\"/></testsuite>";
        ParsedSuite suite = parser.parse(xml);
        Map<String, ParsedTest> byName = new java.util.HashMap<>();
        suite.tests().forEach(t -> byName.put(t.methodName(), t));

        ParsedTest flaky = byName.get("flaky");
        assertEquals(TestStatus.PASSED, flaky.status());
        assertEquals(2, flaky.retries());
        assertNull(flaky.failureMessage(), "it passed in the end");
        assertTrue(
                flaky.stackTrace().contains("flakyFailure 1: org.opentest4j.AssertionFailedError: first try"),
                flaky.stackTrace());
        assertTrue(
                flaky.stackTrace().contains("trace one") && flaky.stackTrace().contains("trace two"),
                flaky.stackTrace());

        ParsedTest hopeless = byName.get("hopeless");
        assertEquals(TestStatus.FAILED, hopeless.status());
        assertEquals(1, hopeless.retries());
        assertEquals("still", hopeless.failureMessage());
        assertTrue(hopeless.stackTrace().startsWith("final trace"), hopeless.stackTrace());
        assertTrue(hopeless.stackTrace().contains("rerunFailure 1: E: again\nretry trace"), hopeless.stackTrace());

        assertEquals(0, byName.get("plain").retries());
        TestNode root = TestNode.root();
        TestTreeBuilder.merge(root, suite);
        TestNode leaf = root.childById("com.x.T").childById("com.x.T#flaky");
        assertTrue(leaf.isFlaky());
        assertEquals(2, leaf.retries());
        assertFalse(root.childById("com.x.T").childById("com.x.T#hopeless").isFlaky());
    }

    @Test
    void nonTestsuiteRootYieldsNull() throws Exception {
        assertNull(parser.parse("<other/>"));
    }

    @Test
    void unreadableFileYieldsNull() {
        assertNull(parser.parseReportFile(Path.of("/no/such/TEST-x.xml")));
    }

    @Test
    void doctypeIsRejected() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY x \"y\">]><testsuite name=\"t\"/>";
        assertThrows(Exception.class, () -> parser.parse(xxe));
    }
}
