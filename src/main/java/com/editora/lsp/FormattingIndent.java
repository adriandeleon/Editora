package com.editora.lsp;

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
 * @param tabSize the indent width in columns (for a tab-indented file: the width a tab is shown with)
 * @param insertSpaces whether the document indents with spaces
 */
public record FormattingIndent(int tabSize, boolean insertSpaces) {

    /** What is sent when nothing better is known. */
    static final int DEFAULT_TAB_SIZE = 4;

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
