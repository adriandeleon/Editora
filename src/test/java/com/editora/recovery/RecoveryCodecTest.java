package com.editora.recovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryCodecTest {

    static RecoveryRecord record(String id, String path, String text) {
        return new RecoveryRecord(
                id, path, "notes.txt", null, "utf-8", true, "CRLF", 1234L, 56L, "abc", 99_000L, 3, "", text);
    }

    @Test
    void everyFieldAndTheTextComeBack() throws IOException {
        RecoveryRecord in = new RecoveryRecord(
                "b-1",
                "/home/u/notes \"quoted\"\n.txt",
                "notes.txt",
                "draft.md",
                "utf-16le",
                false,
                "CRLF",
                1_700_000_000_123L,
                4096L,
                "deadbeef",
                1_700_000_111_000L,
                7,
                "project-9",
                "línea uno\nline two 😀\n\ttabbed\n");
        assertEquals(in, RecoveryCodec.decode(RecoveryCodec.encode(in), true));
    }

    @Test
    void anUntitledBufferKeepsItsNullPath() throws IOException {
        RecoveryRecord out = RecoveryCodec.decode(RecoveryCodec.encode(record("b", null, "x")), true);
        assertNull(out.path());
        assertTrue(out.untitled());
    }

    @Test
    void aListingChecksTheTextButLeavesItOut() throws IOException {
        byte[] bytes = RecoveryCodec.encode(record("b", "/f", "hello"));
        assertNull(RecoveryCodec.decode(bytes, false).text());
        bytes[bytes.length - 1] ^= 1;
        assertThrows(IOException.class, () -> RecoveryCodec.decode(bytes, false));
    }

    /** UTF-8 cannot carry half a surrogate pair; the buffer's exact characters must still come back. */
    @Test
    void anUnpairedSurrogateSurvives() throws IOException {
        String text = "before \uD83D after";
        assertEquals(
                text,
                RecoveryCodec.decode(RecoveryCodec.encode(record("b", "/f", text)), true)
                        .text());
    }

    @Test
    void anEmptyDocumentIsARecord() throws IOException {
        assertEquals(
                "",
                RecoveryCodec.decode(RecoveryCodec.encode(record("b", null, "")), true)
                        .text());
    }

    @Test
    void aRecordCutShortAnywhereIsRejected() {
        byte[] whole = RecoveryCodec.encode(record("b", "/f", "some text\nthat matters\n"));
        for (int length = 0; length < whole.length; length++) {
            byte[] torn = Arrays.copyOf(whole, length);
            assertThrows(IOException.class, () -> RecoveryCodec.decode(torn, true), "cut at " + length);
        }
    }

    @Test
    void aRecordWithExtraOrAlteredBytesIsRejected() {
        byte[] whole = RecoveryCodec.encode(record("b", "/f", "some text"));
        byte[] longer = Arrays.copyOf(whole, whole.length + 1);
        assertThrows(IOException.class, () -> RecoveryCodec.decode(longer, true));
        byte[] flipped = whole.clone();
        flipped[whole.length - 3] ^= 0x20;
        assertThrows(IOException.class, () -> RecoveryCodec.decode(flipped, true));
    }

    @Test
    void somethingElseEntirelyIsRejected() {
        assertThrows(IOException.class, () -> RecoveryCodec.decode(new byte[0], true));
        assertThrows(IOException.class, () -> RecoveryCodec.decode("{\"a\":1}".getBytes(StandardCharsets.UTF_8), true));
        assertThrows(
                IOException.class,
                () -> RecoveryCodec.decode(
                        (RecoveryCodec.MAGIC + "not json\n").getBytes(StandardCharsets.UTF_8), true));
        assertThrows(
                IOException.class,
                () -> RecoveryCodec.decode((RecoveryCodec.MAGIC + "{}\n").getBytes(StandardCharsets.UTF_8), true));
    }
}
