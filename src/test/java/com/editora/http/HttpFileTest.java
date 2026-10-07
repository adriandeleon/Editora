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
        // A # @name comment is the name later requests refer to; the ### label is only the fallback.
        assertEquals("createUser", rs.get(1).name());
    }

    @Test
    void bareSeparatorDoesNotBlockNameComment() {
        List<Request> rs = HttpFile.parse("""
                ###
                # @name create
                POST https://example.com/items

                ###
                // @name fetch
                GET https://example.com/items/{{create.response.body.$.id}}
                """);
        assertEquals(2, rs.size());
        assertEquals("create", rs.get(0).name());
        assertEquals("fetch", rs.get(1).name());
        assertEquals("create", HttpFile.parseRequest(rs.get(0)).name());
    }

    @Test
    void nameCommentWinsOverSeparatorLabel() {
        List<Request> rs = HttpFile.parse("""
                ### Create item
                # @name create
                POST https://example.com/items
                """);
        assertEquals(1, rs.size());
        // As in the JetBrains HTTP Client: the title does not stop `{{create.response…}}` from resolving.
        assertEquals("create", rs.get(0).name());
    }

    @Test
    void namesSecondRequestAfterBareSeparator() {
        List<Request> rs = HttpFile.parse("""
                GET https://example.com/ping

                ###
                # @name create
                POST https://example.com/items

                ###
                GET https://example.com/items
                """);
        assertEquals(3, rs.size());
        assertNull(rs.get(0).name());
        assertEquals("create", rs.get(1).name());
        assertNull(rs.get(2).name());
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
