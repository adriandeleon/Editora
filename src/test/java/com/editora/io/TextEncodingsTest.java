package com.editora.io;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.editora.editorconfig.EditorConfigCharset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEncodingsTest {

    private static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    @Test
    void choicesListTheCommonEncodingsFirstAndNoneTwice() {
        List<TextEncodings.Encoding> choices = TextEncodings.choices();
        assertEquals(EditorConfigCharset.UTF_8, choices.get(0).name());
        assertEquals(TextEncodings.Group.UNICODE, choices.get(0).group());
        Set<String> names = new HashSet<>();
        Set<String> charsets = new HashSet<>();
        boolean other = false;
        for (TextEncodings.Encoding e : choices) {
            assertTrue(names.add(e.name()), "listed twice: " + e.name());
            assertTrue(TextEncodings.supported(e.name()), "unsupported: " + e.name());
            if (e.group() == TextEncodings.Group.OTHER) {
                other = true;
                // Two names for one Java charset is fine only among the common ones (utf-8 and utf-8-bom).
                assertTrue(charsets.add(EditorConfigCharset.charsetFor(e.name()).name()), "duplicate " + e.name());
            } else {
                assertFalse(other, "a common encoding after the others: " + e.name());
                charsets.add(EditorConfigCharset.charsetFor(e.name()).name());
            }
        }
        assertTrue(names.contains("Shift_JIS"));
        assertTrue(names.contains(EditorConfigCharset.LATIN1));
        assertFalse(names.contains("ISO-8859-1"), "ISO-8859-1 is listed once, by its EditorConfig name");
        assertFalse(names.contains("UTF-8"), "UTF-8 is listed once, by its EditorConfig name");
    }

    @Test
    void theRuntimeHasTheExtendedCharsets() {
        // jdk.charsets is required by the module so the image always links it; these live only there.
        for (String name : List.of("ISO-2022-JP", "windows-1256", "x-MacRoman", "x-windows-949")) {
            assertTrue(TextEncodings.supported(name), name);
        }
    }

    @Test
    void normalizeMapsJavaNamesToEditorConfigNames() {
        assertEquals(EditorConfigCharset.UTF_8, TextEncodings.normalize("UTF-8"));
        assertEquals(EditorConfigCharset.UTF_8, TextEncodings.normalize("utf8"));
        assertEquals(EditorConfigCharset.LATIN1, TextEncodings.normalize("ISO-8859-1"));
        assertEquals(EditorConfigCharset.UTF_16LE, TextEncodings.normalize("UTF-16LE"));
        assertEquals(EditorConfigCharset.UTF_16BE, TextEncodings.normalize("UTF-16BE"));
        assertEquals("Shift_JIS", TextEncodings.normalize("sjis"));
        assertEquals("no-such-charset", TextEncodings.normalize("no-such-charset"));
        assertEquals("bad name!", TextEncodings.normalize("bad name!"));
    }

    @Test
    void supportedRejectsUnknownAndIllegalNames() {
        assertTrue(TextEncodings.supported(EditorConfigCharset.UTF_8_BOM));
        assertTrue(TextEncodings.supported("windows-1251"));
        assertFalse(TextEncodings.supported("no-such-charset"));
        assertFalse(TextEncodings.supported("bad name!"));
        assertFalse(TextEncodings.supported(null));
    }

    @Test
    void decodesShiftJis() {
        TextEncodings.Decoded d = TextEncodings.decode(hex("93fa967b8cea0d0a"), "Shift_JIS");
        assertTrue(d.ok());
        assertEquals("日本語\r\n", d.text());
        assertEquals("Shift_JIS", d.charset());
    }

    @Test
    void decodesCp1251AndGbk() {
        assertEquals(
                "Привет",
                TextEncodings.decode(hex("cff0e8e2e5f2"), "windows-1251").text());
        assertEquals(
                "中文",
                TextEncodings.decode("中文".getBytes(Charset.forName("GBK")), "GBK")
                        .text());
    }

    @Test
    void reportsTheLineAndColumnOfTheFirstUnreadableCharacter() {
        // "ab\ncd" then a lone 0xFF, which is never valid UTF-8.
        TextEncodings.Decoded d = TextEncodings.decode(hex("61620a6364ff65"), EditorConfigCharset.UTF_8);
        assertFalse(d.ok());
        assertNull(d.text());
        assertEquals(2, d.line());
        assertEquals(3, d.column());
    }

    @Test
    void reportsAnErrorAtTheStart() {
        TextEncodings.Decoded d = TextEncodings.decode(hex("ff"), EditorConfigCharset.UTF_8);
        assertFalse(d.ok());
        assertEquals(1, d.line());
        assertEquals(1, d.column());
    }

    @Test
    void reportsATruncatedSequenceAtTheEnd() {
        // 日 is E6 97 A5 in UTF-8; the file ends after its first two bytes.
        TextEncodings.Decoded d = TextEncodings.decode(hex("41e697"), EditorConfigCharset.UTF_8);
        assertFalse(d.ok());
        assertEquals(1, d.line());
        assertEquals(2, d.column());
    }

    @Test
    void anUnmappableByteIsAnErrorNotAReplacement() {
        // 0x81 is undefined in windows-1252.
        assertFalse(TextEncodings.decode(hex("6181"), EditorConfigCharset.WINDOWS_1252)
                .ok());
    }

    @Test
    void utf8WithABomBecomesUtf8BomAndTheMarkIsNotShown() {
        TextEncodings.Decoded d = TextEncodings.decode(hex("efbbbf6869"), EditorConfigCharset.UTF_8);
        assertTrue(d.ok());
        assertEquals("hi", d.text());
        assertEquals(EditorConfigCharset.UTF_8_BOM, d.charset());
        // Without a BOM, utf-8-bom still reads the first bytes as text.
        assertEquals(
                "hi",
                TextEncodings.decode(hex("6869"), EditorConfigCharset.UTF_8_BOM).text());
    }

    @Test
    void utf16DropsOnlyItsOwnByteOrderMark() {
        assertEquals(
                "hi",
                TextEncodings.decode(hex("fffe68006900"), EditorConfigCharset.UTF_16LE)
                        .text());
        assertEquals(
                "hi",
                TextEncodings.decode(hex("68006900"), EditorConfigCharset.UTF_16LE)
                        .text());
        assertEquals(
                "hi",
                TextEncodings.decode(hex("feff00680069"), EditorConfigCharset.UTF_16BE)
                        .text());
    }

    @Test
    void decodesALargeInputThatOutgrowsTheFirstBuffer() {
        String text = "é".repeat(50_000) + "\n";
        TextEncodings.Decoded d = TextEncodings.decode(text.getBytes(StandardCharsets.UTF_16BE), "UTF-32BE");
        // UTF-16BE bytes are not UTF-32: an error, found after decoding a prefix.
        assertFalse(d.ok());
        TextEncodings.Decoded ok = TextEncodings.decode(text.getBytes(Charset.forName("UTF-32LE")), "UTF-32LE");
        assertEquals(text, ok.text());
        String ascii = "abc\n".repeat(30_000);
        assertEquals(
                ascii,
                TextEncodings.decode(ascii.getBytes(StandardCharsets.US_ASCII), "Shift_JIS")
                        .text());
        String late = "x".repeat(100_000);
        TextEncodings.Decoded bad = TextEncodings.decode(
                (late + "\u00ff").getBytes(StandardCharsets.ISO_8859_1), EditorConfigCharset.UTF_8);
        assertFalse(bad.ok());
        assertEquals(100_001, bad.column(), "the position survives the buffer growing");
    }

    @Test
    void decodesEmptyBytes() {
        TextEncodings.Decoded d = TextEncodings.decode(new byte[0], "Shift_JIS");
        assertTrue(d.ok());
        assertEquals("", d.text());
    }
}
