package com.editora.maven;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MavenOutputStyleTest {

    @Test
    void nullAndBlankAreUncolored() {
        assertNull(MavenOutputStyle.styleClassFor(null));
        assertNull(MavenOutputStyle.styleClassFor(""));
        assertNull(MavenOutputStyle.styleClassFor("   "));
    }

    @Test
    void plainInfoLinesAreNotColored() {
        // Real ANSI Maven output leaves INFO at the default foreground; coloring it green would make
        // most of the console green, since INFO is by far the most common line.
        assertNull(MavenOutputStyle.styleClassFor("[INFO] Building demo-app 1.0.0"));
        assertNull(MavenOutputStyle.styleClassFor(
                "[INFO] ------------------------------------------------------------------------"));
    }

    @Test
    void warningsAndErrorsAreColored() {
        assertEquals("log-warn", MavenOutputStyle.styleClassFor("[WARNING] Some deprecation notice"));
        assertEquals(
                "log-error",
                MavenOutputStyle.styleClassFor(
                        "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin"));
    }

    @Test
    void debugAndTraceAreColoredMuted() {
        assertEquals("log-debug", MavenOutputStyle.styleClassFor("[DEBUG] Resolving artifact foo:bar:1.0"));
        assertEquals("log-trace", MavenOutputStyle.styleClassFor("[TRACE] entering method"));
    }

    @Test
    void buildResultIsDetectedUnderTheRealInfoPrefixFormat() {
        // This is the actual format Maven prints — always under [INFO], never bare.
        assertEquals("maven-build-success", MavenOutputStyle.styleClassFor("[INFO] BUILD SUCCESS"));
        assertEquals("maven-build-failure", MavenOutputStyle.styleClassFor("[INFO] BUILD FAILURE"));
    }

    @Test
    void bareBuildResultIsAlsoDetected() {
        assertEquals("maven-build-success", MavenOutputStyle.styleClassFor("BUILD SUCCESS"));
        assertEquals("maven-build-failure", MavenOutputStyle.styleClassFor("BUILD FAILURE"));
        // Tolerates surrounding whitespace.
        assertEquals("maven-build-success", MavenOutputStyle.styleClassFor("  BUILD SUCCESS  "));
    }

    @Test
    void unrecognizedLinesAreUncolored() {
        assertNull(MavenOutputStyle.styleClassFor("\tat com.example.Foo.bar(Foo.java:10)"));
        assertNull(MavenOutputStyle.styleClassFor("Downloading from central: https://repo.maven.apache.org/x.jar"));
    }
}
