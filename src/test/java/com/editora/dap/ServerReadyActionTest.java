package com.editora.dap;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** launch.json's {@code serverReadyAction}, read and matched as VS Code does. */
class ServerReadyActionTest {

    private static ServerReadyAction action(Object... keyValues) throws ServerReadyAction.Invalid {
        Map<String, Object> a = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            a.put((String) keyValues[i], keyValues[i + 1]);
        }
        ServerReadyAction r = ServerReadyAction.of(Map.of("type", "node", "serverReadyAction", a), "/work");
        assertNotNull(r);
        return r;
    }

    private static String uri(ServerReadyAction a, String... chunks) {
        for (String c : chunks) {
            ServerReadyAction.Result r = a.feed(c);
            if (r != null) {
                assertEquals("", r.problem());
                return r.uri();
            }
        }
        return null;
    }

    @Test
    void noActionIsNone() throws Exception {
        assertNull(ServerReadyAction.of(Map.of("type", "node"), "/work"));
    }

    @Test
    void theDefaultPatternTakesAPortOrAnAddress() throws Exception {
        assertEquals("http://localhost:3000", uri(action(), "Server listening on port 3000\n"));
        assertEquals("https://localhost:5001", uri(action(), "info: Now listening on: https://localhost:5001\n"));
        assertEquals(ServerReadyAction.Kind.OPEN_EXTERNALLY, action().kind());
    }

    @Test
    void itFiresOnceAndOnlyOnAMatch() throws Exception {
        ServerReadyAction a = action();
        assertNull(a.feed("starting…\n"));
        assertNotNull(a.feed("Listening on port 8080\n")); // any case, as VS Code's "i"
        assertNull(a.feed("listening on port 9090\n"));
    }

    @Test
    void aLineSplitAcrossOutputEventsIsJoined() throws Exception {
        assertEquals("http://localhost:4000", uri(action(), "App listen", "ing on por", "t 4000\nmore\n"));
    }

    @Test
    void aLineWithoutItsNewlineIsLookedAtToo() throws Exception {
        assertEquals("http://localhost:8000", uri(action(), "listening on port 8000"));
    }

    @Test
    void coloursAreTakenOut() throws Exception {
        ServerReadyAction vite = action("pattern", "Local:\\s+(https?://\\S+)");
        assertEquals(
                "http://localhost:5173/",
                uri(
                        vite,
                        "  \u001B[32m➜\u001B[39m  \u001B[1mLocal\u001B[22m:   \u001B[36mhttp://localhost:\u001B[1m5173\u001B[22m/\u001B[39m\n"));
    }

    @Test
    void uriFormatIsFilledWithWhatWasCaught() throws Exception {
        ServerReadyAction a = action("pattern", "port (\\d+)", "uriFormat", "http://127.0.0.1:%s/admin");
        assertEquals("http://127.0.0.1:8080/admin", uri(a, "port 8080\n"));
    }

    @Test
    void aPatternWithoutAGroupUsesUriFormatAsItIs() throws Exception {
        ServerReadyAction a = action("pattern", "Started Application", "uriFormat", "http://localhost:8080");
        assertEquals("http://localhost:8080", uri(a, "Started Application in 2.1 seconds\n"));
        ServerReadyAction none = action("pattern", "ready");
        ServerReadyAction.Result r = none.feed("ready\n");
        assertNotNull(r);
        assertNull(r.uri());
        assertFalse(r.problem().isEmpty());
        ServerReadyAction percent = action("pattern", "ready", "uriFormat", "http://localhost:%s");
        assertNull(percent.feed("ready\n").uri());
    }

    @Test
    void theActionsAreRead() throws Exception {
        ServerReadyAction chrome =
                action("action", "debugWithChrome", "webRoot", "/work/web", "killOnServerStop", true);
        assertEquals(ServerReadyAction.Kind.DEBUG_WITH_CHROME, chrome.kind());
        assertTrue(chrome.killOnServerStop());
        Map<String, Object> launch = chrome.browserLaunch("http://localhost:3000");
        assertEquals("pwa-chrome", launch.get("type"));
        assertEquals("launch", launch.get("request"));
        assertEquals("http://localhost:3000", launch.get("url"));
        assertEquals("/work/web", launch.get("webRoot"));

        ServerReadyAction edge = action("action", "debugWithEdge");
        assertEquals("pwa-msedge", edge.browserLaunch("http://x").get("type"));
        assertEquals("/work", edge.browserLaunch("http://x").get("webRoot"));
        assertFalse(edge.killOnServerStop());

        ServerReadyAction named = action("action", "startDebugging", "name", "Launch Chrome");
        assertEquals(ServerReadyAction.Kind.START_DEBUGGING, named.kind());
        assertEquals("Launch Chrome", named.name());
        assertNull(named.config());

        ServerReadyAction inline =
                action("action", "startDebugging", "config", Map.of("type", "chrome", "request", "launch", "url", "x"));
        assertEquals("chrome", inline.config().get("type"));
    }

    @Test
    void whatCannotBeFollowedSaysWhy() {
        assertThrows(ServerReadyAction.Invalid.class, () -> action("action", "openInBrowser"));
        assertThrows(ServerReadyAction.Invalid.class, () -> action("pattern", "listening on ("));
        assertThrows(ServerReadyAction.Invalid.class, () -> action("uriFormat", "%s:%s"));
        assertThrows(ServerReadyAction.Invalid.class, () -> action("action", "startDebugging"));
    }

    @Test
    void theFieldsStillDoNotReachTheAdapter() {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "type",
                "node",
                "request",
                "launch",
                "program",
                "a.js",
                "postDebugTask",
                "down",
                "serverReadyAction",
                Map.of("action", "openExternally")));
        Map<String, Object> sent = LaunchRoute.of(body, Map.of()).request();
        assertFalse(sent.containsKey("serverReadyAction"));
        assertFalse(sent.containsKey("postDebugTask"));
    }
}
