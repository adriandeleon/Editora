package com.editora.completion;

import com.editora.snippet.Snippet;

/**
 * One entry in the autocomplete popup. {@code insert} is the text that replaces the typed prefix when
 * accepted; {@code label} is what the popup shows; {@code detail} is the muted right-hand hint (a snippet
 * description, a word source, or an LSP type/signature). For a {@link Kind#SNIPPET} completion,
 * {@code snippet} is non-null so the editor can start a full snippet session (with tab stops) instead of
 * inserting literal text.
 *
 * <p>The remaining fields drive the IntelliJ-style presentation: {@code iconKind} picks the per-row glyph,
 * {@code sortText}/{@code preselect} drive relevance ordering, {@code deprecated} strikes the label, and
 * {@code resolveToken} is an opaque handle (the LSP {@code CompletionItem}, kept as {@code Object} so this
 * package never depends on lsp4j) the controller resolves to lazily fetch the documentation popup's text.
 */
public record Completion(
        String label,
        String insert,
        Kind kind,
        String detail,
        Snippet snippet,
        Runnable onAccept,
        CompletionIconKind iconKind,
        String sortText,
        boolean preselect,
        boolean deprecated,
        Object resolveToken,
        ReplaceStart replaceStart) {

    public enum Kind {
        SNIPPET,
        WORD
    }

    /**
     * The LSP {@code textEdit.range.start} of the replaced range (0-based line/character), when a language
     * server sent one. Editors must replace the server-specified range on accept, not just the identifier
     * before the caret — the two differ when a server advertises a trigger character that is part of its own
     * insert text (phpactor's {@code $}, bash variables). Null for word/snippet items and {@code insertText}-
     * only LSP items. The end of the replaced range is always the current caret (absorbing chars typed since
     * the request).
     */
    public record ReplaceStart(int line, int character) {}

    /** Returns a copy carrying the given LSP replace-start (or null to clear it). */
    public Completion withReplaceStart(ReplaceStart rs) {
        return new Completion(
                label,
                insert,
                kind,
                detail,
                snippet,
                onAccept,
                iconKind,
                sortText,
                preselect,
                deprecated,
                resolveToken,
                rs);
    }

    /** A plain word completion (dictionary or user word). */
    public static Completion word(String w, String detail) {
        return new Completion(
                w, w, Kind.WORD, detail, null, null, CompletionIconKind.TEXT, null, false, false, null, null);
    }

    /** A snippet completion; accepting it expands the snippet body via a snippet session. */
    public static Completion ofSnippet(Snippet s) {
        String detail = s.description() == null || s.description().isBlank() ? s.name() : s.description();
        return new Completion(
                s.prefix(),
                s.prefix(),
                Kind.SNIPPET,
                detail,
                s,
                null,
                CompletionIconKind.SNIPPET,
                null,
                false,
                false,
                null,
                null);
    }

    /** A completion from a language server: insert literal text (no snippet session). */
    public static Completion lsp(String label, String insert, String detail) {
        return lsp(label, insert, detail, null);
    }

    /**
     * A language-server completion with an optional {@code onAccept} action run after the text is
     * inserted — used for TypeScript auto-imports (resolve the item + apply its additional edits).
     */
    public static Completion lsp(String label, String insert, String detail, Runnable onAccept) {
        return lsp(label, insert, detail, onAccept, CompletionIconKind.OTHER, null, false, false, null);
    }

    /**
     * The full language-server factory: carries the display kind, relevance keys (sortText/preselect),
     * deprecation, and the opaque resolve token used by the documentation popup.
     */
    public static Completion lsp(
            String label,
            String insert,
            String detail,
            Runnable onAccept,
            CompletionIconKind iconKind,
            String sortText,
            boolean preselect,
            boolean deprecated,
            Object resolveToken) {
        return new Completion(
                label,
                insert,
                Kind.WORD,
                detail,
                null,
                onAccept,
                iconKind == null ? CompletionIconKind.OTHER : iconKind,
                sortText,
                preselect,
                deprecated,
                resolveToken,
                null);
    }
}
