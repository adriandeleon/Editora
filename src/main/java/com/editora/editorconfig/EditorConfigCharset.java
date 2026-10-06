package com.editora.editorconfig;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Maps EditorConfig {@code charset} names to {@link Charset}s and handles byte-order marks, so files can be
 * decoded on read and encoded on write per {@code .editorconfig}. Names: {@code utf-8}, {@code utf-8-bom},
 * {@code latin1}, {@code utf-16le}, {@code utf-16be}, plus the internal {@code windows-1252} fallback. Pure
 * (no I/O); the editor reads/writes the bytes.
 */
public final class EditorConfigCharset {

    public static final String UTF_8 = "utf-8";
    public static final String UTF_8_BOM = "utf-8-bom";
    public static final String LATIN1 = "latin1";
    public static final String UTF_16LE = "utf-16le";
    public static final String UTF_16BE = "utf-16be";
    /**
     * Not an EditorConfig value: the charset a BOM-less file that is not UTF-8 is assumed to be in when its
     * bytes allow it (see {@link #decodeLossless}).
     */
    public static final String WINDOWS_1252 = "windows-1252";

    /** Null where the runtime does not ship the charset (a trimmed native image); ISO-8859-1 is used then. */
    private static final Charset CP1252 = Charset.isSupported(WINDOWS_1252) ? Charset.forName(WINDOWS_1252) : null;

    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] BOM_UTF16LE = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] BOM_UTF16BE = {(byte) 0xFE, (byte) 0xFF};

    private EditorConfigCharset() {}

    /** The JVM charset for an EditorConfig name (defaults to UTF-8 for null/unknown). */
    public static Charset charsetFor(String name) {
        return switch (name == null ? "" : name) {
            case LATIN1 -> StandardCharsets.ISO_8859_1;
            case WINDOWS_1252 -> CP1252 != null ? CP1252 : StandardCharsets.ISO_8859_1;
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

    /**
     * The charset name to decode {@code bytes} with: a BOM, if present, wins; else the file's
     * {@code .editorconfig} charset ({@code editorConfigCharset}, or null when EditorConfig is off / the file
     * is remote / has no rule); else UTF-8. The same resolution the editor uses to read a file — so both sides
     * of a diff agree rather than the HEAD side force-decoding as UTF-8. Pure.
     */
    public static String resolveName(byte[] bytes, String editorConfigCharset) {
        String bom = detectByBom(bytes);
        if (bom != null) {
            return bom;
        }
        return editorConfigCharset != null ? editorConfigCharset : UTF_8;
    }

    /**
     * Decodes {@code bytes} as {@code name}, dropping a leading BOM only when the bytes <em>actually</em>
     * begin with this charset's BOM. A BOM charset (e.g. {@code utf-8-bom}) declared in {@code .editorconfig}
     * does not guarantee the on-disk file has a BOM, so skipping unconditionally would silently drop the
     * first 2-3 real content bytes of a BOM-less file.
     */
    public static String decode(byte[] bytes, String name) {
        byte[] bom = bomFor(name);
        int skip = bom.length > 0 && startsWith(bytes, bom) ? bom.length : 0;
        return new String(bytes, skip, bytes.length - skip, charsetFor(name));
    }

    /**
     * The result of a lossless decode: the text, the charset it was really decoded with, and whether that
     * charset is an assumption ({@code declared} could not decode the bytes).
     *
     * @param declared the charset the BOM / {@code .editorconfig} / UTF-8 default asked for
     */
    public record Decoded(String text, String charset, String declared, boolean assumed) {}

    /**
     * Decodes {@code bytes} so that encoding the result with the returned charset reproduces them.
     *
     * <p>The declared charset ({@link #resolveName}) is tried <b>strictly</b>. {@link #decode} substitutes
     * U+FFFD for every byte sequence the charset cannot represent, and that substitution is permanent: a
     * BOM-less Latin-1, Windows-1252 or Shift-JIS file read as UTF-8 came back with each non-ASCII character
     * replaced, and the next save wrote {@code EF BF BD} over the user's text. When the strict decode fails
     * the bytes are instead read with a single-byte charset that is reported as {@link Decoded#assumed}.
     *
     * <p>Which one depends on what keeps the round trip exact. ISO-8859-1 is the only single-byte charset in
     * the JDK that maps all 256 byte values, so it is always safe — but it shows 0x80–0x9F as invisible C1
     * controls, where the far more common windows-1252 file has curly quotes, dashes and {@code €}.
     * windows-1252 leaves five values undefined (0x81, 0x8D, 0x8F, 0x90, 0x9D; they occur as lead bytes in
     * Shift-JIS and other multi-byte encodings), and every other value maps to exactly one character. So a
     * BOM-or-default UTF-8 file with no {@code .editorconfig} charset that contains <em>none</em> of those
     * five is read as windows-1252, and anything else as ISO-8859-1. The text may display as mojibake for a
     * file that is really in another encoding, but every byte survives an edit and a save.
     */
    public static Decoded decodeLossless(byte[] bytes, String editorConfigCharset) {
        String declared = resolveName(bytes, editorConfigCharset);
        try {
            return new Decoded(decodeStrict(bytes, declared), declared, declared, false);
        } catch (CharacterCodingException malformed) {
            boolean utf8Default = editorConfigCharset == null && (UTF_8.equals(declared) || UTF_8_BOM.equals(declared));
            if (utf8Default && CP1252 != null && definedInWindows1252(bytes)) {
                return new Decoded(new String(bytes, CP1252), WINDOWS_1252, declared, true);
            }
            return new Decoded(new String(bytes, StandardCharsets.ISO_8859_1), LATIN1, declared, true);
        }
    }

    /** True when {@code bytes} holds none of the five values windows-1252 leaves undefined. */
    static boolean definedInWindows1252(byte[] bytes) {
        for (byte value : bytes) {
            switch (value & 0xFF) {
                case 0x81, 0x8D, 0x8F, 0x90, 0x9D -> {
                    return false;
                }
                default -> {}
            }
        }
        return true;
    }

    /** As {@link #decode}, but malformed or unmappable input is an error instead of U+FFFD. */
    public static String decodeStrict(byte[] bytes, String name) throws CharacterCodingException {
        String fast = decode(bytes, name);
        if (fast.indexOf('\uFFFD') < 0) {
            return fast; // nothing was substituted, so the intrinsic decode was already exact
        }
        // A replacement character is present: either the file really contains U+FFFD or the decoder
        // substituted it. Only the reporting decoder can tell the two apart.
        byte[] bom = bomFor(name);
        int skip = bom.length > 0 && startsWith(bytes, bom) ? bom.length : 0;
        return charsetFor(name)
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip))
                .toString();
    }

    /**
     * True when {@code bytes} are BOM-less UTF-16 text in the {@code declared} byte order: {@code declared}
     * is {@code utf-16le}/{@code utf-16be}, there is no BOM, and the bytes decode strictly with no NUL
     * character. Such a file is half NUL <em>bytes</em>, so a binary sniff sends it to the hex viewer although
     * {@code .editorconfig} says exactly what it is.
     */
    public static boolean isBomlessUtf16(byte[] bytes, String declared) {
        if (!(UTF_16LE.equals(declared) || UTF_16BE.equals(declared))
                || bytes == null
                || bytes.length == 0
                || bytes.length % 2 != 0
                || detectByBom(bytes) != null) {
            return false;
        }
        try {
            return decodeStrict(bytes, declared).indexOf('\0') < 0;
        } catch (CharacterCodingException notUtf16) {
            return false;
        }
    }

    /**
     * Encodes {@code text} as {@code name}; {@code bom} false leaves the byte-order mark off even for a BOM
     * charset, for a file that was read without one — a save must not add bytes the file never had.
     */
    public static byte[] encode(String text, String name, boolean bom) {
        return bom ? encode(text, name) : text.getBytes(charsetFor(name));
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

    /**
     * True when {@code text} can be written in charset {@code name} <b>without loss</b>.
     *
     * <p>{@code String.getBytes(Charset)} silently replaces anything the charset can't represent with
     * {@code '?'}. With an {@code .editorconfig} saying {@code charset = latin1}, every em dash, curly quote,
     * {@code €}, emoji or CJK character the user typed or pasted was written to disk as a question mark — and
     * the editor kept showing the real character until the file was reopened, so the corruption was invisible
     * until it was permanent. Callers check this first and fall back to UTF-8 rather than mangle the text.
     */
    public static boolean canEncode(String text, String name) {
        java.nio.charset.CharsetEncoder encoder = charsetFor(name).newEncoder();
        return encoder.canEncode(text);
    }

    /** A human-readable label for the status bar (e.g. {@code "UTF-8 BOM"}, {@code "UTF-16 LE"}). */
    public static String displayName(String name) {
        return switch (name == null ? "" : name) {
            case UTF_8_BOM -> "UTF-8 BOM";
            case LATIN1 -> "ISO-8859-1";
            case WINDOWS_1252 -> "Windows-1252";
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
