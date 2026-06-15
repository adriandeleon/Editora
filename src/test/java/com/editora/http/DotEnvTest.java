package com.editora.http;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the {@code .env} reader. */
class DotEnvTest {

    @Test
    void parsesKeyValuePairsStrippingQuotesCommentsAndExport() {
        Map<String, String> env = DotEnv.parse("""
                # a comment
                SECRET=plain
                QUOTED="with spaces"
                SINGLE='single'
                export EXPORTED=yes

                EQUALS=a=b=c
                """);
        assertEquals("plain", env.get("SECRET"));
        assertEquals("with spaces", env.get("QUOTED"));
        assertEquals("single", env.get("SINGLE"));
        assertEquals("yes", env.get("EXPORTED"));
        assertEquals("a=b=c", env.get("EQUALS"));
    }

    @Test
    void blankOrNullYieldsEmpty() {
        assertTrue(DotEnv.parse("").isEmpty());
        assertTrue(DotEnv.parse(null).isEmpty());
    }
}
