package com.editora.http;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The curl flags, quoting rules and truncated commands {@code CurlImportTest} does not reach. */
class CurlImportFormsTest {

    @Test
    void userAgentCookieAndUrlFlags() {
        assertEquals(
                "GET https://example.com/a\nUser-Agent: probe/1.0\nCookie: sid=1; theme=dark\n",
                CurlImport.toHttpRequest("curl -A 'probe/1.0' -b 'sid=1; theme=dark' --url https://example.com/a"));
        assertEquals(
                "GET https://example.com/b\nUser-Agent: ua\nCookie: c=1\n",
                CurlImport.toHttpRequest("curl --user-agent ua --cookie c=1 https://example.com/b"));
    }

    @Test
    void longFormFlagsMatchTheShortOnes() {
        assertEquals(
                "PUT https://example.com/c\nAccept: text/plain\nAuthorization: " + HttpAuth.basic("ada", "pw")
                        + "\nContent-Type: application/x-www-form-urlencoded\n\na=1\n",
                CurlImport.toHttpRequest(
                        "curl --request PUT --header 'Accept: text/plain' --user ada:pw --data a=1 https://example.com/c"));
    }

    @Test
    void severalDataFlagsAreJoinedWithAmpersands() {
        assertEquals(
                "POST https://example.com/d\nContent-Type: application/x-www-form-urlencoded\n\na=1&b=2&c=3&d=4\n",
                CurlImport.toHttpRequest(
                        "curl https://example.com/d -d a=1 --data-raw b=2 --data-binary c=3 --data-ascii d=4"));
    }

    @Test
    void anExplicitContentTypeIsNotOverridden() {
        assertEquals(
                "POST https://example.com/e\ncontent-type: application/json\n\n{}\n",
                CurlImport.toHttpRequest("curl -H 'content-type: application/json' -d '{}' https://example.com/e"));
    }

    @Test
    void aUserWithoutAPasswordGetsAnEmptyOne() {
        assertEquals(
                "GET https://example.com/f\nAuthorization: " + HttpAuth.basic("ada", "") + "\n",
                CurlImport.toHttpRequest("curl -u ada https://example.com/f"));
    }

    @Test
    void aHeaderWithoutAColonHasAnEmptyValue() {
        assertEquals(
                "GET https://example.com/g\nX-Empty: \n",
                CurlImport.toHttpRequest("curl -H ' X-Empty ' https://example.com/g"));
    }

    @Test
    void unknownFlagsAndLaterBareWordsAreIgnored() {
        assertEquals(
                "GET https://example.com/h\n",
                CurlImport.toHttpRequest("curl -L -k -s --compressed https://example.com/h https://second.example"));
    }

    @Test
    void aFlagAtTheEndWithoutItsValueIsDropped() {
        for (String flag : List.of("-X", "-H", "-d", "-u", "-A", "-b", "--url")) {
            assertEquals(
                    "GET https://example.com/i\n",
                    CurlImport.toHttpRequest("curl https://example.com/i " + flag),
                    flag);
        }
    }

    @Test
    void nothingToImportIsAnEmptyGet() {
        assertEquals("GET \n", CurlImport.toHttpRequest(null));
        assertEquals("GET \n", CurlImport.toHttpRequest("   "));
        assertEquals("GET \n", CurlImport.toHttpRequest("curl"));
    }

    @Test
    void doubleQuotesHonourBackslashEscapesSingleQuotesDoNot() {
        assertEquals(List.of("a \"quoted\" b", "c\\d"), CurlImport.tokenize("\"a \\\"quoted\\\" b\" 'c\\d'"));
        // A quote glued to bare text continues the same token; an empty pair is an empty token.
        assertEquals(List.of("pre fix", ""), CurlImport.tokenize("pre' 'fix ''"));
        // An unterminated quote runs to the end; a trailing backslash inside it is kept.
        assertEquals(List.of("open end\\"), CurlImport.tokenize("\"open end\\"));
    }

    @Test
    void continuationsWorkWithWindowsLineEndingsToo() {
        assertEquals(List.of("curl", "-X", "POST", "u"), CurlImport.tokenize("curl \\\r\n -X POST \\\n u"));
        // A backslash that is not at a line end is an ordinary character.
        assertEquals(List.of("a\\b", "\\"), CurlImport.tokenize("a\\b \\"));
    }
}
