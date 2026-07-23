package com.editora.editor;

/**
 * One occurrence of the symbol under the caret, as reported by the language server's
 * {@code textDocument/documentHighlight} (#675) — 0-based LSP line/columns plus whether the occurrence
 * <b>writes</b> the symbol (an assignment) rather than reads it, which the overlay shades differently.
 * Neutral so {@code editor} stays lsp4j-free (the {@code LspDiagnostic}/{@code LspTextEdit} idiom).
 */
public record OccurrenceSpan(int startLine, int startCol, int endLine, int endCol, boolean write) {}
