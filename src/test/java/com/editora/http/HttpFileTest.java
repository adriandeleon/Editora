package com.editora.http;

import java.util.List;

import com.editora.http.HttpFile.Request;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure .http parser. */
class HttpFileTest {

    private static final String SAMPLE = """
            @host = https://api.example.com
            @token = abc

            ### Get users
            GET {{host}}/users
            Authorization: Bearer {{token}}

            ### Create user
            # @name createUser
            POST {{host}}/users
            Content-Type: application/json

            {
              "name": "Ada"
            }
            """;

    @Test
    void parsesTwoRequestsWithMethodsUrlsAndNames() {
        List<Request> rs = HttpFile.parse(SAMPLE);
        assertEquals(2, rs.size());
        assertEquals("GET", rs.get(0).method());
        assertEquals("{{host}}/users", rs.get(0).url());
        assertEquals("Get users", rs.get(0).name());
        assertEquals("POST", rs.get(1).method());
        assertEquals("{{host}}/users", rs.get(1).url());
        // The ### separator label is the display name; a # @name comment is only a fallback.
        assertEquals("Create user", rs.get(1).name());
    }

    @Test
    void requestStartLineIsTheMethodLineNotTheSeparator() {
        Request first = HttpFile.parse(SAMPLE).get(0);
        // lines: 0 @host,1 @token,2 blank,3 ### Get users,4 GET ...
        assertEquals(4, first.startLine());
    }

    @Test
    void requestIndexAtMapsAnyLineInTheBlock() {
        // The Authorization header (line 5) belongs to request 0; the body brace of request 1 to request 1.
        assertEquals(0, HttpFile.requestIndexAt(SAMPLE, 5));
        assertEquals(1, HttpFile.requestIndexAt(SAMPLE, 12)); // POST line region
        assertEquals(-1, HttpFile.requestIndexAt(SAMPLE, 2)); // a blank/preamble line
    }

    @Test
    void fileVariablesCollectsAtDeclarations() {
        assertEquals("@host = https://api.example.com\n@token = abc", HttpFile.fileVariables(SAMPLE));
    }

    @Test
    void extractPrependsVariablesToTheChosenRequest() {
        String extracted = HttpFile.extract(SAMPLE, 0);
        assertTrue(extracted.startsWith("@host = https://api.example.com\n@token = abc\n\n"));
        assertTrue(extracted.contains("GET {{host}}/users"));
        assertTrue(extracted.contains("Authorization: Bearer {{token}}"));
        // The other request's body is NOT included.
        assertTrue(!extracted.contains("POST"));
    }

    @Test
    void extractOutOfRangeIsNull() {
        assertNull(HttpFile.extract(SAMPLE, 5));
        assertNull(HttpFile.extract("", 0));
    }

    @Test
    void bareUrlDefaultsToGetAndDropsHttpVersion() {
        List<Request> rs = HttpFile.parse("https://x.test/a HTTP/1.1\n");
        assertEquals(1, rs.size());
        assertEquals("GET", rs.get(0).method());
        assertEquals("https://x.test/a", rs.get(0).url());
    }

    @Test
    void firstRequestNeedsNoLeadingSeparator() {
        List<Request> rs = HttpFile.parse("GET https://x.test/a\n\n###\nGET https://x.test/b\n");
        assertEquals(2, rs.size());
        assertEquals(0, rs.get(0).startLine());
    }

    @Test
    void emptyOrCommentOnlyYieldsNoRequests() {
        assertTrue(HttpFile.parse("").isEmpty());
        assertTrue(HttpFile.parse("# just a comment\n// another\n").isEmpty());
    }
}
