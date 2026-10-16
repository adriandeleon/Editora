package com.editora.http;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session variables a response sets ({@code @capture}, {@code client.global.set}): where they sit among the
 * other variables, that they are never resolved, and when they are cleared.
 */
class HttpSessionVariablesTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-15T10:00:00Z");

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static String resolve(
            String text,
            Map<String, String> env,
            List<String[]> fileVars,
            CapturedResponses s,
            HttpVars.Unresolved sink) {
        HttpVars.Scope scope = HttpVars.scope(env, fileVars, NOW, s, null);
        return HttpVars.substitute(text, scope, NOW, s, null, sink);
    }

    @Test
    void aSessionVariableIsAboveTheEnvironmentAndBelowTheFile() {
        CapturedResponses s = new CapturedResponses();
        s.setGlobal("token", "from-response");
        Map<String, String> env = Map.of("token", "from-env", "auth", "Bearer {{token}}");
        assertEquals("from-response", resolve("{{token}}", env, List.of(), s, null));
        assertEquals(
                "Bearer from-response",
                resolve("{{auth}}", env, List.of(), s, null),
                "an environment value that uses it sees the session value");
        assertEquals(
                "from-file",
                resolve("{{token}}", env, List.<String[]>of(new String[] {"token", "from-file"}), s, null));
        assertEquals(
                "x-from-response",
                resolve("{{t2}}", env, List.<String[]>of(new String[] {"t2", "x-{{token}}"}), s, null));
    }

    @Test
    void aSessionValueIsInsertedAsIsNeverResolved() {
        CapturedResponses s = new CapturedResponses();
        s.setGlobal("evil", "{{$processEnv HOME}} and {{other}} and \\{{kept}}");
        HttpVars.Unresolved sink = new HttpVars.Unresolved();
        assertEquals(
                "{{$processEnv HOME}} and {{other}} and \\{{kept}}",
                resolve("{{evil}}", Map.of("other", "o"), List.of(), s, sink));
        assertTrue(sink.secretReads().isEmpty(), "a response cannot make a request read a secret");
        assertTrue(sink.isEmpty(), sink.entries().toString());
    }

    @Test
    void sessionVariablesAreClearedWithTheSession() {
        CapturedResponses s = new CapturedResponses();
        s.resetUnlessEnvironment("dev");
        s.setGlobal("a", "1");
        s.resetUnlessEnvironment("dev");
        assertEquals(Map.of("a", "1"), s.globals());
        s.resetUnlessEnvironment("prod");
        assertTrue(s.globals().isEmpty(), "a token from dev is not sent to prod");
        s.setGlobal("b", "2");
        s.clear();
        assertTrue(s.globals().isEmpty());
    }

    @Test
    void changesApplyInOrder() {
        CapturedResponses s = new CapturedResponses();
        s.setGlobal("keep", "k");
        List.of(
                        new ResponseScripts.GlobalChange(ResponseScripts.GlobalChange.SET, "a", "1"),
                        new ResponseScripts.GlobalChange(ResponseScripts.GlobalChange.CLEAR_ALL, null, null),
                        new ResponseScripts.GlobalChange(ResponseScripts.GlobalChange.SET, "b", "2"),
                        new ResponseScripts.GlobalChange(ResponseScripts.GlobalChange.SET, "c", "3"),
                        new ResponseScripts.GlobalChange(ResponseScripts.GlobalChange.CLEAR, "c", null))
                .forEach(c -> c.applyTo(s));
        assertEquals(Map.of("b", "2"), s.globals());
    }
}
