package com.editora.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Maven console output styler (moved from the old {@code MavenOutputStyle} onto {@link OutputStyle#maven()}). */
class OutputStyleTest {

    private static String maven(String line) {
        return OutputStyle.maven().styleClassFor(line);
    }

    @Test
    void nullAndBlankAndInfoAreNotColored() {
        assertNull(maven(null));
        assertNull(maven(""));
        assertNull(maven("   "));
        assertNull(maven("[INFO] Building demo-app 1.0.0"));
    }

    @Test
    void warningsAndErrorsAndTraceLevelsAreColored() {
        assertEquals("log-warn", maven("[WARNING] Some deprecation notice"));
        assertEquals("log-error", maven("[ERROR] Failed to execute goal"));
        assertEquals("log-debug", maven("[DEBUG] Resolving artifact foo:bar:1.0"));
        assertEquals("log-trace", maven("[TRACE] entering method"));
    }

    @Test
    void buildResultIsColoredBySuffixNotLevel() {
        assertEquals("maven-build-success", maven("[INFO] BUILD SUCCESS"));
        assertEquals("maven-build-failure", maven("[INFO] BUILD FAILURE"));
        assertEquals("maven-build-success", maven("BUILD SUCCESS"));
        assertEquals("maven-build-failure", maven("BUILD FAILURE"));
        assertEquals("maven-build-success", maven("  BUILD SUCCESS  "));
    }

    @Test
    void passthroughColorsNothing() {
        assertNull(OutputStyle.passthrough().styleClassFor("[ERROR] anything"));
    }
}
