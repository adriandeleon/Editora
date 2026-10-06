package com.editora.ui;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Local History reads and writes files in the charset the editor would use, not a hard-coded UTF-8. */
class HistoryCharsetTest {

    private static final String TEXT = "café naïve\r\nsecond line\n";

    private static byte[] withBom(byte[] bom, byte[] body) {
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    @Test
    void preDeleteCaptureDecodesWithTheFilesCharset() {
        // As UTF-8, each of these bytes became U+FFFD in what may be the only copy left.
        assertEquals(TEXT, HistoryCoordinator.decodeCaptured(TEXT.getBytes(StandardCharsets.ISO_8859_1), "latin1"));
        byte[] utf16 = withBom(new byte[] {(byte) 0xFF, (byte) 0xFE}, TEXT.getBytes(StandardCharsets.UTF_16LE));
        assertEquals(TEXT, HistoryCoordinator.decodeCaptured(utf16, null), "a byte-order mark wins over the default");
        assertEquals(TEXT, HistoryCoordinator.decodeCaptured(utf16, "latin1"), "…and over an .editorconfig rule");
        assertEquals(TEXT, HistoryCoordinator.decodeCaptured(TEXT.getBytes(StandardCharsets.UTF_8), null));
    }

    @Test
    void restoreToDiskWritesTheCharsetTheEditorWouldUse() {
        assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8), HistoryCoordinator.restoredBytes(TEXT, null, null));
        assertArrayEquals(
                TEXT.getBytes(StandardCharsets.ISO_8859_1),
                HistoryCoordinator.restoredBytes(TEXT, null, "latin1"),
                "a deleted file is recreated in its .editorconfig charset");
        byte[] bom = {(byte) 0xFE, (byte) 0xFF};
        byte[] existing = withBom(bom, "old".getBytes(StandardCharsets.UTF_16BE));
        assertArrayEquals(
                withBom(bom, TEXT.getBytes(StandardCharsets.UTF_16BE)),
                HistoryCoordinator.restoredBytes(TEXT, existing, null),
                "an existing UTF-16 file stays UTF-16, byte-order mark included");
        assertArrayEquals(
                "price €".getBytes(StandardCharsets.UTF_8),
                HistoryCoordinator.restoredBytes("price €", null, "latin1"),
                "text the charset cannot hold falls back to UTF-8 rather than writing '?'");
    }

    @Test
    void preDeleteCaptureOfAFileThatIsNotValidUtf8KeepsItsCharacters() {
        // BOM-less windows-1252 with no .editorconfig charset: the editor reads it losslessly, and so must the
        // capture — as UTF-8 with replacement this stored "a\uFFFDo caf\uFFFD".
        byte[] bytes = {'a', (byte) 0xF1, 'o', ' ', 'c', 'a', 'f', (byte) 0xE9, '\r', '\n'};
        assertEquals("año café\r\n", HistoryCoordinator.decodeCaptured(bytes, null));
        assertEquals("año café\r\n", HistoryCoordinator.decodeCaptured(bytes, "utf-8"));
    }

    @Test
    void restoreToDiskKeepsTheLineEndingsOfTheFileItReplaces() {
        byte[] crlf = "one\r\ntwo\r\nthree\r\n".getBytes(StandardCharsets.UTF_8);
        // Revisions hold the editor's \n-only text.
        assertArrayEquals(
                "one\r\nTWO\r\n".getBytes(StandardCharsets.UTF_8),
                HistoryCoordinator.restoredBytes("one\nTWO\n", crlf, null));
        assertArrayEquals(
                "one\nTWO\n".getBytes(StandardCharsets.UTF_8),
                HistoryCoordinator.restoredBytes("one\r\nTWO\r\n", "x\ny\n".getBytes(StandardCharsets.UTF_8), null),
                "and a pre-delete capture with CRLF follows an LF file that now sits at the path");
    }

    @Test
    void restoreToDiskKeepsTheAssumedCharsetOfTheFileItReplaces() {
        java.nio.charset.Charset cp1252 = java.nio.charset.Charset.forName("windows-1252");
        byte[] existing = "café €\n".getBytes(cp1252); // not valid UTF-8, no byte-order mark, no rule
        assertArrayEquals(
                "café € más\n".getBytes(cp1252),
                HistoryCoordinator.restoredBytes("café € más\n", existing, null),
                "restoring over a windows-1252 file must not rewrite it as UTF-8");
    }
}
