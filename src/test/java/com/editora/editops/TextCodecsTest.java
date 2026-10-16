package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextCodecsTest {

    @Test
    void base64RoundTripsUtf8AndAcceptsUrlSafeAndWhitespace() {
        assertEquals("aMOpbGxv", TextCodecs.base64Encode("héllo"));
        assertEquals("héllo", TextCodecs.base64Decode("aMOpbGxv"));
        assertEquals("héllo", TextCodecs.base64Decode(" aMOp\nbGxv "));
        assertEquals("??>", TextCodecs.base64Decode("Pz8-"), "URL-safe alphabet, no padding");
        assertEquals("😀", TextCodecs.base64Decode(TextCodecs.base64Encode("😀")));
    }

    @Test
    void base64RefusesWhatIsNotBase64OrNotText() {
        assertThrows(IllegalArgumentException.class, () -> TextCodecs.base64Decode("not base64!"));
        assertThrows(IllegalArgumentException.class, () -> TextCodecs.base64Decode("/w=="), "0xFF is not UTF-8");
    }

    @Test
    void urlEncodingUsesPercentTwentyForSpaces() {
        assertEquals("a%20b%26c%3Dd~%C3%A9", TextCodecs.urlEncode("a b&c=d~é"));
        assertEquals("a b&c=d~é", TextCodecs.urlDecode("a%20b%26c%3Dd~%C3%A9"));
        assertEquals("a b", TextCodecs.urlDecode("a+b"), "form-style plus");
        assertThrows(IllegalArgumentException.class, () -> TextCodecs.urlDecode("100%"));
        assertThrows(IllegalArgumentException.class, () -> TextCodecs.urlDecode("%zz"));
    }

    @Test
    void jsonEscapesRoundTrip() {
        String raw = "say \"hi\"\\\n\ttab\u0001";
        String escaped = TextCodecs.jsonEscape(raw);
        assertEquals("say \\\"hi\\\"\\\\\\n\\ttab\\u0001", escaped);
        assertEquals(raw, TextCodecs.jsonUnescape(escaped));
        assertEquals("é/", TextCodecs.jsonUnescape("\\u00e9\\/"));
        assertThrows(IllegalArgumentException.class, () -> TextCodecs.jsonUnescape("bad \\q"));
        assertThrows(IllegalArgumentException.class, () -> TextCodecs.jsonUnescape("end \\"));
        assertThrows(IllegalArgumentException.class, () -> TextCodecs.jsonUnescape("\\u12"));
        assertThrows(IllegalArgumentException.class, () -> TextCodecs.jsonUnescape("\\u12zz"));
    }

    @Test
    void htmlEscapesTheReservedFiveAndReadsNamedAndNumericEntities() {
        assertEquals(
                "&lt;a href=&quot;x&quot;&gt;Tom &amp; Jerry&#39;s&lt;/a&gt;",
                TextCodecs.htmlEscape("<a href=\"x\">Tom & Jerry's</a>"));
        assertEquals(
                "<a> & “é” 😀 ©", TextCodecs.htmlUnescape("&lt;a&gt; &amp; &ldquo;&#233;&rdquo; &#x1F600; &copy;"));
        assertEquals("&unknown; & &;", TextCodecs.htmlUnescape("&unknown; & &;"), "left as written");
        assertEquals("&#xZZ; &#1114112;", TextCodecs.htmlUnescape("&#xZZ; &#1114112;"), "not a code point");
    }

    @Test
    void unicodeEscapesEveryNonAsciiCharacter() {
        assertEquals("caf\\u00e9 \\ud83d\\ude00", TextCodecs.unicodeEscape("café 😀"));
        assertEquals("café 😀 \\n stays", TextCodecs.unicodeUnescape("caf\\u00e9 \\ud83d\\ude00 \\n stays"));
        assertThrows(IllegalArgumentException.class, () -> TextCodecs.unicodeUnescape("\\uZZZZ"));
    }
}
