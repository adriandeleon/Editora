package com.editora.test;

import java.nio.file.Path;
import java.util.Map;

import com.editora.build.BuildTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
