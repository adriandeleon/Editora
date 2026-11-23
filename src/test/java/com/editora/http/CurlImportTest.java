package com.editora.http;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the curl → .http import. */
class CurlImportTest {

    @Test
    void getWithHeaders() {
        String http = CurlImport.toHttpRequest("curl 'https://api.test/x' -H 'Accept: application/json'");
        assertTrue(http.startsWith("GET https://api.test/x\n"), http);
        assertTrue(http.contains("Accept: application/json"), http);
    }

    @Test
    void dataInfersPostAndContentType() {
        String http = CurlImport.toHttpRequest("curl https://api.test/x -d 'a=1&b=2'");
        assertTrue(http.startsWith("POST https://api.test/x\n"), http);
        assertTrue(http.contains("Content-Type: application/x-www-form-urlencoded"), http);
        assertTrue(http.contains("\na=1&b=2"), http);
    }

    @Test
    void explicitMethodAndQuotedJsonBody() {
        String http = CurlImport.toHttpRequest(
                "curl -X PUT https://api.test/x -H 'Content-Type: application/json' -d '{\"k\": \"v\"}'");
        assertTrue(http.startsWith("PUT https://api.test/x\n"), http);
        assertTrue(http.contains("{\"k\": \"v\"}"), http);
        // an explicit Content-Type is not overwritten
        assertTrue(http.indexOf("Content-Type") == http.lastIndexOf("Content-Type"), http);
    }

    @Test
    void userBecomesBasicAuth() {
        String http = CurlImport.toHttpRequest("curl https://api.test/x -u user:pass");
        assertTrue(http.contains("Authorization: Basic dXNlcjpwYXNz"), http);
    }

    @Test
    void lineContinuationsAreJoined() {
        String http = CurlImport.toHttpRequest("curl https://api.test/x \\\n  -H 'X-A: 1' \\\n  -H 'X-B: 2'");
        assertTrue(http.contains("X-A: 1"), http);
        assertTrue(http.contains("X-B: 2"), http);
    }

    private static String http(String curl) {
        return CurlImport.toHttpRequest(curl);
    }

    @Test
    void theCurlWordMayBeAPathOrCurlExeAfterAPrompt() {
        assertEquals("GET http://h/a\n", http("$ sudo /usr/bin/curl http://h/a"));
        assertEquals("GET http://h/a\n", http("'C:\\tools\\CURL.EXE' http://h/a"));
        assertNull(http("wget http://h/a"), "no curl word");
        assertNull(http("-v curl http://h/a"), "an option before any curl word");
        assertEquals(-1, CurlImport.curlWord(List.of()));
    }

    @Test
    void powerShellBackticksAndTheEndOfOptionsMarker() {
        assertEquals("GET http://h/a\nX: 1\n", http("curl http://h/a ` -H 'X: 1' `"));
        assertEquals("GET -odd\n", http("curl -s -- -odd -H"), "after -- every word is an argument");
        assertEquals("GET -\n", http("curl -"), "a lone dash is a word");
    }

    @Test
    void aShortOptionAtTheEndWithoutItsValueIsDropped() {
        assertEquals("GET http://h/a\n", http("curl http://h/a -X"));
        assertEquals("GET http://h/a\n", http("curl http://h/a -sH"));
    }

    @Test
    void protocolFlags() {
        assertTrue(http("curl -0 http://h/a").startsWith("GET http://h/a HTTP/1.0\n"));
        assertTrue(http("curl --http1.0 http://h/a").startsWith("GET http://h/a HTTP/1.0\n"));
        assertTrue(http("curl --http2-prior-knowledge http://h/a")
                .startsWith("GET http://h/a HTTP/2 (Prior Knowledge)\n"));
        assertEquals(
                "GET http://h/a\nAuthorization: Basic dTpw\n",
                http("curl --digest --basic -u u:p http://h/a"),
                "the last of --digest/--basic wins");
        assertEquals("HEAD http://h/a\n", http("curl --head http://h/a"));
        assertEquals("GET http://h/a?x=1\n", http("curl --get -d x=1 http://h/a"));
    }

    @Test
    void headerForms() {
        assertEquals("GET http://h/a\n", http("curl -H @headers.txt http://h/a"), "a file of headers is not read");
        assertEquals("GET http://h/a\nX-Empty: \n", http("curl -H 'X-Empty;' http://h/a"));
        assertEquals(
                "GET http://h/a\nB: 2\n",
                http("curl -H 'A: 1' -H 'B: 2' -H 'a:' http://h/a"),
                "Name: removes the header, in any case");
        assertEquals(
                "POST http://h/a\nX: y;z\nContent-Type: application/x-www-form-urlencoded\n\nk=v\n",
                http("curl -H 'X: y;z' -d k=v http://h/a"));
    }

    @Test
    void aRemovedContentTypeIsNotAddedBack() {
        assertEquals("POST http://h/a\n\nk=v\n", http("curl -H 'Content-Type:' -d k=v http://h/a"));
        assertEquals(
                "POST http://h/a\n\nk=v\n",
                http("curl -H 'Content-Type: text/plain' -H 'Content-Type:' -d k=v http://h/a"));
    }

    @Test
    void dataFromStdinOrABareAtIsSentAsWritten() {
        assertTrue(http("curl -d @- http://h/a").endsWith("\n\n@-\n"));
        assertTrue(http("curl -d @ http://h/a").endsWith("\n\n@\n"));
        assertEquals(
                "POST http://h/a\nContent-Type: application/json\nAccept: application/json\n\n< ./in.json\n",
                http("curl --json @in.json http://h/a"));
        assertEquals(
                "POST http://h/a\nContent-Type: application/json\nAccept: application/json\n\n\n",
                http("curl --json @- http://h/a"),
                "stdin JSON has no body to carry over");
    }

    @Test
    void dataUrlencodeForms() {
        assertTrue(http("curl --data-urlencode 'a b' http://h/a").endsWith("\n\na%20b\n"));
        assertTrue(http("curl --data-urlencode '=a b' http://h/a").endsWith("\n\na%20b\n"));
        assertTrue(http("curl --data-urlencode 'n=a=b' http://h/a").endsWith("\n\nn=a%3Db\n"));
    }

    @Test
    void urlQueryPiecesAreAppended() {
        assertEquals(
                "GET http://h/a?x=a%20b&raw=c d\n", http("curl --url-query 'x=a b' --url-query '+raw=c d' http://h/a"));
        assertEquals("GET http://h/a?k=1&y=2\n", http("curl --url-query y=2 'http://h/a?k=1'"));
        assertEquals("GET http://h/a?k=1&q=2\n", http("curl -G -d q=2 'http://h/a?k=1'"));
    }

    @Test
    void aCookieWithoutEqualsNamesACookieFile() {
        assertEquals("GET http://h/a\n", http("curl -b cookies.txt http://h/a"));
        assertEquals("GET http://h/a\nReferer: http://r/\n", http("curl -e http://r/ http://h/a"));
    }

    @Test
    void uploadWithOtherBodiesKeepsTheOtherBody() {
        assertEquals("GET http://h/a\n", http("curl -T . http://h/a"), "every file of a directory: nothing to send");
        assertTrue(http("curl -T up.bin -d k=v http://h/a").endsWith("\n\nk=v\n"));
        assertEquals("PUT http://h/a\n\n< ../up.bin\n", http("curl -T ../up.bin http://h/a"));
        assertEquals("PUT http://h/a\n\n< C:\\up.bin\n", http("curl -T 'C:\\up.bin' http://h/a"));
    }

    @Test
    void anExplicitMethodWithoutABody() {
        assertEquals("DELETE http://h/a\n", http("curl -X DELETE http://h/a"));
        assertEquals("POST http://h/a\n", http("curl --request POST http://h/a"));
    }

    @Test
    void formPartsWithQuotedValuesAndAttributes() {
        String out = http("curl -F 'n=\"a;b\";type=text/plain' -F 'm=v;filename=\"x.json\";type=application/json' "
                + "-F 'h=v;headers=X-A: 1' -F 'q=\"open' -F bare http://h/a");
        assertTrue(out.contains("name=\"n\"\nContent-Type: text/plain\n\na;b\n"), out);
        assertTrue(out.contains("name=\"m\"; filename=\"x.json\"\nContent-Type: application/json\n\nv\n"), out);
        assertTrue(out.contains("name=\"h\"\n\nv\n"), out);
        assertTrue(out.contains("name=\"q\"\n\nopen\n"), out);
        assertTrue(out.contains("name=\"bare\"\n\n\n"), out);
    }

    @Test
    void formFilePartsAsTextOrWithQuotedNames() {
        String out = http("curl -F 'notes=<notes.txt' -F 'f=@\"open.bin' -F 'g=@a\\\\b.png;filename=\"q\\\"t.png\"' "
                + "-F 'w=@C:\\dir\\w.txt' http://h/a");
        assertTrue(out.contains("name=\"notes\"\n\n< ./notes.txt\n"), out);
        assertTrue(out.contains("name=\"f\"; filename=\"open.bin\"\n\n< ./open.bin\n"), out);
        assertTrue(out.contains("name=\"g\"; filename=\"q\\\"t.png\"\n"), out);
        assertTrue(out.contains("name=\"w\"; filename=\"w.txt\"\n\n< C:\\dir\\w.txt\n"), out);
    }

    @Test
    void aFormKeepsItsOwnBoundaryAndDropsAPlainContentType() {
        String own = http("curl -H 'Content-Type: multipart/form-data; boundary=XyZ' -F a=1 http://h/a");
        assertTrue(own.contains("\nContent-Type: multipart/form-data; boundary=XyZ\n"), own);
        assertTrue(own.endsWith("--XyZ\nContent-Disposition: form-data; name=\"a\"\n\n1\n--XyZ--\n"), own);
        String plain = http("curl -H 'Content-Type: multipart/form-data' -F a=1 http://h/a");
        assertTrue(plain.contains("\nContent-Type: multipart/form-data; boundary=WebAppBoundary\n"), plain);
        assertEquals(1, plain.split("Content-Type", -1).length - 1, plain);
    }

    @Test
    void formAttributesKeepQuotedSemicolons() {
        assertEquals(
                List.of("type=a", "filename=\"x;y\\\"z\""), CurlImport.attributes(";type=a; ;filename=\"x;y\\\"z\";"));
        assertEquals(List.of(), CurlImport.attributes(""));
    }

    @Test
    void ansiCEscapes() {
        assertEquals(
                List.of("curl", "\t\r\u0007\b\u001b\u001b\f\u000b'?A\u00e9\uD83D\uDE00"),
                CurlImport.tokenize("curl $'\\t\\r\\a\\b\\e\\E\\f\\v\\'\\?\\x41\\u00e9\\U0001F600'"));
        assertEquals(List.of("\\xg\\uz\\Uq"), CurlImport.tokenize("$'\\xg\\uz\\Uq'"), "an escape without digits stays");
        assertEquals(List.of("a\\"), CurlImport.tokenize("$'a\\"), "an unclosed string with a trailing backslash");
    }

    @Test
    void shellQuotingDetails() {
        assertEquals(List.of("a b", "c\\"), CurlImport.tokenize("a\\ b c\\"));
        assertEquals(List.of("x\"\\$`y", "ab"), CurlImport.tokenize("\"x\\\"\\\\\\$\\`y\" \"a\\\nb\""));
        assertEquals(List.of("\\n"), CurlImport.tokenize("\"\\n\""), "other escapes keep their backslash");
        assertEquals(List.of("a", "b"), CurlImport.tokenize("a \\\r\nb"));
        assertEquals(List.of("a", "b"), CurlImport.tokenize("a \\\rb"));
        assertEquals(List.of(), CurlImport.tokenize(null));
        assertEquals(List.of(""), CurlImport.tokenize("''"), "an empty quoted word is a word");
    }

    @Test
    void cmdQuotingDetails() {
        assertTrue(CurlImport.isCmdStyle("curl x ^\r\n -v"));
        assertEquals(List.of("curl", "a b", "-v"), CurlImport.tokenize("curl ^\"a ^\r\nb^\" ^\r\n-v"));
        assertEquals(
                List.of("curl", "x", "x^"), CurlImport.tokenize("curl ^\"x^\" x^"), "a caret at the very end stays");
        assertEquals(List.of("curl", "a\"b"), CurlImport.tokenize("curl ^\"a\\^\"b^\""));
    }

    @Test
    void alteredBodyLinesOfNothingOrOfCommentsOnly() {
        assertEquals(0, CurlImport.alteredBodyLines(null));
        assertEquals(0, CurlImport.alteredBodyLines("GET http://h/a\n"));
        assertEquals(2, CurlImport.alteredBodyLines("POST http://h/a\nX: 1\n\n# one\nkept\n// two\n"));
        assertEquals(2, CurlImport.alteredBodyLines("POST http://h/a\n\nbody\n<> ref\n\nafter\n"));
    }

    @Test
    void inertCommentsOutEverythingFromAHandlerOn() {
        assertEquals("a\n# > h\n\n# b", CurlImport.inert("a\n> h\n\nb"));
        assertEquals("a", CurlImport.inert("a"));
    }
}
