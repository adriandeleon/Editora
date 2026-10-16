package com.editora.http;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code \{{} escape (G5) and what a resolution records for the trust gate and the history mask (G2, G6): an
 * escaped reference is sent as a literal {@code {{} and read by nothing — not the resolver, not the gate, not the
 * shared scanner the editor marks use; a real resolution keeps the values it read from the environment, a dry one
 * never does; and response references are recorded however they are reached. {@code PATH} stands in for a secret.
 */
class HttpVarsEscapeTest {

    private static final String SECRET = System.getenv("PATH");
    private static final ZonedDateTime NOW = ZonedDateTime.now();

    private static String sub(String text, Map<String, String> env, HttpVars.Unresolved sink) {
        HttpVars.Scope scope = HttpVars.scope(env, List.of(), NOW, null, (Path) null);
        return HttpVars.substitute(text, scope, NOW, null, null, sink);
    }

    @Test
    void anEscapedReferenceIsSentAsALiteralAndReadsNothing() {
        HttpVars.Unresolved sink = new HttpVars.Unresolved();
        assertEquals(
                "x={{$env PATH}}&y={{name}}&z=v",
                sub("x=\\{{$env PATH}}&y=\\{{name}}&z={{name}}", Map.of("name", "v"), sink));
        assertEquals(List.of(), sink.secretReads(), "an escaped read is not a read");
        assertTrue(sink.isEmpty(), "nor an unresolved reference");
        assertFalse(HttpVars.referencePattern().matcher("\\{{$env PATH}}").find(), "the shared scanner skips it");
        assertEquals("{{a}} {{b}}", sub("\\{{a}} \\{{b}}", Map.of(), new HttpVars.Unresolved()), "outside a match too");
    }

    @Test
    void escapeAndUnescapeRoundTrip() {
        String text = "GET http://h.test/{{x}}?q={{$env PATH}}";
        String escaped = HttpVars.escape(text);
        assertEquals("GET http://h.test/\\{{x}}?q=\\{{$env PATH}}", escaped);
        assertEquals(text, sub(escaped, Map.of("x", "nope"), new HttpVars.Unresolved()), "sent exactly as imported");
        assertEquals(text, HttpVars.unescape(escaped));
        assertEquals("no braces", HttpVars.escape("no braces"));
    }

    @Test
    void aRealResolutionKeepsTheValuesItReadAndADryOneNever() {
        Map<String, String> env = Map.of("a", "{{$env PATH}}", "b", "{{a}}");
        HttpVars.Unresolved real = new HttpVars.Unresolved();
        assertEquals("k=" + SECRET, sub("k={{b}}", env, real));
        assertEquals(List.of(SECRET), real.secretValues(), "through env-to-env indirection");

        HttpVars.Unresolved dry = HttpVars.Unresolved.dry();
        HttpVars.Scope scope = HttpVars.scope(env, List.of(), NOW, null, null, true);
        HttpVars.substitute("k={{b}}", scope, NOW, null, null, dry);
        assertEquals(List.of("$env PATH"), dry.secretReads());
        assertEquals(List.of(), dry.secretValues(), "a dry resolution never reads a value");

        HttpVars.Unresolved unset = new HttpVars.Unresolved();
        sub("k={{$env EDITORA_SURELY_UNSET_VARIABLE_42}}", Map.of(), unset);
        assertEquals(List.of(), unset.secretValues(), "an unset variable has no value to mask");
    }

    @Test
    void responseAndDynamicReferencesAreRecordedThroughVariables() {
        HttpVars.Unresolved sink = HttpVars.Unresolved.dry();
        HttpVars.Scope scope = HttpVars.scope(
                Map.of(),
                List.<String[]>of(new String[] {"base", "{{login.response.body.$.next}}"}),
                NOW,
                null,
                null,
                true);
        HttpVars.substitute("{{base}}/x?n={{$uuid}}", scope, NOW, null, null, sink);
        assertEquals(List.of("login.response.body.$.next"), sink.responseReads());
        assertEquals(List.of("$uuid"), sink.dynamicReads());
    }
}
