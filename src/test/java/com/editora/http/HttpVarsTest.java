package com.editora.http;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure {@code {{variable}}} substitution. */
class HttpVarsTest {

    private static final ZonedDateTime CLOCK = ZonedDateTime.of(2026, 6, 10, 9, 30, 0, 0, ZoneOffset.UTC);

    @Test
    void substitutesNamedVariables() {
        Map<String, String> vars = Map.of("host", "https://api.test", "token", "abc");
        assertEquals("https://api.test/users", HttpFixtures.substitute("{{host}}/users", vars, CLOCK));
        assertEquals("Bearer abc", HttpFixtures.substitute("Bearer {{token}}", vars, CLOCK));
    }

    @Test
    void unknownVariableBecomesEmpty() {
        assertEquals("/x", HttpFixtures.substitute("{{missing}}/x", Map.of(), CLOCK));
    }

    @Test
    void builtinTimestampUsesTheClock() {
        long expected = CLOCK.toEpochSecond();
        assertEquals(String.valueOf(expected), HttpFixtures.substitute("{{$timestamp}}", Map.of(), CLOCK));
    }

    @Test
    void builtinUuidIsAUuid() {
        String s = HttpFixtures.substitute("{{$uuid}}", Map.of(), CLOCK);
        assertEquals(36, s.length());
        assertTrue(s.matches("[0-9a-f-]{36}"));
    }

    @Test
    void resolveLayersFileVarsOverEnvAndChains() {
        Map<String, String> env = Map.of("host", "https://api.test");
        List<String[]> fileVars = List.of(
                new String[] {"base", "{{host}}/v1"}, // references an env var
                new String[] {"users", "{{base}}/users"}); // references an earlier @var
        Map<String, String> resolved =
                HttpVars.scope(env, fileVars, CLOCK, null, null).values();
        assertEquals("https://api.test/v1", resolved.get("base"));
        assertEquals("https://api.test/v1/users", resolved.get("users"));
    }
}
