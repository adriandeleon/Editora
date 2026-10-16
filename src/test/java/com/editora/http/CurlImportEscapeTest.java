package com.editora.http;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Import cURL never turns pasted text into a variable reference (G5): every {@code {{} of the command — URL, headers,
 * body, form fields — is written as the {@code \{{} escape, so the request sends it as typed and the trust gate has
 * nothing to ask about.
 */
class CurlImportEscapeTest {

    @Test
    void everyBraceOfTheCommandIsEscaped() {
        String http = CurlImport.toHttpRequest("curl 'http://h.test/a?x={{$env PATH}}' -H 'X-T: {{token}}' "
                + "-d '{\"q\":\"{{name}}\"}' -F 'f={{field}}'");
        assertFalse(HttpVars.referencePattern().matcher(http).find(), "no reference is left: " + http);
        assertTrue(http.contains("x=\\{{$env PATH}}"), http);
        assertTrue(http.contains("X-T: \\{{token}}"), http);
        assertTrue(http.contains("\\{{field}}"), http);
    }

    @Test
    void theImportedRequestResolvesToExactlyTheCommandsText() {
        HttpFile.Parsed p = HttpFile.parseRequest(
                HttpFile.parse(CurlImport.toHttpRequest("curl 'http://h.test/a?x={{$env PATH}}' -H 'X-T: {{token}}'"))
                        .get(0));
        HttpVars.Unresolved sink = new HttpVars.Unresolved();
        HttpVars.Scope scope = HttpVars.scope(Map.of("token", "nope"), p.variables(), null, null, null);
        assertEquals("http://h.test/a?x={{$env PATH}}", HttpVars.substitute(p.url(), scope, null, null, null, sink));
        assertEquals("{{token}}", HttpVars.substitute(p.headers().get(0)[1], scope, null, null, null, sink));
        assertEquals(List.of(), sink.secretReads());
    }

    @Test
    void aCommandWithoutBracesIsUnchanged() {
        assertEquals("GET http://h.test/a\n", CurlImport.toHttpRequest("curl http://h.test/a"));
    }
}
