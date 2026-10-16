package com.editora.http;

import java.util.List;

import com.editora.http.HttpFile.Handler;
import com.editora.http.HttpFile.Parsed;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How {@link HttpFile} reads response handlers and the {@code @assert}/{@code @capture} directives. */
class HttpResponseHandlerParseTest {

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static Parsed first(String text) {
        return HttpFile.parseRequest(HttpFile.parse(text).get(0));
    }

    @Test
    void aOneLineInlineHandlerKeepsItsSourceAndPlace() {
        Parsed p = first("### a\nGET http://h/a\n\n> {% client.test(\"ok\", () => {}); %}\n");
        Handler h = p.handlers().get(0);
        assertEquals(" client.test(\"ok\", () => {}); ", h.source());
        assertNull(h.path());
        assertEquals(3, h.line(), "the line in the file, not in the request block");
        assertEquals(4, h.column(), "just after {%");
        assertTrue(h.closed());
    }

    @Test
    void aMultiLineHandlerKeepsItsLineBreaksSoLinesMatchTheFile() {
        String text = "GET http://h/a\n\n> {%\n  const a = 1;\n  client.log(a);\n%}\n";
        Handler h = first(text).handlers().get(0);
        assertEquals("\n  const a = 1;\n  client.log(a);\n", h.source());
        assertEquals(2, h.line());
        assertEquals(4, h.column());
    }

    @Test
    void anIndentedHandlerCountsItsIndentInTheColumn() {
        Handler h = first("GET http://h/a\n\n   > {% x %}\n").handlers().get(0);
        assertEquals(7, h.column());
        assertEquals(" x ", h.source());
    }

    @Test
    void anUnclosedHandlerIsReadButMarkedAndWarned() {
        Parsed p = first("GET http://h/a\n\n> {%\n  client.log(1);\n");
        assertFalse(p.handlers().get(0).closed());
        assertTrue(
                p.warnings().stream().anyMatch(w -> w.contains("no closing %}")),
                p.warnings().toString());
    }

    @Test
    void aHandlerFileIsAPathAndSeveralHandlersKeepTheirOrder() {
        Parsed p = first("POST http://h/a\n\n{\"a\":1}\n\n> ./check.js\n> {% client.log(2) %}\n>> out.json\n");
        assertEquals("{\"a\":1}", p.body());
        assertEquals(2, p.handlers().size());
        assertEquals("./check.js", p.handlers().get(0).path());
        assertTrue(p.handlers().get(0).isFile());
        assertEquals(" client.log(2) ", p.handlers().get(1).source());
        assertEquals(1, p.redirects().size());
    }

    @Test
    void aPreRequestScriptIsStillIgnoredWithAWarning() {
        Parsed p = first("< {% request.variables.set(\"a\", 1) %}\nGET http://h/a\n");
        assertTrue(p.handlers().isEmpty());
        assertTrue(
                p.warnings().stream().anyMatch(w -> w.contains("pre-request script")),
                p.warnings().toString());
    }

    @Test
    void assertAndCaptureLinesAboveTheRequestAreItsChecksInOrder() {
        Parsed p = first("### login\n# @assert status == 200\n// @capture token = $.auth.token\n"
                + "# @assert header Content-Type contains json\nPOST http://h/login\n");
        List<HttpChecks.Check> checks = p.checks();
        assertEquals(3, checks.size());
        assertEquals("status == 200", checks.get(0).text());
        assertEquals(HttpChecks.Subject.STATUS, checks.get(0).subject());
        assertEquals("token", checks.get(1).captureName());
        assertEquals("$.auth.token", checks.get(1).arg());
        assertEquals(HttpChecks.Subject.HEADER, checks.get(2).subject());
        assertEquals("Content-Type", checks.get(2).arg());
        assertTrue(p.warnings().isEmpty(), p.warnings().toString());
    }

    @Test
    void aCheckLineInTheBodyIsABodyCommentNotACheck() {
        Parsed p = first("POST http://h/a\n\n# @assert status == 200\nbody\n");
        assertTrue(p.checks().isEmpty());
        assertEquals("body", p.body());
    }

    @Test
    void anUnreadableCheckIsAWarningNamingIt() {
        Parsed p =
                first("# @assert stauts == 200\n# @capture = $.x\n# @assertion is not a directive\nGET http://h/a\n");
        assertTrue(p.checks().isEmpty());
        assertEquals(2, p.warnings().size(), p.warnings().toString());
        assertTrue(
                p.warnings().get(0).contains("@assert stauts == 200"),
                p.warnings().get(0));
        assertTrue(
                p.warnings().get(0).contains("\"stauts\" is not a subject"),
                p.warnings().get(0));
        assertTrue(p.warnings().get(1).contains("@capture"), p.warnings().get(1));
    }
}
