package com.editora.diff;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryDiffTest {

    @Test
    void emptyContentIsText() {
        assertFalse(BinaryDiff.isProbablyBinary(null));
        assertFalse(BinaryDiff.isProbablyBinary(new byte[0]));
    }

    @Test
    void detectsNulDataButNotUtf16Bom() {
        assertTrue(BinaryDiff.isProbablyBinary(new byte[] {'a', 0, 'b'}));
        assertFalse(BinaryDiff.isProbablyBinary(new byte[] {(byte) 0xff, (byte) 0xfe, 'a', 0}));
        assertFalse(BinaryDiff.isProbablyBinary("plain text\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void descriptionIsStableAndIdentifiesCommonFormats() {
        String description = BinaryDiff.describe(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2});
        assertTrue(description.contains("PNG image"));
        assertTrue(description.contains("SHA-256"));
    }

    @Test
    void describesCommonBinaryFormatsAndReadableSizes() {
        assertTrue(BinaryDiff.describe(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})
                .contains("JPEG image"));
        assertTrue(
                BinaryDiff.describe(new byte[] {'G', 'I', 'F', '8', '9', 'a'}).contains("GIF image"));
        assertTrue(BinaryDiff.describe(new byte[] {'%', 'P', 'D', 'F', '-'}).contains("PDF"));
        assertTrue(BinaryDiff.describe(new byte[] {'P', 'K', 3, 4}).contains("ZIP archive"));
        assertTrue(BinaryDiff.describe(new byte[2 * 1024]).contains("2.0 KiB"));
        assertTrue(BinaryDiff.describe(new byte[1024 * 1024]).contains("1.0 MiB"));
        assertTrue(BinaryDiff.describe(null).contains("0 B"));
    }

    @Test
    void detectsDenseControlBytes() {
        byte[] bytes = "ordinary text with enough length".getBytes(StandardCharsets.UTF_8);
        bytes[0] = 1;
        bytes[1] = 2;
        bytes[2] = 3;

        assertTrue(BinaryDiff.isProbablyBinary(bytes));
    }

    @Test
    void aLargeFilesIdentityFollowsItsContent(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path a = java.nio.file.Files.writeString(dir.resolve("a.log"), "a".repeat(200_000));
        java.nio.file.Path same = java.nio.file.Files.writeString(dir.resolve("same.log"), "a".repeat(200_000));
        java.nio.file.Path b = java.nio.file.Files.writeString(dir.resolve("b.log"), "b".repeat(200_000));

        assertTrue(BinaryDiff.describeLarge(a).equals(BinaryDiff.describeLarge(same)));
        assertFalse(BinaryDiff.describeLarge(a).equals(BinaryDiff.describeLarge(b)));
        assertTrue(BinaryDiff.describeLarge(a).startsWith("195.3 KiB · SHA-256 "), BinaryDiff.describeLarge(a));
    }
}
