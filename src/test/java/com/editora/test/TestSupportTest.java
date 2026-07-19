package com.editora.test;

import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Small pure helpers: source-file hints, report dirs, and parser/file-based classification. */
class TestSupportTest {

    @Test
    void sourceFileHint() {
        assertEquals("FooTest.java", TestSourceLocator.fileHint("com.x.FooTest", BuildTool.MAVEN));
        // A @Nested test class Outer$Inner lives in the enclosing class's source file.
        assertEquals("Outer.java", TestSourceLocator.fileHint("com.x.Outer$Inner", BuildTool.GRADLE));
        assertNull(TestSourceLocator.fileHint("ex/pkg", BuildTool.GO));
        assertNull(TestSourceLocator.fileHint("", BuildTool.MAVEN));
        assertEquals("Bar", TestSourceLocator.simpleName("a.b.Bar"));
    }

    @Test
    void reportDirs() {
        Path root = Path.of("/proj");
        List<Path> maven = JvmReportDirs.reportDirs(BuildTool.MAVEN, root);
        assertTrue(maven.contains(root.resolve("target").resolve("surefire-reports")));
        assertTrue(maven.contains(root.resolve("target").resolve("failsafe-reports")));
        assertEquals(
                List.of(root.resolve("build").resolve("test-results").resolve("test")),
                JvmReportDirs.reportDirs(BuildTool.GRADLE, root));
        assertTrue(JvmReportDirs.reportDirs(BuildTool.GO, root).isEmpty());
        assertTrue(JvmReportDirs.isReportDirName(BuildTool.MAVEN, "surefire-reports"));
    }

    @Test
    void parserSelectionAndFileBased() {
        assertTrue(TestResultParsers.forTool(BuildTool.MAVEN) instanceof JUnitXmlParser);
        assertTrue(TestResultParsers.forTool(BuildTool.GO) instanceof GoTestJsonParser);
        assertTrue(TestResultParsers.forTool(BuildTool.CARGO) instanceof CargoTestParser);
        assertTrue(TestResultParsers.forTool(BuildTool.NPM) instanceof TapParser);
        assertTrue(TestResultParsers.isFileBased(BuildTool.MAVEN));
        assertTrue(TestResultParsers.isFileBased(BuildTool.GRADLE));
        assertFalse(TestResultParsers.isFileBased(BuildTool.GO));
    }
}
