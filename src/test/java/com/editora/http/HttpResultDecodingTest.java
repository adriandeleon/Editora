package com.editora.http;

import java.nio.charset.Charset;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a response's bytes become display text: the charset to decode with and whether there is text at all. */
class HttpResultDecodingTest {

    private static HttpResult result(byte[] bytes, String contentType) {
        return HttpFixtures.bytes(200, List.of(), bytes, contentType, 1, List.of(), false);
    }

    @Test
    void anHtmlMetaCharsetDecodesTheBodyWhenTheHeaderHasNone() throws Exception {
        byte[] body = "<html><head><meta charset=\"windows-1252\"></head><p>café</p>".getBytes("windows-1252");
        assertTrue(
                result(body, "text/html").body().contains("café"),
                result(body, "text/html").body());
        byte[] equiv = "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=ISO-8859-1\"><p>ñ</p>"
                .getBytes("ISO-8859-1");
        assertTrue(result(equiv, "text/html").body().contains("ñ"));
    }

    @Test
    void anXmlPrologEncodingDecodesTheBody() throws Exception {
        byte[] body = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>é</a>".getBytes("ISO-8859-1");
        assertEquals(
                "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>é</a>",
                result(body, "application/xml").body());
    }

    @Test
    void aDeclaredHeaderCharsetWinsOverTheDocument() {
        byte[] body = "<meta charset=\"windows-1252\">é".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(Charset.forName("UTF-8"), HttpResult.charsetOf("text/html; charset=utf-8", body));
        assertEquals(
                "<meta charset=\"windows-1252\">é",
                result(body, "text/html; charset=utf-8").body());
    }

    @Test
    void binaryMediaTypesHaveNoTextFormEvenWithoutANulByte() {
        byte[] noNul = {(byte) 0x89, 'P', 'N', 'G', (byte) 0xff, (byte) 0xfe, 1, 2, 3};
        assertTrue(result(noNul, "application/octet-stream").binary());
        assertTrue(result(noNul, "image/png").binary());
        assertTrue(result(noNul, "application/pdf").binary());
        assertFalse(result("<svg/>".getBytes(), "image/svg+xml").binary(), "SVG is text");
        assertFalse(result("plain".getBytes(), "").binary(), "no type and no NUL is text");
        assertFalse(
                result("x".getBytes(), "application/octet-stream; charset=utf-8")
                        .binary(),
                "a charset wins");
    }
}
