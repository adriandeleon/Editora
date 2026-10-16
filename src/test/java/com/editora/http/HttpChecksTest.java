package com.editora.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HttpChecks}: what {@code @assert} and {@code @capture} accept and what they find in a response. */
class HttpChecksTest {

    private static final String JSON = "{\"id\":7,\"name\":\"Ada\",\"ok\":true,\"none\":null,\"price\":\"12.50\","
            + "\"tags\":[\"a\",\"b\"],\"items\":[{\"id\":1},{\"id\":2}],\"a b\":{\"c\":\"x\"}}";

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static HttpResult response(int status, String contentType, String body, String... headers) {
        List<String[]> h = new ArrayList<>();
        h.add(new String[] {"Content-Type", contentType});
        for (int i = 0; i + 1 < headers.length; i += 2) {
            h.add(new String[] {headers[i], headers[i + 1]});
        }
        return HttpFixtures.text(status, h, body, contentType, 42, body.length(), null);
    }

    private static HttpChecks.Check check(String text) {
        List<String> warnings = new ArrayList<>();
        HttpChecks.Check c = HttpChecks.parse(text, false, warnings);
        assertNotNull(c, text + " → " + warnings);
        return c;
    }

    private static String failure(String text, HttpResult r) {
        HttpChecks.Outcome o = HttpChecks.evaluate(List.of(check(text)), r);
        HttpTests.Test t = o.tests().get(0);
        assertEquals(text, t.name());
        return t.passed() ? null : t.message();
    }

    private static void passes(String text) {
        assertNull(failure(text, response(200, "application/json", JSON, "X-Count", "3")), text);
    }

    private static String fails(String text) {
        String why = failure(text, response(200, "application/json", JSON, "X-Count", "3"));
        assertNotNull(why, text + " should fail");
        return why;
    }

    @Test
    void statusTimeAndNumbers() {
        passes("status == 200");
        passes("status >= 200");
        passes("status < 300");
        passes("time < 1000");
        assertEquals("expected == 201, got 200", fails("status == 201"));
        passes("$.id == 7");
        passes("$.id == 7.0");
        passes("$.price == 12.5"); // numeric text compares as a number
        passes("$.price > 12");
        passes("$.items[1].id == 2");
        passes("header X-Count >= 3");
    }

    @Test
    void textBooleansNullAndQuotes() {
        passes("$.name == Ada");
        passes("$.name == \"Ada\"");
        passes("$.name != 'Bob'");
        passes("$.ok == true");
        passes("$.none == null");
        passes("$.price == \"12.50\"");
        assertEquals("expected == \"Bob\", got \"Ada\"", fails("$.name == Bob"));
        assertTrue(fails("$.name == null").contains("expected == null"));
        passes("$[\"a b\"].c == x");
        passes("$.items == [{\"id\":1},{\"id\":2}]");
    }

    @Test
    void containsMatchesAndExistence() {
        passes("$.tags contains a");
        passes("body contains \"Ada\"");
        passes("header content-type contains json"); // header names are case-insensitive
        passes("$.name matches ^A.a$");
        passes("$.none exists");
        passes("$.missing !exists");
        passes("header Location !exists");
        assertEquals("$.missing is not in the response", fails("$.missing exists"));
        assertEquals("$.missing is not in the response", fails("$.missing == 1"));
        assertEquals("header Location is not in the response", fails("header Location == /x"));
        assertTrue(fails("$.tags contains c").startsWith("expected to contain \"c\""));
        assertTrue(fails("$.name < 3").contains("is not a number"));
    }

    @Test
    void aBodyThatIsNotJsonMissesEveryPath() {
        String why = failure("$.id == 7", response(200, "text/plain", "not json"));
        assertEquals("$.id is not in the response", why);
        assertNull(failure("body == \"not json\"", response(200, "text/plain", "not json")));
    }

    @Test
    void aRunawayPatternIsStoppedNotLeftHolding() {
        String body = "a".repeat(30); // (a*)*\1b backtracks for about a minute here without the deadline
        String why = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(15),
                () -> failure("body matches (a*)*\\1b", response(200, "text/plain", body)));
        assertTrue(why.contains("was stopped"), why);
    }

    @Test
    void capturesStoreTextAndClearWhatIsMissing() {
        List<String> warnings = new ArrayList<>();
        List<HttpChecks.Check> checks = List.of(
                HttpChecks.parse("id = $.id", true, warnings),
                HttpChecks.parse("item = $.items[0]", true, warnings),
                HttpChecks.parse("count = header x-count", true, warnings),
                HttpChecks.parse("code = status", true, warnings),
                HttpChecks.parse("gone = $.nope", true, warnings),
                HttpChecks.parse("nil = $.none", true, warnings));
        assertTrue(warnings.isEmpty(), warnings.toString());
        HttpChecks.Outcome o = HttpChecks.evaluate(checks, response(200, "application/json", JSON, "X-Count", "3"));
        Map<String, String> c = o.captures();
        assertEquals("7", c.get("id"));
        assertEquals("{\"id\":1}", c.get("item"));
        assertEquals("3", c.get("count"));
        assertEquals("200", c.get("code"));
        assertTrue(c.containsKey("gone"));
        assertNull(c.get("gone"), "a capture that misses clears the variable");
        assertEquals("", c.get("nil"));
        assertTrue(o.tests().isEmpty(), "captures are not tests");
        assertEquals(1, o.warnings().size());
        assertTrue(
                o.warnings().get(0).contains("$.nope is not in the response"),
                o.warnings().get(0));
    }

    @Test
    void whatIsNotACheckSaysWhy() {
        assertProblem("", false, "nothing to check");
        assertProblem("status", false, "an operator is missing");
        assertProblem("status is 200", false, "\"is\" is not an operator");
        assertProblem("status ==", false, "== needs a value");
        assertProblem("$.x exists please", false, "unexpected text \"please\"");
        assertProblem("body matches (", false, "regular expression is not valid");
        assertProblem("header", false, "name the header");
        assertProblem("token $.x", true, "@capture name = subject");
        assertProblem("1bad = $.x", true, "\"1bad\" is not a variable name");
        assertProblem("t = time", true, "time cannot be captured");
        assertProblem("t = $.x extra", true, "unexpected text");
    }

    private static void assertProblem(String body, boolean capture, String expected) {
        List<String> warnings = new ArrayList<>();
        assertNull(HttpChecks.parse(body, capture, warnings), body);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(expected), warnings.get(0));
    }

    @Test
    void aValueIsALiteralNeverAVariable() {
        // "{{token}}" is compared as the text it is: a check never resolves, so it cannot read a secret.
        passes("$.name != {{name}}");
        assertFalse(check("$.name == {{name}}").expected().isEmpty());
    }
}
