package com.editora.editor;

/**
 * A language-server text edit reduced to an editor-agnostic form (0-based line/character range +
 * replacement text), so {@link EditorBuffer#applyLspEdits} can apply it without the {@code editor}
 * package depending on lsp4j. Used for completion {@code additionalTextEdits} — e.g. the {@code import}
 * line a TypeScript auto-import completion adds.
 */
public record LspTextEdit(int startLine, int startCol, int endLine, int endCol, String newText) {}
