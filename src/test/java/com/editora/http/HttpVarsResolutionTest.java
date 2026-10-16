package com.editora.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a reference resolves to, and — the part that used to be silent — what happens when it does not:
 * every unresolved {@code {{x}}} is still sent as {@code ""} but is recorded with the reason.
 */
class HttpVarsResolutionTest {

    private static final ZonedDateTime CLOCK = ZonedDateTime.of(2026, 6, 10, 9, 30, 0, 0, ZoneOffset.UTC);

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static String sub(String text, HttpVars.Scope scope, CapturedResponses captured, HttpVars.Unresolved u) {
        return HttpVars.substitute(text, scope, CLOCK, captured, null, u);
    }

    private static HttpResult ok(String body) {
        return HttpFixtures.text(200, List.of(), body, "application/json", 1, body.length(), null);
    }

    @Test
    void anUndefinedVariableIsSentEmptyAndReported() {
        HttpVars.Unresolved u = new HttpVars.Unresolved();
        String out = sub("Bearer {{tokn}} / {{ host }}", HttpVars.Scope.of(Map.of("token", "t")), null, u);
        assertEquals("Bearer  / ", out);
        assertEquals(List.of("{{tokn}}", "{{host}}"), List.copyOf(u.entries().keySet()));
        assertTrue(
                u.warnings().get(0).startsWith("{{tokn}} was sent as empty text"),
                u.warnings().get(0));
    }

    @Test
    void unknownDynamicVariablesAndMissingResponsesAreReported() {
        HttpVars.Unresolved u = new HttpVars.Unresolved();
        CapturedResponses session = new CapturedResponses();
        session.put("broken", HttpResult.failure("connection refused", List.of()));
        session.put("login", ok("{\"token\":\"abc\"}"));
        sub(
                "{{$random.name.firstName}} {{auth.response.body.$.token}} {{broken.response.body.$.x}}"
                        + " {{login.response.body.$.missing}} {{login.response.body.$.token}}",
                HttpVars.Scope.of(Map.of()),
                session,
                u);
        Map<String, String> e = u.entries();
        assertEquals(4, e.size(), e.toString());
        assertTrue(e.get("{{auth.response.body.$.token}}").contains("has not run"), e.toString());
        assertTrue(e.get("{{broken.response.body.$.x}}").contains("failed"), e.toString());
        assertTrue(e.get("{{login.response.body.$.missing}}").contains("not found"), e.toString());
    }

    @Test
    void aResolvedReferenceIsNotReported() {
        HttpVars.Unresolved u = new HttpVars.Unresolved();
        assertEquals("x=1", sub("x={{a}}", HttpVars.Scope.of(Map.of("a", "1")), null, u));
        assertTrue(u.isEmpty());
        // A variable defined as empty is resolved: it is what the user wrote.
        assertEquals("x=", sub("x={{a}}", HttpVars.Scope.of(Map.of("a", "")), null, u));
        assertTrue(u.isEmpty());
    }

    @Test
    void environmentValuesMayUseOtherEnvironmentValues() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("api", "{{base}}/v1");
        env.put("base", "https://{{host}}");
        env.put("host", "api.test");
        env.put("loop", "{{loop}}!");
        HttpVars.Scope scope = HttpVars.scope(env, List.of(), CLOCK, null, null);
        HttpVars.Unresolved u = new HttpVars.Unresolved();
        assertEquals("https://api.test/v1/users", sub("{{api}}/users", scope, null, u));
        assertTrue(u.isEmpty(), u.entries().toString());
        assertEquals("!", sub("{{loop}}", scope, null, u));
        assertTrue(u.entries().containsKey("{{loop}}"), "a self-reference is reported, not looped on");
    }

    @Test
    void aMissingReferenceInsideAVariableIsReportedWhereTheVariableIsUsed() {
        HttpVars.Scope scope = HttpVars.scope(
                Map.of(), List.<String[]>of(new String[] {"auth", "Bearer {{token}}"}), CLOCK, null, null);
        HttpVars.Unresolved u = new HttpVars.Unresolved();
        assertEquals("Bearer ", sub("{{auth}}", scope, null, u));
        assertEquals(List.of("{{token}}"), List.copyOf(u.entries().keySet()));
    }

    @Test
    void fileVariablesSeeResponsesAndTheDotenvBesideTheFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".env"), "TOKEN=from-dotenv\n");
        CapturedResponses session = new CapturedResponses();
        session.put("login", ok("{\"token\":\"abc\"}"));
        HttpVars.Scope scope = HttpVars.scope(
                Map.of(),
                List.<String[]>of(
                        new String[] {"secret", "{{$dotenv TOKEN}}"},
                        new String[] {"t", "{{login.response.body.$.token}}"}),
                CLOCK,
                session,
                dir);
        assertEquals("from-dotenv", scope.values().get("secret"));
        assertEquals("abc", scope.values().get("t"));
    }

    @Test
    void bracesAroundAVariableStayBraces() {
        assertEquals("{1}", HttpFixtures.substitute("{{{a}}}", Map.of("a", "1"), CLOCK));
    }

    @Test
    @Timeout(5)
    void pathologicalInputIsLinear() {
        String spaces = "{{" + " ".repeat(200_000);
        assertEquals(spaces, HttpFixtures.substitute(spaces, Map.of(), CLOCK));
        String opens = "{{ ".repeat(50_000);
        assertEquals(opens, HttpFixtures.substitute(opens, Map.of(), CLOCK));
    }
}
