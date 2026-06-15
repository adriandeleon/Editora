package com.editora.http;

import java.util.List;

import com.editora.http.HttpFile.Parsed;
import com.editora.http.HttpFile.Request;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the IntelliJ extensions: directives, external bodies, redirect operators. */
class HttpDirectivesTest {

    @Test
    void parsesPerRequestDirectives() {
        String text = """
                ### one
                # @no-redirect
                # @no-cookie-jar
                # @timeout 30
                GET https://api.test/x
                """;
        Request r = HttpFile.parse(text).get(0);
        HttpFile.Directives d = r.directives();
        assertTrue(d.noRedirect());
        assertTrue(d.noCookieJar());
        assertEquals(30, d.timeoutSeconds());
        assertFalse(d.noLog());
    }

    @Test
    void rawExternalBodyReference() {
        Parsed p = HttpFile.parseRequest("POST https://api.test/x\nContent-Type: application/json\n\n< ./body.json\n");
        assertEquals("./body.json", p.bodyRef().path());
        assertFalse(p.bodyRef().substitute());
        assertEquals("", p.body());
    }

    @Test
    void substitutedExternalBodyWithEncoding() {
        Parsed p = HttpFile.parseRequest("POST https://api.test/x\n\n<@latin1 ./body.json\n");
        assertTrue(p.bodyRef().substitute());
        assertEquals("latin1", p.bodyRef().encoding());
        assertEquals("./body.json", p.bodyRef().path());
    }

    @Test
    void redirectOperatorsWithForceFlag() {
        Parsed p = HttpFile.parseRequest("GET https://api.test/x\n\nbody\n\n>> ./out.json\n>>! ./forced.json\n");
        assertEquals("body", p.body());
        assertEquals(2, p.redirects().size());
        assertEquals("./out.json", p.redirects().get(0).path());
        assertFalse(p.redirects().get(0).force());
        assertEquals("./forced.json", p.redirects().get(1).path());
        assertTrue(p.redirects().get(1).force());
    }

    @Test
    void parseRequestFromRequestCarriesNameAndDirectives() {
        String text = "### login\n# @no-log\nPOST https://api.test/login\n";
        Parsed p = HttpFile.parseRequest(HttpFile.parse(text).get(0));
        assertEquals("login", p.name());
        assertTrue(p.directives().noLog());
    }

    @Test
    void plainRequestKeepsTheOldShape() {
        Parsed p = HttpFile.parseRequest("GET https://api.test/x\nAccept: */*\n\nhello\n");
        assertEquals("GET", p.method());
        assertEquals("hello", p.body());
        assertNull(p.bodyRef());
        assertEquals(List.of(), p.redirects());
        assertEquals(HttpFile.Directives.NONE, p.directives());
        assertNull(p.name());
    }
}
