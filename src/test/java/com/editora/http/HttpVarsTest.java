package com.editora.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Unit tests for the pure {@code {{variable}}} substitution. */
class HttpVarsTest {

    private static final LocalDateTime CLOCK = LocalDateTime.of(2026, 6, 10, 9, 30, 0);

    @Test
    void substitutesNamedVariables() {
        Map<String, String> vars = Map.of("host", "https://api.test", "token", "abc");
        assertEquals("https://api.test/users",
                HttpVars.substitute("{{host}}/users", vars, CLOCK));
        assertEquals("Bearer abc", HttpVars.substitute("Bearer {{token}}", vars, CLOCK));
    }

    @Test
    void unknownVariableBecomesEmpty() {
        assertEquals("/x", HttpVars.substitute("{{missing}}/x", Map.of(), CLOCK));
    }

    @Test
    void builtinTimestampUsesTheClock() {
        long expected = CLOCK.toEpochSecond(ZoneOffset.UTC);
        assertEquals(String.valueOf(expected), HttpVars.substitute("{{$timestamp}}", Map.of(), CLOCK));
    }

    @Test
    void builtinUuidIsAUuid() {
        String s = HttpVars.substitute("{{$uuid}}", Map.of(), CLOCK);
        assertEquals(36, s.length());
        assertTrue(s.matches("[0-9a-f-]{36}"));
    }

    @Test
    void resolveLayersFileVarsOverEnvAndChains() {
        Map<String, String> env = Map.of("host", "https://api.test");
        List<String[]> fileVars = List.of(
                new String[]{"base", "{{host}}/v1"},          // references an env var
                new String[]{"users", "{{base}}/users"});     // references an earlier @var
        Map<String, String> resolved = HttpVars.resolve(env, fileVars, CLOCK);
        assertEquals("https://api.test/v1", resolved.get("base"));
        assertEquals("https://api.test/v1/users", resolved.get("users"));
    }
}
