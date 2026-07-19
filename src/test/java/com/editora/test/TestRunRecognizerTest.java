package com.editora.test;

import java.util.List;

import com.editora.build.BuildTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-tool test-run recognition + the Go -json argv augmentation. */
class TestRunRecognizerTest {

    @Test
    void recognizesPerTool() {
        assertTrue(TestRunRecognizer.isTestRun(BuildTool.MAVEN, List.of("test")));
        assertTrue(TestRunRecognizer.isTestRun(BuildTool.MAVEN, List.of("verify")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.MAVEN, List.of("package")));

        assertTrue(TestRunRecognizer.isTestRun(BuildTool.GRADLE, List.of("test")));
        assertTrue(TestRunRecognizer.isTestRun(BuildTool.GRADLE, List.of("integrationTest")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.GRADLE, List.of("build")));

        assertTrue(TestRunRecognizer.isTestRun(BuildTool.NPM, List.of("run", "test")));
        assertTrue(TestRunRecognizer.isTestRun(BuildTool.NPM, List.of("run", "test:unit")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.NPM, List.of("run", "build")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.NPM, List.of("install")));

        assertTrue(TestRunRecognizer.isTestRun(BuildTool.CARGO, List.of("test")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.CARGO, List.of("build")));

        assertTrue(TestRunRecognizer.isTestRun(BuildTool.GO, List.of("test", "./...")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.GO, List.of("build", "./...")));

        assertFalse(TestRunRecognizer.isTestRun(BuildTool.MAVEN, List.of()));
    }

    @Test
    void augmentArgvInsertsJsonForGoOnly() {
        assertEquals(
                List.of("go", "test", "-json", "./..."),
                TestRunRecognizer.augmentArgv(BuildTool.GO, List.of("go", "test", "./...")));
        // idempotent
        List<String> already = List.of("go", "test", "-json", "./...");
        assertEquals(already, TestRunRecognizer.augmentArgv(BuildTool.GO, already));
        // other tools untouched
        assertEquals(List.of("mvn", "test"), TestRunRecognizer.augmentArgv(BuildTool.MAVEN, List.of("mvn", "test")));
    }
}
