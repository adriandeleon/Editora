package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An agent's write to a file with no open buffer keeps the file's encoding and hands back its pre-image. */
class AgentFileWritesTest {

    @Test
    void aLatin1CrlfFileStaysLatin1AndCrlf(@TempDir Path dir) throws IOException {
        byte[] before = "café\r\nnaïve\r\n".getBytes(StandardCharsets.ISO_8859_1);
        Path file = Files.write(dir.resolve("legacy.txt"), before);

        AgentFileWrites.Plan plan = AgentFileWrites.plan(file, "café\nnaïve\nmore\n", null);

        assertArrayEquals(before, plan.existing());
        assertEquals("café\nnaïve\n", plan.previousText(), "the Local History pre-image");
        assertEquals("café\r\nnaïve\r\n", plan.currentText(), "what a read serves");
        assertArrayEquals("café\r\nnaïve\r\nmore\r\n".getBytes(StandardCharsets.ISO_8859_1), plan.replacement());
        assertEquals("café\r\nnaïve\r\nmore\r\n", plan.replacementText());
    }

    @Test
    void aByteOrderMarkAndUtf16Survive(@TempDir Path dir) throws IOException {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        Path marked = Files.write(dir.resolve("bom.txt"), concat(bom, "old\n".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(
                concat(bom, "new é\n".getBytes(StandardCharsets.UTF_8)),
                AgentFileWrites.plan(marked, "new é\n", null).replacement());

        byte[] utf16 = concat(new byte[] {(byte) 0xFF, (byte) 0xFE}, "old\r\n".getBytes(StandardCharsets.UTF_16LE));
        Path wide = Files.write(dir.resolve("wide.txt"), utf16);
        AgentFileWrites.Plan plan = AgentFileWrites.plan(wide, "new\n", null);
        assertEquals("old\n", plan.previousText());
        assertArrayEquals(
                concat(new byte[] {(byte) 0xFF, (byte) 0xFE}, "new\r\n".getBytes(StandardCharsets.UTF_16LE)),
                plan.replacement());
    }

    @Test
    void aPlainUtf8FileAndANewFileAreWrittenAsGiven(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("a.txt"), "one\n");
        AgentFileWrites.Plan plan = AgentFileWrites.plan(file, "two é\n", null);
        assertArrayEquals("two é\n".getBytes(StandardCharsets.UTF_8), plan.replacement());

        AgentFileWrites.Plan created = AgentFileWrites.plan(dir.resolve("new.txt"), "x\r\ny\r\n", null);
        assertNull(created.existing());
        assertNull(created.previousText());
        assertArrayEquals("x\r\ny\r\n".getBytes(StandardCharsets.UTF_8), created.replacement());
    }

    @Test
    void textThatTheFilesCharsetCannotHoldFallsBackToUtf8RatherThanLosingCharacters(@TempDir Path dir)
            throws IOException {
        Path file = Files.write(dir.resolve("legacy.txt"), "café\n".getBytes(StandardCharsets.ISO_8859_1));
        byte[] replacement = AgentFileWrites.plan(file, "café 中\n", null).replacement();
        assertTrue(new String(replacement, StandardCharsets.UTF_8).contains("café 中"));
    }

    @Test
    void aBinaryOrReadOnlyTargetIsRefused(@TempDir Path dir) throws IOException {
        Path binary = Files.write(dir.resolve("image.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 1, 2});
        IOException notText = assertThrows(IOException.class, () -> AgentFileWrites.plan(binary, "text", null));
        assertTrue(notText.getMessage().contains("not a text file"), notText.getMessage());

        Path folder = Files.createDirectories(dir.resolve("folder"));
        assertThrows(IOException.class, () -> AgentFileWrites.plan(folder, "text", null));

        Path locked = Files.writeString(dir.resolve("locked.txt"), "keep\n");
        org.junit.jupiter.api.Assumptions.assumeTrue(locked.toFile().setWritable(false) && !Files.isWritable(locked));
        try {
            IOException readOnly = assertThrows(IOException.class, () -> AgentFileWrites.plan(locked, "x", null));
            assertTrue(readOnly.getMessage().contains("read-only"), readOnly.getMessage());
        } finally {
            locked.toFile().setWritable(true);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
