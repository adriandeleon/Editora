package com.editora.http;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-world curl commands — browsers' "Copy as cURL", shell one-liners — that used to import wrongly. */
class CurlImportCommandsTest {

    private static String http(String curl) {
        return CurlImport.toHttpRequest(curl);
    }

    @Test
    void optionArgumentsAreNeverTakenForTheUrl() {
        assertEquals("GET https://h/api\n", http("curl -s -o /dev/null -w '%{http_code}' https://h/api"));
        assertEquals("GET https://h/api\n", http("curl --connect-timeout 5 --max-time 30 https://h/api"));
        assertEquals("GET https://h/api\n", http("curl -x http://proxy:3128 https://h/api"));
    }

    @Test
    void attachedAndClusteredShortOptions() {
        assertTrue(http("curl -XPOST https://h/api").startsWith("POST https://h/api\n"));
        assertTrue(http("curl -sSLX PUT https://h/api").startsWith("PUT https://h/api\n"));
        assertTrue(http("curl -H'X-A: 1' https://h/api").contains("\nX-A: 1\n"));
    }

    @Test
    void headAndGet() {
        assertEquals("HEAD https://h/api\n", http("curl -I https://h/api"));
        assertEquals("GET https://h/search?q=1&n=2\n", http("curl -G https://h/search -d q=1 -d n=2"));
    }

    @Test
    void aDataFileBecomesAFileReference() {
        String out = http("curl -X POST https://h/api -d @body.json -H 'Content-Type: application/json'");
        assertEquals("POST https://h/api\nContent-Type: application/json\n\n< ./body.json\n", out);
        assertTrue(http("curl --data-binary @/tmp/x.bin https://h/up").endsWith("\n\n< /tmp/x.bin\n"));
        // --data-raw has no @file meaning.
        assertTrue(http("curl --data-raw @literal https://h/api").endsWith("\n\n@literal\n"));
    }

    @Test
    void jsonSetsTheContentTypeAndAccept() {
        assertEquals(
                "POST https://h/api\nContent-Type: application/json\nAccept: application/json\n\n{\"a\":1}\n",
                http("curl --json '{\"a\":1}' https://h/api"));
    }

    @Test
    void dataUrlencodeEncodesTheValue() {
        assertTrue(http("curl https://h/api --data-urlencode 'q=a b&c'").endsWith("\n\nq=a%20b%26c\n"));
    }

    @Test
    void formPartsBecomeAMultipartBody() {
        String out = http("curl https://h/up -F 'file=@shots/a.png;type=image/png' -F 'name=x'");
        assertEquals(
                "POST https://h/up\nContent-Type: multipart/form-data; boundary=WebAppBoundary\n\n"
                        + "--WebAppBoundary\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"a.png\"\n"
                        + "Content-Type: image/png\n\n"
                        + "< ./shots/a.png\n"
                        + "--WebAppBoundary\n"
                        + "Content-Disposition: form-data; name=\"name\"\n\n"
                        + "x\n"
                        + "--WebAppBoundary--\n",
                out);
        // The result parses back into the same two parts.
        HttpFile.Parsed p = HttpFile.parseRequest(out);
        List<Multipart.Part> parts = Multipart.parse(p.body(), "WebAppBoundary");
        assertEquals(2, parts.size());
        assertEquals("./shots/a.png", parts.get(0).filePath());
        assertEquals("x", parts.get(1).inlineBody());
    }

    @Test
    void digestUsesTheShorthand() {
        assertTrue(http("curl --digest -u ada:pw https://h/api").contains("\nAuthorization: Digest ada pw\n"));
    }

    @Test
    void chromeBashQuotingWithAnsiCStrings() {
        String out = http("curl 'https://h/api' -H 'content-type: application/json' "
                + "--data-raw $'{\"a\":\"it\\'s\\nnew\",\"u\":\"\\u00e9\"}' --compressed");
        assertTrue(out.startsWith("POST https://h/api\n"), out);
        assertTrue(out.endsWith("\n\n{\"a\":\"it's\nnew\",\"u\":\"é\"}\n"), out);
    }

    @Test
    void chromeCmdQuoting() {
        String out =
                http("curl ^\"https://h/api^\" ^\n  -H ^\"accept: */*^\" ^\n  --data-raw ^\"^{^\\^\"a^\\^\":1^}^\"");
        assertEquals(
                "POST https://h/api\naccept: */*\nContent-Type: application/x-www-form-urlencoded\n\n{\"a\":1}\n", out);
    }

    @Test
    void editorasOwnExportRoundTrips() {
        String exported = "curl -X POST 'http://h/a' \\\n  -H 'Content-Type: application/json' \\\n"
                + "  --data '{\"a\":\"it'\\''s\"}'";
        assertEquals("POST http://h/a\nContent-Type: application/json\n\n{\"a\":\"it's\"}\n", http(exported));
    }

    @Test
    void editorasOwnMultipartExportRoundTrips() {
        // What CurlExport writes for a multipart file part: a double-quoted path, which may hold ';' and '"'.
        String exported = "curl -X POST -L 'http://h/up' \\\n"
                + "  -F 'f=@\"/work/a;b \\\"x\\\".json\";filename=\"b.json\";type=application/json' \\\n"
                + "  --form-string 'note=hi'";
        String out = http(exported);
        assertTrue(
                out.contains(
                        "name=\"f\"; filename=\"b.json\"\nContent-Type: application/json\n\n< /work/a;b \"x\".json\n"),
                out);
        assertTrue(out.contains("name=\"note\"\n\nhi\n"), out);
    }

    @Test
    void theHttpVersionFlagsAreKept() {
        assertTrue(http("curl -L --http1.1 'http://h/v'").startsWith("GET http://h/v HTTP/1.1\n"));
        assertTrue(http("curl --http2 https://h/v").startsWith("GET https://h/v HTTP/2\n"));
        HttpFile.Parsed p = HttpFile.parseRequest(http("curl --http1.1 http://h/v"));
        assertEquals("http://h/v", p.url());
        assertEquals("HTTP/1.1", p.httpVersion());
    }

    @Test
    void uploadFileIsAPutOfTheFile() {
        assertEquals("PUT http://h/put\n\n< ./file.bin\n", http("curl -T ./file.bin http://h/put"));
        assertEquals("POST http://h/put\n\n< ./file.bin\n", http("curl -X POST --upload-file file.bin http://h/put"));
        assertEquals("GET http://h/put\n", http("curl -T - http://h/put"), "stdin cannot be written in a .http file");
    }
}
