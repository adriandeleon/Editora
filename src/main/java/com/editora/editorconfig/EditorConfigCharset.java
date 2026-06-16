package com.editora.editorconfig;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Maps EditorConfig {@code charset} names to {@link Charset}s and handles byte-order marks, so files can be
 * decoded on read and encoded on write per {@code .editorconfig}. Names: {@code utf-8}, {@code utf-8-bom},
 * {@code latin1}, {@code utf-16le}, {@code utf-16be}. Pure (no I/O); the editor reads/writes the bytes.
 */
public final class EditorConfigCharset {

    public static final String UTF_8 = "utf-8";
    public static final String UTF_8_BOM = "utf-8-bom";
    public static final String LATIN1 = "latin1";
    public static final String UTF_16LE = "utf-16le";
    public static final String UTF_16BE = "utf-16be";

    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] BOM_UTF16LE = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] BOM_UTF16BE = {(byte) 0xFE, (byte) 0xFF};

    private EditorConfigCharset() {}

    /** The JVM charset for an EditorConfig name (defaults to UTF-8 for null/unknown). */
    public static Charset charsetFor(String name) {
        return switch (name == null ? "" : name) {
            case LATIN1 -> StandardCharsets.ISO_8859_1;
            case UTF_16LE -> StandardCharsets.UTF_16LE;
            case UTF_16BE -> StandardCharsets.UTF_16BE;
            default -> StandardCharsets.UTF_8; // utf-8 + utf-8-bom
        };
    }

    /** Whether files in this charset carry a leading BOM. */
    public static boolean writesBom(String name) {
        return UTF_8_BOM.equals(name) || UTF_16LE.equals(name) || UTF_16BE.equals(name);
    }

    /** The BOM bytes for this charset, or an empty array when none. */
    public static byte[] bomFor(String name) {
        return switch (name == null ? "" : name) {
            case UTF_8_BOM -> BOM_UTF8.clone();
            case UTF_16LE -> BOM_UTF16LE.clone();
            case UTF_16BE -> BOM_UTF16BE.clone();
            default -> new byte[0];
        };
    }

    /** The charset name a leading BOM indicates, or {@code null} if the bytes start with no recognized BOM. */
    public static String detectByBom(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (startsWith(bytes, BOM_UTF8)) {
            return UTF_8_BOM;
        }
        // UTF-16: BE first so FE FF isn't mistaken; LE FF FE is distinct.
        if (startsWith(bytes, BOM_UTF16BE)) {
            return UTF_16BE;
        }
        if (startsWith(bytes, BOM_UTF16LE)) {
            return UTF_16LE;
        }
        return null;
    }

    /** Decodes {@code bytes} as {@code name}, dropping a leading BOM for BOM charsets. */
    public static String decode(byte[] bytes, String name) {
        int skip = writesBom(name) ? bomFor(name).length : 0;
        skip = Math.min(skip, bytes.length);
        return new String(bytes, skip, bytes.length - skip, charsetFor(name));
    }

    /** Encodes {@code text} as {@code name}, prepending the BOM for BOM charsets. */
    public static byte[] encode(String text, String name) {
        byte[] body = text.getBytes(charsetFor(name));
        if (!writesBom(name)) {
            return body;
        }
        byte[] bom = bomFor(name);
        byte[] out = Arrays.copyOf(bom, bom.length + body.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    /** A human-readable label for the status bar (e.g. {@code "UTF-8 BOM"}, {@code "UTF-16 LE"}). */
    public static String displayName(String name) {
        return switch (name == null ? "" : name) {
            case UTF_8_BOM -> "UTF-8 BOM";
            case LATIN1 -> "ISO-8859-1";
            case UTF_16LE -> "UTF-16 LE";
            case UTF_16BE -> "UTF-16 BE";
            default -> "UTF-8";
        };
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
