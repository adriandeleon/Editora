package com.editora.editops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TextFingerprintTest {

    private static String sha256Utf16(String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_16BE);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void isTheSha256OfTheUtf16CodeUnits() throws Exception {
        assertEquals(sha256Utf16(""), TextFingerprint.of(""));
        assertEquals(sha256Utf16("hello\nwörld"), TextFingerprint.of("hello\nwörld"));
        assertEquals(64, TextFingerprint.of("x").length());
    }

    @Test
    void chunkBoundariesDoNotChangeTheAnswer() throws Exception {
        String justUnder = "a".repeat(4095) + "é";
        String exact = "b".repeat(4096);
        String over = "c".repeat(4096 * 3 + 17) + "😀";
        assertEquals(sha256Utf16(justUnder), TextFingerprint.of(justUnder));
        assertEquals(sha256Utf16(exact), TextFingerprint.of(exact));
        assertEquals(sha256Utf16(over), TextFingerprint.of(over));
    }

    @Test
    void tellsTextsApartThatSharePrefixLengthAndCharacters() {
        assertNotEquals(TextFingerprint.of("ab"), TextFingerprint.of("ba"));
        assertNotEquals(TextFingerprint.of("a\nb"), TextFingerprint.of("a\r\nb"));
        assertEquals(TextFingerprint.of(new StringBuilder("same")), TextFingerprint.of("same"));
    }
}
