package com.editora.http;

import org.junit.jupiter.api.Test;

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
}
