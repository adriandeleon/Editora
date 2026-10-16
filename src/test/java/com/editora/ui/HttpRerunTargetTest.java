package com.editora.ui;

import java.util.List;

import com.editora.http.HttpFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
    void aNamedEntryNeverFallsBackToARequestWithAnotherName() {
        // Lead's call (2026-10-15): the name is gone, so Run takes the caret's request and says so.
        var reqs = parse("### Old name now new\nGET http://h/same\n");
        assertEquals(-1, HttpRerunTarget.find(reqs, "Old name", "GET", "http://h/same", 3));
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

    private static int findLike(List<HttpFile.Request> now, List<HttpFile.Request> then, int index, String sentUrl) {
        HttpFile.Parsed ran = HttpFile.parseRequest(then.get(index));
        return HttpRerunTarget.find(
                now, ran.name(), ran.method(), sentUrl, ran.url(), HttpRerunTarget.fingerprint(ran), index);
    }

    @Test
    void twoUnnamedRequestsWithOneUrlAreToldApartByTheirTextAfterAnInsert() {
        String two = "###\nPOST {{h}}/items\n\n{\"n\":\"A\"}\n\n###\nPOST {{h}}/items\n\n{\"n\":\"B\"}\n";
        var then = parse(two);
        var now = parse("###\nGET {{h}}/health\n\n" + two);
        assertEquals(2, findLike(now, then, 1, "http://x/items"), "B, though A now sits at B's old position");
    }

    @Test
    void anUnnamedEntryWhoseRequestIsGoneMatchesNoGenericRequest() {
        var then = parse("###\nDELETE {{h}}/users/1\n");
        var now = parse("###\nDELETE {{h}}/{{kind}}/{{id}}\n");
        assertEquals(-1, findLike(now, then, 0, "http://x/users/1"));
    }

    @Test
    void aNamedEntryDoesNotTakeAnotherNamesUrl() {
        var reqs = parse("### signin\nPOST {{h}}/v2/login\n\n### other\nPOST {{h}}/{{p}}\n");
        assertEquals(-1, HttpRerunTarget.find(reqs, "login", "POST", "http://x/login", 0));
    }

    @Test
    void aReferenceBetweenSlashesStandsForOneSegment() {
        assertTrue(HttpRerunTarget.templateMatches("{{h}}/users/{{id}}", "http://x/users/1"));
        assertFalse(HttpRerunTarget.templateMatches("{{h}}/users/{{id}}", "http://x/users/1/delete"));
        assertTrue(HttpRerunTarget.templateMatches("{{h}}/users/{{id}}/x", "http://x/users/7/x"));
    }

    @Test
    void urlsTheRunRewroteStillMatchTheirTemplate() {
        assertTrue(HttpRerunTarget.templateMatches(
                "http://h/search?q=hello world&city=München", "http://h/search?q=hello%20world&city=M%C3%BCnchen"));
        assertTrue(HttpRerunTarget.templateMatches("example.com/x", "http://example.com/x"), "scheme-less");
        assertTrue(HttpRerunTarget.templateMatches("/path?a={{v}}", "http://h.test/path?a=1"), "origin form");
    }

    @Test
    void aLongUrlAndManyReferencesMatchAtOnce() {
        StringBuilder template = new StringBuilder("{{h}}/a?");
        for (int i = 0; i < 6; i++) {
            template.append("{{p").append(i).append("}}&");
        }
        template.append("z");
        String url = "http://h/a?" + "x&".repeat(5_000); // no z: every split fails, which a backtracking regex tried
        assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5),
                () -> assertFalse(HttpRerunTarget.templateMatches(template.toString(), url)));
        var reqs = parse("###\nGET " + template + "\n");
        assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5),
                () -> assertEquals(-1, HttpRerunTarget.find(reqs, null, "GET", url, 0)));
    }
}
