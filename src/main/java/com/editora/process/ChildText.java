package com.editora.process;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * The text encoding of a child that runs in the <em>user's</em> locale (a run, a build, a test, an external
 * tool). Such a child writes whatever its runtime picks for a pipe: Node, Go and Rust always write UTF-8,
 * while a JVM and Python write the platform's native encoding — the ANSI code page on Windows, ISO-8859-1
 * under a legacy Unix locale. Decoding everything as UTF-8 turned every non-ASCII character of the second
 * group into U+FFFD.
 *
 * <p>So output is decoded as strict UTF-8 first — text in a legacy encoding is practically never valid UTF-8
 * — and falls back to the native encoding when it is not. On a UTF-8 system both are the same and nothing
 * changes. Pure.
 */
public final class ChildText {

    private static final Charset NATIVE = nativeCharset(System.getProperty("native.encoding"));

    private ChildText() {}

    /** The charset named by {@code native.encoding}; UTF-8 when it is missing or unknown. */
    static Charset nativeCharset(String name) {
        try {
            return name == null || name.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** The platform's native encoding — what a JVM or Python child reads on stdin and writes to a pipe. */
    public static Charset nativeCharset() {
        return NATIVE;
    }

    /** Decodes a child's output: UTF-8 when it is valid UTF-8, else the native encoding. */
    public static String decode(byte[] bytes) {
        return decode(bytes, 0, bytes.length, NATIVE);
    }

    /** As {@link #decode(byte[])} on a slice. */
    public static String decode(byte[] bytes, int offset, int length) {
        return decode(bytes, offset, length, NATIVE);
    }

    /** As {@link #decode(byte[], int, int)} with an explicit fallback (the form the unit tests drive). */
    static String decode(byte[] bytes, int offset, int length, Charset fallback) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, offset, length, fallback); // lenient: U+FFFD when the fallback is UTF-8 too
        }
    }

    /** Encodes text typed for a child's stdin the way that child reads it: in the native encoding. */
    public static byte[] encodeInput(String text) {
        return text.getBytes(NATIVE);
    }

    /**
     * How many bytes at the end of {@code bytes[0, length)} are the start of a UTF-8 character whose remaining
     * bytes have not arrived (0–3). A stream cut there — a partial line flushed mid-character, a line capped
     * mid-character — must hold those bytes back, or valid UTF-8 would look malformed and take the fallback.
     */
    static int incompleteUtf8Tail(byte[] bytes, int length) {
        for (int back = 1; back <= 3 && back <= length; back++) {
            int b = bytes[length - back] & 0xFF;
            if ((b & 0xC0) == 0x80) {
                continue; // a continuation byte: keep looking for its lead
            }
            int expected = b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : b >= 0xC2 ? 2 : 1;
            return expected > back ? back : 0;
        }
        return 0;
    }
}
