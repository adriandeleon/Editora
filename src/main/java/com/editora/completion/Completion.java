package com.editora.completion;

import com.editora.snippet.Snippet;

/**
 * One entry in the autocomplete popup. {@code insert} is the text that replaces the typed prefix when
 * accepted; {@code label} is what the popup shows; {@code detail} is the muted right-hand hint (a snippet
 * description or the word source). For a {@link Kind#SNIPPET} completion, {@code snippet} is non-null so
 * the editor can start a full snippet session (with tab stops) instead of inserting literal text.
 */
public record Completion(String label, String insert, Kind kind, String detail, Snippet snippet) {

    public enum Kind { SNIPPET, WORD }

    /** A plain word completion (dictionary or user word). */
    public static Completion word(String w, String detail) {
        return new Completion(w, w, Kind.WORD, detail, null);
    }

    /** A snippet completion; accepting it expands the snippet body via a snippet session. */
    public static Completion ofSnippet(Snippet s) {
        String detail = s.description() == null || s.description().isBlank() ? s.name() : s.description();
        return new Completion(s.prefix(), s.prefix(), Kind.SNIPPET, detail, s);
    }

    /** A completion from a language server: insert literal text (no snippet session). */
    public static Completion lsp(String label, String insert, String detail) {
        return new Completion(label, insert, Kind.WORD, detail, null);
    }
}
