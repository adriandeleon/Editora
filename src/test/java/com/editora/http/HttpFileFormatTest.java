package com.editora.http;

import java.util.List;

import com.editora.http.HttpFile.Parsed;
import com.editora.http.HttpFile.Request;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JetBrains .http format details the parser used to get wrong (review findings A4, A6, A13–A15, A19). */
class HttpFileFormatTest {

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static Parsed only(String text) {
        List<Request> rs = HttpFile.parse(text);
        assertEquals(1, rs.size(), text);
        return HttpFile.parseRequest(rs.get(0));
    }

    // --- A4: body comments, directives only above the request ---------------------------------------------

    @Test
    void commentLinesInsideABodyAreNotSent() {
        Parsed p = only("POST http://h\nContent-Type: application/json\n\n{\n  # a comment\n  \"a\": 1,\n"
                + "  // another\n  \"b\": 2\n}\n");
        assertEquals("{\n  \"a\": 1,\n  \"b\": 2\n}", p.body());
    }

    @Test
    void aDirectiveInsideABodyIsNotADirective() {
        Request r = HttpFile.parse("POST http://h\nContent-Type: text/x-sh\n\n# @no-redirect\necho hi\n")
                .get(0);
        assertFalse(r.directives().noRedirect());
        assertEquals("echo hi", HttpFile.parseRequest(r).body());
    }

    // --- A6: timeout units -----------------------------------------------------------------------------------

    @Test
    void timeoutsTakeJetBrainsUnits() {
        HttpFile.Directives d = HttpFile.parse("# @timeout 2 m\n# @connection-timeout 500 ms\nGET http://h\n")
                .get(0)
                .directives();
        assertEquals(120_000, d.timeoutMillis());
        assertEquals(500, d.connectionTimeoutMillis());
        assertEquals(5_000, HttpFile.parseDurationMillis("5 s"));
        assertEquals(600_000, HttpFile.parseDurationMillis("600"));
        assertEquals(250, HttpFile.parseDurationMillis("250ms"));
    }

    @Test
    void anUnreadableTimeoutWarnsAndKeepsTheDefault() {
        Request r = HttpFile.parse("# @timeout soon\nGET http://h\n").get(0);
        assertEquals(0, r.directives().timeoutMillis());
        Parsed p = HttpFile.parseRequest(r);
        assertTrue(
                p.warnings().stream().anyMatch(w -> w.contains("@timeout") && w.contains("soon")),
                p.warnings().toString());
    }

    // --- A13: declarations in effect per request -------------------------------------------------------------

    @Test
    void declarationsApplyFromWhereTheyAreWritten() {
        List<Request> rs =
                HttpFile.parse("@id = 1\n\n### one\nGET http://h/{{id}}\n\n### two\n@id = 2\nGET http://h/{{id}}\n");
        assertEquals(1, rs.get(0).variables().size());
        assertEquals("1", rs.get(0).variables().get(0)[1]);
        assertEquals(2, rs.get(1).variables().size());
        assertEquals("2", rs.get(1).variables().get(1)[1], "the redeclaration applies from its own request on");
    }

    @Test
    void aBodyLineThatLooksLikeADeclarationIsBodyText() {
        String text =
                "### a\nPOST http://h\nContent-Type: text/plain\n\n@user = mallory\n\n### b\nGET http://h/{{user}}\n";
        List<Request> rs = HttpFile.parse(text);
        assertTrue(rs.get(1).variables().isEmpty());
        assertTrue(HttpFile.fileVariablePairs(text).isEmpty());
        assertEquals("@user = mallory", HttpFile.parseRequest(rs.get(0)).body());
    }

    // --- A14: the caret anywhere in a section ------------------------------------------------------------------

    @Test
    void theTitleNameAndDirectiveLinesBelongToTheirRequest() {
        String text =
                "@h = x\n\n### Login\n# @name login\n# @timeout 5\nPOST http://h/login\n\n{}\n\n\n### Next\nGET http://h\n";
        int[] expected = {-1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1};
        for (int line = 0; line < expected.length; line++) {
            assertEquals(expected[line], HttpFile.requestIndexAt(text, line), "line " + line);
        }
    }

    // --- A15 + version: the request line ------------------------------------------------------------------------

    @Test
    void methodsInAnyCaseTabsAndCustomMethods() {
        assertEquals("GET", only("get http://h/a\n").method());
        assertEquals("http://h/a", only("get http://h/a\n").url());
        assertEquals("http://h/a", only("GET\thttp://h/a\n").url());
        assertEquals("PROPFIND", only("PROPFIND http://h/dav\n").method());
        assertEquals("QUERY", only("query http://h/q\n").method());
        // Method-less targets are still GET.
        assertEquals("GET", only("{{host}}/a\n").method());
        assertEquals("localhost:8080/a", only("localhost:8080/a HTTP/1.1\n").url());
    }

    @Test
    void graphqlWebsocketAndGrpcAreRecognizedAndNotSendable() {
        for (String kind : List.of("GRAPHQL", "WEBSOCKET", "GRPC")) {
            Parsed p = only(kind + " http://h/x\n");
            assertEquals(kind, p.method());
            assertEquals("http://h/x", p.url());
            assertNotNull(p.error(), kind);
        }
        assertNull(only("GET http://h/x\n").error());
    }

    @Test
    void theVersionIsParsedAndNeverPartOfTheUrl() {
        Parsed split = only("GET http://h/api\n    ?a=1\n    &b=2 HTTP/1.1\nAccept: x\n");
        assertEquals("http://h/api?a=1&b=2", split.url());
        assertEquals("HTTP/1.1", split.httpVersion());
        assertEquals("HTTP/2", only("GET https://h HTTP/2\n").httpVersion());
        assertEquals("HTTP/2", only("GET https://h HTTP/2.0\n").httpVersion());
        assertEquals(
                "HTTP/2 (Prior Knowledge)",
                only("GET http://h HTTP/2 (Prior Knowledge)\n").httpVersion());
        assertNull(only("GET http://h\n").httpVersion());
    }

    @Test
    void indentedHeadersAreHeadersAndIndentedValuesContinueTheHeader() {
        Parsed p = only("GET http://h/api\n  Accept: application/json\n  X-A: 1\n");
        assertEquals("http://h/api", p.url());
        assertEquals(2, p.headers().size());
        Parsed folded = only("GET http://h\nX-Long: part1\n  part2\nAccept: x\n");
        assertEquals("part1 part2", folded.headers().get(0)[1]);
        assertEquals(2, folded.headers().size());
        assertTrue(folded.warnings().isEmpty(), folded.warnings().toString());
    }

    // --- A19: scripts ------------------------------------------------------------------------------------------

    @Test
    void aPreRequestScriptIsSkippedNotTakenForTheRequestLine() {
        String text = "### with script\n< {%\n  request.variables.set(\"x\", \"1\");\n%}\nPOST http://h/a\n\nbody\n";
        Request r = HttpFile.parse(text).get(0);
        assertEquals("POST", r.method());
        assertEquals("http://h/a", r.url());
        assertEquals(4, r.startLine());
        Parsed p = HttpFile.parseRequest(r);
        assertEquals("body", p.body());
        assertTrue(
                p.warnings().stream().anyMatch(w -> w.contains("pre-request script")),
                p.warnings().toString());
        assertEquals("http://h/a", only("< ./prepare.js\nGET http://h/a\n").url());
        assertEquals(
                "GET",
                only("< {% request.variables.set(\"a\", 1) %}\nGET http://h/a\n")
                        .method());
    }

    @Test
    void aHandlerRightAfterTheRequestIsNotAMissingBlankLine() {
        Parsed p = only("GET http://h/a\n> {%\n  client.global.set(\"t\", response.body.token);\n%}\n>> ./out.json\n");
        assertEquals("", p.body());
        assertEquals(1, p.redirects().size(), "a redirect after a multi-line handler is still read");
        assertEquals(1, p.warnings().size(), p.warnings().toString());
        assertTrue(
                p.warnings().get(0).contains("response handler"), p.warnings().toString());
    }

    @Test
    void aBodyLineStartingWithAngleBracketButNoSpaceIsBody() {
        assertEquals(
                ">not a handler\n=>arrow",
                only("POST http://h\n\n>not a handler\n=>arrow\n").body());
    }
}
