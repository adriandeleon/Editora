package com.editora.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Response-save extension inference from a content type. */
class HttpResponseFormatTest {

    @Test
    void extensionForKnownContentTypes() {
        assertEquals(".json", HttpResponseFormat.extensionFor("application/json"));
        assertEquals(".json", HttpResponseFormat.extensionFor("application/vnd.api+json; charset=utf-8"));
        assertEquals(".html", HttpResponseFormat.extensionFor("text/html"));
        assertEquals(".xml", HttpResponseFormat.extensionFor("application/xml"));
    }

    @Test
    void extensionForIsCaseInsensitive() {
        assertEquals(".json", HttpResponseFormat.extensionFor("APPLICATION/JSON"));
        assertEquals(".html", HttpResponseFormat.extensionFor("TEXT/HTML"));
    }

    @Test
    void extensionForFallsBackToTxt() {
        assertEquals(".txt", HttpResponseFormat.extensionFor("text/plain"));
        assertEquals(".txt", HttpResponseFormat.extensionFor(""));
        assertEquals(".txt", HttpResponseFormat.extensionFor(null));
    }
}
