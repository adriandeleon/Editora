package com.editora.run;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvVarsTest {

    @Test
    void parsesSimplePairs() {
        assertEquals(Map.of("FOO", "bar", "BAZ", "qux"), EnvVars.parse("FOO=bar BAZ=qux"));
    }

    @Test
    void quotedValueKeepsItsSpaces() {
        assertEquals(Map.of("GREETING", "hello world"), EnvVars.parse("GREETING=\"hello world\""));
    }

    @Test
    void valueMayContainEquals() {
        // Splitting on the FIRST '=' only — a base64/connection-string value survives.
        assertEquals(Map.of("TOKEN", "ab=cd=="), EnvVars.parse("TOKEN=ab=cd=="));
    }

    @Test
    void emptyValueIsAllowed() {
        assertEquals(Map.of("EMPTY", ""), EnvVars.parse("EMPTY="));
    }

    @Test
    void malformedTokensAreSkippedNotFatal() {
        // "novalue" has no '='; "=orphan" has an empty name. Neither should break the launch.
        assertEquals(Map.of("OK", "1"), EnvVars.parse("novalue =orphan OK=1"));
    }

    @Test
    void blankAndNullYieldEmptyMap() {
        assertTrue(EnvVars.parse("").isEmpty());
        assertTrue(EnvVars.parse("   ").isEmpty());
        assertTrue(EnvVars.parse(null).isEmpty());
    }

    @Test
    void insertionOrderIsPreserved() {
        assertEquals(
                List.of("A", "B", "C"), List.copyOf(EnvVars.parse("A=1 B=2 C=3").keySet()));
    }
}
