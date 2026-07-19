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

    @Test
    void singleTestTaskPerTool() {
        // Maven: simple class name + #method (matches Surefire's -Dtest), + failIfNoTests=false for reactors.
        assertEquals(
                List.of("test", "-Dtest=FooTest#bar", "-DfailIfNoTests=false"),
                TestRunRecognizer.singleTestTask(BuildTool.MAVEN, "com.x.FooTest", "bar"));
        assertEquals(
                List.of("test", "-Dtest=FooTest", "-DfailIfNoTests=false"),
                TestRunRecognizer.singleTestTask(BuildTool.MAVEN, "com.x.FooTest", null));
        // Gradle: FQN.method / FQN.
        assertEquals(
                List.of("test", "--tests", "com.x.FooTest.bar"),
                TestRunRecognizer.singleTestTask(BuildTool.GRADLE, "com.x.FooTest", "bar"));
        assertEquals(
                List.of("test", "--tests", "com.x.FooTest"),
                TestRunRecognizer.singleTestTask(BuildTool.GRADLE, "com.x.FooTest", null));
        // Non-JVM tools have no per-test filter → empty (caller does nothing / a full run).
        assertTrue(TestRunRecognizer.singleTestTask(BuildTool.GO, "x", "y").isEmpty());
        assertTrue(TestRunRecognizer.singleTestTask(BuildTool.NPM, "x", "y").isEmpty());
        // The produced Maven/Gradle task still registers as a test run (flows through the hook).
        assertTrue(TestRunRecognizer.isTestRun(
                BuildTool.MAVEN, TestRunRecognizer.singleTestTask(BuildTool.MAVEN, "com.x.FooTest", "bar")));
        assertTrue(TestRunRecognizer.isTestRun(
                BuildTool.GRADLE, TestRunRecognizer.singleTestTask(BuildTool.GRADLE, "com.x.FooTest", "bar")));
    }
}
