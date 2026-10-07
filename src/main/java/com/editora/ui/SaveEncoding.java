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
 *   <li><b>Text that is not text</b>: an unpaired surrogate, left behind by an edit that split an emoji.
 *       No charset can represent it — not the file's, not UTF-8 — so this too is refused and the line named,
 *       rather than written as {@code ?}.
 * </ul>
 */
final class SaveEncoding {

    /**
     * @param bytes the bytes to write, or null when the save is refused
     * @param fallbackFrom the charset that could not hold the text when {@code bytes} are UTF-8 instead
     * @param refused the first character that cannot be written — one the assumed charset cannot
     *     represent, or an unpaired surrogate — when the save is refused
     */
    record Plan(byte[] bytes, String fallbackFrom, Unencodable refused) {}

    /** A character the charset has no encoding for, and the 1-based line it is on. */
    record Unencodable(String character, int line) {

        /** Half of a surrogate pair: not a character at all, so no charset has an encoding for it. */
        boolean unpairedSurrogate() {
            return character.length() == 1 && Character.isSurrogate(character.charAt(0));
        }

        /** The character as a message can show it; a lone surrogate has no glyph, so its code is given. */
        String display() {
            return unpairedSurrogate() ? String.format("U+%04X", (int) character.charAt(0)) : character;
        }
    }

    private SaveEncoding() {}

    /**
     * @param assumed the charset is a lossless stand-in, not the file's real encoding
     * @param bom false for a UTF-16 file that was read without a byte-order mark
     */
    static Plan plan(String text, String charset, boolean assumed, boolean bom) {
        if (EditorConfigCharset.canEncode(text, charset)) {
            return new Plan(EditorConfigCharset.encode(text, charset, bom), null, null);
        }
        // Half of a surrogate pair (an edit split an emoji) is why even UTF-8 or UTF-16 "cannot encode" a
        // text. The UTF-8 fallback below cannot hold it either: String.getBytes wrote '?' in its place, put
        // a byte-order mark on a file that had none, and the buffer was then marked clean over it.
        Unencodable broken = firstUnpairedSurrogate(text);
        if (broken != null) {
            return new Plan(null, null, broken);
        }
        if (assumed) {
            return new Plan(null, null, firstUnencodable(text, charset));
        }
        return new Plan(EditorConfigCharset.encode(text, EditorConfigCharset.UTF_8_BOM), charset, null);
    }

    /** The first half of a surrogate pair that stands alone in {@code text}, or null when there is none. */
    static Unencodable firstUnpairedSurrogate(String text) {
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++; // a whole pair
            } else if (Character.isSurrogate(c)) {
                return new Unencodable(String.valueOf(c), line);
            }
        }
        return null;
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
