package com.editora.ui;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.http.HttpExchange;
import com.editora.http.HttpFile;
import com.editora.http.HttpFixtures;
import com.editora.http.HttpResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The words of the response view's empty and running states (U6, U7): how a request is named, the "Previous" row,
 * and the trust prompts' wait (U8) reaching the panel through {@link HttpAskingOps}.
 */
class HttpPanelStatesTest {

    private static String before;

    @BeforeAll
    static void english() {
        before = com.editora.i18n.Messages.current();
        com.editora.i18n.Messages.init("en");
    }

    @AfterAll
    static void restore() {
        com.editora.i18n.Messages.init(before);
    }

    @Test
    void aRequestIsNamedByItsTitleThenItsNameThenItsMethodAndUrl() {
        assertEquals("Get one user", HttpPanelStates.requestLabel("### Get one user", "getUser", "GET", "{{host}}/u"));
        assertEquals("Get one user", HttpPanelStates.requestLabel("  #### Get one user  ", null, "GET", "/u"));
        assertEquals("getUser", HttpPanelStates.requestLabel("###", "getUser", "GET", "/u"), "a bare separator");
        assertEquals("getUser", HttpPanelStates.requestLabel("GET /u", "getUser", "GET", "/u"), "not a separator");
        assertEquals("POST {{host}}/users", HttpPanelStates.requestLabel(null, " ", "POST", "{{host}}/users"));
        assertEquals("GET", HttpPanelStates.requestLabel(null, null, "GET", null));
        assertEquals("", HttpPanelStates.requestLabel(null, null, null, null));
    }

    @Test
    void theNamesComeFromTheParsedFile() {
        String text = "### List users\nGET /users\n\n### Get one user\n# @name getUser\nGET /users/42\n\n###\nGET /x\n";
        List<HttpFile.Request> requests = HttpFile.parse(text);
        String[] lines = text.split("\n", -1);
        List<String> labels = new ArrayList<>();
        for (HttpFile.Request r : requests) {
            labels.add(HttpPanelStates.requestLabel(lines[r.sectionStart()], r.name(), r.method(), r.url()));
        }
        assertEquals(List.of("List users", "Get one user", "GET /x"), labels);
        assertEquals(1, HttpEmptyStateFeed.indexAt(requests, 4), "the caret on the @name line");
        assertEquals(1, HttpEmptyStateFeed.indexAt(requests, 3), "…on the ### line");
        assertEquals(-1, HttpEmptyStateFeed.indexAt(requests, 99));
    }

    @Test
    void aRequestBeingSentIsNamedByItsTitleElseAsResolved() {
        assertEquals("Get one user", HttpPanelStates.runLabel("### Get one user", "getUser", "GET http://h/u"));
        assertEquals("getUser", HttpPanelStates.runLabel(null, "getUser", "GET http://h/u"));
        assertEquals("GET http://h/u", HttpPanelStates.runLabel("GET {{host}}/u", null, "GET http://h/u"));
        assertEquals("", HttpPanelStates.runLabel(null, null, null));
    }

    @Test
    void thePreviousRowSaysTheStatusTheTimeAndTheName() {
        HttpExchange ok = HttpFixtures.exchange(
                "GET http://h/users/42",
                "GET",
                "http://h/users/42",
                List.of(),
                "",
                HttpFixtures.text(200, List.of(), "{}", "application/json", 3, 2, null));
        HttpHistoryEntry named = new HttpHistoryEntry(ok, "getUser", 1, 60_151_000L, false, false, false);
        assertEquals("Previous: 200 OK · 16:42:31 · getUser", HttpPanelStates.previousLine(named, ZoneOffset.UTC));
        HttpHistoryEntry unnamed = new HttpHistoryEntry(ok, null, -1, 60_151_000L, false, false, false);
        assertEquals(
                "Previous: 200 OK · 16:42:31 · GET http://h/users/42",
                HttpPanelStates.previousLine(unnamed, ZoneOffset.UTC));
        HttpExchange refused = HttpFixtures.exchange(
                "GET http://h/", "GET", "http://h/", List.of(), "", HttpResult.failure("refused", List.of()));
        assertTrue(HttpPanelStates.previousLine(HttpHistoryEntry.of(refused), ZoneOffset.UTC)
                .startsWith("Previous: " + tr("httppanel.requestFailed") + " · "));
    }

    @Test
    void secondsAreNeverNegative() {
        assertEquals("1.5", HttpPanelStates.secondsSince(0, 1_500_000_000L));
        assertEquals("0.0", HttpPanelStates.secondsSince(5, 1));
    }

    @Test
    void theAskingOpsSayWhenAPromptMayShowAndWhenItIsAnswered() {
        List<String> said = new ArrayList<>();
        HttpClientCoordinator.WindowOps window = new HttpClientCoordinator.WindowOps() {
            @Override
            public void openTab(EditorBuffer buffer) {}

            @Override
            public void updateRunGating() {}

            @Override
            public boolean allowScripts(Path file) {
                said.add("asked scripts");
                return true;
            }

            @Override
            public boolean allowSecretReads(Path file, List<String> hosts, List<String> reads) {
                said.add("asked secrets");
                throw new IllegalStateException("closing");
            }
        };
        HttpAskingOps ops = new HttpAskingOps(window, asking -> said.add(asking ? "waiting" : "answered"));
        assertTrue(ops.allowScripts(Path.of("a.http")));
        try {
            ops.allowSecretReads(Path.of("a.http"), List.of(), List.of());
        } catch (IllegalStateException expected) {
            // a failed question is still "answered": the panel must not keep waiting
        }
        assertEquals(List.of("waiting", "asked scripts", "answered", "waiting", "asked secrets", "answered"), said);
    }

    @Test
    void theAskingOpsPassOnEveryOtherWindowService() throws Exception {
        // A WindowOps method the wrapper does not override would answer its default to the gates, not the window's.
        for (Method m : HttpClientCoordinator.WindowOps.class.getMethods()) {
            Method own = HttpAskingOps.class.getMethod(m.getName(), m.getParameterTypes());
            assertEquals(HttpAskingOps.class, own.getDeclaringClass(), "HttpAskingOps passes on " + m.getName());
        }
        assertNotNull(HttpAskingOps.class.getDeclaredConstructors()[0]);
        assertFalse(HttpClientCoordinator.WindowOps.class.getMethods().length == 0);
    }
}
