package com.editora.ui;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.editora.editorconfig.EditorConfigCharset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Diff sides, merge stages and history captures are decoded into the text an editor buffer would hold. */
class DiffSideTextTest {

    private static final Charset CP1252 = Charset.forName("windows-1252");

    @Test
    void bytesThatAreNotValidUtf8AreReadLosslesslyNotWithReplacementCharacters() {
        byte[] bytes = "año café €\n".getBytes(CP1252);
        DiffSideText.Side side = DiffSideText.decode(bytes, null, null);
        assertEquals("año café €\n", side.text());
        assertEquals(EditorConfigCharset.WINDOWS_1252, side.charset());
        assertEquals(
                "año café \u0080\n", DiffSideText.decode(bytes, "utf-8", null).text(), "declared UTF-8: ISO-8859-1");
    }

    @Test
    void lineTerminatorsAreNormalisedAsTheEditorDoes() {
        assertEquals(
                "a\nb\nc\nd",
                DiffSideText.decode("a\r\nb\rc\nd".getBytes(StandardCharsets.UTF_8), null, null)
                        .text());
        assertEquals(
                "a\r\nb",
                DiffSideText.decodeRaw("a\r\nb".getBytes(StandardCharsets.UTF_8), null, null)
                        .text());
    }

    @Test
    void theOpenBuffersCharsetDecidesForAVersionOfThatFile() {
        // 0x81 is undefined in windows-1252, so on its own this blob would fall back to ISO-8859-1; the open
        // buffer was read as ISO-8859-1 too, and a blob that is valid UTF-8 must still be read like the buffer.
        byte[] utf8Looking = "cafÃ©\n".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("café\n", DiffSideText.decode(utf8Looking, null, null).text());
        DiffSideText.Side likeBuffer = DiffSideText.decode(utf8Looking, null, EditorConfigCharset.LATIN1);
        assertEquals("cafÃ©\n", likeBuffer.text());
        assertEquals(EditorConfigCharset.LATIN1, likeBuffer.charset());
    }

    @Test
    void aByteOrderMarkStillWinsOverTheOpenBuffersCharset() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        assertEquals(
                "hi", DiffSideText.decode(bom, null, EditorConfigCharset.LATIN1).text());
    }

    @Test
    void aVersionTheOpenCharsetCannotDecodeFallsBackToItsOwnBytes() {
        byte[] latin = {'a', (byte) 0xF1, 'o'};
        assertEquals(
                "año",
                DiffSideText.decode(latin, null, EditorConfigCharset.UTF_8).text());
    }
}
