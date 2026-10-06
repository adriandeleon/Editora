package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import com.editora.editorconfig.EditorConfigCharset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What a save writes when the buffer's charset cannot represent its text. */
class SaveEncodingTest {

    @Test
    void textTheCharsetCanHoldIsWrittenInIt() {
        SaveEncoding.Plan plan = SaveEncoding.plan("café\n", EditorConfigCharset.LATIN1, false, true);

        assertEquals("636166e90a", HexFormat.of().formatHex(plan.bytes()));
        assertNull(plan.fallbackFrom());
        assertNull(plan.refused());
    }

    @Test
    void aStandInCharsetIsNeverReplacedByUtf8() {
        // Shift-JIS 日本 read through ISO-8859-1, plus a kanji the user typed on the second line.
        String standIn = new String(HexFormat.of().parseHex("93fa967b"), StandardCharsets.ISO_8859_1);

        SaveEncoding.Plan plan = SaveEncoding.plan(standIn + "\n日\n", EditorConfigCharset.LATIN1, true, true);

        assertNull(plan.bytes(), "nothing safe can be written: UTF-8 would re-encode every original byte");
        assertNull(plan.fallbackFrom());
        assertEquals(new SaveEncoding.Unencodable("日", 2), plan.refused());
    }

    @Test
    void aStandInCharsetThatCanHoldTheEditIsWrittenByteForByte() {
        byte[] original = HexFormat.of().parseHex("93fa967b0a");
        String standIn = new String(original, StandardCharsets.ISO_8859_1);

        SaveEncoding.Plan plan = SaveEncoding.plan(standIn, EditorConfigCharset.LATIN1, true, true);

        assertArrayEquals(original, plan.bytes());
    }

    @Test
    void aDeclaredCharsetFallsBackToUtf8WithAByteOrderMark() {
        SaveEncoding.Plan plan = SaveEncoding.plan("€café\n", EditorConfigCharset.LATIN1, false, true);

        // The BOM is what makes the next open read these bytes as UTF-8 despite `charset = latin1`.
        assertEquals("efbbbfe282ac636166c3a90a", HexFormat.of().formatHex(plan.bytes()));
        assertEquals(EditorConfigCharset.LATIN1, plan.fallbackFrom());
        assertEquals(
                "€café\n",
                EditorConfigCharset.decodeLossless(plan.bytes(), EditorConfigCharset.LATIN1)
                        .text());
    }

    @Test
    void theFirstUnencodableCharacterIsFoundWholeAndOnItsLine() {
        SaveEncoding.Unencodable emoji = SaveEncoding.firstUnencodable("a\nb\né 😀 €", EditorConfigCharset.LATIN1);

        assertNotNull(emoji);
        assertEquals("😀", emoji.character(), "a surrogate pair is one character");
        assertEquals(3, emoji.line());
        assertNull(SaveEncoding.firstUnencodable("plain é", EditorConfigCharset.LATIN1));
    }

    @Test
    void aBomlessUtf16FileKeepsNoByteOrderMark() {
        assertEquals(
                "68006900",
                HexFormat.of()
                        .formatHex(SaveEncoding.plan("hi", EditorConfigCharset.UTF_16LE, false, false)
                                .bytes()));
        assertEquals(
                "fffe68006900",
                HexFormat.of()
                        .formatHex(SaveEncoding.plan("hi", EditorConfigCharset.UTF_16LE, false, true)
                                .bytes()));
    }
}
