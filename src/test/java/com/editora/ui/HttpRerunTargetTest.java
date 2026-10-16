package com.editora.ui;

import java.util.List;

import com.editora.http.HttpFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which request Run re-sends for a history entry: by name, then method + URL template, never by position (U2). */
class HttpRerunTargetTest {

    private static List<HttpFile.Request> parse(String text) {
        return HttpFile.parse(text);
    }

    @Test
    void aNamedRequestIsFoundWhereverItMoved() {
        var reqs = parse(
                "### Danger\nDELETE http://h/danger\n\n### Users\nGET http://h/users\n\n### Missing\nGET http://h/missing\n");
        assertEquals(2, HttpRerunTarget.find(reqs, "Missing", "GET", "http://h/missing", 1));
    }

    @Test
    void anUnnamedRequestIsFoundByMethodAndTemplate() {
        var reqs = parse("POST {{host}}/items\n\n###\nGET {{host}}/one\n\n###\nGET {{host}}/items/{{id}}\n");
        assertEquals(2, HttpRerunTarget.find(reqs, null, "GET", "http://other.test/items/7", 0));
        assertEquals(-1, HttpRerunTarget.find(reqs, null, "DELETE", "http://other.test/items/7", 0), "method differs");
    }

    @Test
    void theOldPositionOnlyBreaksATie() {
        var reqs = parse("GET {{h}}/a\n\n###\nGET {{h}}/a\n\n###\nGET {{h}}/a\n");
        assertEquals(1, HttpRerunTarget.find(reqs, null, "GET", "http://x/a", 1));
        assertEquals(2, HttpRerunTarget.find(reqs, null, "GET", "http://x/a", 5));
    }

    @Test
    void aRequestThatIsGoneIsNotFound() {
        var reqs = parse("### Kept\nGET http://h/kept\n");
        assertEquals(-1, HttpRerunTarget.find(reqs, "Gone", "GET", "http://h/gone", 0));
        assertEquals(-1, HttpRerunTarget.find(List.of(), "Gone", "GET", "http://h/gone", 0));
    }

    @Test
    void aRenamedRequestWithTheSameUrlIsStillFound() {
        var reqs = parse("### Old name now new\nGET http://h/same\n");
        assertEquals(0, HttpRerunTarget.find(reqs, "Old name", "GET", "http://h/same", 3));
    }

    @Test
    void templatesMatchAnyValueForEachReferenceButNotOtherLiterals() {
        assertTrue(HttpRerunTarget.templateMatches("{{host}}/users/{{id}}?x=1", "http://a/users/42?x=1"));
        assertTrue(HttpRerunTarget.templateMatches("http://h/plain", " http://h/plain "));
        assertFalse(HttpRerunTarget.templateMatches("{{host}}/users/{{id}}?x=1", "http://a/users/42?x=2"));
        assertFalse(HttpRerunTarget.templateMatches("{{host}}/a", "http://a/b"));
        assertTrue(HttpRerunTarget.templateMatches("{{host}}/a.b", "http://h/a.b"), "literal dots are literal");
        assertFalse(HttpRerunTarget.templateMatches("{{host}}/a.b", "http://h/axb"));
    }
}
