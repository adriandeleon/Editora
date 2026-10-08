package com.editora.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Each console classifier, line shape by line shape. */
class OutputStyleFormsTest {

    @Test
    void passthroughColoursNothing() {
        assertNull(OutputStyle.passthrough().styleClassFor("[ERROR] anything"));
        assertNull(OutputStyle.passthrough().styleClassFor(null));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "[ERROR] boom|log-error",
                "FATAL: out of memory|log-error",
                "[WARNING] deprecated|log-warn",
                "[INFO] compiling|log-info",
                "[DEBUG] resolving|log-debug",
                "TRACE entering|log-trace",
            })
    void theGenericConsoleColoursEverySeverity(String line, String style) {
        assertEquals(style, OutputStyle.console().styleClassFor(line));
    }

    @Test
    void theGenericConsoleLeavesProseAlone() {
        assertNull(OutputStyle.console().styleClassFor("Downloading from central"));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "[INFO] BUILD SUCCESS|maven-build-success",
                "[INFO] BUILD FAILURE  |maven-build-failure",
                "[ERROR] COMPILATION ERROR|log-error",
                "[WARNING] unchecked|log-warn",
                "[DEBUG] plugin realm|log-debug",
                "[TRACE] mojo|log-trace",
            })
    void mavenColoursProblemsAndTheResult(String line, String style) {
        assertEquals(style, OutputStyle.maven().styleClassFor(line));
    }

    @Test
    void mavenLeavesInfoNoiseAndProseUncoloured() {
        assertNull(OutputStyle.maven().styleClassFor("[INFO] Scanning for projects..."));
        assertNull(OutputStyle.maven().styleClassFor("Downloaded 3 artifacts"));
        assertNull(OutputStyle.maven().styleClassFor(null));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "build\tCompile\t2026-01-01T00:00:00Z ##[error]Process completed with exit code 1.|log-error",
                "build\tCompile\t2026-01-01T00:00:00Z ##[warning]Node 16 is deprecated|log-warn",
                "[ERROR] tests failed|log-error",
                "FATAL: cannot continue|log-error",
                "[WARNING] slow test|log-warn",
            })
    void ciLogsColourWorkflowCommandsAndProblems(String line, String style) {
        assertEquals(style, OutputStyle.ci().styleClassFor(line));
    }

    @Test
    void ciLogsDoNotPaintInfoDebugOrProse() {
        assertNull(OutputStyle.ci().styleClassFor("[INFO] step started"));
        assertNull(OutputStyle.ci().styleClassFor("[DEBUG] cache key"));
        assertNull(OutputStyle.ci().styleClassFor("TRACE details"));
        assertNull(OutputStyle.ci().styleClassFor("Run actions/checkout@v4"));
        assertNull(OutputStyle.ci().styleClassFor(null));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "diff --git a/x b/x|diff-header",
                "index 83db48f..bf269f4 100644|diff-header",
                "--- a/x|diff-header",
                "+++ b/x|diff-header",
                "@@ -1,3 +1,4 @@|diff-range",
                " + 1a2b3c4...5d6e7f8 main -> main (forced update)|git-output-forced",
                " * [new branch]      feature -> origin/feature|git-output-added",
                " * [new tag]         v1.0 -> v1.0|git-output-added",
                "+added line|diff-inserted",
                "+ 1a2b3c4 a plus that is not a forced update|diff-inserted",
                "-removed line|diff-deleted",
                "\tmodified:   src/Main.java|git-output-modified",
                "\ttypechange: link|git-output-modified",
                "\tnew file:   src/New.java|git-output-added",
                " create mode 100644 src/New.java|git-output-added",
                "\tdeleted:    old.txt|git-output-deleted",
                " delete mode 100644 old.txt|git-output-deleted",
                "\trenamed:    a.txt -> b.txt|git-output-renamed",
                "renamed from: a.txt|git-output-renamed",
                "renamed to: b.txt|git-output-renamed",
                "error: failed to push some refs|log-error",
                "warning: LF will be replaced by CRLF|log-warn",
            })
    void gitOutputIsColouredByItsStableMarkers(String line, String style) {
        assertEquals(style, OutputStyle.git().styleClassFor(line));
    }

    @Test
    void gitDiffstatLinesAreRecognised() {
        assertEquals("git-output-stat", OutputStyle.git().styleClassFor(" src/Main.java | 12 ++++----"));
        assertEquals("git-output-stat", OutputStyle.git().styleClassFor(" logo.png | Bin 0 -> 1234 bytes"));
        assertEquals("git-output-stat", OutputStyle.git().styleClassFor(" notes.txt | 0"));
        assertNull(OutputStyle.git().styleClassFor("a | b"), "a pipe in prose is not a diffstat");
    }

    @Test
    void gitProseIsLeftAlone() {
        assertNull(OutputStyle.git().styleClassFor("Your branch is up to date with 'origin/main'."));
        assertNull(OutputStyle.git().styleClassFor("modified: "), "a marker with nothing after it is not a file line");
        assertNull(OutputStyle.git().styleClassFor(null));
    }
}
