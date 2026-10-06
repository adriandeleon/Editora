package com.editora.editor;

/**
 * A resolved highlight span from an LSP server's semantic tokens: 0-based {@code line} and
 * {@code startChar}, a character {@code length}, and a space-separated CSS class string
 * (e.g. {@code "sem-parameter"} or {@code "sem-type sem-deprecated"}).
 *
 * <p>This is a neutral value type so the {@code editor} package never imports lsp4j — it mirrors
 * {@link LspDiagnostic} / {@link LspTextEdit}. The {@code lsp} layer decodes the wire format into
 * these; {@code EditorBuffer} overlays them onto the TextMate highlight.
 */
public record SemanticToken(int line, int startChar, int length, String cssClasses) {

    /**
     * The inclusive line range {@code {first, last}} (lines of the current text) whose styling changes when
     * {@code next} replaces {@code current}, or {@code null} when nothing does — the same tokens over the
     * same text, which is what most responses are (a scroll that ends where it began, a typing pause with
     * nothing new). Only that range has to be restyled, not everything from its first line to the end of
     * the document.
     *
     * <p>Tokens the two lists share at their start and at their end are already on screen and bound the
     * range. After an edit the old tokens are still painted too — the styles moved with the text — just
     * {@code delta} lines further down below the edit, so a shared tail is recognised through that shift:
     * typing on one line of a fully tokenized file restyles that line, not the file. The exception is the
     * {@code erased} line range, where the old overlay is gone (the edited lines themselves, and whatever
     * the lexical pass restyled without it): old tokens there need no clearing, and new ones there always
     * need painting.
     *
     * @param erasedFirst first line (current text) of the range where {@code current} is no longer
     *     painted, or negative when the overlay is intact
     * @param erasedLast last line of that range
     * @param delta lines the text has gained since {@code current} was anchored (0 when intact)
     */
    public static int[] changedLines(
            java.util.List<SemanticToken> current,
            java.util.List<SemanticToken> next,
            int erasedFirst,
            int erasedLast,
            int delta) {
        boolean intact = erasedFirst < 0;
        int shift = intact ? 0 : delta;
        int oldErasedLast = erasedLast - shift;
        int currentSize = current.size();
        int nextSize = next.size();
        int prefix = 0;
        while (prefix < currentSize
                && prefix < nextSize
                && (intact || current.get(prefix).line() < erasedFirst)
                && current.get(prefix).equals(next.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < currentSize - prefix && suffix < nextSize - prefix) {
            SemanticToken was = current.get(currentSize - 1 - suffix);
            SemanticToken now = next.get(nextSize - 1 - suffix);
            if ((!intact && was.line() <= oldErasedLast)
                    || now.line() != was.line() + shift
                    || now.startChar() != was.startChar()
                    || now.length() != was.length()
                    || !now.cssClasses().equals(was.cssClasses())) {
                break;
            }
            suffix++;
        }
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (int i = prefix; i < currentSize - suffix; i++) {
            int line = current.get(i).line();
            if (!intact && line >= erasedFirst) {
                if (line <= oldErasedLast) {
                    continue; // already cleared
                }
                line += shift;
            }
            first = Math.min(first, line);
            last = Math.max(last, line);
        }
        for (int i = prefix; i < nextSize - suffix; i++) {
            first = Math.min(first, next.get(i).line());
            last = Math.max(last, next.get(i).line());
        }
        return last < 0 ? null : new int[] {Math.max(0, first), last};
    }
}
