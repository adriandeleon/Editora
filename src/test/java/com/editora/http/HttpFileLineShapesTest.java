package com.editora.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How {@link HttpFile} reads single lines: request lines, body references, scripts, durations, separators. */
class HttpFileLineShapesTest {

    private static String line(String l) {
        HttpFile.RequestLine r = HttpFile.requestLine(l);
        return r.method() + "|" + r.url() + "|" + r.version();
    }

    @Test
    void requestLines() {
        assertEquals("GET|http://h|HTTP/2", line("get http://h HTTP/2.0"));
        assertEquals("PROPFIND|http://h|HTTP/1.1", line("PROPFIND http://h HTTP/1.1"));
        assertEquals("GET|http://h/a b|HTTP/2 (Prior Knowledge)", line("http://h/a b HTTP/2 (Prior Knowledge)"));
        assertEquals("GET|1abc http://h|null", line("1abc http://h"), "a method starts with a letter");
        assertEquals("GET|pro.find http://h|null", line("pro.find http://h"), "a method has no dot");
        assertEquals("X-Y_Z|http://h|null", line("x-Y_z http://h"));
        assertEquals("GET|HTTP/1.1|null", line("HTTP/1.1"), "a version alone is the target");
        assertEquals("POST|http://h|HTTP/3", line("POST  http://h  HTTP/3"));
        assertNotNull(HttpFile.requestLine("GRAPHQL http://h").error());
        assertNull(HttpFile.requestLine("GET http://h").error());
    }

    @Test
    void preRequestScripts() {
        assertTrue(HttpFile.isPreRequestScript("< {% x %}"));
        assertTrue(HttpFile.isPreRequestScript("< ./a.js"));
        assertFalse(HttpFile.isPreRequestScript("<./a.js"), "a file needs the space");
        assertFalse(HttpFile.isPreRequestScript("<> x"));
        assertFalse(HttpFile.isPreRequestScript("<@ x"));
        assertFalse(HttpFile.isPreRequestScript("<"));
        assertFalse(HttpFile.isPreRequestScript("< ./a.json"), "a body file, not a script");
        assertFalse(HttpFile.isPreRequestScript("x"));
    }

    @Test
    void bodyReferences() {
        assertEquals(new HttpFile.BodyRef("./f", true, "latin1"), HttpFile.parseBodyRef("<@latin1 ./f"));
        assertEquals(new HttpFile.BodyRef("./f", true, "latin1"), HttpFile.parseBodyRef("<@ latin1 ./f"));
        assertEquals(new HttpFile.BodyRef("./f", true, null), HttpFile.parseBodyRef("<@ ./f"));
        assertEquals(new HttpFile.BodyRef("/f", true, null), HttpFile.parseBodyRef("<@ /f"));
        assertEquals(new HttpFile.BodyRef("./f", false, null), HttpFile.parseBodyRef("< ./f"));
        assertEquals(new HttpFile.BodyRef("latin1", true, null), HttpFile.parseBodyRef("<@latin1"));
        assertTrue(HttpFile.isBodyFileRef("<@latin1 ./f"));
        assertFalse(HttpFile.isBodyFileRef("<@ "));
        assertFalse(HttpFile.isBodyFileRef("<x"));
        assertTrue(HttpFile.isBodyFileRef("< f"));
        assertFalse(HttpFile.isBodyFileRef("x"));
    }

    @Test
    void durations() {
        assertEquals(-1, HttpFile.parseDurationMillis(null));
        assertEquals(5000, HttpFile.parseDurationMillis("5"));
        assertEquals(100, HttpFile.parseDurationMillis("100 ms"));
        assertEquals(120_000, HttpFile.parseDurationMillis("2 m"));
        assertEquals(-1, HttpFile.parseDurationMillis("0"));
        assertEquals(-1, HttpFile.parseDurationMillis("x"));
        assertEquals(-1, HttpFile.parseDurationMillis("3 h"));
    }

    @Test
    void variablesAndSeparators() {
        assertTrue(HttpFile.isVarDecl("@x=1"));
        assertFalse(HttpFile.isVarDecl("@=1"));
        assertFalse(HttpFile.isVarDecl("x=1"));
        assertFalse(HttpFile.isVarDecl("@x"));
        assertEquals("A b", HttpFile.separatorTitle("### A b"));
        assertNull(HttpFile.separatorTitle("####  "));
        assertNull(HttpFile.separatorTitle("x"));
        assertNull(HttpFile.separatorTitle(null));
    }
}
