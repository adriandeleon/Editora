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
}
