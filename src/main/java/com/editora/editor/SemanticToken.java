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
     * The first line whose styling a new token list can change, or {@link Integer#MAX_VALUE} when replacing
     * {@code current} with {@code next} changes nothing — the same tokens over the same text, which is what
     * most responses are (a scroll that ends where it began, a typing pause with nothing new). A token list
     * only styles lines from its first token on, so the earlier of the two lists' first lines bounds the
     * restyle. {@code suppressed} says the current tokens are not on screen (the text was edited since they
     * were anchored), in which case even an identical list has to be painted again.
     */
    public static int firstChangedLine(
            java.util.List<SemanticToken> current, java.util.List<SemanticToken> next, boolean suppressed) {
        if (!suppressed && current.equals(next)) {
            return Integer.MAX_VALUE;
        }
        return Math.min(firstLine(current), firstLine(next));
    }

    private static int firstLine(java.util.List<SemanticToken> tokens) {
        int first = Integer.MAX_VALUE;
        for (SemanticToken token : tokens) {
            first = Math.min(first, Math.max(0, token.line()));
        }
        return first;
    }
}
