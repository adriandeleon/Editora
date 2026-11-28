package com.editora.editor;

import com.editora.editops.LineEndings;
import org.jspecify.annotations.Nullable;

/**
 * The file-format settings of one buffer: its line ending and conversions, the tab size, the charset (detected,
 * chosen by the user, or forced by EditorConfig) and the EditorConfig overrides.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferFileFormat {

    private final EditorBuffer b;

    BufferFileFormat(EditorBuffer b) {
        this.b = b;
    }

    @Nullable
    private String charsetOverride; // EditorConfig charset to write; null = keep detected
    /** The declared charset could not decode the file, so {@link EditorBuffer#detectedCharset} is a lossless stand-in. */
    private boolean charsetAssumed;

    private com.editora.editorconfig.EditorConfigProperties editorConfigProps =
            com.editora.editorconfig.EditorConfigProperties.EMPTY;
    /**
     * Chooses the line ending the next save writes, and marks the buffer unsaved when that changes it. The
     * document always holds bare {@code \n} (RichTextFX splits paragraphs on any terminator), so there is
     * nothing to rewrite in the editor: the choice is applied to the bytes on save.
     */
    void convertLineEndings(boolean crlf) {
        String target = crlf ? LineEndings.CRLF : LineEndings.LF;
        if (!target.equals(b.lineEnding)) {
            b.lineEnding = target;
            b.dirty.set(
                    b.dirtyState.differsFromSaved()); // converting back to the file's own ending is no change at all
        }
    }
    /** Whether {@code .editorconfig} fixes the line ending, so a manual conversion cannot take effect. */
    boolean isLineEndingForced() {
        return b.eolOverride != null;
    }
    /** Sets the visual tab width: how wide a tab is drawn in both panes, and the minimap's. */
    void setTabSize(int tabSize) {
        b.tabSize = tabSize;
        TabStops.apply(b.viewHost, tabSize);
        b.minimap.setTabSize(tabSize);
        b.spell.geometryChanged();
        if (b.indentGuides != null) {
            b.indentGuides.refresh(); // where a tab's guides fall moved
        }
    }
    /** Forces the indent unit (EditorConfig {@code indent_style}/{@code indent_size}); null = auto-detect. */
    void setIndentOverride(@Nullable Boolean insertSpaces, @Nullable Integer size) {
        b.indentInsertSpacesOverride = insertSpaces;
        b.indentSizeOverride = size;
    }
    /** The EditorConfig line ending to write ({@code "LF"}/{@code "CRLF"}/{@code "CR"}); null = no override. */
    void setEolOverride(@Nullable String eol) {
        b.eolOverride = LineEndings.isLabel(eol) ? eol : null;
    }

    void setDetectedCharset(String charset) {
        setDetectedCharset(charset, false);
    }
    /** {@code assumed}: a lossless stand-in for a file its declared charset could not decode. */
    void setDetectedCharset(String charset, boolean assumed) {
        b.detectedCharset = charset == null ? com.editora.editorconfig.EditorConfigCharset.UTF_8 : charset;
        this.charsetAssumed = assumed;
    }
    /** The charset the file has on disk: the one it was decoded with, or the one a save last wrote. */
    String getDetectedCharset() {
        return b.detectedCharset;
    }

    void setCharsetOverride(@Nullable String charset) {
        this.charsetOverride = charset;
    }
    /** True when the text was decoded with a stand-in charset: it is the file's bytes, not its real text. */
    boolean isCharsetAssumed() {
        return charsetAssumed;
    }
    /**
     * The charset to write: the EditorConfig override if set, else the charset detected on open. An assumed
     * charset wins over the override — re-encoding text that was decoded with a stand-in would rewrite bytes
     * the override never understood.
     */
    String getEffectiveCharset() {
        if (b.userCharset != null) {
            return b.userCharset;
        }
        return charsetOverride != null && !charsetAssumed ? charsetOverride : b.detectedCharset;
    }
    /** The encoding the user chose for this file, or null when it follows EditorConfig and detection. */
    @Nullable
    String getUserCharset() {
        return b.userCharset;
    }
    /**
     * Chooses the encoding this buffer is written in, whatever EditorConfig says; null returns it to the rules.
     * A choice other than the encoding on disk is an unsaved change, like a line-ending conversion.
     */
    void setUserCharset(@Nullable String charset) {
        b.userCharset = charset;
        b.dirty.set(b.dirtyState.differsFromSaved());
    }
    /** A save wrote the file in {@code charset}: that is what is on disk now, and no longer a pending change. */
    void charsetSaved(String charset) {
        setDetectedCharset(charset);
        b.dirty.set(b.dirtyState.differsFromSaved());
    }

    void setEditorConfigProps(com.editora.editorconfig.EditorConfigProperties props) {
        this.editorConfigProps = props == null ? com.editora.editorconfig.EditorConfigProperties.EMPTY : props;
    }

    com.editora.editorconfig.EditorConfigProperties getEditorConfigProps() {
        return editorConfigProps;
    }
}
