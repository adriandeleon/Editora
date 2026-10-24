package com.editora.text;

/**
 * A language-server text edit reduced to an editor-agnostic form (0-based line/character range +
 * replacement text), so {@code EditorBuffer.applyLspEdits} can apply it without the {@code editor}
 * package depending on lsp4j. Used for completion {@code additionalTextEdits} — e.g. the {@code import}
 * line a TypeScript auto-import completion adds.
 */
public record LspTextEdit(int startLine, int startCol, int endLine, int endCol, String newText) {}
