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

    // --- a deleted file comes back as the bytes that were deleted (V6) --------------------------------

    /** Capture → index row (through JSON, as it is stored) → restore with the file gone. */
    private static byte[] deletedThenRestored(byte[] original, String editorConfigCharset) throws Exception {
        String captured = HistoryCoordinator.decodeCaptured(original, editorConfigCharset);
        com.editora.config.HistoryRevision recorded = HistoryCoordinator.Encoding.of(original, editorConfigCharset)
                .on(new com.editora.config.HistoryRevision(
                        "/p/f.txt", 1L, 1L, "sha", com.editora.config.HistoryRevision.REASON_DELETE));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.editora.config.HistoryRevision stored =
                json.readValue(json.writeValueAsString(recorded), com.editora.config.HistoryRevision.class);
        assertEquals(recorded, stored);
        return HistoryCoordinator.restoredBytes(stored, captured, null, editorConfigCharset);
    }

    @Test
    void aDeletedFileIsRestoredInTheEncodingItWasDeletedIn() throws Exception {
        byte[] utf8Bom =
                withBom(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "héllo\n".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(utf8Bom, deletedThenRestored(utf8Bom, null), "the byte-order mark was dropped");

        byte[] utf16le = withBom(new byte[] {(byte) 0xFF, (byte) 0xFE}, TEXT.getBytes(StandardCharsets.UTF_16LE));
        assertArrayEquals(utf16le, deletedThenRestored(utf16le, null), "UTF-16 LE came back as UTF-8");
        byte[] utf16be = withBom(new byte[] {(byte) 0xFE, (byte) 0xFF}, TEXT.getBytes(StandardCharsets.UTF_16BE));
        assertArrayEquals(utf16be, deletedThenRestored(utf16be, null));
        byte[] utf16NoBom = "plain\r\n".getBytes(StandardCharsets.UTF_16LE);
        assertArrayEquals(
                utf16NoBom,
                deletedThenRestored(utf16NoBom, "utf-16le"),
                "no byte-order mark is added to a file without one");

        byte[] cp1252 = {'h', (byte) 0xE9, 'l', 'l', 'o', ' ', (byte) 0x80, '\n'}; // no rule, not valid UTF-8
        assertArrayEquals(cp1252, deletedThenRestored(cp1252, null), "windows-1252 came back as UTF-8");

        for (String text : new String[] {"héllo\nx\n", "héllo\r\nx\r\n", "a\r\nb\nc\r\n", "old mac\rline\r", ""}) {
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(utf8, deletedThenRestored(utf8, null), text);
            byte[] latin1 = text.getBytes(StandardCharsets.ISO_8859_1);
            assertArrayEquals(latin1, deletedThenRestored(latin1, "latin1"), text);
        }
    }

    @Test
    void aRevisionWithoutRecordedEncodingRestoresAsBefore() {
        var old = new com.editora.config.HistoryRevision(
                "/p/f.txt", 1L, 1L, "sha", com.editora.config.HistoryRevision.REASON_DELETE);
        assertArrayEquals(
                TEXT.getBytes(StandardCharsets.UTF_8), HistoryCoordinator.restoredBytes(old, TEXT, null, null));
        assertArrayEquals(
                TEXT.getBytes(StandardCharsets.ISO_8859_1),
                HistoryCoordinator.restoredBytes(old, TEXT, null, "latin1"));
    }

    @Test
    void recordedEncodingYieldsToAFileThatExistsAndToTextItCannotHold() {
        var utf16 = new com.editora.config.HistoryRevision(
                "/p/f.txt", 1L, 1L, "sha", "DELETE", "", "utf-16le", true, "CRLF");
        assertArrayEquals(
                "one\nTWO\n".getBytes(StandardCharsets.UTF_8),
                HistoryCoordinator.restoredBytes(utf16, "one\nTWO\n", "x\ny\n".getBytes(StandardCharsets.UTF_8), null),
                "a file now at the path decides, as for any other restore");
        var latin1 =
                new com.editora.config.HistoryRevision("/p/f.txt", 1L, 1L, "sha", "DELETE", "", "latin1", false, "LF");
        assertArrayEquals(
                "price €\n".getBytes(StandardCharsets.UTF_8),
                HistoryCoordinator.restoredBytes(latin1, "price €\n", null, null),
                "never '?' for a character the recorded charset cannot hold");
        // A body that lost its terminators' form (LF only) gets the recorded one back.
        assertArrayEquals(
                withBom(new byte[] {(byte) 0xFF, (byte) 0xFE}, "a\r\nb\r\n".getBytes(StandardCharsets.UTF_16LE)),
                HistoryCoordinator.restoredBytes(utf16, "a\nb\n", null, null));
    }
}
