package com.editora.http;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the Basic-auth convenience. */
class HttpAuthTest {

    @Test
    void basicEncodesUserAndPass() {
        // base64("user:pass") = dXNlcjpwYXNz
        assertEquals("Basic dXNlcjpwYXNz", HttpAuth.basic("user", "pass"));
    }

    @Test
    void normalizeRewritesTwoTokenBasic() {
        List<String[]> in = List.<String[]>of(new String[] {"Authorization", "Basic user pass"});
        assertEquals("Basic dXNlcjpwYXNz", HttpAuth.normalizeHeaders(in).get(0)[1]);
    }

    @Test
    void normalizeLeavesEncodedAndOtherHeaders() {
        List<String[]> in = List.of(
                new String[] {"Authorization", "Basic dXNlcjpwYXNz"},
                new String[] {"Authorization", "Bearer xyz"},
                new String[] {"Accept", "application/json"});
        List<String[]> out = HttpAuth.normalizeHeaders(in);
        assertEquals("Basic dXNlcjpwYXNz", out.get(0)[1]);
        assertEquals("Bearer xyz", out.get(1)[1]);
        assertEquals("application/json", out.get(2)[1]);
    }
}
