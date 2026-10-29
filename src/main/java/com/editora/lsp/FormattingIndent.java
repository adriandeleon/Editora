package com.editora.lsp;

import org.jspecify.annotations.Nullable;

/**
 * The {@code tabSize}/{@code insertSpaces} pair a formatting request ({@code textDocument/formatting},
 * range and on-type formatting) carries for one document. Pure.
 *
 * <p>It describes the <b>document's</b> indentation — its EditorConfig {@code indent_style}/{@code indent_size}
 * when it has one, else what the file itself uses — not the global tab-size preference. Sending the global
 * value told formatters that honour it to re-indent everything: bash-language-server turns {@code tabSize}
 * into {@code shfmt -i}, and shfmt ignores {@code .editorconfig} once that flag is given, so a two-space
 * script came back indented by four.
 *
 * <p>A Format Document or Format Selection request also carries the file's save rules (EditorConfig
 * {@code trim_trailing_whitespace} / {@code insert_final_newline}, or the project's), so the server's answer
 * already looks like what saving writes: lemminx adds the final newline only when the request asks for it. Tab
 * re-indent and on-type formatting leave them out — they touch one line while the user types.
 *
 * <p>Two facts about the document ride along for formatters that take them as settings rather than request
 * options — the HTML server wraps at its {@code html.format.wrapLineLength} and keeps the final newline only for
 * {@code html.format.endWithNewline} (HTML review A3, D6): the EditorConfig {@code max_line_length} and whether the
 * text ends with a line break. {@link #options} never sends them.
 *
 * @param tabSize the indent width in columns (for a tab-indented file: the width a tab is shown with)
 * @param insertSpaces whether the document indents with spaces
 * @param trimTrailingWhitespace the file's {@code trim_trailing_whitespace}; null when unset (not sent)
 * @param insertFinalNewline the file's {@code insert_final_newline}; null when unset (not sent)
 * @param maxLineLength the file's EditorConfig {@code max_line_length} ({@code -1} for {@code off}); null when unset
 * @param endsWithNewline whether the document's text ends with a line break; null when unknown
 */
public record FormattingIndent(
        int tabSize,
        boolean insertSpaces,
        @Nullable Boolean trimTrailingWhitespace,
        @Nullable Boolean insertFinalNewline,
        @Nullable Integer maxLineLength,
        @Nullable Boolean endsWithNewline) {

    /** What is sent when nothing better is known. */
    static final int DEFAULT_TAB_SIZE = 4;

    /** Indentation only, with no save rules. */
    public FormattingIndent(int tabSize, boolean insertSpaces) {
        this(tabSize, insertSpaces, null, null);
    }

    /** Indentation and save rules, with nothing known about the document's text. */
    public FormattingIndent(
            int tabSize,
            boolean insertSpaces,
            @Nullable Boolean trimTrailingWhitespace,
            @Nullable Boolean insertFinalNewline) {
        this(tabSize, insertSpaces, trimTrailingWhitespace, insertFinalNewline, null, null);
    }

    /** This indentation with the file's save rules (each null when the file leaves it unset). */
    public FormattingIndent withSaveRules(@Nullable Boolean trim, @Nullable Boolean finalNewline) {
        return new FormattingIndent(tabSize, insertSpaces, trim, finalNewline, maxLineLength, endsWithNewline);
    }

    /** This indentation with the document's EditorConfig {@code max_line_length} and its last character. */
    public FormattingIndent withDocument(@Nullable Integer maxLength, @Nullable Boolean endsWithLineBreak) {
        return new FormattingIndent(
                tabSize, insertSpaces, trimTrailingWhitespace, insertFinalNewline, maxLength, endsWithLineBreak);
    }

    /**
     * Whether a formatter that decides the final newline itself should end the text with one: the file's
     * {@code insert_final_newline} when it sets one, else whether the text ends with one now (null: unknown).
     */
    public @Nullable Boolean finalNewline() {
        return insertFinalNewline != null ? insertFinalNewline : endsWithNewline;
    }

    /** The request's {@code FormattingOptions}: the indentation, plus each save rule the file sets. */
    public org.eclipse.lsp4j.FormattingOptions options() {
        org.eclipse.lsp4j.FormattingOptions options = new org.eclipse.lsp4j.FormattingOptions(tabSize, insertSpaces);
        if (trimTrailingWhitespace != null) {
            options.setTrimTrailingWhitespace(trimTrailingWhitespace);
        }
        if (insertFinalNewline != null) {
            options.setInsertFinalNewline(insertFinalNewline);
        }
        return options;
    }

    /**
     * The options for a document whose indent unit is {@code indentUnit} (its literal one-level indent: spaces
     * or a tab) and whose tabs are shown {@code tabSize} columns wide.
     */
    public static FormattingIndent of(String indentUnit, int tabSize) {
        int tab = tabSize > 0 ? tabSize : DEFAULT_TAB_SIZE;
        if (indentUnit == null || indentUnit.isEmpty()) {
            return new FormattingIndent(tab, true);
        }
        if (indentUnit.indexOf('\t') >= 0) {
            return new FormattingIndent(tab, false);
        }
        return new FormattingIndent(indentUnit.length(), true);
    }
}
