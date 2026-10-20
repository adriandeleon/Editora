package com.editora.io;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.editora.editorconfig.EditorConfigCharset;
import org.jspecify.annotations.Nullable;

/**
 * The encodings a user can choose for a file (Reopen with Encoding, Save with Encoding), and a strict decode
 * that says where a file stops being valid in one. Pure.
 *
 * <p>Names are the ones the rest of the editor already passes around: the {@link EditorConfigCharset} names
 * where one exists ({@code utf-8}, {@code utf-8-bom}, {@code latin1}, {@code utf-16le}, {@code utf-16be},
 * {@code windows-1252}), so a chosen UTF-16 file keeps its byte-order-mark handling, and the canonical Java
 * name for everything else ({@code Shift_JIS}, {@code windows-1251}).
 */
public final class TextEncodings {

    /** The script or region an encoding is for; the picker shows it next to the name. */
    public enum Group {
        UNICODE,
        WESTERN,
        CENTRAL_EUROPEAN,
        CYRILLIC,
        GREEK,
        TURKISH,
        HEBREW,
        ARABIC,
        BALTIC,
        VIETNAMESE,
        THAI,
        JAPANESE,
        CHINESE_SIMPLIFIED,
        CHINESE_TRADITIONAL,
        KOREAN,
        DOS,
        /** Everything else the runtime supports, listed after the common ones. */
        OTHER
    }

    /** One choice: the name stored and written, and its group. */
    public record Encoding(String name, Group group) {}

    /** The common encodings, in the order the picker lists them. Filtered by what the runtime supports. */
    static final List<Encoding> COMMON = List.of(
            new Encoding(EditorConfigCharset.UTF_8, Group.UNICODE),
            new Encoding(EditorConfigCharset.UTF_8_BOM, Group.UNICODE),
            new Encoding(EditorConfigCharset.UTF_16LE, Group.UNICODE),
            new Encoding(EditorConfigCharset.UTF_16BE, Group.UNICODE),
            new Encoding("UTF-32LE", Group.UNICODE),
            new Encoding("UTF-32BE", Group.UNICODE),
            new Encoding(EditorConfigCharset.LATIN1, Group.WESTERN),
            new Encoding("ISO-8859-15", Group.WESTERN),
            new Encoding(EditorConfigCharset.WINDOWS_1252, Group.WESTERN),
            new Encoding("x-MacRoman", Group.WESTERN),
            new Encoding("ISO-8859-2", Group.CENTRAL_EUROPEAN),
            new Encoding("windows-1250", Group.CENTRAL_EUROPEAN),
            new Encoding("windows-1251", Group.CYRILLIC),
            new Encoding("KOI8-R", Group.CYRILLIC),
            new Encoding("KOI8-U", Group.CYRILLIC),
            new Encoding("ISO-8859-5", Group.CYRILLIC),
            new Encoding("IBM866", Group.CYRILLIC),
            new Encoding("ISO-8859-7", Group.GREEK),
            new Encoding("windows-1253", Group.GREEK),
            new Encoding("ISO-8859-9", Group.TURKISH),
            new Encoding("windows-1254", Group.TURKISH),
            new Encoding("ISO-8859-8", Group.HEBREW),
            new Encoding("windows-1255", Group.HEBREW),
            new Encoding("ISO-8859-6", Group.ARABIC),
            new Encoding("windows-1256", Group.ARABIC),
            new Encoding("ISO-8859-13", Group.BALTIC),
            new Encoding("windows-1257", Group.BALTIC),
            new Encoding("windows-1258", Group.VIETNAMESE),
            new Encoding("TIS-620", Group.THAI),
            new Encoding("x-windows-874", Group.THAI),
            new Encoding("Shift_JIS", Group.JAPANESE),
            new Encoding("windows-31j", Group.JAPANESE),
            new Encoding("EUC-JP", Group.JAPANESE),
            new Encoding("ISO-2022-JP", Group.JAPANESE),
            new Encoding("GB18030", Group.CHINESE_SIMPLIFIED),
            new Encoding("GBK", Group.CHINESE_SIMPLIFIED),
            new Encoding("GB2312", Group.CHINESE_SIMPLIFIED),
            new Encoding("Big5", Group.CHINESE_TRADITIONAL),
            new Encoding("Big5-HKSCS", Group.CHINESE_TRADITIONAL),
            new Encoding("EUC-KR", Group.KOREAN),
            new Encoding("x-windows-949", Group.KOREAN),
            new Encoding("IBM437", Group.DOS),
            new Encoding("IBM850", Group.DOS));

    private TextEncodings() {}

    /**
     * Every encoding the user can choose: the common ones the runtime supports, then each other charset the
     * runtime has, by its canonical name — none listed twice.
     */
    public static List<Encoding> choices() {
        List<Encoding> out = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (Encoding e : COMMON) {
            if (supported(e.name())) {
                out.add(e);
                listed.add(EditorConfigCharset.charsetFor(e.name()).name());
            }
        }
        for (String name : Charset.availableCharsets().keySet()) {
            if (listed.add(name)) {
                out.add(new Encoding(normalize(name), Group.OTHER));
            }
        }
        return out;
    }

    /** Whether {@code name} — an EditorConfig name or a Java one — is a charset this runtime can use. */
    public static boolean supported(String name) {
        return switch (name == null ? "" : name) {
            case EditorConfigCharset.UTF_8,
                    EditorConfigCharset.UTF_8_BOM,
                    EditorConfigCharset.LATIN1,
                    EditorConfigCharset.UTF_16LE,
                    EditorConfigCharset.UTF_16BE -> true;
            default -> EditorConfigCharset.isSupported(name);
        };
    }

    /**
     * The name the editor uses for a Java charset name: its EditorConfig name when it has one (so
     * {@code UTF-8} and {@code utf-8} are one choice), else the canonical Java name. Unsupported names are
     * returned as they are.
     */
    public static String normalize(String javaName) {
        if (!EditorConfigCharset.isSupported(javaName)) {
            return javaName;
        }
        String canonical = Charset.forName(javaName).name();
        return switch (canonical) {
            case "UTF-8" -> EditorConfigCharset.UTF_8;
            case "ISO-8859-1" -> EditorConfigCharset.LATIN1;
            case "UTF-16LE" -> EditorConfigCharset.UTF_16LE;
            case "UTF-16BE" -> EditorConfigCharset.UTF_16BE;
            default -> canonical;
        };
    }

    /**
     * What a strict decode found: the text and the name it was decoded as, or — when the bytes are not valid
     * in that encoding — the 1-based line and column of the first character that could not be read.
     *
     * @param charset the name decoded as; {@code utf-8} becomes {@code utf-8-bom} for bytes that start with
     *     a UTF-8 byte-order mark, so the mark is neither shown nor lost
     */
    public record Decoded(@Nullable String text, String charset, int line, int column) {

        public boolean ok() {
            return text != null;
        }
    }

    /**
     * Decodes {@code bytes} as {@code name} without substituting anything: malformed or unmappable input is
     * reported with its position, never replaced with U+FFFD (which a save would then write back). A leading
     * byte-order mark of the chosen encoding is dropped from the text.
     */
    public static Decoded decode(byte[] bytes, String name) {
        String effective = EditorConfigCharset.UTF_8.equals(name)
                        && EditorConfigCharset.UTF_8_BOM.equals(EditorConfigCharset.detectByBom(bytes))
                ? EditorConfigCharset.UTF_8_BOM
                : name;
        byte[] bom = EditorConfigCharset.bomFor(effective);
        int skip = bom.length > 0 && effective.equals(EditorConfigCharset.detectByBom(bytes)) ? bom.length : 0;
        CharsetDecoder decoder = EditorConfigCharset.charsetFor(effective)
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes, skip, bytes.length - skip);
        // A quarter of the bytes, then doubling: a multi-byte encoding needs fewer chars than bytes, and the
        // few doublings a single-byte file costs are cheap next to reading it.
        CharBuffer out = CharBuffer.allocate(Math.max(16, in.remaining() / 4));
        CoderResult result = decoder.decode(in, out, true);
        while (true) {
            if (result.isOverflow()) {
                out = grow(out);
            } else if (result.isError()) {
                return malformed(out, effective);
            } else {
                break;
            }
            result = decoder.decode(in, out, true);
        }
        result = decoder.flush(out);
        while (result.isOverflow()) {
            out = grow(out);
            result = decoder.flush(out);
        }
        if (result.isError()) {
            return malformed(out, effective);
        }
        out.flip();
        return new Decoded(out.toString(), effective, 0, 0);
    }

    private static CharBuffer grow(CharBuffer out) {
        CharBuffer bigger = CharBuffer.allocate(out.capacity() * 2);
        out.flip();
        bigger.put(out);
        return bigger;
    }

    /** The position just after the text that did decode: where the first unreadable character is. */
    private static Decoded malformed(CharBuffer decodedSoFar, String charset) {
        decodedSoFar.flip();
        int line = 1;
        int column = 1;
        for (int i = 0; i < decodedSoFar.length(); i++) {
            if (decodedSoFar.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new Decoded(null, charset, line, column);
    }
}
