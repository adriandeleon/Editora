package com.editora.ui;

import java.nio.charset.CharacterCodingException;

import com.editora.editor.LineEndings;
import com.editora.editorconfig.EditorConfigCharset;

/**
 * Decodes the bytes of a diff side, a merge stage or a history capture the way the editor reads a file, so
 * that text always compares equal to what a buffer of the same bytes holds.
 *
 * <p>The editor loads a file with {@link EditorConfigCharset#decodeLossless} (a BOM-less file that is not
 * valid in its declared charset is read as windows-1252 / ISO-8859-1 instead of being filled with U+FFFD)
 * and keeps its text with bare {@code \n}. Sides decoded any other way disagreed with the buffer for the
 * same bytes: an unmodified Latin-1 file showed changes against HEAD and applying one wrote U+FFFD into the
 * document, and a hunk could never be applied to a closed CRLF file because its text was compared with the
 * buffer's LF text. Pure.
 */
final class DiffSideText {

    private DiffSideText() {}

    /** Decoded text in the editor's normal form ({@code \n} only) and the charset that reproduces the bytes. */
    record Side(String text, String charset) {}

    /**
     * Decodes {@code bytes} for the file they are a version of.
     *
     * @param editorConfigCharset the file's {@code .editorconfig} charset, or {@code null}
     * @param openCharset the effective charset of the file's open buffer, or {@code null} when it is not
     *     open; tried first so a blob and the buffer it is compared with cannot pick different stand-ins
     */
    static Side decode(byte[] bytes, String editorConfigCharset, String openCharset) {
        EditorConfigCharset.Decoded decoded = decodeRaw(bytes, editorConfigCharset, openCharset);
        return new Side(LineEndings.toLf(decoded.text()), decoded.charset());
    }

    /** As {@link #decode} but with the bytes' own line terminators left in the text. */
    static EditorConfigCharset.Decoded decodeRaw(byte[] bytes, String editorConfigCharset, String openCharset) {
        byte[] source = bytes == null ? new byte[0] : bytes;
        if (openCharset != null && EditorConfigCharset.detectByBom(source) == null) {
            try {
                String text = EditorConfigCharset.decodeStrict(source, openCharset);
                return new EditorConfigCharset.Decoded(text, openCharset, openCharset, false);
            } catch (CharacterCodingException notThatCharset) {
                // This version of the file is in another encoding than the open one: decide from its bytes.
            }
        }
        return EditorConfigCharset.decodeLossless(source, editorConfigCharset);
    }
}
