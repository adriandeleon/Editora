package com.editora.ui;

import java.util.List;

import com.editora.build.BuildTool;
import com.editora.config.RunConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a before-launch step runs for a configuration's text, and what its failure status quotes. Pure. */
class BeforeLaunchStepArgvTest {

    private static RunConfiguration with(String beforeLaunch) {
        return new RunConfiguration("Demo", "java", "", "com.example.App", "", "", "", "", "", beforeLaunch);
    }

    @Test
    void aPlainCommandIsTokenizedAsTyped() {
        assertEquals(
                List.of("mvn", "-q", "compile"),
                BeforeLaunchStep.argvFor(with("mvn -q compile"), tool -> List.of("never")));
        assertEquals(List.of(), BeforeLaunchStep.argvFor(with(""), tool -> List.of("never")));
        assertEquals(List.of(), BeforeLaunchStep.argvFor(with(null), tool -> List.of("never")));
    }

    /** {@code maven:compile} runs the Maven the tasks tree runs: wrapper, override or PATH command (review D3). */
    @Test
    void aSymbolicStepRunsThroughTheToolsExecutable() {
        assertEquals(
                List.of("./mvnw", "-q", "compile"),
                BeforeLaunchStep.argvFor(
                        with("maven:compile"), tool -> tool == BuildTool.MAVEN ? List.of("./mvnw") : null));
        assertEquals(
                List.of("/opt/mvn/bin/mvn", "-s", "s.xml", "-q", "compile", "resources:resources"),
                BeforeLaunchStep.argvFor(
                        with("maven:compile resources:resources"), tool -> List.of("/opt/mvn/bin/mvn", "-s", "s.xml")));
    }

    /** A refused executable (trust declined) means nothing runs — never a quiet fallback to PATH. */
    @Test
    void aRefusedToolRunsNothing() {
        assertNull(BeforeLaunchStep.argvFor(with("maven:compile"), tool -> null));
    }

    /** Maven's first {@code [ERROR]} line says what went wrong; its last only says where to read more (review D4). */
    @Test
    void theFailureDetailPrefersTheFirstErrorLine() {
        assertEquals(
                "[ERROR] Failed to execute goal … compile: Fatal error compiling",
                BeforeLaunchStep.failureDetail(
                        "[ERROR] Failed to execute goal … compile: Fatal error compiling ", "[ERROR] -> [Help 1]", 1));
        assertEquals("BUILD FAILED in 2s", BeforeLaunchStep.failureDetail(null, "  BUILD FAILED in 2s\n", 1));
        assertEquals("exit 137", BeforeLaunchStep.failureDetail("", " ", 137));
    }

    @Test
    void anErrorLineIsRecognisedForMavenGradleAndJavac() {
        assertTrue(BeforeLaunchStep.isErrorLine("[ERROR] Could not find artifact x"));
        assertTrue(BeforeLaunchStep.isErrorLine("e: file:///p/A.kt:3:1 Unresolved reference"));
        assertTrue(BeforeLaunchStep.isErrorLine("/p/src/main/java/A.java:3: error: ';' expected"));
        assertTrue(BeforeLaunchStep.isErrorLine("error: release version 25 not supported"));
        assertFalse(BeforeLaunchStep.isErrorLine("[INFO] BUILD SUCCESS"));
        assertFalse(BeforeLaunchStep.isErrorLine("[WARNING] deprecated"));
        assertFalse(BeforeLaunchStep.isErrorLine(null));
    }
}
