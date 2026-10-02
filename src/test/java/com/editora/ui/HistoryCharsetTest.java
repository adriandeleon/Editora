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
}
