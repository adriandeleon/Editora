package com.editora.ui;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinarySniffTest {

    @Test
    void plainTextIsNotBinary() {
        assertFalse(BinarySniff.looksBinary("hello world\nsecond line\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void utf8ProseWithHighBytesIsNotBinary() {
        assertFalse(BinarySniff.looksBinary("café — naïve — 日本語".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void emptyOrNullIsNotBinary() {
        assertFalse(BinarySniff.looksBinary(new byte[0]));
        assertFalse(BinarySniff.looksBinary(null));
    }

    @Test
    void nulByteIsBinary() {
        assertTrue(BinarySniff.looksBinary(new byte[] {'a', 'b', 0x00, 'c'}));
    }

    @Test
    void bomMarkedUtf16TextIsNotBinaryDespiteNulBytes() {
        // UTF-16 LE BOM (FF FE) + "Hi" = 48 00 69 00 — contains NULs but is text.
        byte[] utf16 = {(byte) 0xFF, (byte) 0xFE, 0x48, 0x00, 0x69, 0x00};
        assertFalse(BinarySniff.looksBinary(utf16));
    }

    @Test
    void utf8BomTextIsNotBinary() {
        byte[] withBom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'o', 'k'};
        assertFalse(BinarySniff.looksBinary(withBom));
    }

    @Test
    void denseControlBytesAreBinary() {
        byte[] data = new byte[100];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 8); // 0x00..0x07 — but no need: half are control, and 0x00 triggers anyway
        }
        assertTrue(BinarySniff.looksBinary(data));
    }

    @Test
    void controlHeavyButNoNulIsBinary() {
        byte[] data = new byte[50];
        java.util.Arrays.fill(data, (byte) 0x01); // SOH control char, no NUL
        assertTrue(BinarySniff.looksBinary(data));
    }
}
