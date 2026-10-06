package com.editora.logviewer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTailTest {

    @Test
    void firstLineStartSkipsPartialFirstLine() {
        assertEquals(3, LogTail.firstLineStart("ab\ncd".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, LogTail.firstLineStart("no newline here".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void completeEndTrimsIncompleteUtf8() {
        assertEquals(3, LogTail.completeEnd("abc".getBytes(StandardCharsets.UTF_8)));

        byte[] euro = "€".getBytes(StandardCharsets.UTF_8); // 3 bytes: E2 82 AC
        assertEquals(3, euro.length);
        assertEquals(3, LogTail.completeEnd(euro), "complete 3-byte char is kept");
        assertEquals(0, LogTail.completeEnd(new byte[] {euro[0], euro[1]}), "lead+1 continuation trimmed");
        assertEquals(0, LogTail.completeEnd(new byte[] {euro[0]}), "lone lead byte trimmed");

        byte[] eacute = "é".getBytes(StandardCharsets.UTF_8); // 2 bytes: C3 A9
        assertEquals(2, LogTail.completeEnd(eacute));
        assertEquals(0, LogTail.completeEnd(new byte[] {eacute[0]}));

        // ASCII tail after a complete multibyte char keeps everything.
        byte[] mixed = "a€b".getBytes(StandardCharsets.UTF_8);
        assertEquals(mixed.length, LogTail.completeEnd(mixed));
    }

    @Test
    void readTailDropsPartialFirstLineForBigFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("app.log");
        Files.writeString(f, "line one\nline two\nline three\nline four\n", StandardCharsets.UTF_8);
        LogTail.Tail tail = LogTail.readTail(f, 20); // less than the whole file
        assertFalse(tail.text().contains("line one"), "partial first line of the slice is dropped");
        assertTrue(tail.text().contains("line four"));
        assertEquals(Files.size(f), tail.offset(), "offset is at EOF so a follow resumes correctly");
    }

    @Test
    void readTailReturnsWholeSmallFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("small.log");
        Files.writeString(f, "alpha\nbeta\n", StandardCharsets.UTF_8);
        LogTail.Tail tail = LogTail.readTail(f, 1 << 20);
        assertEquals("alpha\nbeta\n", tail.text());
    }

    @Test
    void readAppendedReadsOnlyTheDelta(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("growing.log");
        Files.writeString(f, "first\n", StandardCharsets.UTF_8);
        long offset = Files.size(f);

        Files.writeString(f, "second\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        LogTail.Append a = LogTail.readAppended(f, offset);
        assertEquals("second\n", a.text());
        assertFalse(a.reset());
        assertEquals(Files.size(f), a.offset());

        // No new bytes -> empty append.
        LogTail.Append none = LogTail.readAppended(f, a.offset());
        assertEquals("", none.text());
        assertFalse(none.reset());
    }

    @Test
    void readAppendedDetectsRotation(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("rotated.log");
        Files.writeString(f, "a lot of old content here\n", StandardCharsets.UTF_8);
        long offset = Files.size(f);
        // File rotated/truncated: now smaller than the prior offset.
        Files.writeString(f, "fresh\n", StandardCharsets.UTF_8);
        LogTail.Append a = LogTail.readAppended(f, offset);
        assertTrue(a.reset(), "shrunk file signals a reset (reload)");
        assertEquals("fresh\n", a.text());
    }

    // --- a follow step never reads more than the viewer keeps ---------------------------------------------

    @Test
    void aBurstLargerThanTheCapIsReadFromItsTailAtALineBoundary(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path log = dir.resolve("app.log");
        StringBuilder sb = new StringBuilder("first\n");
        for (int i = 0; i < 1000; i++) {
            sb.append("line ").append(i).append('\n');
        }
        java.nio.file.Files.writeString(log, sb);
        long size = java.nio.file.Files.size(log);

        LogTail.Append a = LogTail.readAppended(log, 6, 100); // everything after "first\n", at most 100 bytes

        assertFalse(a.reset());
        assertEquals(size, a.offset(), "the follow resumes at the true end");
        assertTrue(a.text().length() <= 100, "read " + a.text().length());
        assertTrue(a.text().endsWith("line 999\n"));
        assertTrue(
                a.text().startsWith("line "),
                "it starts on a whole line: " + a.text().substring(0, 12));
        // An append within the cap is unchanged: every byte since the offset.
        assertEquals(sb.substring(6), LogTail.readAppended(log, 6, 1 << 20).text());
        assertEquals(sb.substring(6), LogTail.readAppended(log, 6).text());
    }

    @Test
    void aRotationToALargeFileReadsItsTailNotAllOfIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path log = dir.resolve("app.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("rotated ").append(i).append('\n');
        }
        java.nio.file.Files.writeString(log, sb);
        long size = java.nio.file.Files.size(log);

        LogTail.Append a = LogTail.readAppended(log, size + 500, 64); // we were further along: it shrank

        assertTrue(a.reset());
        assertEquals(size, a.offset());
        assertTrue(a.text().length() <= 64);
        assertTrue(a.text().startsWith("rotated ") && a.text().endsWith("rotated 999\n"), a.text());
        // A small rotated file still comes back whole.
        LogTail.Append whole = LogTail.readAppended(log, size + 500, 1 << 20);
        assertTrue(whole.reset());
        assertEquals(sb.toString(), whole.text());
    }

    @Test
    void aCutReadStillHoldsBackAHalfWrittenCharacter(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path log = dir.resolve("app.log");
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        bytes.write("aaaaaaaaaaaaaaaaaaaaaaaa\nbb\ncc".getBytes(StandardCharsets.UTF_8));
        bytes.write(euro, 0, 2); // the writer is mid-character
        java.nio.file.Files.write(log, bytes.toByteArray());

        LogTail.Append a = LogTail.readAppended(log, 0, 10);

        assertEquals("bb\ncc", a.text());
        assertEquals(bytes.size() - 2, a.offset(), "the two bytes of the unfinished character are read next time");
    }
}
