package com.editora.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GzipTextTest {

    static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static String text(GzipText.Read read) {
        return new String(read.bytes(), StandardCharsets.UTF_8);
    }

    @Test
    void namesOneCompressedFileNotAnArchive() {
        assertTrue(GzipText.isGzipName("app.log.2.gz"));
        assertTrue(GzipText.isGzipName("ACCESS.LOG.GZ"));
        assertFalse(GzipText.isGzipName("logs.tar.gz"));
        assertFalse(GzipText.isGzipName("logs.tgz"));
        assertFalse(GzipText.isGzipName(".gz"));
        assertEquals("app.log.2", GzipText.innerName("app.log.2.gz"));
        assertEquals("notes.txt", GzipText.innerName("notes.txt"));
    }

    @Test
    void readsAWholeFile(@TempDir Path dir) throws Exception {
        Path file = Files.write(dir.resolve("a.log.gz"), gzip("one\ntwo\n"));
        GzipText.Read read = GzipText.read(file, 1024, true);
        assertEquals("one\ntwo\n", text(read));
        assertEquals(8, read.total());
        assertFalse(read.cut());
    }

    @Test
    void keepsTheStartOrTheEndFromALineWhenTooBig() throws Exception {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            log.append("line ").append(i).append('\n');
        }
        byte[] gz = gzip(log.toString());
        GzipText.Read head = GzipText.read(new java.util.zip.GZIPInputStream(new ByteArrayInputStream(gz)), 100, false);
        assertTrue(head.cut());
        assertEquals(log.length(), head.total());
        assertEquals(log.substring(0, 100), text(head));

        GzipText.Read tail = GzipText.read(new java.util.zip.GZIPInputStream(new ByteArrayInputStream(gz)), 100, true);
        assertTrue(tail.cut());
        assertEquals(log.length(), tail.total());
        assertTrue(text(tail).startsWith("line "), "from the start of a line: " + text(tail));
        assertTrue(text(tail).endsWith("line 999\n"));
        assertTrue(text(tail).length() <= 100);
    }

    @Test
    void aFileThatIsNotGzipIsRefused(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("fake.log.gz"), "plain text");
        assertThrows(java.io.IOException.class, () -> GzipText.read(file, 1024, true));
    }
}
