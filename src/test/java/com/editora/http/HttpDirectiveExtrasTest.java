package com.editora.http;

import com.editora.http.HttpFile.Directives;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the @no-auto-encoding and @connection-timeout directives. */
class HttpDirectiveExtrasTest {

    @Test
    void parsesNoAutoEncodingAndConnectionTimeout() {
        String text = """
                ### one
                # @no-auto-encoding
                # @connection-timeout 5
                GET https://api.test/a b
                """;
        Directives d = HttpFile.parse(text).get(0).directives();
        assertTrue(d.noAutoEncoding());
        assertEquals(5, d.connectionTimeoutSeconds());
    }

    @Test
    void connectionTimeoutDoesNotMatchTimeout() {
        // "@timeout 30" must not be read as a connection-timeout, and vice versa.
        Directives d = HttpFile.parse("### x\n# @timeout 30\nGET https://api.test/x\n")
                .get(0)
                .directives();
        assertEquals(30, d.timeoutSeconds());
        assertEquals(0, d.connectionTimeoutSeconds());
        assertFalse(d.noAutoEncoding());
    }
}
