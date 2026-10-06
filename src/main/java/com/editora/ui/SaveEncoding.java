package com.editora.ui;

import java.nio.charset.CharsetEncoder;

import com.editora.editorconfig.EditorConfigCharset;

/**
 * Decides how a document's text becomes bytes when its charset cannot represent all of it. Pure.
 *
 * <p>Two cases look alike and must be treated in opposite ways:
 *
 * <ul>
 *   <li><b>A declared charset</b> ({@code charset = latin1} in {@code .editorconfig}) and the user typed
 *       {@code €}: the text is real, so it is written as UTF-8 rather than with a {@code ?} in place of
 *       the character. It is written <em>with a byte-order mark</em>, because the next open resolves the
 *       charset again and a BOM is the one thing that outranks the rule — without it the UTF-8 bytes
 *       were read back as Latin-1 mojibake, and the following save made that permanent.
 *   <li><b>An assumed charset</b>: the file could not be decoded as declared, so it is shown through a
 *       single-byte stand-in that keeps every byte (a Shift-JIS file displayed as ISO-8859-1). That text is
 *       not the file's text. Encoding it as UTF-8 turns every original byte above 0x7F into the UTF-8 form
 *       of an unrelated Latin-1 character: the whole file becomes mojibake that is neither its old encoding
 *       nor meaningful UTF-8. There is nothing safe to write, so the save is refused and the offending
 *       character named.
 * </ul>
 */
final class SaveEncoding {

    /**
     * @param bytes the bytes to write, or null when the save is refused
     * @param fallbackFrom the charset that could not hold the text when {@code bytes} are UTF-8 instead
     * @param refused the first character the assumed charset cannot represent, when the save is refused
     */
    record Plan(byte[] bytes, String fallbackFrom, Unencodable refused) {}

    /** A character the charset has no encoding for, and the 1-based line it is on. */
    record Unencodable(String character, int line) {}

    private SaveEncoding() {}

    /**
     * @param assumed the charset is a lossless stand-in, not the file's real encoding
     * @param bom false for a UTF-16 file that was read without a byte-order mark
     */
    static Plan plan(String text, String charset, boolean assumed, boolean bom) {
        if (EditorConfigCharset.canEncode(text, charset)) {
            return new Plan(EditorConfigCharset.encode(text, charset, bom), null, null);
        }
        if (assumed) {
            return new Plan(null, null, firstUnencodable(text, charset));
        }
        return new Plan(EditorConfigCharset.encode(text, EditorConfigCharset.UTF_8_BOM), charset, null);
    }

    static Unencodable firstUnencodable(String text, String charset) {
        CharsetEncoder encoder = EditorConfigCharset.charsetFor(charset).newEncoder();
        int line = 1;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (codePoint == '\n') {
                line++;
            } else if (codePoint >= 0x80 && !encoder.canEncode(text.subSequence(i, i + width))) {
                return new Unencodable(text.substring(i, i + width), line);
            }
            i += width;
        }
        return null;
    }
}
